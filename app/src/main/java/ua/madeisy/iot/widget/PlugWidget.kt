package ua.madeisy.iot.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.MainActivity
import ua.madeisy.iot.R
import ua.madeisy.iot.data.DeviceConfig

/**
 * Спільна логіка віджетів. Розміри — окремі провайдери (так кожен з'являється
 * в списку віджетів лаунчера окремо): [SmallWidget] 1×1, [PlugWidget] 3×1, [LargeWidget] 3×2.
 */
abstract class BaseWidget : AppWidgetProvider() {
    abstract val size: WidgetSize

    companion object {
        private const val ACTION_TOGGLE = "ua.madeisy.iot.WIDGET_TOGGLE"
        private const val ACTION_REFRESH = "ua.madeisy.iot.WIDGET_REFRESH"
        private const val ACTION_PERIODIC = "ua.madeisy.iot.WIDGET_PERIODIC"
        private const val ACTION_PINNED = "ua.madeisy.iot.WIDGET_PINNED"
        private const val EXTRA_CFG = "cfg"
        private const val EXTRA_KEY = "deviceKey"
        private const val PERIOD_MS = 5 * 60 * 1000L

        val providers = listOf(SmallWidget::class.java, PlugWidget::class.java, LargeWidget::class.java)

        fun sizeOf(ctx: Context, widgetId: Int): WidgetSize {
            val cls = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(widgetId)?.provider?.className
            return when (cls) {
                SmallWidget::class.java.name -> WidgetSize.SMALL
                LargeWidget::class.java.name -> WidgetSize.LARGE
                else -> WidgetSize.MEDIUM
            }
        }

        /** Усі віджети всіх розмірів: (id, розмір). */
        private fun allWidgets(ctx: Context): List<Pair<Int, WidgetSize>> {
            val mgr = AppWidgetManager.getInstance(ctx)
            return providers.flatMap { p ->
                val size = when (p) { SmallWidget::class.java -> WidgetSize.SMALL; LargeWidget::class.java -> WidgetSize.LARGE; else -> WidgetSize.MEDIUM }
                mgr.getAppWidgetIds(ComponentName(ctx, p)).map { it to size }
            }
        }

        private fun deviceFor(ctx: Context, widgetId: Int): Pair<DeviceConfig, WidgetCfg>? {
            val storage = IotApp.instance.storage
            val w = WidgetData.load(ctx, widgetId)
            val dev = w?.let { storage.device(it.deviceKey) } ?: return null
            return dev to w
        }

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            allWidgets(ctx).forEach { (id, size) -> runCatching { render(ctx, mgr, id, size) } }
        }

        fun update(ctx: Context, widgetId: Int) {
            render(ctx, AppWidgetManager.getInstance(ctx), widgetId, sizeOf(ctx, widgetId))
        }

