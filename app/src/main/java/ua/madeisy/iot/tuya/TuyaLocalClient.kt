package ua.madeisy.iot.tuya

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Подія від пристрою: оновлення точок даних (DP) або розрив з'єднання. */
sealed class LocalEvent {
    data class Dps(val dps: Map<String, Any?>) : LocalEvent()
    data class Disconnected(val reason: String) : LocalEvent()
}

/**
 * Постійне локальне з'єднання з пристроєм Tuya по TCP 6668.
 * Пристрій зазвичай приймає лише одне локальне з'єднання одночасно.
 */
class TuyaLocalClient(
    private val ip: String,
    private val deviceId: String,
    localKey: String,
    private val version: TuyaVersion,
    private val port: Int = 6668,
) {
    private val session = TuyaSession(version, localKey.toByteArray(Charsets.UTF_8))
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private val writeLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    private var pendingQuery: CompletableDeferred<Map<String, Any?>>? = null
    private var device22 = false
    private var queryDps: List<Int> = listOf(1)

    private val _events = MutableSharedFlow<LocalEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LocalEvent> = _events

    @Volatile
    var connected = false
        private set

    suspend fun connect(timeoutMs: Int = 4000) = withContext(Dispatchers.IO) {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(ip, port), timeoutMs)
            s.soTimeout = timeoutMs
            socket = s
            input = DataInputStream(s.getInputStream())
            output = s.getOutputStream()
            if (version.needsSession) negotiate()
            s.soTimeout = 0
            connected = true
            readerJob = scope.launch { readLoop() }
        } catch (e: Exception) {
            runCatching { s.close() }
            socket = null
            throw when (e) {
                is TuyaProtocolException -> e
                is SocketTimeoutException -> IOException("Пристрій $ip не відповідає")
                else -> IOException("Не вдалося підключитися до $ip: ${e.message}")
            }
        }
    }

    private fun negotiate() {
        val out = output!!
        out.write(session.negotiationStart()); out.flush()
        val resp = try { session.parse(readFrame()) } catch (e: SocketTimeoutException) {
            throw TuyaProtocolException("Пристрій не відповів на узгодження ключа — перевір версію протоколу")
        }
        val step3 = session.negotiationStep3(resp)
        out.write(step3); out.flush()
        session.negotiationFinalize()
    }

    /** Читає один кадр з потоку, пропускаючи сміття до відомого префікса. */
    private fun readFrame(): ByteArray {
        val inp = input ?: throw IOException("Немає з'єднання")
        val head = ByteArray(TuyaFrame.HEADER_6699)
        inp.readFully(head, 0, 4)
        var prefix = java.nio.ByteBuffer.wrap(head).getInt(0)
        var guard = 0
        while (prefix != TuyaFrame.PREFIX_55AA && prefix != TuyaFrame.PREFIX_6699) {
            System.arraycopy(head, 1, head, 0, 3)
            head[3] = inp.readByte()
            prefix = java.nio.ByteBuffer.wrap(head).getInt(0)
            if (++guard > 4096) throw IOException("Не вдалося синхронізуватися з потоком")
        }
        val hsize = TuyaFrame.headerSize(prefix)
        inp.readFully(head, 4, hsize - 4)
        val total = TuyaFrame.frameLength(head, hsize)
        if (total < hsize || total > 64 * 1024) throw IOException("Пошкоджений кадр ($total байт)")
        val frame = ByteArray(total)
        System.arraycopy(head, 0, frame, 0, hsize)
        inp.readFully(frame, hsize, total - hsize)
        return frame
    }

    private suspend fun readLoop() {
        try {
            while (scope.isActive) {
                val frame = readFrame()
                val msg = try { session.parse(frame) } catch (e: Exception) { continue }
                val text = try { session.decodeText(msg) } catch (e: Exception) { null } ?: continue
                if (text.contains("data unvalid") && !device22) {
                    device22 = true
                    scope.launch { runCatching { sendQuery() } }
                    continue
                }
                val dps = extractDps(text) ?: continue
                pendingQuery?.let { if (!it.isCompleted) it.complete(dps) }
                _events.emit(LocalEvent.Dps(dps))
            }
        } catch (e: Exception) {
            if (connected) {
                connected = false
                pendingQuery?.completeExceptionally(IOException("З'єднання розірвано"))
                _events.tryEmit(LocalEvent.Disconnected(e.message ?: "з'єднання розірвано"))
            }
        }
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    private suspend fun encodeAndSend(cmd: Int, json: String) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val out = output ?: throw IOException("Немає з'єднання")
            val frame = session.encode(cmd, json.toByteArray(Charsets.UTF_8))
            try {
                out.write(frame); out.flush()
            } catch (e: IOException) {
                connected = false
                throw IOException("З'єднання розірвано")
            }
        }
    }

    /** Список DP, які запитувати у режимі device22 (деякі старі пристрої 3.3). */
    fun setQueryDps(dps: List<Int>) { queryDps = dps.ifEmpty { listOf(1) } }

    private suspend fun sendQuery() {
        when {
            version != TuyaVersion.V33 -> encodeAndSend(TuyaCmd.DP_QUERY_NEW, "{}")
            device22 -> {
                val dps = JSONObject().apply { queryDps.forEach { put(it.toString(), JSONObject.NULL) } }
                val j = JSONObject().put("devId", deviceId).put("uid", deviceId)
                    .put("t", now().toString()).put("dps", dps)
                encodeAndSend(TuyaCmd.CONTROL_NEW, j.toString())
            }
            else -> {
                val j = JSONObject().put("gwId", deviceId).put("devId", deviceId)
                    .put("uid", deviceId).put("t", now().toString())
                encodeAndSend(TuyaCmd.DP_QUERY, j.toString())
            }
        }
    }

    /** Запитує повний стан. Повертає DP з першої відповіді або null, якщо пристрій мовчить. */
    suspend fun query(timeoutMs: Long = 5000): Map<String, Any?>? {
        val d = CompletableDeferred<Map<String, Any?>>()
        pendingQuery = d
        return try {
            sendQuery()
            withTimeoutOrNull(timeoutMs) { d.await() }
        } finally {
            pendingQuery = null
        }
    }

    /** Встановлює значення DP, напр. mapOf("1" to true). */
    suspend fun set(dps: Map<String, Any?>) {
        val d = JSONObject().apply { dps.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
        if (version == TuyaVersion.V33) {
            val j = JSONObject().put("devId", deviceId).put("uid", deviceId)
                .put("t", now().toString()).put("dps", d)
            encodeAndSend(TuyaCmd.CONTROL, j.toString())
        } else {
            val j = JSONObject().put("protocol", 5).put("t", now())
                .put("data", JSONObject().put("dps", d))
            encodeAndSend(TuyaCmd.CONTROL_NEW, j.toString())
        }
    }

    /** Просить пристрій надіслати свіжі значення вказаних DP (потужність, напруга, струм). */
    suspend fun refresh(dpIds: List<Int>) {
        val j = JSONObject().put("dpId", JSONArray(dpIds))
        encodeAndSend(TuyaCmd.UPDATEDPS, j.toString())
    }

    suspend fun heartbeat() {
        val j = if (version == TuyaVersion.V33)
            JSONObject().put("gwId", deviceId).put("devId", deviceId).toString() else "{}"
        encodeAndSend(TuyaCmd.HEART_BEAT, j)
    }

    fun close() {
        connected = false
        runCatching { socket?.close() }
        socket = null
        scope.cancel()
    }

    companion object {
        /** Витягує DP з JSON відповіді (формати 3.3 та 3.4+/data.dps). */
        fun extractDps(text: String): Map<String, Any?>? {
            val j = try { JSONObject(text) } catch (e: Exception) { return null }
            val dps = j.optJSONObject("dps") ?: j.optJSONObject("data")?.optJSONObject("dps") ?: return null
            val out = LinkedHashMap<String, Any?>()
            dps.keys().forEach { k -> out[k] = dps.opt(k).takeIf { it != JSONObject.NULL } }
            return out
        }
    }
}
