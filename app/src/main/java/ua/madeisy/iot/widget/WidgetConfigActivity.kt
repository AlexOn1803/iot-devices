@file:OptIn(ExperimentalMaterial3Api::class)

package ua.madeisy.iot.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.data.DeviceConfig
import ua.madeisy.iot.ui.AppTheme
import ua.madeisy.iot.ui.KeepConnected

/** Налаштування віджета: пристрій, кнопка і які дані показувати. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val size = BaseWidget.sizeOf(this, widgetId)
        // віджет щойно додано з додатку — налаштування вже вибрані там
        BaseWidget.takePending(this, widgetId)?.let { done(widgetId, it); return }
        val existing = WidgetData.load(this, widgetId)
        val group = GroupWidget.groupOf(this, widgetId)
        setContent {
            AppTheme {
                if (group != null) GroupConfigScreen(group, existing) { cfg -> done(widgetId, cfg) }
                else ConfigScreen(size, existing) { cfg -> done(widgetId, cfg) }
            }
        }
    }

    private fun done(widgetId: Int, cfg: WidgetCfg) {
        WidgetData.save(this, widgetId, cfg)
        BaseWidget.schedulePeriodic(this)
        BaseWidget.update(this, widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@Composable
private fun ConfigScreen(size: WidgetSize, existing: WidgetCfg?, onSave: (WidgetCfg) -> Unit) {
    val app = IotApp.instance
    val devices by app.storage.devices.collectAsState()
    val inverters by app.inverters.devices.collectAsState()
    var deviceKey by remember {
        mutableStateOf(existing?.deviceKey ?: devices.firstOrNull()?.key ?: inverters.firstOrNull()?.let { WidgetData.INV_PREFIX + it.sn })
    }
    var toggle by remember { mutableStateOf(existing?.toggle ?: WidgetCfg.AUTO) }
    val metrics = remember { mutableStateListOf<String>().apply { existing?.metrics?.let { addAll(it) } } }
    val device = devices.firstOrNull { it.key == deviceKey }
    val inverter = inverters.firstOrNull { WidgetData.INV_PREFIX + it.sn == deviceKey }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Віджет · ${size.label}") }) },
        bottomBar = {
            Button(
                enabled = device != null || inverter != null,
                onClick = {
                    if (device != null) onSave(WidgetCfg(device.key, toggle, metrics.toList()))
                    else if (inverter != null) onSave(WidgetCfg(WidgetData.INV_PREFIX + inverter.sn, WidgetCfg.NONE, metrics.toList()))
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 16.dp),
            ) { Text("Додати віджет") }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (devices.isEmpty() && inverters.isEmpty()) {
                Text("Спершу додай розетку, плату чи інвертор у самому додатку.", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            Text("Пристрій", style = MaterialTheme.typography.titleMedium)
            devices.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (deviceKey != d.key) { deviceKey = d.key; toggle = WidgetCfg.AUTO; metrics.clear() }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = deviceKey == d.key, onClick = null, modifier = Modifier.padding(12.dp))
                    Column {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge)
                        Text(if (d.isEsp) "Плата ESP" else "Розетка Tuya", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            inverters.forEach { d ->
                val k = WidgetData.INV_PREFIX + d.sn
                Row(
                    Modifier.fillMaxWidth().clickable { if (deviceKey != k) { deviceKey = k; metrics.clear() } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = deviceKey == k, onClick = null, modifier = Modifier.padding(12.dp))
                    Column {
                        Text(d.alias, style = MaterialTheme.typography.bodyLarge)
                        Text("Інвертор SmartESS", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (device != null) {
                key(device.key) { DeviceOptions(device, size, toggle, { toggle = it }, metrics) }
            } else if (inverter != null) {
                key(inverter.sn) { InverterOptions(WidgetData.INV_PREFIX + inverter.sn, size, metrics) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Діалог «Віджет на головний екран» з екрана пристрою. */
