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
    G22("2×2", 2, 1), G42("4×2", 3, 3), G43("4×3", 4, 3), G34("3×4", 6, 4), G44("4×4", 6, 6);

    val hint: String get() = "до $maxDevices пристроїв, по $perDevice ${if (perDevice == 1) "значенню" else "значення"} на кожен"
}

/** Віджет «кілька пристроїв»: рядок на пристрій — назва, головне значення, ще кілька, кнопка. */
abstract class GroupWidget : BaseWidget() {
    abstract val group: GroupSize
    override val size = WidgetSize.LARGE   // для спільного коду; малюємо своє

    /** Віджет розтягнули чи зменшили — перемалювати під новий розмір. */
    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, options: android.os.Bundle) {
        runCatching { render(context, mgr, id) }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        BaseWidget.schedulePeriodic(context)
        ids.forEach { id -> if (WidgetData.load(context, id) == null) BaseWidget.takePending(context, id)?.let { WidgetData.save(context, id, it) } }
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
            // висота віджета (dp, у портретній орієнтації — максимальна) → скільки рядків даних влазить
            val opts = mgr.getAppWidgetOptions(id)
            val heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: (g.maxDevices * 70)
            val rowDp = (heightDp - 40) / items.size
            val multiline = rowDp >= 78
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
                        // «офлайн» — лише якщо даних давно немає (зв'язок через хмару теж рахується)
                        offline = !sum.online && System.currentTimeMillis() - sum.updatedAt > 5 * 60_000
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
                row.setTextViewText(R.id.r_extra_multi, extra)
                row.setViewVisibility(R.id.r_extra, if (extra.isEmpty() || multiline) View.GONE else View.VISIBLE)
                row.setViewVisibility(R.id.r_extra_multi, if (extra.isNotEmpty() && multiline) View.VISIBLE else View.GONE)
                // низький рядок (віджет зменшили): головне значення дрібніше, щоб не обрізалось
                row.setTextViewTextSize(R.id.r_main, android.util.TypedValue.COMPLEX_UNIT_SP, if (rowDp < 52) 14f else 17f)
                if (rowDp < 44) {
                    // зовсім тісно: назва й значення в один рядок, без додаткових
                    row.setTextViewText(R.id.r_name, name + "  " + (if (busyKey == key) "…" else main?.value ?: on?.let { if (it) "Увімк." else "Вимк." } ?: "—"))
                    row.setViewVisibility(R.id.r_main, View.GONE)
                    row.setViewVisibility(R.id.r_extra, View.GONE)
                    row.setViewVisibility(R.id.r_extra_multi, View.GONE)
                }
                if (android.os.Build.VERSION.SDK_INT >= 31 && rowDp < 44) {
                    val px = android.util.TypedValue.COMPLEX_UNIT_DIP
                    val sz = maxOf(24, rowDp - 4).toFloat()
                    row.setViewLayoutWidth(R.id.r_toggle, sz, px)
                    row.setViewLayoutHeight(R.id.r_toggle, sz, px)
                }
                // на 2×2 немає місця на головне значення окремим рядком, якщо даних нема
                row.setViewVisibility(R.id.r_toggle, if (toggle) View.VISIBLE else View.GONE)
                row.setImageViewResource(R.id.r_toggle, if (on == true) R.drawable.widget_btn_on else R.drawable.widget_btn_off)
                if (toggle) row.setOnClickPendingIntent(R.id.r_toggle, BaseWidget.action(ctx, BaseWidget.ACTION_TOGGLE, key, id))
                // з кнопкою: натиск на рядок лише оновлює стан; без кнопки — відкриває пристрій
                row.setOnClickPendingIntent(
                    R.id.r_root,
                    if (toggle) BaseWidget.action(ctx, BaseWidget.ACTION_REFRESH, key, id) else BaseWidget.openApp(ctx, key),
                )
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
