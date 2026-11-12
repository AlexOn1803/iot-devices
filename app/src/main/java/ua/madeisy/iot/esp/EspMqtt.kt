package ua.madeisy.iot.esp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocketFactory

data class MqttSettings(val host: String = "", val port: Int = 8883, val user: String = "", val pass: String = "") {
    val configured: Boolean get() = host.isNotBlank()
    /** Підпис налаштувань — щоб знати, чи вже передали їх на плату. */
    val signature: String get() = if (!configured) "" else "$host:$port:$user:${pass.hashCode()}"
    val url: String get() = (if (port == 1883) "tcp://" else "ssl://") + host.trim() + ":" + port
}

/** Останній стан плати, отриманий через брокер. */
data class RemoteState(val state: JSONObject, val at: Long, val online: Boolean)

/**
 * Клієнт MQTT для доступу до плат через інтернет.
 * Плати публікують iotdev/<id>/state і /status, команди йдуть у iotdev/<id>/set.
 */
class EspMqtt {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: MqttClient? = null
    private var current: MqttSettings? = null
    private val remote = ConcurrentHashMap<String, RemoteState>()

    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    /** id плати, для якої прийшов новий стан. */
    val updates: SharedFlow<String> = _updates

    private val _status = MutableStateFlow("вимкнено")
    val status: StateFlow<String> = _status

    fun remote(id: String): RemoteState? = remote[id]

    val connected: Boolean get() = client?.isConnected == true

    @Volatile private var connecting = false
    @Volatile private var lostAt = 0L
    @Volatile private var lastAttempt = 0L

    private fun options(s: MqttSettings) = MqttConnectOptions().apply {
        isCleanSession = true
        // власне перепідключення (див. ensure): вбудоване в Paho після сну телефона чи зміни мережі часто «зависає»
        isAutomaticReconnect = false
        connectionTimeout = 10
        keepAliveInterval = 45
        if (s.user.isNotBlank()) userName = s.user
        if (s.pass.isNotEmpty()) password = s.pass.toCharArray()
        if (s.port != 1883) socketFactory = SSLSocketFactory.getDefault()
    }

    /** Під'єднатися (або перепід'єднатися при зміні налаштувань). Безпечно викликати часто. */
    fun ensure(s: MqttSettings, force: Boolean = false) {
        if (!s.configured) { stop(); return }
        val now = System.currentTimeMillis()
        synchronized(this) {
            val c = client
            if (current == s && !force) {
                if (connecting) return
                if (c != null && c.isConnected) return
                // розірвано або попередня спроба не вдалась — повтор не частіше ніж раз на 10 с
                if (now - lastAttempt < 10_000) return
            }
            stopLocked()
            current = s
            connecting = true
            lastAttempt = now
        }
        scope.launch {
            try {
                _status.value = "підключення…"
                val c = MqttClient(s.url, "iotapp-" + UUID.randomUUID().toString().take(8), MemoryPersistence())
                c.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        _status.value = "підключено"
                        runCatching { c.subscribe(arrayOf("iotdev/+/state", "iotdev/+/status"), intArrayOf(0, 0)) }
                    }
                    override fun connectionLost(cause: Throwable?) {
                        lostAt = System.currentTimeMillis()
                        _status.value = "зв'язок втрачено, перепідключення…"
                    }
                    override fun messageArrived(topic: String, message: MqttMessage) = onMessage(topic, message)
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                synchronized(this@EspMqtt) { if (current != s) { runCatching { c.close() }; return@launch }; client = c }
                c.connect(options(s))
                // connectComplete не викликається для першого connect() у синхронному клієнті
                _status.value = "підключено"
                c.subscribe(arrayOf("iotdev/+/state", "iotdev/+/status"), intArrayOf(0, 0))
            } catch (e: MqttException) {
                _status.value = "помилка: ${explain(e)}"
            } catch (e: Exception) {
                _status.value = "помилка: ${e.message}"
            } finally {
                connecting = false
            }
        }
    }

    private fun onMessage(topic: String, m: MqttMessage) {
        val parts = topic.split('/')
        if (parts.size != 3 || parts[0] != "iotdev") return
        val id = parts[1]
        val text = String(m.payload)
        val old = remote[id]
        when (parts[2]) {
            "state" -> {
                val j = runCatching { JSONObject(text) }.getOrNull() ?: return
                remote[id] = RemoteState(j, System.currentTimeMillis(), old?.online ?: true)
            }
            "status" -> {
                val on = text == "online"
                remote[id] = RemoteState(old?.state ?: JSONObject(), old?.at ?: 0, on)
            }
            else -> return
        }
        _updates.tryEmit(id)
    }

    /** Надіслати команди платі через брокер. Підписуються ключем плати. */
    suspend fun send(id: String, key: String, cmds: JSONArray): Boolean = withContext(Dispatchers.IO) {
        val c = client?.takeIf { it.isConnected } ?: return@withContext false
        val body = JSONObject().put("key", key).put("cmds", cmds).toString()
        runCatching { c.publish("iotdev/$id/set", MqttMessage(body.toByteArray()).apply { qos = 1 }) }.isSuccess
    }

    fun stop() = synchronized(this) { stopLocked() }

    /** Мережа телефона змінилась (Wi-Fi ↔ мобільний): старе з'єднання, найімовірніше, мертве. */
    fun reconnectNow() {
        val s = current ?: return
        lastAttempt = 0
        ensure(s, force = true)
    }

    private fun stopLocked() {
        val c = client
        client = null
        current = null
        _status.value = "вимкнено"
        if (c != null) scope.launch { runCatching { c.disconnectForcibly(1000) }; runCatching { c.close() } }
    }

    companion object {
        fun explain(e: MqttException): String = when (e.reasonCode.toShort()) {
            MqttException.REASON_CODE_FAILED_AUTHENTICATION, MqttException.REASON_CODE_NOT_AUTHORIZED -> "невірний логін або пароль"
            MqttException.REASON_CODE_SERVER_CONNECT_ERROR -> "не вдалося з'єднатися з брокером (адреса/порт?)"
            MqttException.REASON_CODE_CLIENT_TIMEOUT -> "брокер не відповідає"
            else -> e.message ?: "код ${e.reasonCode}"
        }

        /** Перевірка налаштувань: під'єднатися й одразу від'єднатися. */
        suspend fun test(s: MqttSettings): String? = withContext(Dispatchers.IO) {
            try {
                val c = MqttClient(s.url, "iotapp-test-" + UUID.randomUUID().toString().take(6), MemoryPersistence())
                c.connect(MqttConnectOptions().apply {
                    isCleanSession = true; connectionTimeout = 10
                    if (s.user.isNotBlank()) userName = s.user
                    if (s.pass.isNotEmpty()) password = s.pass.toCharArray()
                    if (s.port != 1883) socketFactory = SSLSocketFactory.getDefault()
                })
                c.disconnect(); c.close()
                null
            } catch (e: MqttException) { explain(e) } catch (e: Exception) { e.message ?: "помилка" }
        }
    }
}
