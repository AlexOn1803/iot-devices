package ua.madeisy.iot.flash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Порт-імітація ROM-завантажувача: відповідає на кожну команду успішним статусом. */
private class FakeRom(private val readRegValue: Int) : LoaderPort {
    private val pending = ArrayDeque<Byte>()
    val commands = mutableListOf<Int>()
    var lastData: ByteArray = ByteArray(0)
    private val frame = ByteArrayOutputStream()

    override fun write(data: ByteArray) {
        // розбираємо SLIP-кадри з боку «плати»
        var esc = false
        for (x in data) {
            val b = x.toInt() and 0xFF
            when {
                esc -> { frame.write(if (b == 0xDC) 0xC0 else 0xDB); esc = false }
                b == 0xDB -> esc = true
                b == 0xC0 -> if (frame.size() > 0) { handle(frame.toByteArray()); frame.reset() }
                else -> frame.write(b)
            }
        }
    }

    private fun handle(p: ByteArray) {
        val op = p[1].toInt() and 0xFF
        commands += op
        lastData = p.copyOfRange(8, p.size)
        val value = if (op == EspLoader.READ_REG) readRegValue else 0x1234
        val body = if (op == EspLoader.SPI_FLASH_MD5) md5Hex.toByteArray() + byteArrayOf(0, 0) else byteArrayOf(0, 0, 0, 0)
        val resp = byteArrayOf(1, op.toByte(), body.size.toByte(), 0) + EspLoader.le(value) + body
        EspLoader.slipEncode(resp).forEach { pending.addLast(it) }
    }

    var md5Hex = ""
    override fun read(timeoutMs: Int): ByteArray {
        val out = ByteArray(pending.size) { pending.removeFirst() }
        return out
    }
    override fun setDtr(v: Boolean) {}
    override fun setRts(v: Boolean) {}
    override fun setBaud(baud: Int) {}
}

class EspLoaderTest {
    @Test fun slipEscapes() {
        val enc = EspLoader.slipEncode(byteArrayOf(0x01, 0xC0.toByte(), 0xDB.toByte(), 0x02))
        assertArrayEquals(
            byteArrayOf(0xC0.toByte(), 0x01, 0xDB.toByte(), 0xDC.toByte(), 0xDB.toByte(), 0xDD.toByte(), 0x02, 0xC0.toByte()),
            enc,
        )
    }

    @Test fun checksumSeed() = assertEquals(0xEF xor 1 xor 2 xor 0xFF, EspLoader.checksum(byteArrayOf(1, 2, 0xFF.toByte())))

    /** Значення з esptool 5.6 (ESP8266ROM.get_erase_size). */
    @Test fun esp8266EraseWorkaround() {
        assertEquals(364544, EspLoader.esp8266EraseSize(0, 428907))
        assertEquals(4096, EspLoader.esp8266EraseSize(0x1000, 4096))
        assertEquals(876544, EspLoader.esp8266EraseSize(0x10000, 940000))
        assertEquals(4096, EspLoader.esp8266EraseSize(0, 1000))
    }

    @Test fun commandHeader() {
        val c = EspLoader.buildCommand(0x08, byteArrayOf(1, 2, 3), 0x55)
        assertArrayEquals(byteArrayOf(0, 8, 3, 0, 0x55, 0, 0, 0, 1, 2, 3), c)
    }

    @Test fun detectsChips() {
        assertEquals(Chip.ESP8266, EspLoader(FakeRom(0xFFF0C101.toInt())).apply { sync() }.detectChip())
        assertEquals(Chip.ESP32, EspLoader(FakeRom(0x00F01D83)).detectChip())
        assertEquals(Chip.ESP32S2, EspLoader(FakeRom(0x000007C6)).detectChip())
        assertEquals(Chip.ESP32C3, EspLoader(FakeRom(0x1B31506F)).detectChip())
        assertEquals(Chip.ESP32S3, EspLoader(FakeRom(0x9)).detectChip())
    }

    @Test fun flashesEsp32Compressed() {
        val image = ByteArray(5000) { (it % 7).toByte() }
        val rom = FakeRom(0x00F01D83)
        rom.md5Hex = java.security.MessageDigest.getInstance("MD5").digest(image).joinToString("") { "%02x".format(it) }
        val l = EspLoader(rom)
        l.detectChip(); l.prepareFlash()
        var last = 0f
        assertTrue(l.flash(image, 0x1000) { last = it })
        assertEquals(1f, last)
        assertTrue(rom.commands.contains(EspLoader.FLASH_DEFL_BEGIN))
        assertTrue(rom.commands.contains(EspLoader.FLASH_DEFL_DATA))
        assertTrue(rom.commands.contains(EspLoader.SPI_FLASH_MD5))
    }

    @Test fun flashesEsp8266Uncompressed() {
        val rom = FakeRom(0xFFF0C101.toInt())
        val l = EspLoader(rom)
        l.detectChip()
        l.flash(ByteArray(3000) { 1 }, 0) {}
        assertEquals(3, rom.commands.count { it == EspLoader.FLASH_DATA })
        // останній блок доповнено 0xFF до 1 КБ: 16 байт заголовка + 1024 даних
        assertEquals(16 + 1024, rom.lastData.size)
    }
}
