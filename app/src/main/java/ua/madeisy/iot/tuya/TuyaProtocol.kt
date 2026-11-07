package ua.madeisy.iot.tuya

import java.nio.ByteBuffer
import java.security.SecureRandom

/** Коди команд локального протоколу Tuya. */
object TuyaCmd {
    const val SESS_KEY_NEG_START = 0x03
    const val SESS_KEY_NEG_RESP = 0x04
    const val SESS_KEY_NEG_FINISH = 0x05
    const val CONTROL = 0x07
    const val STATUS = 0x08
    const val HEART_BEAT = 0x09
    const val DP_QUERY = 0x0a
    const val CONTROL_NEW = 0x0d
    const val DP_QUERY_NEW = 0x10
    const val UPDATEDPS = 0x12
    const val REQ_DEVINFO = 0x25
    const val LAN_EXT_STREAM = 0x40

    /** Команди, до яких не додається заголовок "3.x" + 12 нульових байтів. */
    val NO_PROTOCOL_HEADER = setOf(
        DP_QUERY, DP_QUERY_NEW, UPDATEDPS, HEART_BEAT,
        SESS_KEY_NEG_START, SESS_KEY_NEG_RESP, SESS_KEY_NEG_FINISH, LAN_EXT_STREAM
    )
}

class TuyaProtocolException(message: String) : Exception(message)

/** Розібраний кадр. [payload] — дані без коду повернення (для 3.5 — вже розшифровані GCM). */
class TuyaMessage(
    val seq: Int,
    val cmd: Int,
    val retcode: Int?,
    val payload: ByteArray,
    val integrityOk: Boolean,
)

/** Пакування та розбір кадрів 0x55AA (3.1–3.4) та 0x6699 (3.5). */
object TuyaFrame {
    const val PREFIX_55AA = 0x000055AA
    const val SUFFIX_55AA = 0x0000AA55
    const val PREFIX_6699 = 0x00006699
    const val SUFFIX_6699 = 0x00009966
    const val HEADER_55AA = 16
    const val HEADER_6699 = 18

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** Кадр 55AA. Якщо [hmacKey] задано — HMAC-SHA256 (3.4), інакше CRC32. */
    fun pack55AA(seq: Int, cmd: Int, payload: ByteArray, hmacKey: ByteArray?, retcode: Int? = null): ByteArray {
        val body = if (retcode != null) int32(retcode) + payload else payload
        val endLen = if (hmacKey != null) 32 + 4 else 4 + 4
        val header = ByteBuffer.allocate(HEADER_55AA)
            .putInt(PREFIX_55AA).putInt(seq).putInt(cmd).putInt(body.size + endLen).array()
        val data = header + body
        val check = if (hmacKey != null) TuyaCrypto.hmacSha256(hmacKey, data)
        else int32(TuyaCrypto.crc32(data).toInt())
        return data + check + int32(SUFFIX_55AA)
    }

    /** Кадр 6699 (AES-GCM). Заголовок після префікса — AAD. */
    fun pack6699(seq: Int, cmd: Int, payload: ByteArray, key: ByteArray, iv: ByteArray = randomBytes(12), retcode: Int? = null): ByteArray {
        val plain = if (retcode != null) int32(retcode) + payload else payload
        val length = 12 + plain.size + 16
        val header = ByteBuffer.allocate(HEADER_6699)
            .putInt(PREFIX_6699).putShort(0).putInt(seq).putInt(cmd).putInt(length).array()
        val aad = header.copyOfRange(4, HEADER_6699)
        val enc = TuyaCrypto.gcmEncrypt(key, iv, aad, plain)
        return header + iv + enc + int32(SUFFIX_6699)
    }

    /**
     * Повна довжина кадру за першими байтами [header] (потрібно щонайменше 18 байт для 6699,
     * 16 — для 55AA). Повертає -1, якщо байтів замало.
     */
    fun frameLength(header: ByteArray, available: Int = header.size): Int {
        if (available < 4) return -1
        val bb = ByteBuffer.wrap(header)
        return when (bb.getInt(0)) {
            PREFIX_55AA -> if (available < HEADER_55AA) -1 else HEADER_55AA + bb.getInt(12)
            PREFIX_6699 -> if (available < HEADER_6699) -1 else HEADER_6699 + bb.getInt(14) + 4
            else -> throw TuyaProtocolException("Невідомий префікс кадру")
        }
    }

    fun headerSize(prefix: Int): Int = if (prefix == PREFIX_6699) HEADER_6699 else HEADER_55AA