        private fun layoutFor(size: WidgetSize) = when (size) {
            WidgetSize.SMALL -> R.layout.widget_small
            WidgetSize.MEDIUM -> R.layout.widget_plug
            WidgetSize.LARGE -> R.layout.widget_large
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int, size: WidgetSize, busy: Boolean = false) {
            val views = RemoteViews(ctx.packageName, layoutFor(size))
            val wcfg = WidgetData.load(ctx, id)
            if (wcfg != null && WidgetData.isInverter(wcfg.deviceKey) && WidgetData.inverterDevice(wcfg.deviceKey) != null) {
                renderInverter(ctx, mgr, views, id, size, wcfg, busy)
                return
            }
            val pair = deviceFor(ctx, id)
            if (pair == null) {
                views.setTextViewText(R.id.w_name, "Не налаштовано")
                // у кожного розміру свій набір полів — чіпати можна лише наявні
                if (size == WidgetSize.LARGE) { views.setViewVisibility(R.id.w_row2, View.GONE); views.setViewVisibility(R.id.w_row3, View.GONE) }
                else views.setTextViewText(R.id.w_value, "—")
                if (size != WidgetSize.SMALL) views.setTextViewText(R.id.w_status, "Натисни, щоб вибрати пристрій")
                views.setViewVisibility(R.id.w_toggle, View.GONE)
                views.setOnClickPendingIntent(R.id.w_root, configure(ctx, id))
                mgr.updateAppWidget(id, views)
                return
            }
            val (dev, w) = pair
            val c = IotApp.instance.controller
            val sum = c.summary(dev)
            val metrics = WidgetData.selected(dev, w, size)
            val hasToggle = WidgetData.hasToggle(dev, w)
            val on = WidgetData.toggleOn(dev, w)
            val fresh = System.currentTimeMillis() - sum.updatedAt < 120_000
            val status = when {
                busy -> "Зачекай…"
                sum.updatedAt == 0L -> "Натисни ⟳"
                !fresh -> "о ${android.text.format.DateFormat.format("HH:mm", sum.updatedAt)}"
                !sum.online -> "офлайн"
                else -> ""
            }

            views.setTextViewText(R.id.w_name, dev.name)
            views.setViewVisibility(R.id.w_toggle, if (hasToggle) View.VISIBLE else View.GONE)
            views.setImageViewResource(R.id.w_toggle, if (on == true) R.drawable.widget_btn_on else R.drawable.widget_btn_off)

            when (size) {
                WidgetSize.SMALL -> {
                    val m = metrics.firstOrNull()
                    val text = when {
                        m != null -> m.value
                        on != null -> if (on) "Увімк." else "Вимк."
                        else -> "—"
                    }
                    views.setTextViewText(R.id.w_value, if (busy) "…" else text)
                    // без кнопки значення — головне, робимо його більшим
                    views.setTextViewTextSize(R.id.w_value, TypedValue.COMPLEX_UNIT_SP, if (hasToggle) 13f else 20f)
                }
                WidgetSize.MEDIUM -> fillMedium(views, dev.name, metrics, status, on?.let { if (it) "Увімк." else "Вимк." })
                WidgetSize.LARGE -> fillLarge(views, metrics, status)
            }

            views.setOnClickPendingIntent(R.id.w_toggle, action(ctx, ACTION_TOGGLE, dev.key, id))
            if (size != WidgetSize.SMALL) views.setOnClickPendingIntent(R.id.w_refresh, action(ctx, ACTION_REFRESH, dev.key, id))
            views.setOnClickPendingIntent(R.id.w_root, openApp(ctx, dev.key))
            mgr.updateAppWidget(id, views)
        }

        /** 3×1: головне значення великим зліва, решта (до трьох) — короткими рядками праворуч. */
        private fun fillMedium(views: RemoteViews, name: String, metrics: List<Metric>, status: String, fallback: String?) {
            val main = metrics.getOrNull(0)
            views.setTextViewText(R.id.w_name, if (main != null && metrics.size > 1) "$name · ${main.label}" else name)
            views.setTextViewText(R.id.w_value, main?.value ?: fallback ?: "—")
            views.setTextViewText(R.id.w_status, status.ifEmpty { if (metrics.size <= 1) main?.label ?: "" else "" })
            views.setViewVisibility(R.id.w_status, if (status.isNotEmpty() || metrics.size <= 1) View.VISIBLE else View.GONE)
            val rest = metrics.drop(1)
            views.setViewVisibility(R.id.w_extra, if (rest.isEmpty()) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.w_extra, rest.joinToString("\n") { it.compact })
        }

        private val TILES = listOf(
            Triple(R.id.w_t0, R.id.w_l0, R.id.w_v0), Triple(R.id.w_t1, R.id.w_l1, R.id.w_v1),
            Triple(R.id.w_t2, R.id.w_l2, R.id.w_v2), Triple(R.id.w_t3, R.id.w_l3, R.id.w_v3),
            Triple(R.id.w_t4, R.id.w_l4, R.id.w_v4), Triple(R.id.w_t5, R.id.w_l5, R.id.w_v5),
        )

