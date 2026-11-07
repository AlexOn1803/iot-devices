package ua.madeisy.iot.esp

import org.json.JSONArray
import org.json.JSONObject

/** Типи компонентів прошивки ESP32-C3 (поле "t" у конфігу). */
enum class EspType(val code: String, val label: String, val hint: String) {
    RELAY("relay", "Реле", "Вихід на модуль реле або MOSFET"),
    BUTTON("button", "Кнопка", "Фізична кнопка, що перемикає інший компонент"),
    PWM("pwm", "Світлодіод (PWM)", "Один колір, з яскравістю"),
    WS2812("ws2812", "Стрічка WS2812", "Адресна RGB-стрічка"),
    DHT("dht", "DHT11/DHT22", "Температура й вологість"),
    DS18B20("ds18b20", "DS18B20", "Температура (1-Wire)"),
    AHT("aht", "AHT10/AHT20", "Температура й вологість (I2C)"),
    ANALOG("analog", "Аналоговий вхід", "Напр. датчик вологості ґрунту (GPIO0–4)"),
    INPUT("input", "Цифровий вхід", "Датчик руху, геркон дверей"),
    UNKNOWN("?", "Невідомо", "");

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.code == s } ?: UNKNOWN
        val addable = listOf(RELAY, BUTTON, PWM, WS2812, DHT, DS18B20, AHT, ANALOG, INPUT)
    }
}

/** Опис компонента в конфігу пристрою (що до якого піна підключено). */
data class EspCompConfig(
    val type: EspType,
    val name: String = type.label,
    val pin: Int = -1,
    val inv: Boolean = false,
    /** Реле: стан після подачі живлення — off | on | memory. */
    val powerOn: String = "off",
    /** Кнопка: індекс компонента, який перемикати. */
    val target: Int = 0,
    val pull: Boolean = true,
    val count: Int = 30,
    val model: Int = 22,
    val sda: Int = 6,
    val scl: Int = 7,
    val min: Int = 0,
    val max: Int = 4095,
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("t", type.code).put("name", name)
        when (type) {
            EspType.RELAY -> o.put("pin", pin).put("inv", inv).put("po", powerOn)
            EspType.BUTTON -> o.put("pin", pin).put("inv", inv).put("pull", pull).put("target", target)
            EspType.PWM -> o.put("pin", pin).put("inv", inv)
            EspType.WS2812 -> o.put("pin", pin).put("count", count)
            EspType.DHT -> o.put("pin", pin).put("model", model)
            EspType.DS18B20 -> o.put("pin", pin)
            EspType.AHT -> o.put("sda", sda).put("scl", scl)
            EspType.ANALOG -> o.put("pin", pin).put("inv", inv).put("min", min).put("max", max)
            EspType.INPUT -> o.put("pin", pin).put("inv", inv).put("pull", pull)
            EspType.UNKNOWN -> {}
        }
        return o
    }

    /** Піни, які займає компонент. */
    val pins: List<Int> get() = if (type == EspType.AHT) listOf(sda, scl) else listOf(pin)

    companion object {
        fun fromJson(o: JSONObject): EspCompConfig {
            val t = EspType.parse(o.optString("t"))
            return EspCompConfig(
                type = t, name = o.optString("name", t.label), pin = o.optInt("pin", -1),
                inv = o.optBoolean("inv"), powerOn = o.optString("po", "off"),
                target = o.optInt("target", 0), pull = o.optBoolean("pull", true),
                count = o.optInt("count", 30), model = o.optInt("model", 22),
                sda = o.optInt("sda", 6), scl = o.optInt("scl", 7),
                min = o.optInt("min", 0), max = o.optInt("max", 4095),
            )
        }

        fun listFromJson(j: JSONObject): List<EspCompConfig> {
            val arr = j.optJSONArray("comps") ?: JSONArray()
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }

        fun listToJson(name: String, list: List<EspCompConfig>): JSONObject =
            JSONObject().put("name", name).put("comps", JSONArray().apply { list.forEach { put(it.toJson()) } })

        /** Піни ESP32-C3, доступні для компонентів, з підказками. */
        val PINS = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 21)

        fun pinHint(p: Int): String? = when (p) {
            2 -> "ADC, strap"
            in 0..4 -> "ADC"
            8 -> "LED на платі"
            9 -> "BOOT"
            20 -> "RX"
            21 -> "TX"
            else -> null
        }
    }
}

/** Поточний стан компонента (з /api/state). */
data class EspComp(
    val i: Int,
    val type: EspType,
    val name: String,
    val on: Boolean? = null,
    val countdown: Int = 0,
    val bri: Int? = null,
    val rgb: Triple<Int, Int, Int>? = null,
    val fx: String? = null,
    val temp: Double? = null,
    val hum: Double? = null,
    val raw: Int? = null,
    val pct: Int? = null,
    val active: Boolean? = null,
) {
    val isSwitchable: Boolean get() = type == EspType.RELAY || type == EspType.PWM || type == EspType.WS2812

    companion object {
        private fun JSONObject.optDoubleOrNull(k: String): Double? =
            if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

        private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
        private fun JSONObject.optBoolOrNull(k: String): Boolean? = if (has(k) && !isNull(k)) optBoolean(k) else null

        fun fromJson(o: JSONObject): EspComp {
            val rgb = o.optJSONArray("rgb")?.let { Triple(it.optInt(0), it.optInt(1), it.optInt(2)) }
            return EspComp(
                i = o.optInt("i"), type = EspType.parse(o.optString("t")), name = o.optString("name"),
                on = o.optBoolOrNull("on"), countdown = o.optInt("cd", 0), bri = o.optIntOrNull("bri"),
                rgb = rgb, fx = o.optString("fx").ifEmpty { null },
                temp = o.optDoubleOrNull("temp"), hum = o.optDoubleOrNull("hum"),
                raw = o.optIntOrNull("raw"), pct = o.optIntOrNull("pct"), active = o.optBoolOrNull("active"),
            )
        }

        fun listFromState(j: JSONObject): List<EspComp> {
            val arr = j.optJSONArray("comps") ?: JSONArray()
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    }
}

data class EspInfo(
    val id: String,
    val name: String,
    val fw: String,
    val ip: String,
    val rssi: Int,
    val claimed: Boolean,
) {
    companion object {
        fun fromJson(o: JSONObject) = EspInfo(
            id = o.optString("id"), name = o.optString("name"), fw = o.optString("fw"),
            ip = o.optString("ip"), rssi = o.optInt("rssi"), claimed = o.optBoolean("claimed"),
        )
    }
}

data class EspState(
    val online: Boolean = false,
    val connecting: Boolean = false,
    val comps: List<EspComp> = emptyList(),
    val rssi: Int? = null,
    val updatedAt: Long = 0,
    val error: String? = null,
) {
    /** Перший компонент, яким можна керувати однією кнопкою (для віджета й розкладу). */
    val primary: EspComp? get() = comps.firstOrNull { it.type == EspType.RELAY } ?: comps.firstOrNull { it.isSwitchable }

    fun countdownLeft(c: EspComp, now: Long = System.currentTimeMillis()): Int? {
        if (c.countdown <= 0) return null
        val left = c.countdown - ((now - updatedAt) / 1000).toInt()
        return left.takeIf { it > 0 }
    }
}
