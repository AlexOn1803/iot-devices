package ua.madeisy.iot.diag

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сторож головного потоку: якщо інтерфейс не відповідає понад 3 с, записує, чим зайнятий
 * головний потік (і хто тримає блокування), у файл. Звіт можна надіслати з налаштувань.
 */
object HangWatchdog {
    private const val LIMIT_MS = 3000L
    private const val FILE = "hangs.txt"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var tick = 0L
    @Volatile private var reportedFor = -1L

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        Thread({
            while (true) {
                val sent = tick
                main.post { tick++ }
                Thread.sleep(LIMIT_MS)
                if (tick == sent && reportedFor != sent) {
                    reportedFor = sent
                    runCatching { record(app) }
                }
            }
        }, "hang-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun record(ctx: Context) {
        val sb = StringBuilder()
        sb.append("=== ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .append(" головний потік не відповідає ≥3 с ===\n")
        val all = Thread.getAllStackTraces()
        val mainThread = Looper.getMainLooper().thread
        fun dump(t: Thread, st: Array<StackTraceElement>, max: Int) {
            sb.append("-- ").append(t.name).append(" [").append(t.state).append("]\n")
            st.take(max).forEach { sb.append("    at ").append(it).append('\n') }
        }
        dump(mainThread, mainThread.stackTrace, 40)
        // хто ще заблокований або щось робить у коді додатка — ймовірні винуватці
        all.filter { (t, st) -> t != mainThread && st.any { it.className.startsWith("ua.madeisy") } }
            .forEach { (t, st) -> dump(t, st, 15) }
        val f = File(ctx.filesDir, FILE)
        val old = if (f.exists()) f.readText().takeLast(30_000) else ""
        f.writeText(old + sb.append('\n').toString())
    }

    fun report(ctx: Context): String? = File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()?.ifBlank { null }

    fun count(ctx: Context): Int = report(ctx)?.split("=== ")?.count { it.contains("не відповідає") } ?: 0

    fun clear(ctx: Context) { File(ctx.filesDir, FILE).delete() }
}
