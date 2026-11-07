package ua.madeisy.iot.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import ua.madeisy.iot.tuya.CloudRegion

data class CloudSettings(val accessId: String = "", val secret: String = "", val region: CloudRegion = CloudRegion.EU) {
    val configured: Boolean get() = accessId.isNotBlank() && secret.isNotBlank()
}

data class AppSettings(
    /** Тариф, грн за кВт·год. */
    val tariff: Double = 4.32,
    val backgroundMonitor: Boolean = false,
)

/** Налаштування, пристрої та розклади (SharedPreferences, JSON). Ключі зберігаються лише на телефоні. */
class Storage(context: Context) {
    private val prefs = context.getSharedPreferences("iot", Context.MODE_PRIVATE)

    private val _devices = MutableStateFlow(loadDevices())
    val devices: StateFlow<List<DeviceConfig>> = _devices

    private val _cloud = MutableStateFlow(loadCloud())
    val cloud: StateFlow<CloudSettings> = _cloud

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings

    private val _schedules = MutableStateFlow(loadSchedules())
    val schedules: StateFlow<List<Schedule>> = _schedules

    private fun loadDevices(): List<DeviceConfig> = try {
        val arr = JSONArray(prefs.getString("devices", "[]"))
        (0 until arr.length()).map { DeviceConfig.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    private fun saveDevices(list: List<DeviceConfig>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs.edit().putString("devices", arr.toString()).apply()
        _devices.value = list
    }

    fun device(key: String): DeviceConfig? = _devices.value.firstOrNull { it.key == key }

    @Synchronized
    fun upsertDevice(d: DeviceConfig) {
        val list = _devices.value.toMutableList()
        val i = list.indexOfFirst { it.key == d.key }
        if (i >= 0) list[i] = d else list += d
        saveDevices(list)
    }

    @Synchronized
    fun updateDevice(key: String, f: (DeviceConfig) -> DeviceConfig) {
        device(key)?.let { upsertDevice(f(it)) }
    }

    @Synchronized
    fun removeDevice(key: String) {
        saveDevices(_devices.value.filterNot { it.key == key })
        saveSchedules(_schedules.value.filterNot { it.deviceKey == key })
    }

    private fun loadCloud() = CloudSettings(
        accessId = prefs.getString("cloudId", "") ?: "",
        secret = prefs.getString("cloudSecret", "") ?: "",
        region = CloudRegion.parse(prefs.getString("cloudRegion", null)),
    )

    fun saveCloud(c: CloudSettings) {
        prefs.edit().putString("cloudId", c.accessId.trim()).putString("cloudSecret", c.secret.trim())
            .putString("cloudRegion", c.region.name).apply()
        _cloud.value = c.copy(accessId = c.accessId.trim(), secret = c.secret.trim())
    }

    private fun loadSettings() = AppSettings(
        tariff = (prefs.getString("tariffS", null)?.toDoubleOrNull()) ?: Math.round(prefs.getFloat("tariff", 4.32f) * 100.0) / 100.0,
        backgroundMonitor = prefs.getBoolean("bgMonitor", false),
    )

    fun saveSettings(s: AppSettings) {
        prefs.edit().putString("tariffS", s.tariff.toString()).putBoolean("bgMonitor", s.backgroundMonitor).apply()
        _settings.value = s
    }

    private fun loadSchedules(): List<Schedule> = try {
        val arr = JSONArray(prefs.getString("schedules", "[]"))
        (0 until arr.length()).map { Schedule.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    @Synchronized
    fun saveSchedules(list: List<Schedule>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs.edit().putString("schedules", arr.toString()).apply()
        _schedules.value = list
    }

    fun upsertSchedule(s: Schedule) {
        val list = _schedules.value.toMutableList()
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list += s
        saveSchedules(list)
    }

    fun removeSchedule(id: String) = saveSchedules(_schedules.value.filterNot { it.id == id })
}

data class Sample(val ts: Long, val power: Double, val voltage: Double?, val current: Double?)

/** Історія вимірювань у SQLite. */
class HistoryDb(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE samples (device TEXT NOT NULL, ts INTEGER NOT NULL, p REAL NOT NULL, v REAL, a REAL)")
        db.execSQL("CREATE INDEX idx_dev_ts ON samples(device, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun add(device: String, s: Sample) {
        writableDatabase.insert("samples", null, ContentValues().apply {
            put("device", device); put("ts", s.ts); put("p", s.power)
            s.voltage?.let { put("v", it) }; s.current?.let { put("a", it) }
        })
    }

    fun since(device: String, fromTs: Long): List<Sample> {
        val out = ArrayList<Sample>()
        readableDatabase.rawQuery(
            "SELECT ts, p, v, a FROM samples WHERE device = ? AND ts >= ? ORDER BY ts",
            arrayOf(device, fromTs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += Sample(
                    c.getLong(0), c.getDouble(1),
                    if (c.isNull(2)) null else c.getDouble(2),
                    if (c.isNull(3)) null else c.getDouble(3),
                )
            }
        }
        return out
    }

    fun prune(olderThan: Long) {
        writableDatabase.delete("samples", "ts < ?", arrayOf(olderThan.toString()))
    }

    fun clear(device: String) {
        writableDatabase.delete("samples", "device = ?", arrayOf(device))
    }

    companion object {
        /** Енергія в кВт·год інтегруванням потужності; проміжки понад [maxGapMs] не враховуються. */
        fun energyKwh(samples: List<Sample>, maxGapMs: Long = 120_000): Double {
            var wh = 0.0
            for (i in 1 until samples.size) {
                val dt = samples[i].ts - samples[i - 1].ts
                if (dt in 1..maxGapMs) {
                    wh += (samples[i].power + samples[i - 1].power) / 2.0 * dt / 3_600_000.0
                }
            }
            return wh / 1000.0
        }
    }
}
