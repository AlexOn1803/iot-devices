package ua.madeisy.iot.esp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class EspHttpException(val code: Int, message: String) : IOException(message)

/** HTTP-клієнт прошивки IoT Devices на ESP32-C3. */
class EspClient(private val host: String, private val key: String) {

    private suspend fun call(method: String, path: String, body: String? = null, timeoutMs: Int = 3500): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL("http://$host$path").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                if (key.isNotEmpty()) conn.setRequestProperty("X-Key", key)
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: "{}"
                when (code) {
                    in 200..299 -> JSONObject(text)
                    401 -> throw EspHttpException(code, "Невірний ключ доступу до пристрою")
                    else -> throw EspHttpException(code, "Пристрій відповів помилкою $code")
                }
            } catch (e: java.net.SocketTimeoutException) {
                throw IOException("Пристрій $host не відповідає")
            } catch (e: java.net.ConnectException) {
                throw IOException("Немає зв'язку з $host")
            } finally {
                conn.disconnect()
            }
        }

    suspend fun info(): EspInfo = EspInfo.fromJson(call("GET", "/api/info"))
    suspend fun state(): JSONObject = call("GET", "/api/state")
    suspend fun set(commands: List<JSONObject>): JSONObject =
        call("POST", "/api/set", JSONArray().apply { commands.forEach { put(it) } }.toString())

    suspend fun getConfig(): JSONObject = call("GET", "/api/config")
    suspend fun postConfig(cfg: JSONObject): JSONObject = call("POST", "/api/config", cfg.toString(), 6000)
    suspend fun claim(newKey: String, name: String): EspInfo =
        EspInfo.fromJson(call("POST", "/api/claim", JSONObject().put("key", newKey).put("name", name).toString()))

    suspend fun reboot() { call("POST", "/api/reboot", "{}") }

    companion object {
        fun newKey(): String {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val r = SecureRandom()
            return (1..16).map { chars[r.nextInt(chars.length)] }.joinToString("")
        }
    }
}

data class EspFound(val id: String, val name: String, val host: String)

/** Пошук плат у мережі через mDNS (_iotdev._tcp). */
object EspDiscovery {
    private const val TYPE = "_iotdev._tcp."
    private val executor = Executors.newSingleThreadExecutor()

    suspend fun scan(ctx: Context, durationMs: Long = 5000): List<EspFound> {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("esp-scan").apply { setReferenceCounted(false) }
        val services = ConcurrentHashMap<String, NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { services[info.serviceName] = info }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        lock.acquire()
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            delay(durationMs)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { lock.release() }
        }
        val out = mutableListOf<EspFound>()
        for (s in services.values) {
            val r = withTimeoutOrNull(3000) { resolve(nsd, s) } ?: continue
            out += r
        }
        return out.distinctBy { it.id }.sortedBy { it.name }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, s: NsdServiceInfo): EspFound? =
        suspendCancellableCoroutine { cont ->
            val l = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull()?.hostAddress
                    else info.host?.hostAddress
                    val attrs = info.attributes
                    val id = attrs["id"]?.let { String(it) } ?: info.serviceName
                    val name = attrs["name"]?.let { String(it) } ?: info.serviceName
                    if (cont.isActive) cont.resume(host?.let { EspFound(id, name, it) })
                }
            }
            if (Build.VERSION.SDK_INT >= 34) nsd.resolveService(s, executor, l) else nsd.resolveService(s, l)
        }
}

/** Оновлення прошивки плат по Wi-Fi з останнього релізу на GitHub. */
object EspUpdater {
    private const val BASE = "https://github.com/AlexOn1803/iot-devices/releases/download/latest"

    @Volatile
    private var cachedLatest: Pair<String, Long>? = null

    /** Версія прошивки в останньому релізі (напр. "1.0.12"); кеш на 10 хв. */
    suspend fun latestVersion(force: Boolean = false): String = withContext(Dispatchers.IO) {
        cachedLatest?.let { (v, at) -> if (!force && System.currentTimeMillis() - at < 600_000) return@withContext v }
        val conn = URL("$BASE/version.json").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) throw IOException("Не вдалося перевірити оновлення (HTTP ${conn.responseCode})")
            val v = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getString("fw")
            cachedLatest = v to System.currentTimeMillis()
            v
        } finally { conn.disconnect() }
    }

    /** true, якщо [latest] новіша за [current]. Збірки "dev" вважаються старими. */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: -1 }
        if (current.isBlank() || current == "dev") return true
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Завантажує esp32c3-ota.bin у кеш додатку. */
    suspend fun download(ctx: Context, onProgress: (Float) -> Unit): java.io.File = withContext(Dispatchers.IO) {
        val out = java.io.File(ctx.cacheDir, "esp32c3-ota.bin")
        val conn = URL("$BASE/esp32c3-ota.bin").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000; conn.readTimeout = 20000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) throw IOException("Не вдалося завантажити прошивку (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1
            conn.inputStream.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(16 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            // образ ESP завжди починається з магічного байта 0xE9
            if (out.length() < 100_000 || out.inputStream().use { it.read() } != 0xE9) throw IOException("Завантажений файл не схожий на прошивку")
            out
        } finally { conn.disconnect() }
    }

    /** Надсилає прошивку на плату (multipart POST /update). Після успіху плата перезавантажується. */
    suspend fun upload(host: String, key: String, file: java.io.File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val boundary = "----iot" + System.currentTimeMillis()
        val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"firmware\"; filename=\"firmware.bin\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val total = head.size + file.length() + tail.size
        val conn = URL("http://$host/update").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 60000
            conn.setFixedLengthStreamingMode(total)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (key.isNotEmpty()) conn.setRequestProperty("X-Key", key)
            conn.outputStream.use { o ->
                o.write(head)
                file.inputStream().use { inp ->
                    val buf = ByteArray(4096)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        onProgress(done.toFloat() / file.length())
                    }
                }
                o.write(tail)
            }
            when (val code = conn.responseCode) {
                in 200..299 -> Unit
                401 -> throw EspHttpException(code, "Невірний ключ доступу до плати")
                else -> throw EspHttpException(code, "Плата не прийняла прошивку (помилка $code)")
            }
        } finally { conn.disconnect() }
    }
}
