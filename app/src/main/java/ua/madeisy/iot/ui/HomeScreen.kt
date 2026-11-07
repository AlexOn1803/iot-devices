@file:OptIn(ExperimentalMaterial3Api::class)

package ua.madeisy.iot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.data.DeviceConfig
import ua.madeisy.iot.data.PlugState
import ua.madeisy.iot.esp.EspState

@Composable
fun HomeScreen(nav: Nav) {
    val app = IotApp.instance
    val devices by app.storage.devices.collectAsState()
    val states by app.controller.states.collectAsState()
    val espStates by app.controller.espStates.collectAsState()
    var addChooser by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мої пристрої") },
                actions = {
                    IconButton(onClick = { nav.push(Screen.Thermostats) }) { Icon(Icons.Filled.Thermostat, "Термостати") }
                    IconButton(onClick = { nav.push(Screen.Settings) }) { Icon(Icons.Filled.Settings, "Налаштування") }
                }
            )
        },
        floatingActionButton = {
            if (devices.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { addChooser = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("Додати") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (devices.isEmpty()) {
            EmptyState(Modifier.padding(pad), nav)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(devices, key = { it.key }) { d ->
                    KeepConnected(d.key)
                    if (d.isEsp) EspCard(
                        cfg = d,
                        s = espStates[d.key] ?: EspState(),
                        onClick = { nav.push(Screen.Device(d.key)) },
                        onToggle = { on ->
                            scope.launch {
                                app.controller.setSwitch(d.key, on).onFailure { snackbar.showSnackbar(it.message ?: "Не вдалося") }
                            }
                        },
                    ) else DeviceCard(
                        cfg = d,
                        s = states[d.key] ?: PlugState(),
                        onClick = { nav.push(Screen.Device(d.key)) },
                        onToggle = { on ->
                            scope.launch {
                                app.controller.setSwitch(d.key, on).onFailure {
                                    snackbar.showSnackbar(it.message ?: "Не вдалося")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
    if (addChooser) AddChooser(nav) { addChooser = false }
}

@Composable
private fun AddChooser(nav: Nav, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Що додаємо?") },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("Розетка Tuya / Smart Life") },
                    supportingContent = { Text("Вручну або з хмари") },
                    modifier = Modifier.clickable { onDismiss(); nav.push(Screen.Edit(null)) },
                )
                ListItem(
                    headlineContent = { Text("Плата ESP32-C3") },
                    supportingContent = { Text("Реле, датчики, світло — з прошивкою IoT Devices") },
                    modifier = Modifier.clickable { onDismiss(); nav.push(Screen.AddEsp) },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

@Composable
private fun EspCard(cfg: DeviceConfig, s: EspState, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val sum = IotApp.instance.controller.summary(cfg)
    val p = s.primary
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (p?.on == true) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cfg.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    when { s.online -> "ESP32 · локально"; s.connecting -> "Підключення…"; else -> s.error ?: "Офлайн" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.online || s.connecting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                Text(sum.sub ?: sum.main, style = MaterialTheme.typography.headlineMedium)
            }
            if (p != null) Switch(
                checked = p.on == true, onCheckedChange = onToggle, enabled = s.online,
                colors = SwitchDefaults.colors(checkedTrackColor = OnGreen),
            )
        }
    }
}

@Composable
private fun DeviceCard(cfg: DeviceConfig, s: PlugState, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (s.on == true) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cfg.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                StatusLine(cfg, s)
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        !s.online && s.on == null -> "—"
                        s.on == false -> "Вимкнено"
                        else -> formatPower(s.powerW)
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Switch(
                checked = s.on == true,
                onCheckedChange = onToggle,
                enabled = s.online || cfg.canLocal,
                colors = SwitchDefaults.colors(checkedTrackColor = OnGreen),
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, nav: Nav) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Power, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Ще немає пристроїв", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Найпростіше — ввести Access ID і Secret з iot.tuya.com у налаштуваннях і імпортувати розетку з хмари разом із ключем.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { nav.push(Screen.Settings) }, modifier = Modifier.fillMaxWidth()) { Text("Імпорт з хмари Tuya") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { nav.push(Screen.Edit(null)) }, modifier = Modifier.fillMaxWidth()) { Text("Додати розетку вручну") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { nav.push(Screen.AddEsp) }, modifier = Modifier.fillMaxWidth()) { Text("Додати плату ESP32-C3") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { nav.push(Screen.Help) }, modifier = Modifier.fillMaxWidth()) { Text("Як отримати ключі") }
        }
    }
}
