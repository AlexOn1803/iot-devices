package ua.madeisy.iot.inverter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * Хмара інверторів SmartESS / DessMonitor (платформа Eybond).
 * Пароль ніде не зберігається: для підпису потрібен лише SHA-1 від нього.
 */
enum class EssServer(val label: String, val base: String, val authAction: String, val source: String) {
    DESS("SmartESS / DessMonitor", "https://api.dessmonitor.com/public/", "authSource", "1"),
    SHINE("ShineMonitor", "https://ios.shinemonitor.com/public/", "auth", "0");

    companion object { fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: DESS }
}

class EssException(message: String, val code: Int? = null) : IOException(message)

data class EssToken(val token: String, val secret: String, val expiresAt: Long)

/** Інвертор (пристрій за збирачем даних Wi-Fi). */
data class EssDevice(val pn: String, val sn: String, val devcode: Int, val devaddr: Int, val alias: String) {
    fun toJson(): JSONObject = JSONObject().put("pn", pn).put("sn", sn).put("devcode", devcode).put("devaddr", devaddr).put("alias", alias)

    companion object {
        fun fromJson(o: JSONObject) = EssDevice(
            o.getString("pn"), o.getString("sn"), o.optInt("devcode"), o.optInt("devaddr"), o.optString("alias"),
        )
    }
}

/** Один показник з відповіді queryDeviceLastData. */
data class EssPoint(val title: String, val value: String, val unit: String) {
    val number: Double? get() = value.trim().replace(',', '.').toDoubleOrNull()
}

object EssSign {
    fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** «&action=x&k=v…» — саме ці байти і підписуються, і йдуть у запит. */
    fun actionString(action: String, params: List<Pair<String, Any>>): String = buildString {
        append("&action=").append(action)
        for ((k, v) in params) append('&').append(k).append('=').append(URLEncoder.encode(v.toString(), "UTF-8"))
    }

    /** Підпис: SHA1(salt + SHA1(пароль) + дія) для входу, SHA1(salt + secret + token + дія) — далі. */
    fun sign(salt: String, action: String, pwdSha1: String?, token: EssToken?): String =
        if (token != null) sha1(salt + token.secret + token.token + action) else sha1(salt + (pwdSha1 ?: "") + action)

    fun url(base: String, salt: String, sign: String, token: EssToken?, action: String): String =
        base + "?sign=" + sign + "&salt=" + salt + (if (token != null) "&token=" + token.token else "") + action
}

