package ua.madeisy.iot.hub

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Як підключатися до хаба Raspberry Pi. */
data class HubSettings(
    val token: String = "",
    val lanHost: String = "",
    val port: Int = 8380,
    val relay: String = "",
    val name: String = "",
) {
    val configured get() = token.isNotBlank() && (lanHost.isNotBlank() || relay.isNotBlank())
}

enum class HubVia { NONE, LAN, RELAY }

data class HubStatus(
    val via: HubVia = HubVia.NONE,
    /** Через ретранслятор: чи на зв'язку сам Pi. */
    val hubOnline: Boolean = false,
    val connecting: Boolean = false,
    val error: String? = null,
) {
    val connected get() = via != HubVia.NONE && hubOnline
}

/**
 * З'єднання з хабом: удома — напряму ws://<pi>:8380/ws, поза домом — через ретранслятор
 * Cloudflare (wss://iot-hub.….workers.dev/app). Хаб сам надсилає зміни стану пристроїв.
 */
class HubClient(context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("hub", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<HubSettings> = _settings

    private val _status = MutableStateFlow(HubStatus())
    val status: StateFlow<HubStatus> = _status

    /** (ключ пристрою, стан) — як надсилає хаб. */
    private val _states = MutableSharedFlow<Pair<String, JSONObject>>(extraBufferCapacity = 256)
    val states: SharedFlow<Pair<String, JSONObject>> = _states

    /** Подія «з'єднались — треба надіслати пристрої». */
    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val connectedEvents: SharedFlow<Unit> = _connected

    /** Ключі пристроїв, якими зараз керує хаб. */
    val managed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val ids = AtomicInteger(1)
    private var loop: Job? = null
    private val kick = Channel<Unit>(Channel.CONFLATED)

    private fun load() = HubSettings(
        prefs.getString("token", "") ?: "", prefs.getString("lan", "") ?: "", prefs.getInt("port", 8380),
        prefs.getString("relay", "") ?: "", prefs.getString("name", "") ?: "",
    )

    fun save(s: HubSettings) {
        prefs.edit().putString("token", s.token).putString("lan", s.lanHost).putInt("port", s.port)
            .putString("relay", s.relay).putString("name", s.name).apply()
        _settings.value = s
        reconnectNow()
    }

    fun forget() = save(HubSettings())

    fun manages(key: String) = _status.value.connected && key in managed

    /** Коли хаб востаннє став доступним (щоб дочекатися його знімка стану). */
    @Volatile var onlineSince = 0L
        private set

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch(Dispatchers.IO) { run() }
    }

    /** Мережа змінилась або налаштування — перепідключитись (можливо, вже напряму вдома). */
    fun reconnectNow() {
        ws?.close(1000, "reconnect")
        kick.trySend(Unit)
    }

    private suspend fun run() {
        while (isActive()) {
            val s = _settings.value
            if (!s.configured) {
                _status.value = HubStatus()
                managed.clear()
                kick.receive()
                continue
            }
            _status.value = _status.value.copy(connecting = true)
            var via = HubVia.NONE
            var closed: CompletableDeferred<String?>? = null
            // удома — напряму (швидко), інакше — через ретранслятор
            if (s.lanHost.isNotBlank()) {
                closed = open("ws://${s.lanHost}:${s.port}/ws?token=${s.token}", HubVia.LAN, 2500)
                if (closed != null) via = HubVia.LAN
            }
            if (via == HubVia.NONE && s.relay.isNotBlank()) {
                val base = s.relay.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
                closed = open("$base/app?token=${s.token}", HubVia.RELAY, 8000)
                if (closed != null) via = HubVia.RELAY
            }
            if (via == HubVia.NONE || closed == null) {
                _status.value = HubStatus(error = "Хаб недоступний")
                managed.clear()
                withTimeoutOrNull(15_000) { kick.receive() }
                continue
            }
            val reason = closed.await()
            ws = null
            managed.clear()
            pending.values.forEach { it.completeExceptionally(IOException("З'єднання з хабом обірвалось")) }
            pending.clear()
            _status.value = HubStatus(error = reason)
            withTimeoutOrNull(3000) { kick.receive() }
        }
    }

    private suspend fun isActive() = kotlin.coroutines.coroutineContext.isActive

    /** Відкрити WebSocket; повертає «обіцянку закриття» або null, якщо не вдалося. */
    private suspend fun open(url: String, via: HubVia, timeoutMs: Long): CompletableDeferred<String?>? {
        val opened = CompletableDeferred<Boolean>()
        val closed = CompletableDeferred<String?>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                // напряму хаб одразу на зв'язку; через ретранслятор — дочекаємось {"t":"hub"}
                _status.value = HubStatus(via = via, hubOnline = via == HubVia.LAN)
                if (via == HubVia.LAN) onlineSince = System.currentTimeMillis()
                opened.complete(true)
                if (via == HubVia.LAN) _connected.tryEmit(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "pong") return
                val j = runCatching { JSONObject(text) }.getOrNull() ?: return
                handle(j, via)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                opened.complete(false); closed.complete(reason.ifBlank { null })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.complete(false)
                closed.complete(if (response?.code == 401) "Хаб не прийняв токен — підключи його заново" else t.message)
            }
        }
        val sock = http.newWebSocket(Request.Builder().url(url).build(), listener)
        val ok = withTimeoutOrNull(timeoutMs) { opened.await() } ?: false
        if (!ok) { sock.cancel(); return null }
        return closed
    }

    private fun handle(j: JSONObject, via: HubVia) {
        when (j.optString("t")) {
            "hub" -> {
                val online = j.optBoolean("online")
                _status.value = _status.value.copy(hubOnline = online, error = if (online) null else "Raspberry Pi не на зв'язку")
                if (online) {
                    onlineSince = System.currentTimeMillis()
                    _connected.tryEmit(Unit)
                    send(JSONObject().put("t", "get"))
                } else managed.clear()
            }
            "hello" -> {
                val lan = j.optString("lan")
                val cur = _settings.value
                // запам'ятати домашню адресу Pi, щоб наступного разу вдома йти напряму
                if (lan.isNotBlank() && lan != cur.lanHost) {
                    val n = cur.copy(lanHost = lan, name = j.optString("name", cur.name))
                    prefs.edit().putString("lan", n.lanHost).putString("name", n.name).apply()
                    _settings.value = n
                }
                if (via == HubVia.RELAY && !_status.value.hubOnline) _status.value = _status.value.copy(hubOnline = true)
            }
            "snapshot" -> {
                val st = j.optJSONObject("states") ?: return
                st.keys().forEach { k -> st.optJSONObject(k)?.let { managed += k; _states.tryEmit(k to it) } }
            }
            "state" -> {
                val k = j.optString("key"); val st = j.optJSONObject("state") ?: return
                managed += k
                _states.tryEmit(k to st)
            }
            "ack" -> pending.remove(j.optInt("id"))?.complete(j)
        }
    }

    fun send(j: JSONObject): Boolean = ws?.send(j.toString()) == true

    /** Команда з очікуванням підтвердження хаба. */
    suspend fun request(j: JSONObject, timeoutMs: Long = 6000): Result<JSONObject> = withContext(Dispatchers.IO) {
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        if (!send(j.put("id", id))) { pending.remove(id); return@withContext Result.failure(IOException("Немає з'єднання з хабом")) }
        val r = withTimeoutOrNull(timeoutMs) { runCatching { d.await() } }
        pending.remove(id)
        when {
            r == null -> Result.failure(IOException("Хаб не відповів"))
            r.isFailure -> Result.failure(r.exceptionOrNull()!!)
            !r.getOrThrow().optBoolean("ok") -> Result.failure(IOException(r.getOrThrow().optString("error", "Помилка хаба")))
            else -> Result.success(r.getOrThrow())
        }
    }

    // ---- підключення (пара) ----

    /** Запитати в хаба токен (працює лише вдома і лише у «вікно пари»). */
    suspend fun pair(host: String, port: Int = 8380): Result<HubSettings> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = http.newCall(Request.Builder().url("http://$host:$port/api/pair").build()).execute()
            resp.use { r ->
                val j = JSONObject(r.body?.string() ?: "{}")
                if (!r.isSuccessful) throw IOException(j.optString("error", "Хаб відповів ${r.code}"))
                if (j.optString("app") != "iot-hub") throw IOException("Це не хаб IoT Devices")
                HubSettings(j.getString("token"), j.optString("lan").ifBlank { host }, j.optInt("port", port),
                    j.optString("relay"), j.optString("name"))
            }
        }
    }
}
