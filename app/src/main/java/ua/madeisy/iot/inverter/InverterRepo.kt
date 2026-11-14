package ua.madeisy.iot.inverter

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray

data class EssAccount(
    val server: EssServer = EssServer.DESS,
    val user: String = "",
    /** SHA-1 пароля (самого пароля не зберігаємо). */
    val pwdSha1: String = "",
    val companyKey: String = SmartEssClient.DEFAULT_COMPANY_KEY,
) {
    val configured get() = user.isNotBlank() && pwdSha1.isNotBlank()
}

data class InverterState(
    val points: List<EssPoint> = emptyList(),
    val summary: EssSummary = EssSummary(),
    val updatedAt: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Акаунт SmartESS, список інверторів і їхні останні дані. */
class InverterRepo(context: Context) {
    private val prefs = context.getSharedPreferences("inverter", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _account = MutableStateFlow(loadAccount())
    val account: StateFlow<EssAccount> = _account

    private val _devices = MutableStateFlow(loadDevices())
    val devices: StateFlow<List<EssDevice>> = _devices

    private val _states = MutableStateFlow<Map<String, InverterState>>(emptyMap())
    val states: StateFlow<Map<String, InverterState>> = _states

    private var client: SmartEssClient? = null
    private var poller: Job? = null
    @Volatile private var watchers = 0

    private fun loadAccount() = EssAccount(
        EssServer.parse(prefs.getString("server", null)),
        prefs.getString("user", "") ?: "",
        prefs.getString("pwd", "") ?: "",
        prefs.getString("company", null)?.ifBlank { null } ?: SmartEssClient.DEFAULT_COMPANY_KEY,
    )

    private fun loadDevices(): List<EssDevice> = runCatching {
        val a = JSONArray(prefs.getString("devices", "[]"))
        (0 until a.length()).map { EssDevice.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun loadToken(): EssToken? {
        val t = prefs.getString("token", null) ?: return null
        val s = prefs.getString("secret", null) ?: return null
        return EssToken(t, s, prefs.getLong("expires", 0))
    }

    private fun clientFor(a: EssAccount): SmartEssClient {
        client?.let { return it }
        return SmartEssClient(a.server, a.user, a.pwdSha1, a.companyKey, loadToken()) { t ->
            prefs.edit().putString("token", t.token).putString("secret", t.secret).putLong("expires", t.expiresAt).apply()
        }.also { client = it }
    }

    /** Вхід і пошук інверторів. Повертає кількість знайдених. */
    suspend fun connect(server: EssServer, user: String, password: String, companyKey: String): Int {
        val a = EssAccount(server, user.trim(), EssSign.sha1(password), companyKey.trim().ifBlank { SmartEssClient.DEFAULT_COMPANY_KEY })
        val c = SmartEssClient(a.server, a.user, a.pwdSha1, a.companyKey)
        val t = c.login()
        val found = c.discover()
        prefs.edit()
            .putString("server", a.server.name).putString("user", a.user).putString("pwd", a.pwdSha1)
            .putString("company", a.companyKey)
            .putString("token", t.token).putString("secret", t.secret).putLong("expires", t.expiresAt)
            .putString("devices", JSONArray().apply { found.forEach { put(it.toJson()) } }.toString())
            .apply()
        client = null
        _account.value = a
        _devices.value = found
        _states.value = emptyMap()
        if (watchers > 0) startPolling()
        refreshAll()
        return found.size
    }

    /** Пошук нових інверторів у вже під'єднаному акаунті. */
    suspend fun rediscover(): Int {
        val a = _account.value
        if (!a.configured) return 0
        val found = clientFor(a).discover()
        val merged = found.map { f -> _devices.value.firstOrNull { it.sn == f.sn }?.let { f.copy(alias = it.alias) } ?: f }
        saveDevices(merged)
        refreshAll()
        return merged.size
    }

    fun rename(sn: String, alias: String) =
        saveDevices(_devices.value.map { if (it.sn == sn) it.copy(alias = alias.trim().ifBlank { it.alias }) else it })

    fun remove(sn: String) = saveDevices(_devices.value.filterNot { it.sn == sn })

    fun disconnect() {
        prefs.edit().clear().apply()
        client = null
        _account.value = EssAccount()
        _devices.value = emptyList()
        _states.value = emptyMap()
        poller?.cancel(); poller = null
    }

    private fun saveDevices(list: List<EssDevice>) {
        prefs.edit().putString("devices", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
        _devices.value = list
    }

    private fun update(sn: String, f: (InverterState) -> InverterState) {
        val m = _states.value.toMutableMap()
        m[sn] = f(m[sn] ?: InverterState())
        _states.value = m
    }

    suspend fun refresh(d: EssDevice) {
        val a = _account.value
        if (!a.configured) return
        update(d.sn) { it.copy(loading = true) }
        try {
            val pts = clientFor(a).lastData(d)
            update(d.sn) { InverterState(pts, EssSummary.of(pts), System.currentTimeMillis()) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            update(d.sn) { it.copy(loading = false, error = e.message ?: "Помилка") }
        }
    }

    fun refreshAll() {
        scope.launch { _devices.value.forEach { refresh(it) } }
    }

    /** Опитування, поки дані на екрані (сервер оновлює дані інвертора приблизно раз на 1–5 хв). */
    fun acquire() {
        watchers++
        if (watchers == 1) startPolling()
    }

    fun release() {
        watchers = (watchers - 1).coerceAtLeast(0)
        if (watchers == 0) { poller?.cancel(); poller = null }
    }

    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            while (isActive) {
                _devices.value.forEach { refresh(it) }
                delay(60_000)
            }
        }
    }

    /** Чи свіжі дані, щоб не смикати сервер (для віджета). */
    fun stateOf(sn: String): InverterState = _states.value[sn] ?: InverterState()
}
