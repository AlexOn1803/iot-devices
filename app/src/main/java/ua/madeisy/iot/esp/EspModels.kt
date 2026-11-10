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
    THERMO("thermo", "Термостат", "Датчик цієї плати керує її реле — працює без телефона"),
    BME("bme", "BME280/BMP280", "Температура, вологість, тиск (I2C)"),
    UNKNOWN("?", "Невідомо", "");

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.code == s } ?: UNKNOWN
        val addable = listOf(RELAY, BUTTON, PWM, WS2812, DHT, DS18B20, AHT, BME, ANALOG, INPUT, THERMO)
        val tempSensors = setOf(DHT, DS18B20, AHT, BME)
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
    /** Термостат: індекс датчика; реле — у [target]. */
    val sensor: Int = -1,
    val heat: Boolean = true,
    val onAt: Double = 18.0,
    val offAt: Double = 19.0,
    val enabled: Boolean = true,
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
            EspType.AHT, EspType.BME -> o.put("sda", sda).put("scl", scl)
            EspType.ANALOG -> o.put("pin", pin).put("inv", inv).put("min", min).put("max", max)
            EspType.INPUT -> o.put("pin", pin).put("inv", inv).put("pull", pull)
            EspType.THERMO -> o.put("sensor", sensor).put("target", target).put("mode", if (heat) "heat" else "cool")
                .put("onAt", onAt).put("offAt", offAt).put("enabled", enabled)
            EspType.UNKNOWN -> {}
        }
        return o
    }

    /** Піни, які займає компонент. */
    val pins: List<Int> get() = when (type) {
        EspType.AHT, EspType.BME -> listOf(sda, scl)
        EspType.THERMO -> emptyList()
        else -> listOf(pin)
    }

    val thermoValid: Boolean get() = sensor >= 0 && target >= 0 && (if (heat) offAt > onAt else offAt < onAt)

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
                sensor = o.optInt("sensor", -1), heat = o.optString("mode", "heat") != "cool",
                onAt = o.optDouble("onAt", 18.0), offAt = o.optDouble("offAt", 19.0), enabled = o.optBoolean("enabled", true),
            )
        }

        fun listFromJson(j: JSONObject): List<EspCompConfig> {
            val arr = j.optJSONArray("comps") ?: JSONArray()
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }

        fun listToJson(name: String, list: List<EspCompConfig>): JSONObject =
            JSONObject().put("name", name).put("comps", JSONArray().apply { list.forEach { put(it.toJson()) } })

        /** Піни, доступні для компонентів на кожній платі. */
        fun pinsFor(board: String): List<Int> = when (board) {
            "esp8266" -> listOf(16, 5, 4, 0, 2, 14, 12, 13, 15, 3, 1, 17)
            "esp01" -> listOf(0, 2, 3, 1)
            "esp32" -> listOf(2, 4, 5, 12, 13, 14, 15, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27, 32, 33, 34, 35, 36, 39, 0)
            "esp32s2" -> (1..18).toList() + listOf(21) + (33..40).toList() + listOf(0)
            "esp32s3" -> (1..18).toList() + listOf(21) + (35..48).toList() + listOf(0)
            else -> listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 21)   // esp32c3
        }

        fun analogPins(board: String): List<Int> = when (board) {
            "esp8266", "esp01" -> listOf(17)
            "esp32" -> listOf(32, 33, 34, 35, 36, 39)
            "esp32s2", "esp32s3" -> (1..10).toList()
            else -> listOf(0, 1, 2, 3, 4)
        }

        /** Світлодіод стану за замовчуванням (так само, як у прошивці). */
        fun defaultLed(board: String): JSONObject = when (board) {
            "esp32c3" -> JSONObject().put("pin", 8).put("inv", true)
            "esp32" -> JSONObject().put("pin", 2).put("inv", false)
            "esp32s2" -> JSONObject().put("pin", 15).put("inv", false)
            "esp32s3" -> JSONObject().put("pin", 48).put("inv", false).put("rgb", true)
            else -> JSONObject().put("pin", 2).put("inv", true)
        }.put("en", true)

        /** Куди додаток пише блок налаштувань (має збігатися з прошивкою — перевіряє CI). */
        fun settingsAddress(board: String): Int = when (board) {
            "esp8266" -> 0x3FB000
            "esp01" -> 0xFB000
            else -> 0x290000
        }

        fun adcMax(board: String) = if (board == "esp8266" || board == "esp01") 1023 else 4095

        private val D1_LABELS = mapOf(16 to "D0", 5 to "D1", 4 to "D2", 0 to "D3", 2 to "D4", 14 to "D5", 12 to "D6", 13 to "D7", 15 to "D8", 3 to "RX", 1 to "TX", 17 to "A0")

        fun pinLabel(p: Int, board: String): String =
            if (board == "esp8266" && D1_LABELS.containsKey(p)) "${D1_LABELS[p]} (GPIO $p)"
            else if (board == "esp01" || board == "esp8266") (if (p == 17) "A0" else "GPIO $p") else "GPIO $p"

        fun pinHint(p: Int, board: String = "esp32c3"): String? = when (board) {
            "esp8266" -> when (p) { 0 -> "FLASH, strap"; 2 -> "LED, strap"; 15 -> "strap, до GND"; 16 -> "без PWM/I2C"; 1, 3 -> "UART"; 17 -> "аналог 0–1 В"; else -> null }
            "esp01" -> when (p) { 0 -> "реле на ESP-01S, strap"; 2 -> "strap"; 1 -> "TX"; 3 -> "RX"; else -> null }
            "esp32" -> when (p) { 0 -> "BOOT"; 2 -> "LED, strap"; 12 -> "strap"; 15 -> "strap"; in 34..39 -> "лише вхід"; else -> null }
            "esp32s2", "esp32s3" -> when (p) { 0 -> "BOOT"; in 1..10 -> "ADC"; 45, 46 -> "strap"; else -> null }
            else -> when (p) {
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
    val enabled: Boolean? = null,
    val heat: Boolean = true,
    val onAt: Double? = null,
    val offAt: Double? = null,
    val thermoState: String? = null,
    val sensor: Int = -1,
    val target: Int = -1,
    val pres: Double? = null,
    val model: String? = null,
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
                enabled = o.optBoolOrNull("enabled"), heat = o.optString("mode", "heat") != "cool",
                onAt = o.optDoubleOrNull("onAt"), offAt = o.optDoubleOrNull("offAt"),
                thermoState = o.optString("state").ifEmpty { null },
                sensor = o.optInt("sensor", -1), target = o.optInt("target", -1),
                pres = o.optDoubleOrNull("pres"), model = o.optString("model").ifEmpty { null },
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
    val chip: String = "",
    /** Тип збірки прошивки: esp32c3, esp32, esp32s3, esp32s2, esp8266, esp01. */
    val board: String = "",
    val mqtt: String = "",
) {
    companion object {
        fun fromJson(o: JSONObject) = EspInfo(
            id = o.optString("id"), name = o.optString("name"), fw = o.optString("fw"),
            ip = o.optString("ip"), rssi = o.optInt("rssi"), claimed = o.optBoolean("claimed"),
            chip = o.optString("chip"), board = o.optString("board"), mqtt = o.optString("mqtt"),
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
    /** true — дані прийшли через інтернет (MQTT), а не напряму. */
    val remote: Boolean = false,
) {
    /** Перший компонент, яким можна керувати однією кнопкою (для віджета й розкладу). */
    val primary: EspComp? get() = comps.firstOrNull { it.type == EspType.RELAY } ?: comps.firstOrNull { it.isSwitchable }

    fun countdownLeft(c: EspComp, now: Long = System.currentTimeMillis()): Int? {
        if (c.countdown <= 0) return null
        val left = c.countdown - ((now - updatedAt) / 1000).toInt()
        return left.takeIf { it > 0 }
    }
}
