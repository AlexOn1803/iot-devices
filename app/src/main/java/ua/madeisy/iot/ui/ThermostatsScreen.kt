@file:OptIn(ExperimentalMaterial3Api::class)

package ua.madeisy.iot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.data.ThermoMode
import ua.madeisy.iot.data.Thermostat
import ua.madeisy.iot.esp.EspType
import ua.madeisy.iot.service.MonitorService
import java.util.Locale

private data class SensorOption(val key: String, val index: Int, val label: String)

@Composable
fun ThermostatsScreen(nav: Nav) {
    val app = IotApp.instance
    val ctx = LocalContext.current
    val list by app.storage.thermostats.collectAsState()
    val status by app.thermostats.status.collectAsState()
    val settings by app.storage.settings.collectAsState()
    val devices by app.storage.devices.collectAsState()
    var editing by remember { mutableStateOf<Thermostat?>(null) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // тримаємо зв'язок з платами, щоб бачити температуру і список датчиків
    devices.filter { it.isEsp }.forEach { d -> androidx.compose.runtime.key(d.key) { KeepConnected(d.key) } }

    fun enableMonitor() {
        app.storage.saveSettings(settings.copy(backgroundMonitor = true))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        MonitorService.start(ctx)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Термостати") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Thermostat() },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Додати") },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (list.any { it.enabled } && !settings.backgroundMonitor) {
                SectionCard("Потрібен фоновий моніторинг") {
                    Text(
                        "Термостат працює на телефоні. Без фонового моніторингу Android може зупинити додаток, і обігрівач залишиться в тому стані, в якому був.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { enableMonitor() }) { Text("Увімкнути") }
                }
            }
            if (list.isEmpty()) {
                Text(
                    "Термостат вмикає розетку чи реле за температурою з датчика на платі ESP32 (DHT, DS18B20, AHT). Наприклад: нижче 18 °C — увімкнути обігрівач, від 19 °C — вимкнути.\n\nЯкщо датчик і реле на одній платі — краще додай компонент «Термостат» у налаштуваннях плати: він працюватиме на самій платі, без телефона.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            list.forEach { t ->
                val st = status[t.id]
                val target = devices.firstOrNull { it.key == t.targetKey }
                val targetOn = target?.let { app.controller.summary(it).on }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { editing = t }) {
                            Text(t.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${t.mode.label}: ${t.describe()}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = t.enabled, onCheckedChange = { app.storage.upsertThermostat(t.copy(enabled = it)) })
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            st?.temp?.let { String.format(Locale.US, "%.1f °C", it) } ?: "—",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            (target?.name ?: "?") + ": " + when (targetOn) { true -> "увімкнено"; false -> "вимкнено"; null -> "невідомо" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (targetOn == true) OnGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    if (t.enabled && st != null) Text(
                        st.text + " · " + android.text.format.DateFormat.format("HH:mm", st.at),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(72.dp))
        }
    }

    editing?.let { t ->
        ThermostatDialog(
            initial = t,
            isNew = list.none { it.id == t.id },
            onDismiss = { editing = null },
            onSave = {
                app.storage.upsertThermostat(it)
                editing = null
                if (!settings.backgroundMonitor) enableMonitor()
            },
            onDelete = { app.storage.removeThermostat(t.id); editing = null },
        )
    }
}

@Composable
private fun ThermostatDialog(initial: Thermostat, isNew: Boolean, onDismiss: () -> Unit, onSave: (Thermostat) -> Unit, onDelete: () -> Unit) {
    val app = IotApp.instance
    val devices by app.storage.devices.collectAsState()
    val esp by app.controller.espStates.collectAsState()
    var t by remember { mutableStateOf(initial) }

    // датчики: компоненти з температурою на платах ESP (з поточного стану)
    val sensors = devices.filter { it.isEsp }.flatMap { d ->
        (esp[d.key]?.comps ?: emptyList())
            .filter { it.type == EspType.DHT || it.type == EspType.AHT || it.type == EspType.DS18B20 }
            .map { SensorOption(d.key, it.i, "${d.name} · ${it.name}" + (it.temp?.let { v -> String.format(Locale.US, " (%.1f °C)", v) } ?: "")) }
    }
    val targets = devices

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Новий термостат" else "Термостат") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(t.name, { t = t.copy(name = it) }, label = { Text("Назва") }, singleLine = true)

                Text("Датчик", style = MaterialTheme.typography.labelLarge)
                if (sensors.isEmpty()) Text(
                    "Немає датчиків температури. Додай плату ESP32 з DHT/DS18B20/AHT і відкрий її хоча б раз, щоб додаток побачив датчик.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                ) else Picker(
                    sensors.firstOrNull { it.key == t.sensorKey && it.index == t.sensorIndex }?.label ?: "Вибрати датчик",
                    sensors.map { it.label },
                ) { i -> t = t.copy(sensorKey = sensors[i].key, sensorIndex = sensors[i].index) }

                Text("Що вмикати", style = MaterialTheme.typography.labelLarge)
                Picker(
                    targets.firstOrNull { it.key == t.targetKey }?.name ?: "Вибрати розетку чи реле",
                    targets.map { it.name },
                ) { i -> t = t.copy(targetKey = targets[i].key) }

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThermoMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = t.mode == m,
                            onClick = {
                                // для охолодження поріг вмикання має бути вищим за поріг вимикання
                                t = if (m == t.mode) t else t.copy(mode = m, onAt = t.offAt, offAt = t.onAt)
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, ThermoMode.entries.size),
                        ) { Text(m.label) }
                    }
                }
                TempStepper(if (t.mode == ThermoMode.HEAT) "Увімкнути, коли ≤" else "Увімкнути, коли ≥", t.onAt) { t = t.copy(onAt = it) }
                TempStepper(if (t.mode == ThermoMode.HEAT) "Вимкнути, коли ≥" else "Вимкнути, коли ≤", t.offAt) { t = t.copy(offAt = it) }
                if (!t.valid && t.sensorKey.isNotBlank() && t.targetKey.isNotBlank()) Text(
                    if (t.mode == ThermoMode.HEAT) "Поріг вимкнення має бути вищим за поріг увімкнення"
                    else "Поріг вимкнення має бути нижчим за поріг увімкнення",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Між порогами стан не змінюється — так обігрівач не клацає щохвилини. Перемикання не частіше ніж раз на хвилину. Якщо датчик недоступний 5 хв, обігрів вимикається.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = t.valid, onClick = { onSave(t) }) { Text("Зберегти") } },
        dismissButton = {
            Row {
                if (!isNew) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Видалити") }
                TextButton(onClick = onDismiss) { Text("Скасувати") }
            }
        },
    )
}

@Composable
private fun Picker(current: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(current, modifier = Modifier.weight(1f), maxLines = 2)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(i); open = false }) }
        }
    }
}

@Composable
internal fun TempStepper(label: String, value: Double, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = { onChange(((value - 0.5) * 2).let { Math.round(it) / 2.0 }) }) { Icon(Icons.Filled.Remove, "Менше") }
        Text(String.format(Locale.US, "%.1f°", value), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(64.dp).padding(horizontal = 4.dp))
        FilledTonalIconButton(onClick = { onChange(((value + 0.5) * 2).let { Math.round(it) / 2.0 }) }) { Icon(Icons.Filled.Add, "Більше") }
    }
}