    /**
     * Розбирає кадр.
     * @param hmacKey для 55AA: ключ HMAC (3.4) або null (CRC32); для 6699 — ключ AES-GCM (обов'язковий).
     * @param stripRetcode для 55AA: true — завжди відкидати 4 байти коду повернення,
     *   null — визначати автоматично.
     */
    fun unpack(frame: ByteArray, hmacKey: ByteArray?, stripRetcode: Boolean? = null): TuyaMessage {
        val bb = ByteBuffer.wrap(frame)
        return when (bb.getInt(0)) {
            PREFIX_55AA -> {
                val seq = bb.getInt(4)
                val cmd = bb.getInt(8)
                val len = bb.getInt(12)
                val total = HEADER_55AA + len
                if (frame.size < total) throw TuyaProtocolException("Неповний кадр")
                val endLen = if (hmacKey != null) 36 else 8
                if (len < endLen) throw TuyaProtocolException("Пошкоджений кадр")
                val dataEnd = total - endLen
                val ok = if (hmacKey != null) {
                    TuyaCrypto.hmacSha256(hmacKey, frame.copyOfRange(0, dataEnd))
                        .contentEquals(frame.copyOfRange(dataEnd, dataEnd + 32))
                } else {
                    TuyaCrypto.crc32(frame, dataEnd).toInt() == bb.getInt(dataEnd)
                }
                var body = frame.copyOfRange(HEADER_55AA, dataEnd)
                var retcode: Int? = null
                if (body.size >= 4 && (stripRetcode ?: looksLikeRetcode(body))) {
                    retcode = ByteBuffer.wrap(body).getInt(0)
                    body = body.copyOfRange(4, body.size)
                }
                TuyaMessage(seq, cmd, retcode, body, ok)
            }
            PREFIX_6699 -> {
                requireNotNull(hmacKey) { "Для 6699 потрібен ключ" }
                val seq = bb.getInt(6)
                val cmd = bb.getInt(10)
                val len = bb.getInt(14)
                val total = HEADER_6699 + len + 4
                if (frame.size < total || len < 28) throw TuyaProtocolException("Неповний кадр")
                val iv = frame.copyOfRange(HEADER_6699, HEADER_6699 + 12)
                val enc = frame.copyOfRange(HEADER_6699 + 12, HEADER_6699 + len)
                val aad = frame.copyOfRange(4, HEADER_6699)
                val plain = try {
                    TuyaCrypto.gcmDecrypt(hmacKey, iv, aad, enc)
                } catch (e: Exception) {
                    return TuyaMessage(seq, cmd, null, ByteArray(0), false)
                }
                var body = plain
                var retcode: Int? = null
                if (body.size >= 4 && looksLikeRetcode(body)) {
                    retcode = ByteBuffer.wrap(body).getInt(0)
                    body = body.copyOfRange(4, body.size)
                }
                TuyaMessage(seq, cmd, retcode, body, true)
            }
            else -> throw TuyaProtocolException("Невідомий префікс кадру")
        }
    }

    /** Код повернення — це 4 байти, з яких старші три нульові (0x000000xx). */
    private fun looksLikeRetcode(b: ByteArray): Boolean =
        b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 0 && b[3].toInt() != '{'.code

    fun int32(v: Int): ByteArray = ByteBuffer.allocate(4).putInt(v).array()
}

enum class TuyaVersion(val label: String) {
    V33("3.3"), V34("3.4"), V35("3.5");

    val header: ByteArray get() = label.toByteArray() + ByteArray(12)
    val needsSession: Boolean get() = this != V33

    companion object {
        fun parse(s: String?): TuyaVersion = entries.firstOrNull { it.label == s?.trim() } ?: V33
    }
}

/**
 * Стан шифрування одного з'єднання: версія, ключ (для 3.4/3.5 — сесійний після узгодження),
 * лічильник послідовності.
 */
class TuyaSession(val version: TuyaVersion, val realKey: ByteArray) {
    init {
        require(realKey.size == 16) { "local key має бути 16 символів (зараз ${realKey.size})" }
    }

    var key: ByteArray = realKey
        private set
    var seq: Int = 1
        private set

    private var localNonce: ByteArray = ByteArray(0)

