package ua.madeisy.iot.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.R

/** Розміри віджета на кілька пристроїв: скільки пристроїв і скільки даних на кожен. */
enum class GroupSize(val label: String, val maxDevices: Int, val perDevice: Int) {
    G22("2×2", 2, 1), G42("4×2", 3, 3), G43("4×3", 4, 3), G34("3×4", 6, 2), G44("4×4", 6, 4);

    val hint: String get() = "до $maxDevices пристроїв, по $perDevice ${if (perDevice == 1) "значенню" else "значення"} на кожен"
}

/** Віджет «кілька пристроїв»: рядок на пристрій — назва, головне значення, ще кілька, кнопка. */
abstract class GroupWidget : BaseWidget() {
    abstract val group: GroupSize
    override val size = WidgetSize.LARGE   // для спільного коду; малюємо своє

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        BaseWidget.schedulePeriodic(context)
        ids.forEach { runCatching { render(context, mgr, it) } }
        val keys = ids.toList().flatMap { WidgetData.load(context, it)?.items?.map { i -> i.key } ?: emptyList() }.distinct()
        if (keys.isEmpty()) return
        val pending = goAsync()
        BaseWidget.refreshStale(context, keys) { pending.finish() }
    }

    companion object {
        val providers: List<Pair<Class<out GroupWidget>, GroupSize>> = listOf(
            GroupWidget22::class.java to GroupSize.G22, GroupWidget42::class.java to GroupSize.G42,
            GroupWidget43::class.java to GroupSize.G43, GroupWidget34::class.java to GroupSize.G34,
            GroupWidget44::class.java to GroupSize.G44,
        )

        fun groupOf(ctx: Context, widgetId: Int): GroupSize? {
            val cls = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(widgetId)?.provider?.className ?: return null
            return providers.firstOrNull { it.first.name == cls }?.second
        }

        fun allIds(ctx: Context): List<Int> {
            val mgr = AppWidgetManager.getInstance(ctx)
            return providers.flatMap { (cls, _) -> mgr.getAppWidgetIds(ComponentName(ctx, cls)).toList() }
        }

        fun providerFor(g: GroupSize): Class<out GroupWidget> = providers.first { it.second == g }.first

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int, busyKey: String? = null) {
            val g = groupOf(ctx, id) ?: return
            val views = RemoteViews(ctx.packageName, R.layout.widget_group)
            val w = WidgetData.load(ctx, id)
            val items = w?.items.orEmpty().filter { WidgetData.nameOf(it.key) != null }.take(g.maxDevices)
            views.removeAllViews(R.id.w_rows)
            if (items.isEmpty()) {
                views.setViewVisibility(R.id.w_empty, View.VISIBLE)
                views.setTextViewText(R.id.w_status, "")
                views.setOnClickPendingIntent(R.id.w_root, BaseWidget.configure(ctx, id))
                mgr.updateAppWidget(id, views)
                return
            }
            views.setViewVisibility(R.id.w_empty, View.GONE)
            views.setTextViewText(R.id.w_name, w?.title?.ifBlank { null } ?: "Мої пристрої")
            val c = IotApp.instance.controller
            var oldest = Long.MAX_VALUE
            for (item in items) {
                val row = RemoteViews(ctx.packageName, R.layout.widget_group_row)
                val key = item.key
                val metrics = WidgetData.itemMetrics(item, g.perDevice)
                val name = WidgetData.nameOf(key) ?: key
                var toggle = false
                var on: Boolean? = null
                var offline = false
                if (WidgetData.isInverter(key)) {
                    val d = WidgetData.inverterDevice(key)
                    val st = d?.let { IotApp.instance.inverters.stateOf(it.sn) }
                    offline = st == null || (st.points.isEmpty() && st.error != null)
                    st?.updatedAt?.takeIf { it > 0 }?.let { oldest = minOf(oldest, it) }
                } else {
                    val dev = IotApp.instance.storage.device(key)
                    if (dev != null) {
                        val sum = c.summary(dev)
                        offline = !sum.online
                        if (sum.updatedAt > 0) oldest = minOf(oldest, sum.updatedAt)
                        val wc = WidgetCfg(key)
                        toggle = WidgetData.hasToggle(dev, wc)
                        on = WidgetData.toggleOn(dev, wc)
                    }
                }
                row.setTextViewText(R.id.r_name, name)
                val main = metrics.firstOrNull()
                row.setTextViewText(
                    R.id.r_main,
                    when {
                        busyKey == key -> "…"
                        main != null -> main.value
                        on != null -> if (on) "Увімк." else "Вимк."
                        else -> "—"
                    },
                )
                val extra = buildList {
                    if (offline) add("офлайн")
                    metrics.drop(1).forEach { add(it.compact) }
                }.joinToString(" · ")
                row.setTextViewText(R.id.r_extra, extra)
                row.setViewVisibility(R.id.r_extra, if (extra.isEmpty()) View.GONE else View.VISIBLE)
                // на 2×2 немає місця на головне значення окремим рядком, якщо даних нема
                row.setViewVisibility(R.id.r_toggle, if (toggle) View.VISIBLE else View.GONE)
                row.setImageViewResource(R.id.r_toggle, if (on == true) R.drawable.widget_btn_on else R.drawable.widget_btn_off)
                if (toggle) row.setOnClickPendingIntent(R.id.r_toggle, BaseWidget.action(ctx, BaseWidget.ACTION_TOGGLE, key, id))
                row.setOnClickPendingIntent(R.id.r_root, BaseWidget.openApp(ctx, key))
                views.addView(R.id.w_rows, row)
            }
            val stale = oldest != Long.MAX_VALUE && System.currentTimeMillis() - oldest > 10 * 60_000
            views.setTextViewText(
                R.id.w_status,
                if (busyKey != null) "Зачекай…" else if (stale) "о ${android.text.format.DateFormat.format("HH:mm", oldest)}" else "",
            )
            views.setOnClickPendingIntent(R.id.w_refresh, BaseWidget.action(ctx, BaseWidget.ACTION_REFRESH, GROUP_REFRESH, id))
            views.setOnClickPendingIntent(R.id.w_name, BaseWidget.configure(ctx, id))
            mgr.updateAppWidget(id, views)
        }

        const val GROUP_REFRESH = "*group*"
    }
}

class GroupWidget22 : GroupWidget() { override val group = GroupSize.G22 }
class GroupWidget42 : GroupWidget() { override val group = GroupSize.G42 }
class GroupWidget43 : GroupWidget() { override val group = GroupSize.G43 }
class GroupWidget34 : GroupWidget() { override val group = GroupSize.G34 }
class GroupWidget44 : GroupWidget() { override val group = GroupSize.G44 }
