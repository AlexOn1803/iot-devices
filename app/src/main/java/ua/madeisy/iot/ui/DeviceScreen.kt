@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ua.madeisy.iot.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.data.HistoryDb
import ua.madeisy.iot.data.PlugState
import ua.madeisy.iot.data.Sample
import ua.madeisy.iot.data.Schedule
import ua.madeisy.iot.service.ScheduleManager
import java.util.Calendar
import java.util.Locale

@Composable
fun DeviceScreen(key: String, nav: Nav) {
    val app = IotApp.instance
    val devices by app.storage.devices.collectAsState()
    val cfg = devices.firstOrNull { it.key == key }
    if (cfg == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    KeepConnected(key)
    val states by app.controller.states.collectAsState()
    val s = states[key] ?: PlugState()
    val settings by app.storage.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun act(block: suspend () -> Result<Unit>) {
        scope.launch { block().onFailure { snackbar.showSnackbar(it.message ?: "Не вдалося") } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cfg.name) },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = { IconButton(onClick = { nav.push(Screen.Edit(key)) }) { Icon(Icons.Filled.Edit, "Редагувати") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusLine(cfg, s, Modifier.padding(top = 4.dp))
            PowerHero(s, enabled = s.online || cfg.canLocal) { act { app.controller.setSwitch(key, s.on != true) } }
            Stats(key, s, settings.tariff)
            HistoryCard(key)
            TimerCard(s) { sec -> act { app.controller.setCountdown(key, sec) } }
            SchedulesCard(key)
            if (s.raw.containsKey(cfg.dps.relayStatus.toString())) {
                RelayStatusCard(s.relayStatus) { v -> act { app.controller.setRelayStatus(key, v) } }
            }
            RawDpsCard(s)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PowerHero(s: PlugState, enabled: Boolean, onToggle: () -> Unit) {
    val on = s.on == true
    val bg by animateColorAsState(if (on) OnGreen else MaterialTheme.colorScheme.surfaceContainerHighest, label = "bg")
    val fg = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape, color = bg, shadowElevation = if (on) 6.dp else 0.dp,
            modifier = Modifier.size(128.dp).clickable(enabled = enabled, onClick = onToggle),
        ) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(48.dp), tint = fg)
                Text(
                    when (s.on) { true -> "Увімкнено"; false -> "Вимкнено"; null -> "Невідомо" },
                    color = fg, style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Spacer(Modifier.width(20.dp))
        Column {
            Text("Потужність", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (s.on == false) "0 Вт" else formatPower(s.powerW),
                style = MaterialTheme.typography.displaySmall,
            )
        }
    }
}

@Composable
private fun Stats(key: String, s: PlugState, tariff: Double) {
    var todayKwh by remember { mutableStateOf(0.0) }
    LaunchedEffect(key) {
        while (true) {
            val start = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            todayKwh = withContext(Dispatchers.IO) { HistoryDb.energyKwh(IotApp.instance.history.since(key, start)) }
            delay(15_000)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("Напруга", s.voltageV?.let { String.format(Locale.US, "%.1f В", it) } ?: "—", Modifier.weight(1f))
        StatTile("Струм", s.currentA?.let { String.format(Locale.US, "%.3f А", it) } ?: "—", Modifier.weight(1f))
        StatTile(
            "Сьогодні", formatEnergy(todayKwh), Modifier.weight(1f),
            sub = String.format(Locale.US, "≈ %.2f грн", todayKwh * tariff),
        )
    }
}

@Composable
private fun HistoryCard(key: String) {
    val ranges = listOf("1 год" to 1, "6 год" to 6, "24 год" to 24, "7 днів" to 168)
    var range by remember { mutableIntStateOf(0) }
    var samples by remember { mutableStateOf<List<Sample>>(emptyList()) }
    var from by remember { mutableLongStateOf(0L) }
    var to by remember { mutableLongStateOf(1L) }
    LaunchedEffect(key, range) {
        while (true) {
            val now = System.currentTimeMillis()
            val f = now - ranges[range].second * 3600_000L
            val list = withContext(Dispatchers.IO) { IotApp.instance.history.since(key, f) }
            samples = downsample(list, 400); from = f; to = now
            delay(10_000)
        }
    }
    SectionCard("Споживання") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ranges.forEachIndexed { i, (label, _) ->
                SegmentedButton(
                    selected = range == i, onClick = { range = i },
                    shape = SegmentedButtonDefaults.itemShape(i, ranges.size),
                ) { Text(label) }
            }
        }
        Spacer(Modifier.height(12.dp))
        PowerChart(samples, from, to)
        if (samples.size >= 2) {
            Spacer(Modifier.height(8.dp))
            val kwh = HistoryDb.energyKwh(samples)
            val peak = samples.maxOf { it.power }
            Text(
                "За період: ${formatEnergy(kwh)} · пік ${formatPower(peak)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Зменшує кількість точок для графіка, зберігаючи піки в кожному відрізку. */
private fun downsample(list: List<Sample>, target: Int): List<Sample> {
    if (list.size <= target) return list
    val bucket = list.size / target + 1
    return list.chunked(bucket).map { c -> c.maxBy { it.power } }
}

@Composable
private fun TimerCard(s: PlugState, onSet: (Int) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.countdownSec, s.countdownAt) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val left = s.countdownLeft(now)
    var custom by remember { mutableStateOf(false) }
    val verb = if (s.on == true) "Вимкнути" else "Увімкнути"
    SectionCard("Таймер") {
        if (left != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (s.on == true) "Вимкнеться через" else "Увімкнеться через", style = MaterialTheme.typography.bodyMedium)
                    Text(formatDuration(left), style = MaterialTheme.typography.headlineMedium)
                }
                TextButton(onClick = { onSet(0) }) { Text("Скасувати") }
            }
        } else {
            Text("$verb через:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("15 хв" to 900, "30 хв" to 1800, "1 год" to 3600, "2 год" to 7200, "4 год" to 14400).forEach { (l, sec) ->
                    AssistChip(onClick = { onSet(sec) }, label = { Text(l) })
                }
                AssistChip(onClick = { custom = true }, label = { Text("Інше…") })
            }
            Text(
                "Таймер працює в самій розетці — спрацює, навіть якщо телефон вимкнено.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    if (custom) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text("$verb через") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { v -> text = v.filter { it.isDigit() }.take(4) },
                    label = { Text("Хвилин") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntOrNull()?.takeIf { it in 1..1440 }?.let { onSet(it * 60) }
                    custom = false
                }) { Text("Запустити") }
            },
            dismissButton = { TextButton(onClick = { custom = false }) { Text("Скасувати") } },
        )
    }
}

