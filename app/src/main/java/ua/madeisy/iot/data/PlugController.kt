package ua.madeisy.iot.data

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.madeisy.iot.tuya.DiscoveredDevice
import ua.madeisy.iot.tuya.LocalEvent
import ua.madeisy.iot.tuya.TuyaCloud
import ua.madeisy.iot.tuya.TuyaDiscovery
import ua.madeisy.iot.tuya.TuyaLocalClient
import ua.madeisy.iot.tuya.TuyaVersion
import ua.madeisy.iot.widget.PlugWidget
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * Керує з'єднаннями з розетками. Поки екран/віджет/сервіс «тримає» пристрій ([acquire]),
 * підтримується постійне локальне з'єднання; у режимі «Авто» при недоступності — опитування хмари.
 */
class PlugController(
    private val ctx: Context,
    val storage: Storage,
    val history: HistoryDb,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _states = MutableStateFlow<Map<String, PlugState>>(emptyMap())
    val states: StateFlow<Map<String, PlugState>> = _states

    private val watchers = HashMap<String, Int>()
    private val jobs = HashMap<String, Job>()
    private val clients = ConcurrentHashMap<String, TuyaLocalClient>()
    private val lastSampleAt = ConcurrentHashMap<String, Long>()
    private val lastWidgetAt = ConcurrentHashMap<String, Long>()
    private val oneShotLock = Mutex()

    private var cloud: TuyaCloud? = null
    private var cloudFor: CloudSettings? = null

    init {
        scope.launch(Dispatchers.IO) {
            runCatching { history.prune(System.currentTimeMillis() - 31L * 24 * 3600 * 1000) }
        }
    }

    fun state(key: String): PlugState = _states.value[key] ?: PlugState()

    @Synchronized
    private fun cloudClient(): TuyaCloud? {
        val s = storage.cloud.value
        if (!s.configured) return null
        if (cloud == null || cloudFor != s) {
            cloud = TuyaCloud(s.accessId, s.secret, s.region)
            cloudFor = s
        }
        return cloud
    }

    fun cloudOrThrow(): TuyaCloud = cloudClient()
        ?: throw IOException("Спершу введи Access ID і Secret у налаштуваннях")

    private fun update(key: String, f: (PlugState) -> PlugState) {
        var changedSwitch = false
        _states.update { m ->
            val old = m[key] ?: PlugState()
            val new = f(old)
            changedSwitch = old.on != new.on || old.source != new.source
            m + (key to new)
        }
        onStateChanged(key, changedSwitch)
    }

    private fun onStateChanged(key: String, important: Boolean) {
        val s = state(key)
        val now = System.currentTimeMillis()
        val p = s.powerW
        if (p != null && s.online && now - (lastSampleAt[key] ?: 0) >= 5000) {
            lastSampleAt[key] = now
            val sample = Sample(now, if (s.on == false) 0.0 else p, s.voltageV, s.currentA)
            scope.launch(Dispatchers.IO) { runCatching { history.add(key, sample) } }
        }
        if (important || now - (lastWidgetAt[key] ?: 0) >= 15_000) {
            lastWidgetAt[key] = now
            scope.launch(Dispatchers.Main) { runCatching { PlugWidget.updateAll(ctx) } }
        }
    }

    // ---- утримання з'єднань ----

    @Synchronized
    fun acquire(key: String) {
        val n = (watchers[key] ?: 0) + 1
        watchers[key] = n
        if (n == 1) start(key)
    }

    @Synchronized
    fun release(key: String) {
        val n = (watchers[key] ?: 1) - 1
        if (n <= 0) {
            watchers.remove(key)
            stop(key)
        } else watchers[key] = n
    }

    /** Перезапуск після зміни налаштувань пристрою. */
    @Synchronized
    fun restart(key: String) {
        if (watchers.containsKey(key)) {
            stop(key); start(key)
        }
    }

    private fun start(key: String) {
        jobs[key] = scope.launch { runLoop(key) }
    }

    private fun stop(key: String) {
        jobs.remove(key)?.cancel()
        clients.remove(key)?.close()
        update(key) { it.copy(source = Source.NONE, connecting = false) }
    }

    private suspend fun runLoop(key: String) {
        var localFails = 0
        var lastLocalTry = 0L
        while (currentCoroutineContext().isActive) {
            val cfg = storage.device(key) ?: return
            val now = System.currentTimeMillis()
            val cloudPossible = cfg.mode != ConnectionMode.LOCAL && storage.cloud.value.configured && cfg.canCloudId
            val retryLocal = localFails < 2 || !cloudPossible || now - lastLocalTry > 60_000
            if (cfg.mode != ConnectionMode.CLOUD && cfg.canLocal && retryLocal) {
                lastLocalTry = now
                if (runLocalSession(key, cfg)) {
                    localFails = 0
                    delay(1000)
                    continue
                }
                localFails++
                if (localFails == 2 || cfg.ip.isBlank()) rediscover(key)
            }
            if (cloudPossible) {
                pollCloud(key, cfg)
                delay(10_000)
            } else {
                if (!cfg.canLocal && !cloudPossible) {
                    update(key) { it.copy(source = Source.NONE, connecting = false, error = "Не вказано local key або хмарні ключі") }
                } else update(key) { it.copy(source = Source.NONE, connecting = false) }
                delay(if (localFails < 3) 3000 else 10_000)
            }
        }
    }

    /** Повертає true, якщо вдалося підключитися (сесія закінчилась розривом). */
    private suspend fun runLocalSession(key: String, start: DeviceConfig): Boolean {
        var cfg = start
        if (cfg.ip.isBlank()) {
            cfg = storage.device(key)?.takeIf { it.ip.isNotBlank() } ?: return false
        }
        update(key) { it.copy(connecting = true) }
        val client = TuyaLocalClient(cfg.ip, cfg.deviceId, cfg.localKey, TuyaVersion.parse(cfg.version))
        client.setQueryDps(listOf(cfg.dps.switch, cfg.dps.countdown) + cfg.dps.measured)
        try {
            client.connect()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            client.close()
            update(key) { it.copy(connecting = false, error = e.message) }
            return false
        }
        clients[key] = client
        try {
            coroutineScope {
                val collector = launch {
                    client.events.collect { ev ->
                        when (ev) {
                            is LocalEvent.Dps -> applyDps(key, cfg, ev.dps, Source.LOCAL)
                            is LocalEvent.Disconnected -> throw IOException("Зв'язок втрачено: ${ev.reason}")
                        }
                    }
                }
                val first = client.query()
                if (first == null) {
                    collector.cancel()
                    throw IOException("Розетка не відповідає — перевір local key і версію протоколу (${cfg.version})")
                }
                var tick = 0
                while (isActive && client.connected) {
                    delay(2000)
                    tick++
                    client.refresh(cfg.dps.measured)
                    if (tick % 5 == 0) client.heartbeat()
                    if (tick % 15 == 0) client.query(3000)
                }
                collector.cancel()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            update(key) { it.copy(source = Source.NONE, connecting = false, error = e.message) }
            val wasData = state(key).updatedAt > 0
            clients.remove(key)
            client.close()
            return wasData
        }
        clients.remove(key)
        client.close()
        return true
    }

    private suspend fun pollCloud(key: String, cfg0: DeviceConfig) {
        val c = cloudClient() ?: return
        var cfg = cfg0
        try {
            if (cfg.cloudCodes.isEmpty()) {
                runCatching { c.getSpecification(cfg.deviceId) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { spec ->
                    storage.updateDevice(key) { it.copy(cloudCodes = spec) }
                    cfg = cfg.copy(cloudCodes = spec)
                }
            }
            val status = c.getStatus(cfg.deviceId)
            applyDps(key, cfg, cloudToDps(cfg, status), Source.CLOUD)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            update(key) { it.copy(source = Source.NONE, connecting = false, error = e.message) }
        }
    }

    private fun interestingDps(cfg: DeviceConfig): List<Int> = with(cfg.dps) {
        listOf(switch, countdown, energy, current, power, voltage, relayStatus)
    }

    private fun cloudToDps(cfg: DeviceConfig, status: Map<String, Any?>): Map<String, Any?> {
        val byCode = HashMap<String, Int>()
        cfg.cloudCodes.forEach { (dp, code) -> byCode[code] = dp }
        interestingDps(cfg).forEach { dp -> cfg.codeFor(dp)?.let { code -> byCode.putIfAbsent(code, dp) } }
        return status.mapKeys { (code, _) -> byCode[code]?.toString() ?: code }
    }

    private fun applyDps(key: String, cfg: DeviceConfig, dps: Map<String, Any?>, source: Source) {
        val now = System.currentTimeMillis()
        val d = cfg.dps
        update(key) { s ->
            val raw = s.raw + dps
            fun num(dp: Int, scale: Int): Double? =
                (raw[dp.toString()] as? Number)?.toDouble()?.div(10.0.pow(scale))
            val cdKey = d.countdown.toString()
            s.copy(
                source = source,
                connecting = false,
                error = null,
                raw = raw,
                on = raw[d.switch.toString()] as? Boolean ?: s.on,
                powerW = num(d.power, d.powerScale),
                voltageV = num(d.voltage, d.voltageScale),
                currentA = num(d.current, d.currentScale),
                countdownSec = if (dps.containsKey(cdKey)) (dps[cdKey] as? Number)?.toInt() else s.countdownSec,
                countdownAt = if (dps.containsKey(cdKey)) now else s.countdownAt,
                relayStatus = raw[d.relayStatus.toString()] as? String,
                updatedAt = now,
            )
        }
    }

    // ---- пошук у мережі ----

    suspend fun scanNetwork(durationMs: Long = 7000): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tuya-scan").apply { setReferenceCounted(false) }
        try {
            lock.acquire()
            TuyaDiscovery.scan(durationMs)
        } finally {
            runCatching { lock.release() }
        }
    }

    /** Шукає пристрій у мережі й оновлює його IP та версію. */
    private suspend fun rediscover(key: String) {
        val cfg = storage.device(key) ?: return
        val found = runCatching { scanNetwork(6000) }.getOrNull()?.firstOrNull { it.deviceId == cfg.deviceId } ?: return
        if (found.ip != cfg.ip || found.version != cfg.version) {
            storage.updateDevice(key) { it.copy(ip = found.ip, version = found.version) }
        }
    }

    // ---- команди ----

    /** Надсилає значення DP найкращим доступним шляхом: активне з'єднання → разове локальне → хмара. */
    suspend fun setDps(key: String, values: Map<Int, Any?>): Result<Unit> {
        val cfg = storage.device(key) ?: return Result.failure(IOException("Пристрій не знайдено"))
        val strValues = values.mapKeys { it.key.toString() }
        var lastError: Exception? = null

        clients[key]?.takeIf { it.connected }?.let { c ->
            try {
                c.set(strValues)
                applyDps(key, cfg, strValues, Source.LOCAL)
                return Result.success(Unit)
            } catch (e: Exception) { lastError = e }
        }

        if (cfg.mode != ConnectionMode.CLOUD && cfg.canLocal && cfg.ip.isNotBlank()) {
            try {
                oneShotLock.withLock {
                    val c = TuyaLocalClient(cfg.ip, cfg.deviceId, cfg.localKey, TuyaVersion.parse(cfg.version))
                    try {
                        c.connect(3000)
                        c.set(strValues)
                        delay(300)
                    } finally { c.close() }
                }
                applyDps(key, cfg, strValues, if (state(key).online) state(key).source else Source.LOCAL)
                return Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
            }
        }

        if (cfg.mode != ConnectionMode.LOCAL) {
            val c = cloudClient()
            if (c != null && cfg.canCloudId) {
                try {
                    val cmds = values.mapKeys { (dp, _) -> cfg.codeFor(dp) ?: throw IOException("Невідомий хмарний код для DP $dp") }
                    c.sendCommands(cfg.deviceId, cmds)
                    applyDps(key, cfg, strValues, Source.CLOUD)
                    return Result.success(Unit)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    lastError = e
                }
            }
        }
        return Result.failure(lastError ?: IOException("Немає способу зв'язатися з розеткою"))
    }

    suspend fun setSwitch(key: String, on: Boolean): Result<Unit> {
        val cfg = storage.device(key) ?: return Result.failure(IOException("Пристрій не знайдено"))
        return setDps(key, mapOf(cfg.dps.switch to on))
    }

    /** Таймер на пристрої: через [seconds] стан розетки зміниться на протилежний. 0 — скасувати. */
    suspend fun setCountdown(key: String, seconds: Int): Result<Unit> {
        val cfg = storage.device(key) ?: return Result.failure(IOException("Пристрій не знайдено"))
        return setDps(key, mapOf(cfg.dps.countdown to seconds))
    }

    suspend fun setRelayStatus(key: String, value: String): Result<Unit> {
        val cfg = storage.device(key) ?: return Result.failure(IOException("Пристрій не знайдено"))
        return setDps(key, mapOf(cfg.dps.relayStatus to value))
    }

    /** Разове оновлення стану без постійного з'єднання (для віджета). */
    suspend fun refreshOnce(key: String): Result<PlugState> {
        val cfg = storage.device(key) ?: return Result.failure(IOException("Пристрій не знайдено"))
        clients[key]?.takeIf { it.connected }?.let { return Result.success(state(key)) }
        var lastError: Exception? = null
        if (cfg.mode != ConnectionMode.CLOUD && cfg.canLocal && cfg.ip.isNotBlank()) {
            try {
                val dps = oneShotLock.withLock {
                    val c = TuyaLocalClient(cfg.ip, cfg.deviceId, cfg.localKey, TuyaVersion.parse(cfg.version))
                    try {
                        c.connect(3000)
                        c.query(3000)
                    } finally { c.close() }
                }
                if (dps != null) {
                    applyDps(key, cfg, dps, Source.LOCAL)
                    return Result.success(state(key))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
            }
        }
        if (cfg.mode != ConnectionMode.LOCAL && cfg.canCloudId) {
            val c = cloudClient()
            if (c != null) {
                try {
                    applyDps(key, cfg, cloudToDps(cfg, c.getStatus(cfg.deviceId)), Source.CLOUD)
                    return Result.success(state(key))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    lastError = e
                }
            }
        }
        return Result.failure(lastError ?: IOException("Немає зв'язку"))
    }

    /** Перемикає стан; якщо поточний невідомий — спершу дізнається його. */
    suspend fun toggle(key: String): Result<Unit> {
        var s = state(key)
        if (s.on == null || System.currentTimeMillis() - s.updatedAt > 60_000) {
            refreshOnce(key).onSuccess { s = it }
        }
        return setSwitch(key, !(s.on ?: false))
    }
}
