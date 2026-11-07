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
