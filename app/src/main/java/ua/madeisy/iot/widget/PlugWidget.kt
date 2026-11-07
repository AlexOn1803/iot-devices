package ua.madeisy.iot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.MainActivity
import ua.madeisy.iot.R
import ua.madeisy.iot.data.DeviceConfig
import ua.madeisy.iot.ui.AppTheme

/** Віджет на головний екран: назва, потужність, кнопка вкл/викл. */
class PlugWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_TOGGLE = "ua.madeisy.iot.WIDGET_TOGGLE"
        private const val ACTION_REFRESH = "ua.madeisy.iot.WIDGET_REFRESH"
        private const val ACTION_PERIODIC = "ua.madeisy.iot.WIDGET_PERIODIC"
        private const val PERIOD_MS = 5 * 60 * 1000L

        /**
         * Тихе оновлення раз на ~5 хв, поки телефон не спить (ELAPSED_REALTIME без пробудження),
         * щоб віджет був свіжим і без фонового моніторингу.
         */
        fun schedulePeriodic(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.setInexactRepeating(
                android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + PERIOD_MS, PERIOD_MS, periodicIntent(ctx)
            )
        }

        fun cancelPeriodic(ctx: Context) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).cancel(periodicIntent(ctx))
        }

        private fun periodicIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 7001, Intent(ctx, PlugWidget::class.java).setAction(ACTION_PERIODIC),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun widgetKeys(ctx: Context): List<String> {
            val mgr = AppWidgetManager.getInstance(ctx)
            return mgr.getAppWidgetIds(ComponentName(ctx, PlugWidget::class.java)).toList()
                .mapNotNull { deviceFor(ctx, it)?.key }.distinct()
        }
        private const val EXTRA_KEY = "deviceKey"
        private const val PREFS = "widgets"

        fun bind(ctx: Context, widgetId: Int, deviceKey: String) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("w$widgetId", deviceKey).apply()
        }

        private fun deviceFor(ctx: Context, widgetId: Int): DeviceConfig? {
            val storage = IotApp.instance.storage
            val key = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("w$widgetId", null)
            return key?.let { storage.device(it) } ?: storage.devices.value.firstOrNull()
        }

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PlugWidget::class.java))
            ids.forEach { render(ctx, mgr, it) }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int, busy: Boolean = false) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_plug)
            val dev = deviceFor(ctx, id)
            if (dev == null) {
                views.setTextViewText(R.id.w_name, "Немає пристроїв")
                views.setTextViewText(R.id.w_power, "—")
                views.setTextViewText(R.id.w_status, "Додай розетку в додатку")
                views.setOnClickPendingIntent(R.id.w_root, openApp(ctx, null))
                mgr.updateAppWidget(id, views)
                return
            }
            val sum = IotApp.instance.controller.summary(dev)
            views.setTextViewText(R.id.w_name, dev.name)
            views.setTextViewText(R.id.w_power, sum.main)
            val fresh = System.currentTimeMillis() - sum.updatedAt < 120_000
            views.setTextViewText(
                R.id.w_status,
                when {
                    busy -> "Зачекай…"
                    sum.updatedAt == 0L -> "Натисни ⟳ для оновлення"
                    !fresh -> "Оновлено ${android.text.format.DateFormat.format("HH:mm", sum.updatedAt)}"
                    sum.sub != null -> sum.sub
                    else -> "Онлайн"
                }
            )
            views.setImageViewResource(
                R.id.w_toggle,
                if (sum.on == true) R.drawable.widget_btn_on else R.drawable.widget_btn_off
            )
            views.setOnClickPendingIntent(R.id.w_toggle, action(ctx, ACTION_TOGGLE, dev.key, id))
            views.setOnClickPendingIntent(R.id.w_refresh, action(ctx, ACTION_REFRESH, dev.key, id))
            views.setOnClickPendingIntent(R.id.w_root, openApp(ctx, dev.key))
            mgr.updateAppWidget(id, views)
        }

        private fun action(ctx: Context, action: String, key: String, widgetId: Int): PendingIntent {
            val i = Intent(ctx, PlugWidget::class.java).setAction(action)
                .putExtra(EXTRA_KEY, key).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            return PendingIntent.getBroadcast(
                ctx, (action + widgetId).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun openApp(ctx: Context, key: String?): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                key?.let { putExtra(MainActivity.EXTRA_DEVICE, it) }
            }
            return PendingIntent.getActivity(
                ctx, (key ?: "none").hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onEnabled(context: Context) { schedulePeriodic(context) }

    override fun onDisabled(context: Context) { cancelPeriodic(context) }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        schedulePeriodic(context)
        ids.forEach { render(context, mgr, it) }
        // тихо оновити дані, якщо застаріли
        val keys = ids.toList().mapNotNull { deviceFor(context, it)?.key }.distinct()
        if (keys.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                keys.forEach { k ->
                    val dev = IotApp.instance.storage.device(k)
                    val age = System.currentTimeMillis() - (dev?.let { IotApp.instance.controller.summary(it).updatedAt } ?: 0)
                    if (age > 60_000) IotApp.instance.controller.refreshOnce(k)
                }
                withContext(Dispatchers.Main) { updateAll(context) }
            } finally { pending.finish() }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action == ACTION_PERIODIC) {
            val keys = widgetKeys(context)
            if (keys.isEmpty()) { cancelPeriodic(context); return }
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val c = IotApp.instance.controller
                    keys.forEach { k ->
                        val dev = IotApp.instance.storage.device(k)
                        val age = System.currentTimeMillis() - (dev?.let { c.summary(it).updatedAt } ?: 0)
                        if (age > 60_000) runCatching { c.refreshOnce(k) }
                    }
                } finally {
                    withContext(Dispatchers.Main) { updateAll(context) }
                    pending.finish()
                }
            }
            return
        }
        if (action != ACTION_TOGGLE && action != ACTION_REFRESH) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val mgr = AppWidgetManager.getInstance(context)
        if (widgetId != -1) render(context, mgr, widgetId, busy = true)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val c = IotApp.instance.controller
                if (action == ACTION_TOGGLE) c.toggle(key) else c.refreshOnce(key)
            } finally {
                withContext(Dispatchers.Main) { updateAll(context) }
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        appWidgetIds.forEach { e.remove("w$it") }
        e.apply()
    }
}

/** Вибір розетки для нового віджета. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val devices = IotApp.instance.storage.devices.value
        if (devices.size <= 1) {
            devices.firstOrNull()?.let { PlugWidget.bind(this, widgetId, it.key) }
            done(widgetId); return
        }
        setContent {
            AppTheme {
                Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
                    Text("Яку розетку показувати?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                    devices.forEach { d ->
                        ListItem(
                            headlineContent = { Text(d.name) },
                            supportingContent = { Text(d.deviceId) },
                            modifier = Modifier.clickable {
                                PlugWidget.bind(this@WidgetConfigActivity, widgetId, d.key)
                                done(widgetId)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun done(widgetId: Int) {
        PlugWidget.updateAll(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
