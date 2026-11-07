package ua.madeisy.iot.data

import org.json.JSONObject
import java.util.UUID

enum class ConnectionMode(val label: String) {
    AUTO("Авто"), LOCAL("Лише локально"), CLOUD("Лише хмара");

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: AUTO
    }
}

/** Номери точок даних (DP) розетки. Типові значення — для розеток Tuya з вимірюванням енергії. */
data class DpMap(
    val switch: Int = 1,
    val countdown: Int = 9,
    val energy: Int = 17,
    val current: Int = 18,
    val power: Int = 19,
    val voltage: Int = 20,
    val relayStatus: Int = 38,
    /** Ділення на 10^scale. Типово потужність і напруга приходять ×10. */
    val powerScale: Int = 1,
    val voltageScale: Int = 1,
    /** Струм у мА → scale 3 дає ампери. */
    val currentScale: Int = 3,
    val energyScale: Int = 3,
) {
    val measured: List<Int> get() = listOf(current, power, voltage)

    fun toJson(): JSONObject = JSONObject()
        .put("switch", switch).put("countdown", countdown).put("energy", energy)
        .put("current", current).put("power", power).put("voltage", voltage)
        .put("relayStatus", relayStatus).put("powerScale", powerScale)
        .put("voltageScale", voltageScale).put("currentScale", currentScale)
        .put("energyScale", energyScale)

    companion object {
        fun fromJson(j: JSONObject?): DpMap {
            if (j == null) return DpMap()
            val d = DpMap()
            return DpMap(
                switch = j.optInt("switch", d.switch),
                countdown = j.optInt("countdown", d.countdown),
                energy = j.optInt("energy", d.energy),
                current = j.optInt("current", d.current),
                power = j.optInt("power", d.power),
                voltage = j.optInt("voltage", d.voltage),
                relayStatus = j.optInt("relayStatus", d.relayStatus),
                powerScale = j.optInt("powerScale", d.powerScale),
                voltageScale = j.optInt("voltageScale", d.voltageScale),
                currentScale = j.optInt("currentScale", d.currentScale),
                energyScale = j.optInt("energyScale", d.energyScale),
            )
        }

        /** Типові хмарні коди Tuya для цих DP. */
        val DEFAULT_CLOUD_CODES = mapOf(
            1 to "switch_1", 9 to "countdown_1", 17 to "add_ele", 18 to "cur_current",
            19 to "cur_power", 20 to "cur_voltage", 38 to "relay_status",
        )
    }
}

data class DeviceConfig(
    val key: String = UUID.randomUUID().toString(),
    val name: String = "Розетка",
    val deviceId: String = "",
    val localKey: String = "",
    val ip: String = "",
    val version: String = "3.3",
    val mode: ConnectionMode = ConnectionMode.AUTO,
    val dps: DpMap = DpMap(),
    /** DP → хмарний код; заповнюється зі специфікації пристрою. */
    val cloudCodes: Map<Int, String> = emptyMap(),
) {
    val canLocal: Boolean get() = deviceId.isNotBlank() && localKey.length == 16
    val canCloudId: Boolean get() = deviceId.isNotBlank()

    fun codeFor(dp: Int): String? = cloudCodes[dp] ?: DpMap.DEFAULT_CLOUD_CODES[defaultIndex(dp)]

    /** Якщо номери DP змінені, переводимо їх у типові, щоб знайти хмарний код. */
    private fun defaultIndex(dp: Int): Int = when (dp) {
        dps.switch -> 1; dps.countdown -> 9; dps.energy -> 17; dps.current -> 18
        dps.power -> 19; dps.voltage -> 20; dps.relayStatus -> 38; else -> dp
    }

    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("name", name).put("deviceId", deviceId).put("localKey", localKey)
        .put("ip", ip).put("version", version).put("mode", mode.name).put("dps", dps.toJson())
        .put("cloudCodes", JSONObject().apply { cloudCodes.forEach { (k, v) -> put(k.toString(), v) } })

    companion object {
        fun fromJson(j: JSONObject): DeviceConfig {
            val codes = HashMap<Int, String>()
            j.optJSONObject("cloudCodes")?.let { o ->
                o.keys().forEach { k -> k.toIntOrNull()?.let { codes[it] = o.getString(k) } }
            }
            return DeviceConfig(
                key = j.optString("key").ifEmpty { UUID.randomUUID().toString() },
                name = j.optString("name", "Розетка"),
                deviceId = j.optString("deviceId"),
                localKey = j.optString("localKey"),
                ip = j.optString("ip"),
                version = j.optString("version", "3.3"),
                mode = ConnectionMode.parse(j.optString("mode")),
                dps = DpMap.fromJson(j.optJSONObject("dps")),
                cloudCodes = codes,
            )
        }
    }
}

enum class Source { NONE, LOCAL, CLOUD }

data class PlugState(
    val source: Source = Source.NONE,
    val connecting: Boolean = false,
    val on: Boolean? = null,
    val powerW: Double? = null,
    val voltageV: Double? = null,
    val currentA: Double? = null,
    val countdownSec: Int? = null,
    /** Коли отримано countdownSec — щоб рахувати залишок локально. */
    val countdownAt: Long = 0,
    val relayStatus: String? = null,
    val raw: Map<String, Any?> = emptyMap(),
    val updatedAt: Long = 0,
    val error: String? = null,
) {
    val online: Boolean get() = source != Source.NONE

    fun countdownLeft(now: Long = System.currentTimeMillis()): Int? {
        val c = countdownSec ?: return null
        if (c <= 0) return null
        val left = c - ((now - countdownAt) / 1000).toInt()
        return left.takeIf { it > 0 }
    }
}

data class Schedule(
    val id: String = UUID.randomUUID().toString(),
    val deviceKey: String,
    val hour: Int,
    val minute: Int,
    /** Біт 0 = понеділок … біт 6 = неділя. 0 — одноразово. */
    val days: Int,
    val turnOn: Boolean,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("deviceKey", deviceKey)
        .put("hour", hour).put("minute", minute).put("days", days)
        .put("turnOn", turnOn).put("enabled", enabled)

    companion object {
        fun fromJson(j: JSONObject) = Schedule(
            id = j.getString("id"), deviceKey = j.getString("deviceKey"),
            hour = j.getInt("hour"), minute = j.getInt("minute"), days = j.getInt("days"),
            turnOn = j.getBoolean("turnOn"), enabled = j.optBoolean("enabled", true),
        )
    }
}
