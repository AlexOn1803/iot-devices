package ua.madeisy.iot.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.MainActivity
import ua.madeisy.iot.R

object Notifications {
    const val CH_MONITOR = "monitor"
    const val CH_ALERTS = "alerts"
    const val ID_MONITOR = 1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, "Фоновий моніторинг", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Сповіщення розклад", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun scheduleFailed(ctx: Context, name: String, on: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_plug)
            .setContentTitle(name)
            .setContentText(if (on) "Не вдалося увімкнути за розкладом" else "Не вдалося вимкнути за розкладом")
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt() + 10, n)
    }
}

/** Тримає з'єднання з усіма розетками у фоні й показує потужність у сповіщенні. */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val held = mutableSetOf<String>()

    companion object {
        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        val n = build("Підключення…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifications.ID_MONITOR, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.ID_MONITOR, n)
        }
        val app = IotApp.instance
        job = scope.launch {
            combine(app.storage.devices, app.controller.states, app.controller.espStates) { d, _, _ -> d }
                .debounce(1000)
                .collect { devices ->
                    val keys = devices.map { it.key }.toSet()
                    (keys - held).forEach { app.controller.acquire(it); held += it }
                    (held - keys).toList().forEach { app.controller.release(it); held -= it }
                    val text = if (devices.isEmpty()) "Немає пристроїв" else devices.joinToString(" · ") { d ->
                        val s = app.controller.summary(d)
                        if (!s.online) "${d.name}: офлайн" else "${d.name}: ${s.main}"
                    }
                    if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                            this@MonitorService, Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        NotificationManagerCompat.from(this@MonitorService).notify(Notifications.ID_MONITOR, build(text))
                    }
                }
        }
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Notifications.CH_MONITOR)
            .setSmallIcon(R.drawable.ic_plug)
            .setContentTitle("IoT Devices")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        job?.cancel()
        held.forEach { IotApp.instance.controller.release(it) }
        held.clear()
        scope.cancel()
        super.onDestroy()
    }
}
