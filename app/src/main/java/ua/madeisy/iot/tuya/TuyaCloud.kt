package ua.madeisy.iot.tuya

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class CloudRegion(val label: String, val host: String) {
    EU("Центральна Європа", "openapi.tuyaeu.com"),
    WEU("Західна Європа", "openapi-weaz.tuyaeu.com"),
    US("Західна Америка", "openapi.tuyaus.com"),
    EUS("Східна Америка", "openapi-ueaz.tuyaus.com"),
    CN("Китай", "openapi.tuyacn.com"),
    IN("Індія", "openapi.tuyain.com"),
    SG("Сингапур", "openapi-sg.iotbing.com");

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: EU
    }
}

data class CloudDevice(
    val id: String,
    val name: String,
    val localKey: String,
    val online: Boolean,
    val productName: String,
    val category: String,
)

class TuyaCloudException(val code: Int, message: String) : Exception(message)

/** Клієнт Tuya OpenAPI (iot.tuya.com → Cloud Project → Access ID / Access Secret). */
class TuyaCloud(
    private val accessId: String,
    private val secret: String,
    private val region: CloudRegion,
) {
    private var token: String? = null
    private var tokenExpires = 0L
    private val tokenLock = Mutex()

    companion object {
        /** Рядок для підпису за алгоритмом Tuya (з 30.06.2021). */
        fun stringToSign(method: String, body: String, pathWithQuery: String): String =
            method + "\n" + TuyaCrypto.sha256Hex(body.toByteArray()) + "\n" + "\n" + pathWithQuery

        fun sign(secret: String, clientId: String, token: String?, t: String, nonce: String, toSign: String): String {
            val str = clientId + (token ?: "") + t + nonce + toSign
            return TuyaCrypto.hmacSha256(secret.toByteArray(), str.toByteArray()).toHex().uppercase()
        }

        fun explain(code: Int, msg: String): String = when (code) {
            1004 -> "Невірний підпис — перевір Access Secret"
            1010, 1011 -> "Токен недійсний"
            1106 -> "Немає доступу до пристрою — прив'яжи акаунт Smart Life до хмарного проєкту"
            2009 -> "Невірний Access ID"
            2017 -> "Невірний регіон (дата-центр)"
            28841101, 28841002 -> "API не підписано або пробний період закінчився — продовж IoT Core на iot.tuya.com"
            2008 -> "Пристрій не підтримує цю команду"
            else -> "Помилка хмари $code: $msg"
        }
    }

    private suspend fun ensureToken(): String = tokenLock.withLock {
        val now = System.currentTimeMillis()
        token?.let { if (now < tokenExpires - 60_000) return@withLock it }
        val r = rawRequest("GET", "/v1.0/token?grant_type=1", null, withToken = false)
        val res = r.getJSONObject("result")
        val t = res.getString("access_token")
        token = t
        tokenExpires = now + res.optLong("expire_time", 7200) * 1000
        t
    }

    private suspend fun rawRequest(method: String, path: String, body: String?, withToken: Boolean): JSONObject =
        withContext(Dispatchers.IO) {
            val tk = if (withToken) token else null
            val t = System.currentTimeMillis().toString()
            val sig = sign(secret, accessId, tk, t, "", stringToSign(method, body ?: "", path))
            val conn = URL("https://${region.host}$path").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.setRequestProperty("client_id", accessId)
                conn.setRequestProperty("sign", sig)
                conn.setRequestProperty("t", t)
                conn.setRequestProperty("sign_method", "HMAC-SHA256")
                if (tk != null) conn.setRequestProperty("access_token", tk)
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }
                    ?: throw IOException("Порожня відповідь хмари (HTTP ${conn.responseCode})")
                val j = JSONObject(text)
                if (!j.optBoolean("success", false)) {
                    val code = j.optInt("code", -1)
                    throw TuyaCloudException(code, explain(code, j.optString("msg")))
                }
                j
            } finally {
                conn.disconnect()
            }
        }

    /** Запит з токеном; при протермінованому токені — одна повторна спроба. */
    suspend fun request(method: String, path: String, body: String? = null): JSONObject {
        ensureToken()
        return try {
            rawRequest(method, path, body, withToken = true)
        } catch (e: TuyaCloudException) {
            if (e.code == 1010 || e.code == 1011) {
                tokenLock.withLock { token = null }
                ensureToken()
                rawRequest(method, path, body, withToken = true)
            } else throw e
        }
    }

    /** Перевірка ключів: просто отримати токен. */
    suspend fun testConnection() { ensureToken() }

    private fun parseDevice(o: JSONObject) = CloudDevice(
        id = o.optString("id"),
        name = o.optString("name"),
        localKey = o.optString("local_key"),
        online = o.optBoolean("online"),
        productName = o.optString("product_name"),
        category = o.optString("category"),
    )

    suspend fun getDevice(id: String): CloudDevice =
        parseDevice(request("GET", "/v1.0/devices/$id").getJSONObject("result"))

    /** Усі пристрої акаунтів, прив'язаних до проєкту (разом з local key). */
    suspend fun listDevices(): List<CloudDevice> {
        val out = mutableListOf<CloudDevice>()
        var lastRowKey: String? = null
        repeat(10) {
            // параметри запиту — в алфавітному порядку (вимога підпису)
            val q = if (lastRowKey != null) "?last_row_key=$lastRowKey&size=50" else "?size=50"
            val res = request("GET", "/v1.0/iot-01/associated-users/devices$q").getJSONObject("result")
            val arr = res.optJSONArray("devices") ?: JSONArray()
            for (i in 0 until arr.length()) out += parseDevice(arr.getJSONObject(i))
            if (!res.optBoolean("has_more", false)) return out
            lastRowKey = res.optString("last_row_key").ifEmpty { return out }
        }
        return out
    }

    /** Поточний статус: код → значення (switch_1, cur_power ...). */
    suspend fun getStatus(id: String): Map<String, Any?> {
        val arr = request("GET", "/v1.0/iot-03/devices/$id/status").getJSONArray("result")
        val out = LinkedHashMap<String, Any?>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out[o.getString("code")] = o.opt("value").takeIf { it != JSONObject.NULL }
        }
        return out
    }

    /** Відповідність DP → код (для перекладу локальних номерів у хмарні назви). */
    suspend fun getSpecification(id: String): Map<Int, String> {
        val res = request("GET", "/v1.0/iot-03/devices/$id/specification").getJSONObject("result")
        val out = HashMap<Int, String>()
        for (key in listOf("functions", "status")) {
            val arr = res.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val dp = o.optInt("dp_id", -1)
                if (dp >= 0) out[dp] = o.optString("code")
            }
        }
        return out
    }

    suspend fun sendCommands(id: String, commands: Map<String, Any?>) {
        val arr = JSONArray()
        commands.forEach { (c, v) -> arr.put(JSONObject().put("code", c).put("value", v ?: JSONObject.NULL)) }
        request("POST", "/v1.0/iot-03/devices/$id/commands", JSONObject().put("commands", arr).toString())
    }
}