    /** Кодує команду з JSON/сирими даними в кадр. [iv] — лише для тестів 3.5. */
    fun encode(cmd: Int, payload: ByteArray, iv: ByteArray? = null): ByteArray {
        val withHeader = cmd !in TuyaCmd.NO_PROTOCOL_HEADER
        val s = seq++
        return when (version) {
            TuyaVersion.V33 -> {
                var enc = TuyaCrypto.ecbEncrypt(key, payload)
                if (withHeader) enc = version.header + enc
                TuyaFrame.pack55AA(s, cmd, enc, null)
            }
            TuyaVersion.V34 -> {
                val p = if (withHeader) version.header + payload else payload
                TuyaFrame.pack55AA(s, cmd, TuyaCrypto.ecbEncrypt(key, p), key)
            }
            TuyaVersion.V35 -> {
                val p = if (withHeader) version.header + payload else payload
                TuyaFrame.pack6699(s, cmd, p, key, iv ?: TuyaFrame.randomBytes(12))
            }
        }
    }

    fun parse(frame: ByteArray): TuyaMessage = when (version) {
        TuyaVersion.V33 -> TuyaFrame.unpack(frame, null)
        TuyaVersion.V34 -> TuyaFrame.unpack(frame, key)
        TuyaVersion.V35 -> TuyaFrame.unpack(frame, key)
    }

    /** Розшифровує корисне навантаження кадру у текст (зазвичай JSON). Порожнє — null. */
    fun decodeText(msg: TuyaMessage): String? {
        var p = msg.payload
        if (p.isEmpty()) return null
        when (version) {
            TuyaVersion.V33 -> {
                if (startsWithVersion(p)) p = p.copyOfRange(15, p.size)
                if (p.isEmpty()) return null
                if (p[0] != '{'.code.toByte()) p = TuyaCrypto.ecbDecrypt(key, p)
            }
            TuyaVersion.V34 -> {
                p = TuyaCrypto.ecbDecrypt(key, p)
                if (startsWithVersion(p)) p = p.copyOfRange(15, p.size)
            }
            TuyaVersion.V35 -> {
                if (startsWithVersion(p)) p = p.copyOfRange(15, p.size)
            }
        }
        val text = String(p, Charsets.UTF_8).trimEnd('\u0000')
        return text.ifEmpty { null }
    }

    private fun startsWithVersion(p: ByteArray): Boolean =
        p.size >= 15 && p[0] == '3'.code.toByte() && p[1] == '.'.code.toByte()

    // ---- узгодження сесійного ключа (3.4 / 3.5) ----

    fun negotiationStart(nonce: ByteArray = TuyaFrame.randomBytes(16)): ByteArray {
        localNonce = nonce
        key = realKey
        return encode(TuyaCmd.SESS_KEY_NEG_START, nonce)
    }

    /**
     * Обробляє відповідь пристрою (крок 2) і повертає кадр кроку 3.
     * Після відправлення кадру треба викликати [negotiationFinalize].
     */
    fun negotiationStep3(resp: TuyaMessage): ByteArray {
        if (resp.cmd != TuyaCmd.SESS_KEY_NEG_RESP)
            throw TuyaProtocolException("Пристрій відповів командою ${resp.cmd} замість узгодження ключа")
        var p = resp.payload
        if (version == TuyaVersion.V34) {
            p = try { TuyaCrypto.ecbDecrypt(realKey, p) } catch (e: Exception) {
                throw TuyaProtocolException("Не вдалося розшифрувати — схоже, невірний local key")
            }
        }
        if (p.size < 48) throw TuyaProtocolException("Невірний local key або версія протоколу")
        val remoteNonce = p.copyOfRange(0, 16)
        val expected = TuyaCrypto.hmacSha256(realKey, localNonce)
        if (!expected.contentEquals(p.copyOfRange(16, 48)))
            throw TuyaProtocolException("Невірний local key (перевірка HMAC не пройшла)")
        pendingRemote = remoteNonce
        return encode(TuyaCmd.SESS_KEY_NEG_FINISH, TuyaCrypto.hmacSha256(realKey, remoteNonce))
    }

    private var pendingRemote: ByteArray? = null

    fun negotiationFinalize() {
        val remote = pendingRemote ?: throw TuyaProtocolException("Узгодження не завершено")
        key = deriveSessionKey(version, realKey, localNonce, remote)
        pendingRemote = null
    }

    companion object {
        fun deriveSessionKey(version: TuyaVersion, realKey: ByteArray, localNonce: ByteArray, remoteNonce: ByteArray): ByteArray {
            val x = ByteArray(16) { (localNonce[it].toInt() xor remoteNonce[it].toInt()).toByte() }
            return when (version) {
                TuyaVersion.V34 -> TuyaCrypto.ecbEncrypt(realKey, x, pad = false)
                TuyaVersion.V35 -> TuyaCrypto.gcmEncrypt(realKey, localNonce.copyOfRange(0, 12), null, x).copyOfRange(0, 16)
                TuyaVersion.V33 -> realKey
            }
        }
    }
}
