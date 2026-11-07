package ua.madeisy.iot.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import ua.madeisy.iot.service.Notifications
import java.util.Locale
import java.util.UUID

enum class ThermoMode(val label: String) {
    HEAT("Обігрів"), COOL("Охолодження");

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: HEAT
    }
}

/**
 * Термостат: датчик температури (компонент плати ESP) керує розеткою чи реле.
 * Обігрів: при t ≤ [onAt] — увімкнути, при t ≥ [offAt] — вимкнути (між ними нічого не змінюється).
 * Охолодження — навпаки.
 */
data class Thermostat(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Термостат",
    val enabled: Boolean = true,
    val sensorKey: String = "",
    val sensorIndex: Int = 0,
    val targetKey: String = "",
    val mode: ThermoMode = ThermoMode.HEAT,
    val onAt: Double = 18.0,
    val offAt: Double = 19.0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("enabled", enabled)
        .put("sensorKey", sensorKey).put("sensorIndex", sensorIndex).put("targetKey", targetKey)
        .put("mode", mode.name).put("onAt", onAt).put("offAt", offAt)

    val valid: Boolean
        get() = sensorKey.isNotBlank() && targetKey.isNotBlank() &&
            (if (mode == ThermoMode.HEAT) offAt > onAt else offAt < onAt)

    fun describe(): String {
        val f = { v: Double -> String.format(Locale.US, "%.1f °C", v) }
        return if (mode == ThermoMode.HEAT) "≤ ${f(onAt)} увімкнути · ≥ ${f(offAt)} вимкнути"
        else "≥ ${f(onAt)} увімкнути · ≤ ${f(offAt)} вимкнути"
    }

    companion object {
        fun fromJson(j: JSONObject) = Thermostat(
            id = j.getString("id"), name = j.optString("name", "Термостат"), enabled = j.optBoolean("enabled", true),
            sensorKey = j.optString("sensorKey"), sensorIndex = j.optInt("sensorIndex"), targetKey = j.optString("targetKey"),
            mode = ThermoMode.parse(j.optString("mode")), onAt = j.optDouble("onAt", 18.0), offAt = j.optDouble("offAt", 19.0),
        )
    }
}

/** Що термостат зробив останнім (тримається в пам'яті, для екрана). */
data class ThermoStatus(val temp: Double?, val text: String, val at: Long)

/**
 * Перевіряє термостати кожні 10 с. Поки є хоч один увімкнений термостат, тримає з'єднання
 * з його датчиком і розеткою. Цілодобово працює лише з фоновим моніторингом (інакше Android
 * може зупинити додаток у фоні).
 */
class ThermostatEngine(
    private val ctx: Context,
    private val storage: Storage,
    private val controller: PlugController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val held = mutableSetOf<String>()
    private val lastCommand = HashMap<String, Pair<Boolean, Long>>()   // id → (стан, коли)
    private val safetyTripped = HashSet<String>()

    val status = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ThermoStatus>>(emptyMap())

    companion object {
        const val MIN_SWITCH_INTERVAL = 60_000L
        const val SENSOR_STALE = 5 * 60_000L
    }

    fun start() {
        scope.launch {
            while (true) {
                try { tick() } catch (e: Exception) { if (e is CancellationException) throw e }
                delay(10_000)
            }
        }
    }

    private fun setStatus(id: String, temp: Double?, text: String) {
        status.value = status.value + (id to ThermoStatus(temp, text, System.currentTimeMillis()))
    }

    private suspend fun tick() {
        val list = storage.thermostats.value.filter { it.enabled && it.valid }
        // утримання з'єднань з потрібними пристроями
        val need = list.flatMap { listOf(it.sensorKey, it.targetKey) }.toSet()
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            (need - held).forEach { controller.acquire(it); held += it }
            (held - need).toList().forEach { controller.release(it); held -= it }
        }
        val now = System.currentTimeMillis()
        for (t in list) {
            val sensor = controller.espState(t.sensorKey)
            val comp = sensor.comps.firstOrNull { it.i == t.sensorIndex }
            val temp = comp?.temp
            val fresh = sensor.online && now - sensor.updatedAt < SENSOR_STALE
            val target = storage.device(t.targetKey) ?: continue
            val targetOn = controller.summary(target).on ?: lastCommand[t.id]?.first

            if (temp == null || !fresh) {
                // датчик пропав — з міркувань безпеки вимикаємо обігрів (охолодження не чіпаємо)
                val lostFor = now - sensor.updatedAt
                if (t.mode == ThermoMode.HEAT && targetOn == true && sensor.updatedAt > 0 && lostFor > SENSOR_STALE && t.id !in safetyTripped) {
                    if (controller.setSwitch(t.targetKey, false).isSuccess) {
                        safetyTripped += t.id
                        lastCommand[t.id] = false to now
                        Notifications.alert(ctx, t.name, "Датчик температури недоступний — обігрів вимкнено")
                    }
                }
                setStatus(t.id, null, "Немає даних з датчика")
                continue
            }
            safetyTripped -= t.id
            val desired: Boolean? = when (t.mode) {
                ThermoMode.HEAT -> when { temp <= t.onAt -> true; temp >= t.offAt -> false; else -> null }
                ThermoMode.COOL -> when { temp >= t.onAt -> true; temp <= t.offAt -> false; else -> null }
            }
            val last = lastCommand[t.id]
            if (desired != null && desired != targetOn && (last == null || now - last.second > MIN_SWITCH_INTERVAL)) {
                val r = controller.setSwitch(t.targetKey, desired)
                lastCommand[t.id] = desired to now
                val tt = String.format(Locale.US, "%.1f °C", temp)
                setStatus(t.id, temp, if (r.isSuccess) "${if (desired) "Увімкнено" else "Вимкнено"} при $tt"
                    else "Не вдалося ${if (desired) "увімкнути" else "вимкнути"}: ${r.exceptionOrNull()?.message}")
            } else {
                val prev = status.value[t.id]
                if (prev == null || prev.temp != temp) setStatus(t.id, temp, prev?.text ?: (if (targetOn == true) "Працює" else "Очікує"))
            }
        }
    }
}