class SmartEssClient(
    private val server: EssServer,
    private val user: String,
    private val pwdSha1: String,
    private val companyKey: String = DEFAULT_COMPANY_KEY,
    var token: EssToken? = null,
    private val onToken: (EssToken) -> Unit = {},
) {
    private suspend fun request(action: String, params: List<Pair<String, Any>>, auth: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            val tk = if (auth) null else token
            val salt = System.currentTimeMillis().toString()
            val act = EssSign.actionString(action, params)
            val url = EssSign.url(server.base, salt, EssSign.sign(salt, act, pwdSha1, tk), tk, act)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            try {
                if (conn.responseCode != 200) throw EssException("Сервер SmartESS відповів HTTP ${conn.responseCode}")
                val j = JSONObject(conn.inputStream.bufferedReader().readText())
                val err = j.optInt("err", 0)
                if (err != 0) throw EssException(explain(err, j.optString("desc")), err)
                j
            } catch (e: org.json.JSONException) {
                throw EssException("Незрозуміла відповідь сервера SmartESS")
            } finally { conn.disconnect() }
        }

    suspend fun login(): EssToken {
        val j = request(
            server.authAction,
            listOf(
                "usr" to user, "company-key" to companyKey, "source" to server.source,
                "_app_client_" to "web", "_app_id_" to "ua.madeisy.iot", "_app_version_" to "1.0",
            ),
            auth = true,
        )
        val d = j.optJSONObject("dat") ?: throw EssException("Сервер не повернув токен")
        val t = EssToken(
            d.getString("token"), d.getString("secret"),
            System.currentTimeMillis() + d.optLong("expire", 604800) * 1000 - 3_600_000,
        )
        token = t
        onToken(t)
        return t
    }

    /** Запит з токеном; якщо токен прострочений чи відкликаний — входимо ще раз. */
    private suspend fun call(action: String, params: List<Pair<String, Any>>): JSONObject {
        val t = token
        if (t == null || System.currentTimeMillis() > t.expiresAt) login()
        return try {
            request(action, params)
        } catch (e: EssException) {
            // токен відкликали (наприклад, вхід з іншого пристрою) — входимо ще раз і повторюємо
            if (e.code != null && t != null) { login(); request(action, params) } else throw e
        }
    }

    /** Усі інвертори акаунта: електростанції → збирачі даних → пристрої. */
    suspend fun discover(): List<EssDevice> {
        val plants = call("queryPlants", listOf("pagesize" to 50)).optJSONObject("dat")?.optJSONArray("plant") ?: JSONArray()
        val out = mutableListOf<EssDevice>()
        for (i in 0 until plants.length()) {
            val pid = plants.optJSONObject(i)?.optLong("pid") ?: continue
            var page = 0
            while (page < 10) {
                val d = runCatching { call("webQueryCollectorsEs", listOf("pid" to pid, "page" to page, "pagesize" to 50)) }
                    .getOrNull()?.optJSONObject("dat") ?: break
                val cols = d.optJSONArray("collector") ?: break
                for (c in 0 until cols.length()) {
                    val pn = cols.optJSONObject(c)?.optString("pn").orEmpty()
                    if (pn.isBlank() || out.any { it.pn == pn }) continue
                    val devs = runCatching { call("queryCollectorDevices", listOf("pn" to pn)) }
                        .getOrNull()?.optJSONObject("dat")?.optJSONArray("dev") ?: continue
                    for (k in 0 until devs.length()) {
                        val o = devs.optJSONObject(k) ?: continue
                        val sn = o.optString("sn")
                        if (sn.isBlank()) continue
                        out += EssDevice(pn, sn, o.optInt("devcode"), o.optInt("devaddr"),
                            o.optString("devalias").ifBlank { o.optString("alias") }.ifBlank { "Інвертор ${sn.takeLast(4)}" })
                    }
                }
                if (cols.length() < 50) break
                page++
            }
        }
        return out
    }

    suspend fun lastData(d: EssDevice): List<EssPoint> {
        val j = call(
            "queryDeviceLastData",
            listOf("pn" to d.pn, "devcode" to d.devcode, "devaddr" to d.devaddr, "sn" to d.sn, "i18n" to "en"),
        )
        return parsePoints(j.optJSONArray("dat"))
    }

    companion object {
        /** Ключ компанії застосунку SmartESS (той самий для всіх акаунтів). */
        const val DEFAULT_COMPANY_KEY = "bnrl_frRFjEz8Mkn"

        fun parsePoints(a: JSONArray?): List<EssPoint> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
                val t = o.optString("title").trim()
                if (t.isEmpty()) null else EssPoint(t, o.optString("val").trim(), o.optString("unit").trim())
            }
        }

        fun explain(err: Int, desc: String): String = when {
            desc.contains("PASSWORD", true) || desc.contains("USR", true) || desc.contains("ACCOUNT", true) ->
                "Невірний логін або пароль SmartESS"
            desc.contains("NO_RECORD", true) -> "Немає даних (інвертор офлайн?)"
            desc.contains("SIGN", true) -> "Сервер відхилив підпис — спробуй увійти ще раз"
            else -> "SmartESS: ${desc.ifBlank { "помилка $err" }}"
        }
    }
}

