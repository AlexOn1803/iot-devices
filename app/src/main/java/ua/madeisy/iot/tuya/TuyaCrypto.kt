package ua.madeisy.iot.tuya

import java.security.MessageDigest
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Криптографічні примітиви, які використовує локальний протокол Tuya. */
object TuyaCrypto {

    /** Ключ, яким шифруються UDP-оголошення пристроїв (відомий з tuya-convert). */
    val UDP_KEY: ByteArray = md5("yGAdlopoPVldABfn".toByteArray())

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun crc32(data: ByteArray, len: Int = data.size): Long {
        val c = CRC32()
        c.update(data, 0, len)
        return c.value
    }

    fun ecbEncrypt(key: ByteArray, data: ByteArray, pad: Boolean = true): ByteArray {
        val input = if (pad) pkcs7Pad(data) else data
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(input)
    }

    /** Розшифровує AES-ECB. Падінг знімається лише якщо він коректний. */
    fun ecbDecrypt(key: ByteArray, data: ByteArray, unpad: Boolean = true): ByteArray {
        require(data.isNotEmpty() && data.size % 16 == 0) { "Довжина шифротексту ${data.size} не кратна 16" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val out = cipher.doFinal(data)
        return if (unpad) pkcs7Unpad(out) else out
    }

    /** AES-GCM, повертає шифротекст + 16 байт тегу. */
    fun gcmEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray?, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    /** AES-GCM, вхід — шифротекст + 16 байт тегу. Кидає виняток, якщо тег не збігся. */
    fun gcmDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray?, cipherAndTag: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(cipherAndTag)
    }

    fun pkcs7Pad(data: ByteArray): ByteArray {
        val padLen = 16 - (data.size % 16)
        return data + ByteArray(padLen) { padLen.toByte() }
    }

    fun pkcs7Unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val n = data.last().toInt() and 0xFF
        if (n < 1 || n > 16 || n > data.size) return data
        for (i in data.size - n until data.size) {
            if ((data[i].toInt() and 0xFF) != n) return data
        }
        return data.copyOf(data.size - n)
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    val s = replace(" ", "")
    require(s.length % 2 == 0) { "Непарна довжина hex" }
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
