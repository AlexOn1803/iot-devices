package ua.madeisy.iot.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.data.Schedule
import java.util.Calendar

/** Розклад вмикання/вимикання. Працює з телефона: потрібна мережа, коли настає час. */
object ScheduleManager {
    private const val ACTION = "ua.madeisy.iot.SCHEDULE"
    private const val EXTRA_ID = "id"

    /** Наступний момент спрацювання (мс) або null, якщо днів не вибрано і час одноразовий уже минув. */
    fun nextTrigger(s: Schedule, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, s.hour); set(Calendar.MINUTE, s.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (s.days == 0) {
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }
        for (i in 0..7) {
            if (cal.timeInMillis > now && (s.days and (1 shl dayIndex(cal))) != 0) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /** Понеділок = 0 … неділя = 6. */
    fun dayIndex(cal: Calendar): Int = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

    private fun pending(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, ScheduleReceiver::class.java).setAction(ACTION).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(ctx: Context, s: Schedule) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, s.id)
        if (!s.enabled) { am.cancel(pi); return }
        val at = nextTrigger(s)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun cancel(ctx: Context, id: String) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx, id))
    }

    fun rescheduleAll(ctx: Context) {
        IotApp.instance.storage.schedules.value.forEach { schedule(ctx, it) }
    }

    internal fun idFrom(intent: Intent): String? = intent.getStringExtra(EXTRA_ID)
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = ScheduleManager.idFrom(intent) ?: return
        val app = IotApp.instance
        val s = app.storage.schedules.value.firstOrNull { it.id == id } ?: return
        if (!s.enabled) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                var ok = app.controller.setSwitch(s.deviceKey, s.turnOn).isSuccess
                if (!ok) { delay(3000); ok = app.controller.setSwitch(s.deviceKey, s.turnOn).isSuccess }
                if (s.days == 0) app.storage.upsertSchedule(s.copy(enabled = false))
                else ScheduleManager.schedule(context, s)
                if (!ok) Notifications.scheduleFailed(context, app.storage.device(s.deviceKey)?.name ?: "Розетка", s.turnOn)
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScheduleManager.rescheduleAll(context)
        if (IotApp.instance.storage.settings.value.backgroundMonitor) MonitorService.start(context)
    }
}
