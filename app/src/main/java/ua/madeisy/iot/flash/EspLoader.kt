package ua.madeisy.iot.flash

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Deflater

class FlashException(message: String) : IOException(message)

/** Чипи, які вміє прошивати додаток. */
enum class Chip(
    val label: String,
    /** Зміщення завантажувача в «повному» образі не потрібне: full.bin пишеться з 0x0. */
    val romDeflate: Boolean,
    /** ROM ESP32 і ESP8266 не підтримують п'ятий параметр FLASH_BEGIN. */
    val extParams: Boolean,
    val defaultBoard: String,
) {
    ESP8266("ESP8266", false, false, "esp8266"),
    ESP32("ESP32", true, false, "esp32"),
    ESP32S2("ESP32-S2", true, true, "esp32s2"),
    ESP32S3("ESP32-S3", true, true, "esp32s3"),
    ESP32C3("ESP32-C3", true, true, "esp32c3"),
}

/** Абстракція порту, щоб протокол можна було тестувати без USB. */
interface LoaderPort {
    fun write(data: ByteArray)
    /** Читає доступні байти, чекаючи не довше [timeoutMs]. Порожній масив — нічого не прийшло. */
    fun read(timeoutMs: Int): ByteArray
    fun setDtr(v: Boolean)
    fun setRts(v: Boolean)
    fun setBaud(baud: Int)
    fun flushInput() {
        repeat(20) { if (read(5).isEmpty()) return }
    }
}

/**
 * Протокол ROM-завантажувача Espressif (те саме, що робить esptool, без stub):
 * кадри SLIP, команди SYNC / READ_REG / FLASH_(DEFL_)BEGIN / DATA / MD5.
 */
class EspLoader(private val port: LoaderPort, private val log: (String) -> Unit = {}) {