/** Головні показники, витягнуті з різних назв полів різних моделей інверторів. */
data class EssSummary(
    val pvW: Double? = null,
    val loadW: Double? = null,
    val loadPct: Double? = null,
    val socPct: Double? = null,
    val batteryV: Double? = null,
    /** + заряд, − розряд. */
    val batteryW: Double? = null,
    val gridV: Double? = null,
    val gridW: Double? = null,
    val mode: String? = null,
    val todayKwh: Double? = null,
) {
    val gridPresent: Boolean? get() = gridV?.let { it > 100 }

    companion object {
        private fun norm(s: String) = s.lowercase(Locale.US).replace(Regex("\\s+"), " ").trim()

        fun of(points: List<EssPoint>): EssSummary {
            val m = points.associateBy { norm(it.title) }
            fun num(vararg keys: String): Double? = keys.firstNotNullOfOrNull { k -> m[k]?.number }
            fun watts(p: EssPoint?): Double? = p?.number?.let { if (p.unit.equals("kW", true)) it * 1000 else it }
            fun w(vararg keys: String): Double? = keys.firstNotNullOfOrNull { k -> watts(m[k]) }

            val pv = w("pv total power", "pv power", "total pv power", "pv input power", "solar power", "dc output power")
                ?: points.filter { norm(it.title).matches(Regex("pv\\d (input |charging |charger )?power")) }
                    .mapNotNull { watts(it) }.takeIf { it.isNotEmpty() }?.sum()
            val bv = num("battery voltage", "bms battery voltage")
            val chg = num("battery charging current", "pv charging current")
            val dis = num("battery discharge current")
            val bw = w("battery power") ?: if (bv != null && (chg != null || dis != null)) bv * ((chg ?: 0.0) - (dis ?: 0.0)) else null
            return EssSummary(
                pvW = pv,
                loadW = w("output active power", "ac output active power", "active power", "load power", "load active power"),
                loadPct = num("ac output load", "load percent", "output load percent", "load percentage", "output load"),
                socPct = num("battery capacity", "soc", "state of charge", "battery soc", "bms soc"),
                batteryV = bv,
                batteryW = bw,
                gridV = num("ac input voltage", "grid voltage", "mains voltage"),
                gridW = w("grid power", "ac input power", "ac input active power"),
                mode = listOf("working state", "operating mode", "work state", "work mode", "mode")
                    .firstNotNullOfOrNull { m[it]?.value?.takeIf { v -> v.isNotBlank() } },
                todayKwh = num("today energy", "energy today", "pv energy today", "today generation"),
            )
        }

        /** Українські назви для найчастіших полів; решта показується як є. */
        val TITLES = mapOf(
            "working state" to "Режим роботи", "operating mode" to "Режим роботи", "work state" to "Режим роботи",
            "ac input voltage" to "Напруга мережі", "ac input frequency" to "Частота мережі",
            "grid voltage" to "Напруга мережі", "grid frequency" to "Частота мережі", "grid power" to "Потужність мережі",
            "pv input voltage" to "Напруга PV", "pv input power" to "Потужність PV", "pv total power" to "Потужність PV (усього)",
            "pv1 input voltage" to "Напруга PV1", "pv1 input power" to "Потужність PV1",
            "pv2 input voltage" to "Напруга PV2", "pv2 input power" to "Потужність PV2",
            "pv power" to "Потужність PV", "pv voltage" to "Напруга PV",
            "battery voltage" to "Напруга батареї", "battery capacity" to "Заряд батареї", "soc" to "Заряд батареї",
            "battery charging current" to "Струм заряду", "battery discharge current" to "Струм розряду",
            "battery power" to "Потужність батареї", "battery piece" to "Кількість батарей",
            "output voltage" to "Напруга виходу", "output frequency" to "Частота виходу",
            "output apparent power" to "Повна потужність виходу", "output active power" to "Навантаження",
            "ac output load" to "Навантаження, %", "load percent" to "Навантаження, %",
            "output source priority" to "Пріоритет живлення", "charger source priority" to "Пріоритет заряду",
            "machine type" to "Тип пристрою", "main cpu version" to "Версія ПЗ (основна)",
            "secondary cpu version" to "Версія ПЗ (додаткова)",
            "inv module termperature" to "Температура інвертора", "dc module termperature" to "Температура DC-модуля",
            "inverter temperature" to "Температура інвертора",
            "today energy" to "Сьогодні", "total energy" to "Усього", "energy today" to "Сьогодні", "energy total" to "Усього",
        )

        fun titleUa(t: String): String = TITLES[norm(t)] ?: t

        fun modeUa(m: String): String = when {
            m.contains("line", true) || m.contains("grid", true) || m.contains("bypass", true) -> "Від мережі"
            m.contains("battery", true) -> "Від батареї"
            m.contains("standby", true) -> "Очікування"
            m.contains("fault", true) -> "Аварія"
            m.contains("power on", true) -> "Увімкнення"
            m.contains("charg", true) -> "Заряд"
            else -> m
        }
    }
}