@Composable
internal fun SchedulesCard(deviceKey: String) {
    val app = IotApp.instance
    val ctx = LocalContext.current
    val all by app.storage.schedules.collectAsState()
    val list = all.filter { it.deviceKey == deviceKey }.sortedBy { it.hour * 60 + it.minute }
    var editing by remember { mutableStateOf<Schedule?>(null) }

    SectionCard("Розклад") {
        if (list.isEmpty()) {
            Text(
                "Наприклад: вимикати щодня о 23:00. Розклад виконує телефон, тож у цей момент потрібен зв'язок з розеткою (вдома або через хмару).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        list.forEachIndexed { i, sch ->
            if (i > 0) HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable { editing = sch }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        String.format(Locale.US, "%02d:%02d", sch.hour, sch.minute) + "  " + if (sch.turnOn) "увімкнути" else "вимкнути",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (sch.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    )
                    Text(formatDays(sch.days), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = sch.enabled, onCheckedChange = { en ->
                    val n = sch.copy(enabled = en)
                    app.storage.upsertSchedule(n)
                    ScheduleManager.schedule(ctx, n)
                })
            }
        }
        TextButton(onClick = {
            editing = Schedule(deviceKey = deviceKey, hour = 23, minute = 0, days = 0b1111111, turnOn = false)
        }) {
            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Додати")
        }
    }

    editing?.let { e ->
        ScheduleDialog(
            initial = e,
            isNew = list.none { it.id == e.id },
            onDismiss = { editing = null },
            onSave = { n ->
                app.storage.upsertSchedule(n); ScheduleManager.schedule(ctx, n); editing = null
            },
            onDelete = {
                ScheduleManager.cancel(ctx, e.id); app.storage.removeSchedule(e.id); editing = null
            },
        )
    }
}

@Composable
private fun ScheduleDialog(initial: Schedule, isNew: Boolean, onDismiss: () -> Unit, onSave: (Schedule) -> Unit, onDelete: () -> Unit) {
    val time = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    var days by remember { mutableIntStateOf(initial.days) }
    var turnOn by remember { mutableStateOf(initial.turnOn) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Новий розклад" else "Розклад") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TimePicker(time)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !turnOn, onClick = { turnOn = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Вимкнути") }
                    SegmentedButton(selected = turnOn, onClick = { turnOn = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Увімкнути") }
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_NAMES.forEachIndexed { i, d ->
                        FilterChip(
                            selected = days and (1 shl i) != 0,
                            onClick = { days = days xor (1 shl i) },
                            label = { Text(d) },
                        )
                    }
                }
                Text(
                    if (days == 0) "Без днів — спрацює один раз" else formatDays(days),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(hour = time.hour, minute = time.minute, days = days, turnOn = turnOn, enabled = true))
            }) { Text("Зберегти") }
        },
        dismissButton = {
            Row {
                if (!isNew) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Видалити") }
                TextButton(onClick = onDismiss) { Text("Скасувати") }
            }
        },
    )
}

@Composable
private fun RelayStatusCard(value: String?, onSet: (String) -> Unit) {
    val options = listOf("off" to "Вимкнено", "on" to "Увімкнено", "memory" to "Як було")
    SectionCard("Після відновлення світла") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (v, label) ->
                SegmentedButton(
                    selected = value == v, onClick = { onSet(v) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label) }
            }
        }
        Text(
            "Що робить розетка, коли після відключення знову з'являється напруга.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun RawDpsCard(s: PlugState) {
    var open by remember { mutableStateOf(false) }
    SectionCard {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("Сирі дані (DP)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            if (s.raw.isEmpty()) Text("Ще немає даних", style = MaterialTheme.typography.bodySmall)
            s.raw.entries.sortedBy { it.key.toIntOrNull() ?: 999 }.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
                    Text(v.toString(), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                "Якщо значення потужності/напруги здаються дивними — номери DP і множники можна змінити в редагуванні пристрою.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