    companion object {
        const val FLASH_BEGIN = 0x02
        const val FLASH_DATA = 0x03
        const val FLASH_END = 0x04
        const val SYNC = 0x08
        const val READ_REG = 0x0A
        const val SPI_SET_PARAMS = 0x0B
        const val SPI_ATTACH = 0x0D
        const val CHANGE_BAUDRATE = 0x0F
        const val FLASH_DEFL_BEGIN = 0x10
        const val FLASH_DEFL_DATA = 0x11
        const val FLASH_DEFL_END = 0x12
        const val SPI_FLASH_MD5 = 0x13
        const val GET_SECURITY_INFO = 0x14

        const val WRITE_BLOCK = 0x400
        const val CHIP_MAGIC_REG = 0x40001000

        fun le(vararg ints: Int): ByteArray {
            val b = ByteBuffer.allocate(ints.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            ints.forEach { b.putInt(it) }
            return b.array()
        }

        fun checksum(data: ByteArray): Int {
            var c = 0xEF
            for (b in data) c = c xor (b.toInt() and 0xFF)
            return c
        }

        fun slipEncode(packet: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(packet.size + 8)
            out.write(0xC0)
            for (b in packet) {
                when (b.toInt() and 0xFF) {
                    0xC0 -> { out.write(0xDB); out.write(0xDC) }
                    0xDB -> { out.write(0xDB); out.write(0xDD) }
                    else -> out.write(b.toInt() and 0xFF)
                }
            }
            out.write(0xC0)
            return out.toByteArray()
        }

        fun buildCommand(op: Int, data: ByteArray, chk: Int = 0): ByteArray {
            val b = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            b.put(0); b.put(op.toByte()); b.putShort(data.size.toShort()); b.putInt(chk); b.put(data)
            return b.array()
        }

        /** Обхід помилки стирання в ROM ESP8266 (як у esptool). */
        fun esp8266EraseSize(offset: Int, size: Int): Int {
            val sectorsPerBlock = 16
            val sectorSize = 4096
            val numSectors = (size + sectorSize - 1) / sectorSize
            val startSector = offset / sectorSize
            var headSectors = sectorsPerBlock - (startSector % sectorsPerBlock)
            if (numSectors < headSectors) headSectors = numSectors
            return if (numSectors < 2 * headSectors) (numSectors + 1) / 2 * sectorSize
            else (numSectors - headSectors) * sectorSize
        }

        fun chipFromMagic(magic: Int): Chip? = when (magic) {
            0xFFF0C101.toInt() -> Chip.ESP8266
            0x00F01D83 -> Chip.ESP32
            0x000007C6 -> Chip.ESP32S2
            0x00000009 -> Chip.ESP32S3
            0x6921506F, 0x1B31506F, 0x4881606F, 0x4361606F -> Chip.ESP32C3
            else -> null
        }

        fun chipFromId(id: Int): Chip? = when (id) {
            2 -> Chip.ESP32S2; 5 -> Chip.ESP32C3; 9 -> Chip.ESP32S3; else -> null
        }

        private fun timeoutPerMb(secondsPerMb: Int, size: Int): Int =
            maxOf(3000, (secondsPerMb * 1000.0 * size / 1_000_000).toInt())
    }

    var chip: Chip? = null
        private set

    // ---- SLIP-читач ----
    private val rx = ArrayDeque<Byte>()

    private fun readPacket(timeoutMs: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val frame = ByteArrayOutputStream()
        var inFrame = false
        var escape = false
        while (true) {
            while (rx.isEmpty()) {
                val left = (deadline - System.currentTimeMillis()).toInt()
                if (left <= 0) return null
                port.read(minOf(left, 200)).forEach { rx.addLast(it) }
            }
            val b = rx.removeFirst().toInt() and 0xFF
            if (!inFrame) {
                if (b == 0xC0) inFrame = true
                continue
            }
            if (escape) {
                frame.write(if (b == 0xDC) 0xC0 else if (b == 0xDD) 0xDB else b)
                escape = false
                continue
            }
            when (b) {
                0xDB -> escape = true
                0xC0 -> {
                    if (frame.size() == 0) continue   // два C0 поспіль — початок нового кадру
                    return frame.toByteArray()
                }
                else -> frame.write(b)
            }
        }
    }

    private fun drain() { rx.clear(); port.flushInput() }

    /** Надсилає команду й чекає на відповідь з тим самим кодом. Повертає (value, data). */
    fun command(op: Int, data: ByteArray = ByteArray(0), chk: Int = 0, timeoutMs: Int = 3000): Pair<Int, ByteArray> {
        port.write(slipEncode(buildCommand(op, data, chk)))
        val deadline = System.currentTimeMillis() + timeoutMs
        repeat(100) {
            val left = (deadline - System.currentTimeMillis()).toInt()
            if (left <= 0) throw FlashException("Плата не відповіла на команду 0x%02X".format(op))
            val p = readPacket(left) ?: throw FlashException("Плата не відповіла на команду 0x%02X".format(op))
            if (p.size < 8 || p[0].toInt() != 1) return@repeat
            if ((p[1].toInt() and 0xFF) != op) return@repeat
            val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
            val size = bb.getShort(2).toInt() and 0xFFFF
            val value = bb.getInt(4)
            val body = p.copyOfRange(8, minOf(p.size, 8 + size))
            return value to body
        }
        throw FlashException("Відповідь не збігається з командою 0x%02X".format(op))
    }

    /** Команда з перевіркою статусу (перші 2 байти після даних відповіді). */
    fun checkCommand(what: String, op: Int, data: ByteArray = ByteArray(0), chk: Int = 0, timeoutMs: Int = 3000, respLen: Int = 0): Pair<Int, ByteArray> {
        val (v, body) = command(op, data, chk, timeoutMs)
        if (body.size < respLen + 2) {
            if (body.isNotEmpty() && body[0].toInt() != 0) throw FlashException("$what: помилка ${body.getOrNull(1)}")
            throw FlashException("$what: коротка відповідь")
        }
        if (body[respLen].toInt() != 0) {
            throw FlashException("$what: помилка 0x%02X".format(body[respLen + 1].toInt() and 0xFF))
        }
        return v to body.copyOfRange(0, respLen)
    }

    // ---- скидання в режим прошивки ----

    /** Для мостів USB-UART (CH340, CP210x, FTDI): DTR → GPIO0, RTS → EN. */
    fun classicReset() {
        port.setDtr(false); port.setRts(true)
        Thread.sleep(100)
        port.setDtr(true); port.setRts(false)
        Thread.sleep(50)
        port.setDtr(false)
    }

    /** Для вбудованого USB-Serial-JTAG (ESP32-C3/S3). */
    fun usbJtagReset() {
        port.setRts(false); port.setDtr(false)
        Thread.sleep(100)
        port.setDtr(true); port.setRts(false)
        Thread.sleep(100)
        port.setRts(true); port.setDtr(false); port.setRts(true)
        Thread.sleep(100)
        port.setDtr(false); port.setRts(false)
    }

    fun hardReset() {
        port.setRts(true)
        Thread.sleep(150)
        port.setRts(false)
    }

    fun sync(): Boolean {
        val payload = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        repeat(5) {
            try {
                command(SYNC, payload, timeoutMs = 150)
                // ROM відповідає на SYNC кількома пакетами — забираємо решту
                repeat(7) { runCatching { readPacket(50) } }
                drain()
                return true
            } catch (e: FlashException) { /* ще раз */ }
        }
        return false
    }

    /**
     * Підключення до завантажувача. [autoReset] — смикати DTR/RTS; без них (адаптер лише з TX/RX)
     * користувач сам тримає GPIO0 на GND і перезавантажує плату.
     */
    fun connect(autoReset: Boolean, usbJtag: Boolean, attempts: Int = 4): Boolean {
        repeat(attempts) { i ->
            if (autoReset) {
                if (usbJtag || i % 2 == 1) usbJtagReset() else classicReset()
                Thread.sleep(50)
            }
            drain()
            if (sync()) return true
        }
        return false
    }

    fun readReg(addr: Int): Int = checkCommand("читання регістра", READ_REG, le(addr)).first

    fun detectChip(): Chip {
        val magic = readReg(CHIP_MAGIC_REG)
        chipFromMagic(magic)?.let { chip = it; return it }
        // новіші чипи повідомляють chip_id у GET_SECURITY_INFO
        val (_, body) = command(GET_SECURITY_INFO, timeoutMs = 2000)
        if (body.size >= 20) {
            val id = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
            chipFromId(id)?.let { chip = it; return it }
        }
        throw FlashException("Невідомий чип (magic 0x%08X)".format(magic))
    }

    /** Підготовка флешу для ROM ESP32-сімейства. */
    fun prepareFlash(flashSize: Int = 4 * 1024 * 1024) {
        val c = chip ?: throw FlashException("Чип не визначено")
        if (c == Chip.ESP8266) return
        checkCommand("підключення SPI-флешу", SPI_ATTACH, ByteArray(8))
        checkCommand("параметри флешу", SPI_SET_PARAMS, le(0, flashSize, 64 * 1024, 4 * 1024, 256, 0xFFFF))
    }

    /** Підвищення швидкості (лише ESP32-сімейство через міст USB-UART). */
    fun changeBaud(baud: Int) {
        command(CHANGE_BAUDRATE, le(baud, 0))
        port.setBaud(baud)
        Thread.sleep(60)
        drain()
    }

    /** Записує образ з адреси [offset]. [progress] — від 0 до 1. Повертає true, якщо MD5 збігся (або перевірка недоступна). */
    fun flash(image: ByteArray, offset: Int, progress: (Float) -> Unit): Boolean {
        val c = chip ?: throw FlashException("Чип не визначено")
        if (c.romDeflate) {
            val comp = deflate(image)
            log("Стиснуто ${image.size / 1024} КБ → ${comp.size / 1024} КБ")
            val numBlocks = (comp.size + WRITE_BLOCK - 1) / WRITE_BLOCK
            val eraseBlocks = (image.size + WRITE_BLOCK - 1) / WRITE_BLOCK
            val writeSize = eraseBlocks * WRITE_BLOCK
            var params = le(writeSize, numBlocks, WRITE_BLOCK, offset)
            if (c.extParams) params += le(0)
            log("Стирання флешу…")
            checkCommand("початок запису", FLASH_DEFL_BEGIN, params, timeoutMs = timeoutPerMb(30, writeSize))
            for (seq in 0 until numBlocks) {
                val block = comp.copyOfRange(seq * WRITE_BLOCK, minOf(comp.size, (seq + 1) * WRITE_BLOCK))
                writeBlock(FLASH_DEFL_DATA, block, seq)
                progress((seq + 1).toFloat() / numBlocks)
            }
            log("Перевірка MD5…")
            val (_, md5) = checkCommand("перевірка MD5", SPI_FLASH_MD5, le(offset, image.size, 0, 0),
                timeoutMs = timeoutPerMb(8, image.size), respLen = 32)
            val got = String(md5).lowercase()
            val want = MessageDigest.getInstance("MD5").digest(image).joinToString("") { "%02x".format(it) }
            if (got != want) throw FlashException("MD5 не збігся — прошивка записалась з помилками")
            return true
        } else {
            val numBlocks = (image.size + WRITE_BLOCK - 1) / WRITE_BLOCK
            val erase = esp8266EraseSize(offset, image.size)
            log("Стирання флешу…")
            checkCommand("початок запису", FLASH_BEGIN, le(erase, numBlocks, WRITE_BLOCK, offset),
                timeoutMs = timeoutPerMb(30, image.size))
            for (seq in 0 until numBlocks) {
                var block = image.copyOfRange(seq * WRITE_BLOCK, minOf(image.size, (seq + 1) * WRITE_BLOCK))
                if (block.size < WRITE_BLOCK) block += ByteArray(WRITE_BLOCK - block.size) { 0xFF.toByte() }
                writeBlock(FLASH_DATA, block, seq)
                progress((seq + 1).toFloat() / numBlocks)
            }
            return true
        }
    }

    private fun writeBlock(op: Int, block: ByteArray, seq: Int) {
        val data = le(block.size, seq, 0, 0) + block
        var last: Exception? = null
        repeat(3) {
            try {
                checkCommand("запис блоку $seq", op, data, checksum(block), timeoutMs = 10_000)
                return
            } catch (e: FlashException) { last = e }
        }
        throw last ?: FlashException("Не вдалося записати блок $seq")
    }

    /**
     * Вийти із завантажувача й запустити прошивку без скидання (для адаптерів без лінії RTS).
     * Як `esptool run`: порожній FLASH_BEGIN, потім FLASH_END з параметром 1 — «запустити код».
     */
    fun runApp() {
        val c = chip ?: return
        runCatching {
            checkCommand("старт", FLASH_BEGIN, le(0, 0, WRITE_BLOCK, 0) + if (c.extParams) le(0) else ByteArray(0))
            command(FLASH_END, le(1), timeoutMs = 500)
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(9)   // формат zlib — саме його чекає ROM
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream(data.size / 2)
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) {
            val n = d.deflate(buf)
            out.write(buf, 0, n)
        }
        d.end()
        return out.toByteArray()
    }
}