@Composable
fun PinWidgetDialog(device: DeviceConfig, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var size by remember { mutableStateOf(WidgetSize.MEDIUM) }
    var toggle by remember { mutableStateOf(WidgetCfg.AUTO) }
    val metrics = remember { mutableStateListOf<String>() }
    val canPin = remember { BaseWidget.canPin(ctx) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Віджет на головний екран") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!canPin) {
                    Text(
                        "Твій лаунчер не дозволяє додавати віджети з додатку. Утримуй порожнє місце на головному екрані → Віджети → IoT Devices.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    return@Column
                }
                Text("Розмір", style = MaterialTheme.typography.titleMedium)
                WidgetSize.entries.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable {
                        size = s
                        while (metrics.size > s.maxMetrics) metrics.removeAt(metrics.lastIndex)
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = size == s, onClick = null, modifier = Modifier.padding(10.dp))
                        Column {
                            Text(s.label)
                            Text(
                                s.hint,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                DeviceOptions(device, size, toggle, { toggle = it }, metrics)
            }
        },
        confirmButton = {
            if (canPin) androidx.compose.material3.TextButton(onClick = {
                pinResult(ctx, BaseWidget.requestPin(ctx, size, WidgetCfg(device.key, toggle, metrics.toList())))
                onDismiss()
            }) { Text("Додати") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(if (canPin) "Скасувати" else "Зрозуміло") } },
    )
}