        /** 3×2: до шести плиток по дві в рядку. */
        private fun fillLarge(views: RemoteViews, metrics: List<Metric>, status: String) {
            views.setTextViewText(R.id.w_status, status)
            TILES.forEachIndexed { i, (tile, label, value) ->
                val m = metrics.getOrNull(i)
                // порожня клітинка в заповненому рядку — INVISIBLE, щоб сітка не «їхала»
                views.setViewVisibility(tile, if (m != null) View.VISIBLE else View.INVISIBLE)
                views.setTextViewText(label, m?.label ?: "")
                views.setTextViewText(value, m?.value ?: "")
            }
            views.setViewVisibility(R.id.w_row2, if (metrics.size > 2) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.w_row3, if (metrics.size > 4) View.VISIBLE else View.GONE)
            // що більше рядків — то дрібніший шрифт, щоб усе влізло у 2 клітинки висоти
            val sp = when { metrics.size > 4 -> 15f; metrics.size > 2 -> 17f; else -> 19f }
            TILES.forEach { (_, _, v) -> views.setTextViewTextSize(v, TypedValue.COMPLEX_UNIT_SP, sp) }
        }

        private fun renderInverter(ctx: Context, mgr: AppWidgetManager, views: RemoteViews, id: Int, size: WidgetSize, w: WidgetCfg, busy: Boolean) {
            val d = WidgetData.inverterDevice(w.deviceKey) ?: return
            val st = IotApp.instance.inverters.stateOf(d.sn)
            val metrics = WidgetData.inverterSelected(w.deviceKey, w, size)
            val status = when {
                busy -> "Зачекай…"
                st.updatedAt == 0L -> "Натисни ⟳"
                System.currentTimeMillis() - st.updatedAt > 10 * 60_000 -> "о ${android.text.format.DateFormat.format("HH:mm", st.updatedAt)}"
                else -> ""
            }
            views.setTextViewText(R.id.w_name, d.alias)
            views.setViewVisibility(R.id.w_toggle, View.GONE)
            when (size) {
                WidgetSize.SMALL -> {
                    views.setTextViewText(R.id.w_value, if (busy) "…" else metrics.firstOrNull()?.value ?: "—")
                    views.setTextViewTextSize(R.id.w_value, TypedValue.COMPLEX_UNIT_SP, 20f)
                }
                WidgetSize.MEDIUM -> fillMedium(views, d.alias, metrics, status, null)
                WidgetSize.LARGE -> fillLarge(views, metrics, status)
            }
            if (size != WidgetSize.SMALL) views.setOnClickPendingIntent(R.id.w_refresh, action(ctx, ACTION_REFRESH, w.deviceKey, id))
            views.setOnClickPendingIntent(R.id.w_root, openApp(ctx, w.deviceKey))
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

        private fun openApp(ctx: Context, key: String): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_DEVICE, key)
            }
            return PendingIntent.getActivity(ctx, key.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun configure(ctx: Context, widgetId: Int): PendingIntent {
            val i = Intent(ctx, WidgetConfigActivity::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE + widgetId)  // унікальний для кожного віджета
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(ctx, widgetId, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun providerFor(size: WidgetSize): Class<out BaseWidget> = when (size) {
            WidgetSize.SMALL -> SmallWidget::class.java
            WidgetSize.MEDIUM -> PlugWidget::class.java
            WidgetSize.LARGE -> LargeWidget::class.java
        }

        fun canPin(ctx: Context): Boolean = AppWidgetManager.getInstance(ctx).isRequestPinAppWidgetSupported

        /**
         * Попросити лаунчер додати віджет на головний екран прямо з додатку.
         * Налаштування передаються в колбек і зберігаються, щойно лаунчер видасть id віджета.
         */
        fun requestPin(ctx: Context, size: WidgetSize, cfg: WidgetCfg): Boolean {
            val mgr = AppWidgetManager.getInstance(ctx)
            if (!mgr.isRequestPinAppWidgetSupported) return false
            val cb = Intent(ctx, PlugWidget::class.java).setAction(ACTION_PINNED).putExtra(EXTRA_CFG, cfg.toJson())
            // MUTABLE — лаунчер дописує в цей intent id нового віджета
            val pi = PendingIntent.getBroadcast(
                ctx, (cfg.toJson() + size).hashCode(), cb,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            )
            return mgr.requestPinAppWidget(ComponentName(ctx, providerFor(size)), null, pi)
        }

        // ---- періодичне оновлення (раз на ~5 хв, без пробудження телефона) ----

        fun schedulePeriodic(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + PERIOD_MS, PERIOD_MS, periodicIntent(ctx))
        }

        private fun cancelPeriodicIfUnused(ctx: Context) {
            if (allWidgets(ctx).isEmpty()) (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(periodicIntent(ctx))
        }

        private fun periodicIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 7001, Intent(ctx, PlugWidget::class.java).setAction(ACTION_PERIODIC),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun usedKeys(ctx: Context): List<String> =
            allWidgets(ctx).mapNotNull { (id, _) -> WidgetData.load(ctx, id)?.deviceKey }.distinct()

        /** Оновити ключ пристрою: інвертор — через хмару SmartESS, решта — як завжди. */
        private suspend fun refreshKey(key: String, force: Boolean) {
            if (WidgetData.isInverter(key)) {
                val d = WidgetData.inverterDevice(key) ?: return
                val repo = IotApp.instance.inverters
                if (force || System.currentTimeMillis() - repo.stateOf(d.sn).updatedAt > 60_000) runCatching { repo.refresh(d) }
                return
            }
            val c = IotApp.instance.controller
            val dev = IotApp.instance.storage.device(key) ?: return
            if (force || System.currentTimeMillis() - c.summary(dev).updatedAt > 60_000) runCatching { c.refreshOnce(key) }
        }

        /** Оновити дані пристроїв, якщо застаріли, і перемалювати віджети. */
        private fun refreshStale(ctx: Context, keys: List<String>, then: () -> Unit) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    keys.forEach { k -> refreshKey(k, force = false) }
                } finally {
                    withContext(Dispatchers.Main) { updateAll(ctx) }
                    then()
                }
            }
        }
    }

    override fun onEnabled(context: Context) = schedulePeriodic(context)

    override fun onDisabled(context: Context) = cancelPeriodicIfUnused(context)

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        schedulePeriodic(context)
        ids.forEach { render(context, mgr, it, size) }
        val keys = ids.toList().mapNotNull { WidgetData.load(context, it)?.deviceKey }.distinct()
        if (keys.isEmpty()) return
        val pending = goAsync()
        refreshStale(context, keys) { pending.finish() }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = WidgetData.remove(context, appWidgetIds)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (val action = intent.action) {
            ACTION_PINNED -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val cfg = WidgetCfg.parse(intent.getStringExtra(EXTRA_CFG)) ?: return
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                WidgetData.save(context, id, cfg)
                schedulePeriodic(context)
                update(context, id)
            }
            ACTION_PERIODIC -> {
                val keys = usedKeys(context)
                if (keys.isEmpty()) { cancelPeriodicIfUnused(context); return }
                val pending = goAsync()
                refreshStale(context, keys) { pending.finish() }
            }
            ACTION_TOGGLE, ACTION_REFRESH -> {
                val key = intent.getStringExtra(EXTRA_KEY) ?: return
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                val mgr = AppWidgetManager.getInstance(context)
                if (widgetId != -1) render(context, mgr, widgetId, sizeOf(context, widgetId), busy = true)
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val c = IotApp.instance.controller
                        val dev = IotApp.instance.storage.device(key)
                        val w = if (widgetId != -1) WidgetData.load(context, widgetId) else null
                        if (WidgetData.isInverter(key)) {
                            refreshKey(key, force = true)
                        } else if (action == ACTION_REFRESH || dev == null) {
                            c.refreshOnce(key)
                        } else if (!dev.isEsp || w == null || w.toggle == WidgetCfg.AUTO) {
                            c.toggle(key)
                        } else if (w.toggle >= 0) {
                            if (c.espState(key).comps.isEmpty()) c.refreshOnce(key)
                            c.espSet(key, JSONObject().put("i", w.toggle).put("toggle", true))
                        }
                    } finally {
                        withContext(Dispatchers.Main) { updateAll(context) }
                        pending.finish()
                    }
                }
            }
        }
    }
}

/** 3×1 — назва, головне значення, друге значення, кнопка. Стара назва класу — щоб наявні віджети не зникли. */
class PlugWidget : BaseWidget() {
    override val size = WidgetSize.MEDIUM

    companion object {
        fun updateAll(ctx: Context) = BaseWidget.updateAll(ctx)
    }
}

/** 1×1 — кнопка і одне значення. */
class SmallWidget : BaseWidget() {
    override val size = WidgetSize.SMALL
}

/** 3×2 — до шести значень і кнопка. */
class LargeWidget : BaseWidget() {
    override val size = WidgetSize.LARGE
}
