package ua.madeisy.iot.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.data.DeviceConfig
import ua.madeisy.iot.data.HistoryDb
import ua.madeisy.iot.esp.EspComp
import ua.madeisy.iot.esp.EspType
import ua.madeisy.iot.ui.formatEnergy
import ua.madeisy.iot.ui.formatPower
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class WidgetSize(val maxMetrics: Int, val label: String) {
    SMALL(1, "Кнопка 1×1"), MEDIUM(2, "Смужка 3×1"), LARGE(4, "Панель 3×2")
}

/**
 * Налаштування одного віджета.
 * [toggle]: -2 — головний перемикач пристрою, -1 — без кнопки, ≥0 — компонент плати ESP.
 * [metrics]: що показувати, у порядку вибору (id з [WidgetData]).
 */
data class WidgetCfg(
    val deviceKey: String,
    val toggle: Int = AUTO,
    val metrics: List<String> = emptyList(),
) {
    fun toJson(): String = JSONObject().put("device", deviceKey).put("toggle", toggle)
        .put("metrics", JSONArray(metrics)).toString()

    companion object {
        const val AUTO = -2
        const val NONE = -1

        fun parse(s: String?): WidgetCfg? {
            if (s.isNullOrBlank()) return null
            // старі віджети зберігали лише ключ пристрою
            if (!s.startsWith("{")) return WidgetCfg(s)
            return runCatching {
                val j = JSONObject(s)
                val arr = j.optJSONArray("metrics") ?: JSONArray()
                WidgetCfg(j.getString("device"), j.optInt("toggle", AUTO), (0 until arr.length()).map { arr.getString(it) })
            }.getOrNull()
        }
    }
}

data class Metric(val id: String, val label: String, val value: String)

/** Які дані пристрою можна показати на віджеті, і їхні поточні значення. */
object WidgetData {
    private val todayCache = ConcurrentHashMap<String, Pair<Double, Long>>()

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)

    /** Енергія розетки за сьогодні (кешується на хвилину — запит до історії не дешевий). */
    private fun todayKwh(key: String): Double {
        val now = System.currentTimeMillis()
        todayCache[key]?.let { (v, at) -> if (now - at < 60_000) return v }
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val v = runCatching { HistoryDb.energyKwh(IotApp.instance.history.since(key, start)) }.getOrDefault(0.0)
        todayCache[key] = v to now
        return v
    }

    /** Усі доступні метрики пристрою. */
    fun available(cfg: DeviceConfig): List<Metric> {
        val c = IotApp.instance.controller
        if (!cfg.isEsp) {
            val s = c.state(cfg.key)
            return listOf(
                Metric("power", "Потужність", if (s.on == false) "0 Вт" else formatPower(s.powerW)),
                Metric("voltage", "Напруга", s.voltageV?.let { "${f1(it)} В" } ?: "—"),
                Metric("current", "Струм", s.currentA?.let { String.format(Locale.US, "%.2f А", it) } ?: "—"),
                Metric("today", "Сьогодні", formatEnergy(todayKwh(cfg.key))),
                Metric("state", "Стан", when (s.on) { true -> "Увімк."; false -> "Вимк."; null -> "—" }),
            )
        }
        val out = mutableListOf<Metric>()
        for (comp in c.espState(cfg.key).comps) out += espMetrics(comp)
        return out
    }

    private fun espMetrics(c: EspComp): List<Metric> {
        val p = "c${c.i}"
        return when (c.type) {
            EspType.RELAY, EspType.PWM, EspType.WS2812 ->
                listOf(Metric("$p:on", c.name, when (c.on) { true -> "Увімк."; false -> "Вимк."; null -> "—" }))
            EspType.DHT, EspType.AHT, EspType.BME, EspType.DS18B20 -> buildList {
                add(Metric("$p:temp", c.name, c.temp?.let { "${f1(it)} °C" } ?: "—"))
                if (c.type != EspType.DS18B20 && (c.hum != null || c.type != EspType.BME))
                    add(Metric("$p:hum", "${c.name}: вологість", c.hum?.let { "${it.toInt()} %" } ?: "—"))
                if (c.type == EspType.BME) add(Metric("$p:pres", "${c.name}: тиск", c.pres?.let { "${(it * 0.750062).toInt()} мм" } ?: "—"))
            }
            EspType.ANALOG -> listOf(Metric("$p:pct", c.name, c.pct?.let { "$it %" } ?: "—"))
            EspType.INPUT -> listOf(Metric("$p:active", c.name, when (c.active) { true -> "Активно"; false -> "Спокій"; null -> "—" }))
            EspType.THERMO -> {
                val st = when (c.thermoState) {
                    "on" -> if (c.heat) " · гріє" else " · охолоджує"
                    "off" -> " · очікує"
                    "nosensor" -> " · немає датчика"
                    else -> " · вимк."
                }
                listOf(Metric("$p:thermo", c.name, (c.temp?.let { "${f1(it)}°" } ?: "—") + st))
            }
            else -> emptyList()
        }
    }

    /** Метрики за налаштуванням віджета; якщо нічого не вибрано — типові. */
    fun selected(cfg: DeviceConfig, w: WidgetCfg, size: WidgetSize): List<Metric> {
        val all = available(cfg)
        val chosen = if (w.metrics.isNotEmpty()) w.metrics.mapNotNull { id -> all.firstOrNull { it.id == id } }
        else defaults(cfg, all)
        return chosen.take(size.maxMetrics)
    }

    fun defaults(cfg: DeviceConfig, all: List<Metric> = available(cfg)): List<Metric> =
        if (!cfg.isEsp) all.filter { it.id != "state" }
        else all.filterNot { it.id.endsWith(":on") }.ifEmpty { all }

    /** Компонент, яким керує кнопка віджета (для ESP), або null. */
    fun toggleComp(cfg: DeviceConfig, w: WidgetCfg): EspComp? {
        val st = IotApp.instance.controller.espState(cfg.key)
        return when (w.toggle) {
            WidgetCfg.NONE -> null
            WidgetCfg.AUTO -> st.primary
            else -> st.comps.firstOrNull { it.i == w.toggle && it.isSwitchable }
        }
    }

    /** Стан кнопки: true/false, або null — якщо кнопки немає/стан невідомий. */
    fun toggleOn(cfg: DeviceConfig, w: WidgetCfg): Boolean? =
        if (w.toggle == WidgetCfg.NONE) null
        else if (!cfg.isEsp) IotApp.instance.controller.state(cfg.key).on
        else toggleComp(cfg, w)?.on

    fun hasToggle(cfg: DeviceConfig, w: WidgetCfg): Boolean =
        w.toggle != WidgetCfg.NONE && (!cfg.isEsp || toggleComp(cfg, w) != null || IotApp.instance.controller.espState(cfg.key).comps.isEmpty())

    // ---- збереження ----
    private const val PREFS = "widgets"

    fun load(ctx: Context, widgetId: Int): WidgetCfg? =
        WidgetCfg.parse(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("w$widgetId", null))

    fun save(ctx: Context, widgetId: Int, w: WidgetCfg) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("w$widgetId", w.toJson()).apply()
    }

    fun remove(ctx: Context, ids: IntArray) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        ids.forEach { e.remove("w$it") }
        e.apply()
    }
}