@Composable
private fun DeviceOptions(
    device: DeviceConfig,
    size: WidgetSize,
    toggle: Int,
    setToggle: (Int) -> Unit,
    metrics: MutableList<String>,
) {
    val app = IotApp.instance
    KeepConnected(device.key)
    // підписка, щоб список компонентів з'явився, щойно плата відповість
    app.controller.espStates.collectAsState().value
    app.controller.states.collectAsState().value
    val available = WidgetData.available(device)
    val espState = app.controller.espState(device.key)

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Кнопка", style = MaterialTheme.typography.titleMedium)
    @Composable
    fun option(value: Int, title: String) {
        Row(Modifier.fillMaxWidth().clickable { setToggle(value) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = toggle == value, onClick = null, modifier = Modifier.padding(12.dp))
            Text(title)
        }
    }
    if (!device.isEsp) {
        option(WidgetCfg.AUTO, "Вкл/викл розетки")
    } else {
                    PinPermissionHint()
        option(WidgetCfg.AUTO, "Головне реле (" + (espState.primary?.name ?: "перше") + ")")
        espState.comps.filter { it.isSwitchable }.forEach { c -> option(c.i, c.name) }
    }
    option(WidgetCfg.NONE, "Без кнопки — лише дані")

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    if (device.isEsp && espState.comps.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            if (espState.online || espState.connecting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Читаю компоненти плати…")
            } else {
                Text(
                    "Плата недоступна — віджет покаже типові дані, а вибрати конкретні можна пізніше (утримуй віджет → налаштувати).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    MetricPicker(available, WidgetData.defaults(device, available), size.maxMetrics, metrics)
}

/** Вибір даних для віджета (спільний для всіх типів пристроїв). */
@Composable
private fun MetricPicker(available: List<Metric>, defaults: List<Metric>, max: Int, metrics: MutableList<String>) {
    Text(
        "Дані — до ${max}" + if (max > 1) " (у порядку вибору)" else "",
        style = MaterialTheme.typography.titleMedium,
    )
    if (metrics.isEmpty() && available.isNotEmpty()) {
        Text(
            "Нічого не вибрано — покажу типові: " + defaults.take(max).joinToString { it.label },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    available.forEach { m ->
        val idx = metrics.indexOf(m.id)
        val checked = idx >= 0
        Row(
            Modifier.fillMaxWidth().clickable {
                if (checked) metrics.remove(m.id)
                else {
                    if (max == 1) metrics.clear()
                    if (metrics.size < max) metrics.add(m.id)
                }
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.label)
                Text(m.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (checked && max > 1) Text("${idx + 1}", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

@Composable
private fun InverterOptions(key: String, size: WidgetSize, metrics: MutableList<String>) {
    ua.madeisy.iot.ui.KeepInverters()
    IotApp.instance.inverters.states.collectAsState().value   // щоб список оновився, щойно прийдуть дані
    val available = WidgetData.inverterMetrics(key)
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    if (available.size <= 7) Text(
        "Поля інвертора з'являться, щойно прийдуть дані з SmartESS.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    MetricPicker(available, WidgetData.inverterDefaults(available), size.maxMetrics, metrics)
}

/** Діалог «Віджет на головний екран» з екрана інвертора. */
@Composable
fun PinInverterWidgetDialog(sn: String, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val key = WidgetData.INV_PREFIX + sn
    var size by remember { mutableStateOf(WidgetSize.LARGE) }
    val metrics = remember { mutableStateListOf<String>() }
    val canPin = remember { BaseWidget.canPin(ctx) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Віджет інвертора") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!canPin) {
                    Text(
                        "Твій лаунчер не дозволяє додавати віджети з додатку. Утримуй порожнє місце на головному екрані → Віджети → IoT Devices.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    PinPermissionHint()
                    Text("Розмір", style = MaterialTheme.typography.titleMedium)
                    WidgetSize.entries.forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable {
                            size = s
                            while (metrics.size > s.maxMetrics) metrics.removeAt(metrics.lastIndex)
                        }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = size == s, onClick = null, modifier = Modifier.padding(10.dp))
                            Column {
                                Text(s.label)
                                Text(s.hint.replace("Кнопка й ", "").replace("Кнопка, ", ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    InverterOptions(key, size, metrics)
                }
            }
        },
        confirmButton = {
            if (canPin) androidx.compose.material3.TextButton(onClick = {
                pinResult(ctx, BaseWidget.requestPin(ctx, size, WidgetCfg(key, WidgetCfg.NONE, metrics.toList())))
                onDismiss()
            }) { Text("Додати") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(if (canPin) "Скасувати" else "Зрозуміло") } },
    )
}


// ======================= віджет на кілька пристроїв =======================

@Composable
private fun GroupConfigScreen(g: GroupSize, existing: WidgetCfg?, onSave: (WidgetCfg) -> Unit) {
    val items = remember { mutableStateListOf<GroupItem>().apply { existing?.items?.let { addAll(it) } } }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Віджет · кілька пристроїв ${g.label}") }) },
        bottomBar = {
            Button(
                enabled = items.isNotEmpty(),
                onClick = { onSave(WidgetCfg("group", WidgetCfg.NONE, emptyList(), items.toList(), title.trim())) },
                modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 16.dp),
            ) { Text("Готово") }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { GroupEditor(g, items, title) { title = it } }
    }
}

/** Вибір пристроїв (у порядку) і даних для кожного. Спільний для налаштування й додавання з додатку. */
@Composable
fun GroupEditor(g: GroupSize, items: MutableList<GroupItem>, title: String, onTitle: (String) -> Unit) {
    val app = IotApp.instance
    val devices by app.storage.devices.collectAsState()
    val inverters by app.inverters.devices.collectAsState()
    app.controller.states.collectAsState().value
    app.controller.espStates.collectAsState().value
    app.inverters.states.collectAsState().value
    val all = inverters.map { WidgetData.INV_PREFIX + it.sn to it.alias } + devices.map { it.key to it.name }
    var editing by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.OutlinedTextField(
        title, onTitle, label = { Text("Заголовок (необов'язково)") }, placeholder = { Text("Мої пристрої") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text("Пристрої — до ${g.maxDevices} (у порядку вибору)", style = MaterialTheme.typography.titleMedium)
    Text(g.hint.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    all.forEach { (key, name) ->
        androidx.compose.runtime.key(key) {
            val idx = items.indexOfFirst { it.key == key }
            val checked = idx >= 0
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (checked) items.removeAt(idx) else if (items.size < g.maxDevices) items.add(GroupItem(key))
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name)
                    val shown = if (checked) WidgetData.itemMetrics(items[idx], g.perDevice) else emptyList()
                    if (checked) Text(
                        shown.joinToString(" · ") { it.compact }.ifBlank { "—" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (checked) {
                    Text("${idx + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    androidx.compose.material3.TextButton(onClick = { editing = key }) { Text("Дані") }
                }
            }
        }
    }
    editing?.let { key ->
        val idx = items.indexOfFirst { it.key == key }
        if (idx < 0) { editing = null; return@let }
        val chosen = remember(key) { mutableStateListOf<String>().apply { addAll(items[idx].metrics) } }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(WidgetData.nameOf(key) ?: key) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val available = WidgetData.metricsFor(key)
                    if (WidgetData.isInverter(key)) ua.madeisy.iot.ui.KeepInverters() else KeepConnected(key)
                    MetricPicker(available, WidgetData.defaultsFor(key, available), g.perDevice, chosen)
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val i = items.indexOfFirst { it.key == key }
                    if (i >= 0) items[i] = items[i].copy(metrics = chosen.toList())
                    editing = null
                }) { Text("Готово") }
            },
        )
    }
}

/** Діалог «віджет на кілька пристроїв» з головного екрана. */
@Composable
fun PinGroupWidgetDialog(onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var g by remember { mutableStateOf(GroupSize.G43) }
    val items = remember { mutableStateListOf<GroupItem>() }
    var title by remember { mutableStateOf("") }
    val canPin = remember { BaseWidget.canPin(ctx) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Віджет: кілька пристроїв") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!canPin) {
                    Text(
                        "Лаунчер не дозволяє додавати віджети з додатку. Утримуй порожнє місце на головному екрані → Віджети → IoT Devices → «Кілька пристроїв».",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    PinPermissionHint()
                    Text("Розмір", style = MaterialTheme.typography.titleMedium)
                    GroupSize.entries.forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable {
                            g = s
                            while (items.size > s.maxDevices) items.removeAt(items.lastIndex)
                        }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = g == s, onClick = null, modifier = Modifier.padding(10.dp))
                            Column {
                                Text(s.label)
                                Text(s.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    GroupEditor(g, items, title) { title = it }
                }
            }
        },
        confirmButton = {
            if (canPin) androidx.compose.material3.TextButton(enabled = items.isNotEmpty(), onClick = {
                pinResult(ctx, BaseWidget.requestPinGroup(ctx, g, WidgetCfg("group", WidgetCfg.NONE, emptyList(), items.toList(), title.trim())))
                onDismiss()
            }) { Text("Додати") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(if (canPin) "Скасувати" else "Зрозуміло") } },
    )
}


/** Після запиту на додавання віджета: якщо лаунчер відмовив — підказати, що робити. */
internal fun pinResult(ctx: android.content.Context, ok: Boolean) {
    val xiaomi = android.os.Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }
    val msg = when {
        !ok -> "Лаунчер не дозволяє додавати віджети з додатку. Утримуй порожнє місце на екрані → Віджети → IoT Devices."
        xiaomi -> "Якщо віджет не з'явився: Налаштування телефона → Додатки → IoT Devices → Інші дозволи → «Ярлики на робочому столі» → Дозволити, і спробуй ще раз."
        else -> null
    }
    if (msg != null) android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
}

/** Кнопка «Дозволи додатку» (на Xiaomi без дозволу «Ярлики на робочому столі» віджет не додається). */
@Composable
internal fun PinPermissionHint() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val m = android.os.Build.MANUFACTURER.lowercase()
    if (!(m.contains("xiaomi") || m.contains("redmi") || m.contains("poco"))) return
    Text(
        "Xiaomi: щоб віджет додавався з додатку, дозволь «Ярлики на робочому столі» (Інші дозволи).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    androidx.compose.material3.TextButton(onClick = {
        // спершу екран дозволів MIUI, якщо його немає — звичайні налаштування додатку
        val miui = android.content.Intent("miui.intent.action.APP_PERM_EDITOR")
            .putExtra("extra_pkgname", ctx.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val std = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(miui) }.onFailure { runCatching { ctx.startActivity(std) } }
    }) { Text("Відкрити дозволи") }
}
