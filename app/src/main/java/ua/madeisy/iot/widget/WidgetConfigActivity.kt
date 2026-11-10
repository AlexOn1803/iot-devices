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
        val existing = WidgetData.load(this, widgetId)
        setContent {
            AppTheme { ConfigScreen(size, existing) { cfg -> done(widgetId, cfg) } }
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
    var deviceKey by remember { mutableStateOf(existing?.deviceKey ?: devices.firstOrNull()?.key) }
    var toggle by remember { mutableStateOf(existing?.toggle ?: WidgetCfg.AUTO) }
    val metrics = remember { mutableStateListOf<String>().apply { existing?.metrics?.let { addAll(it) } } }
    val device = devices.firstOrNull { it.key == deviceKey }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Віджет · ${size.label}") }) },
        bottomBar = {
            Button(
                enabled = device != null,
                onClick = { device?.let { onSave(WidgetCfg(it.key, toggle, metrics.toList())) } },
                modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 16.dp),
            ) { Text("Додати віджет") }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (devices.isEmpty()) {
                Text("Спершу додай розетку чи плату в самому додатку.", style = MaterialTheme.typography.bodyLarge)
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
            if (device != null) {
                key(device.key) { DeviceOptions(device, size, toggle, { toggle = it }, metrics) }
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
                                when (s) {
                                    WidgetSize.SMALL -> "Кнопка й одне значення"
                                    WidgetSize.MEDIUM -> "Кнопка, головне й друге значення"
                                    WidgetSize.LARGE -> "Кнопка й до чотирьох значень"
                                },
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
                BaseWidget.requestPin(ctx, size, WidgetCfg(device.key, toggle, metrics.toList()))
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
        option(WidgetCfg.AUTO, "Головне реле (" + (espState.primary?.name ?: "перше") + ")")
        espState.comps.filter { it.isSwitchable }.forEach { c -> option(c.i, c.name) }
    }
    option(WidgetCfg.NONE, "Без кнопки — лише дані")

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(
        "Дані — до ${size.maxMetrics}" + if (size.maxMetrics > 1) " (у порядку вибору)" else "",
        style = MaterialTheme.typography.titleMedium,
    )
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
    if (metrics.isEmpty() && available.isNotEmpty()) {
        Text(
            "Нічого не вибрано — покажу типові: " + WidgetData.defaults(device, available).take(size.maxMetrics).joinToString { it.label },
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
                    if (size.maxMetrics == 1) metrics.clear()
                    if (metrics.size < size.maxMetrics) metrics.add(m.id)
                }
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.label)
                Text(m.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (checked && size.maxMetrics > 1) Text("${idx + 1}", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
        }
    }
}
