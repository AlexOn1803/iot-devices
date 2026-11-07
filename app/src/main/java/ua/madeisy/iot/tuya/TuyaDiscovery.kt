package ua.madeisy.iot.tuya

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

data class DiscoveredDevice(
    val ip: String,
    val deviceId: String,
    val version: String,
    val productKey: String?,
)

/**
 * Пошук пристроїв Tuya у локальній мережі: вони кожні ~5 с розсилають UDP-оголошення
 * на порти 6666 (3.1), 6667 (3.3/3.4) та 7000 (3.5).
 * На Android перед викликом треба взяти WifiManager.MulticastLock.
 */
object TuyaDiscovery {

    /** Розшифровує одне UDP-оголошення в JSON-рядок або повертає null. */
    fun decodePacket(data: ByteArray): String? {
        return try {
            if (data.size >= 4) {
                val prefix = java.nio.ByteBuffer.wrap(data).getInt(0)
                when (prefix) {
                    TuyaFrame.PREFIX_55AA -> {
                        val msg = TuyaFrame.unpack(data, null, stripRetcode = true)
                        val p = msg.payload
                        if (p.isNotEmpty() && p[0] == '{'.code.toByte()) return String(p).trimEnd('\u0000')
                        return String(TuyaCrypto.ecbDecrypt(TuyaCrypto.UDP_KEY, p)).trimEnd('\u0000')
                    }
                    TuyaFrame.PREFIX_6699 -> {
                        val msg = TuyaFrame.unpack(data, TuyaCrypto.UDP_KEY)
                        if (!msg.integrityOk) return null
                        return String(msg.payload).trimEnd('\u0000')
                    }
                }
            }
            String(TuyaCrypto.ecbDecrypt(TuyaCrypto.UDP_KEY, data)).trimEnd('\u0000')
        } catch (e: Exception) {
            null
        }
    }

    fun parse(text: String, fromIp: String): DiscoveredDevice? = try {
        val j = JSONObject(text)
        val id = j.optString("gwId").ifEmpty { j.optString("devId") }
        if (id.isEmpty()) null else DiscoveredDevice(
            ip = j.optString("ip").ifEmpty { fromIp },
            deviceId = id,
            version = j.optString("version", "3.3"),
            productKey = j.optString("productKey").ifEmpty { null },
        )
    } catch (e: Exception) { null }

    /** Слухає мережу [durationMs] мс і повертає знайдені пристрої. */
    suspend fun scan(durationMs: Long = 7000, localIp: String? = null): List<DiscoveredDevice> =
        withContext(Dispatchers.IO) {
            val found = java.util.concurrent.ConcurrentHashMap<String, DiscoveredDevice>()
            val deadline = System.currentTimeMillis() + durationMs
            coroutineScope {
                for (port in intArrayOf(6666, 6667, 7000)) {
                    launch {
                        val sock = try {
                            DatagramSocket(null).apply {
                                reuseAddress = true
                                broadcast = true
                                bind(InetSocketAddress(port))
                            }
                        } catch (e: Exception) { return@launch }
                        try {
                            if (port == 7000) runCatching { sendDevInfoRequest(sock, localIp) }
                            val buf = ByteArray(4096)
                            while (System.currentTimeMillis() < deadline) {
                                val left = (deadline - System.currentTimeMillis()).toInt()
                                if (left <= 0) break
                                sock.soTimeout = left.coerceAtMost(1000)
                                val pkt = DatagramPacket(buf, buf.size)
                                try { sock.receive(pkt) } catch (e: SocketTimeoutException) { continue }
                                val bytes = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                                val text = decodePacket(bytes) ?: continue
                                val dev = parse(text, pkt.address.hostAddress ?: "") ?: continue
                                found[dev.deviceId] = dev
                            }
                        } finally {
                            sock.close()
                        }
                    }
                }
            }
            found.values.sortedBy { it.ip }
        }

    /** Пристрої 3.5 відповідають на широкомовний запит REQ_DEVINFO. */
    private fun sendDevInfoRequest(sock: DatagramSocket, localIp: String?) {
        val ip = localIp ?: guessLocalIp() ?: return
        val payload = JSONObject().put("from", "app").put("ip", ip).toString().toByteArray()
        val frame = TuyaFrame.pack6699(0, TuyaCmd.REQ_DEVINFO, payload, TuyaCrypto.UDP_KEY)
        val dst = InetAddress.getByName("255.255.255.255")
        sock.send(DatagramPacket(frame, frame.size, dst, 7000))
    }

    /** Визначає IP телефону в локальній мережі (без реальної відправки пакетів). */
    fun guessLocalIp(): String? = try {
        DatagramSocket().use { s ->
            s.connect(InetAddress.getByName("192.168.1.1"), 9)
            s.localAddress?.hostAddress?.takeIf { it != "0.0.0.0" }
        }
    } catch (e: Exception) { null }
}
