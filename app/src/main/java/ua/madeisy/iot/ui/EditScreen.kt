@file:OptIn(ExperimentalMaterial3Api::class)

package ua.madeisy.iot.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.data.ConnectionMode
import ua.madeisy.iot.data.DeviceConfig
import ua.madeisy.iot.data.DpMap
import ua.madeisy.iot.tuya.CloudDevice
import ua.madeisy.iot.tuya.DiscoveredDevice

@Composable
fun EditScreen(key: String?, nav: Nav) {
    val app = IotApp.instance
    val original = remember(key) { key?.let { app.storage.device(it) } ?: DeviceConfig() }
    var name by remember { mutableStateOf(original.name) }
    var deviceId by remember { mutableStateOf(original.deviceId) }
    var localKey by remember { mutableStateOf(original.localKey) }
    var ip by remember { mutableStateOf(original.ip) }
    var version by remember { mutableStateOf(original.version) }
    var mode by remember { mutableStateOf(original.mode) }
    var dps by remember { mutableStateOf(original.dps) }
    var showKey by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var scanResults by remember { mutableStateOf<List<DiscoveredDevice>?>(null) }
    var cloudResults by remember { mutableStateOf<List<CloudDevice>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun applyCloud(d: CloudDevice) {
        deviceId = d.id
        if (d.localKey.isNotBlank()) localKey = d.localKey
        if (name.isBlank() || name == "Розетка") name = d.name.ifBlank { name }
    }

    fun save() {
        val cfg = original.copy(
            name = name.trim().ifEmpty { "Розетка" }, deviceId = deviceId.trim(), localKey = localKey.trim(),
            ip = ip.trim(), version = version, mode = mode, dps = dps,
            cloudCodes = if (deviceId.trim() != original.deviceId) emptyMap() else original.cloudCodes,
        )
        app.storage.upsertDevice(cfg)
        app.controller.restart(cfg.key)
        if (key == null) nav.replace(Screen.Device(cfg.key)) else nav.pop()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (key == null) "Нова розетка" else "Налаштування розетки") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    if (key != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Видалити") }
                    IconButton(onClick = { save() }) { Icon(Icons.Filled.Check, "Зберегти") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    enabled = busy == null,
                    onClick = {
                        busy = "Шукаю в мережі…"
                        scope.launch {
                            val r = runCatching { app.controller.scanNetwork() }
                            busy = null
                            r.onSuccess { scanResults = it }.onFailure { snackbar.showSnackbar(it.message ?: "Помилка пошуку") }
                        }
                    },
                ) { Icon(Icons.Filled.Wifi, null); Spacer(Modifier.width(6.dp)); Text("В мережі") }
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    enabled = busy == null,
                    onClick = {
                        if (!app.storage.cloud.value.configured) {
                            scope.launch { snackbar.showSnackbar("Спершу введи Access ID і Secret у налаштуваннях") }
                        } else scope.launch {
                            busy = "Запит до хмари…"
                            val cloud = app.controller.cloudOrThrow()
                            val id = deviceId.trim()
                            val r = runCatching {
                                if (id.isNotEmpty()) listOf(cloud.getDevice(id)) else cloud.listDevices()
                            }
                            busy = null
                            r.onSuccess { list ->
                                when {
                                    list.isEmpty() -> snackbar.showSnackbar("У хмарному проєкті немає пристроїв — прив'яжи акаунт Smart Life")
                                    id.isNotEmpty() -> { applyCloud(list.first()); snackbar.showSnackbar("Ключ отримано") }
                                    else -> cloudResults = list
                                }
                            }.onFailure { snackbar.showSnackbar(it.message ?: "Помилка хмари") }
                        }
                    },
                ) { Icon(Icons.Filled.Cloud, null); Spacer(Modifier.width(6.dp)); Text("З хмари") }
            }
            busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "«З хмари» з порожнім Device ID покаже всі пристрої, із заповненим — отримає ключ саме для нього. «В мережі» знайде IP та версію протоколу.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(name, { name = it }, label = { Text("Назва") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                deviceId, { deviceId = it.trim() }, label = { Text("Device ID (віртуальний ідентифікатор)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                localKey, { localKey = it.trim() }, label = { Text("Local key") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Показати")
                    }
                },
                isError = localKey.isNotEmpty() && localKey.length != 16,
                supportingText = {
                    if (localKey.isNotEmpty() && localKey.length != 16) Text("Ключ має містити 16 символів (зараз ${localKey.length})")
                },
            )
            OutlinedTextField(
                ip, { ip = it.trim() }, label = { Text("Локальна IP-адреса") }, singleLine = true,
                placeholder = { Text("192.168.1.50") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Порожньо — додаток спробує знайти сам") },
            )

            Text("Версія протоколу", style = MaterialTheme.typography.labelLarge)
            val versions = listOf("3.3", "3.4", "3.5")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                versions.forEachIndexed { i, v ->
                    SegmentedButton(selected = version == v, onClick = { version = v }, shape = SegmentedButtonDefaults.itemShape(i, versions.size)) { Text(v) }
                }
            }
            Text("Підключення", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ConnectionMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(selected = mode == m, onClick = { mode = m }, shape = SegmentedButtonDefaults.itemShape(i, ConnectionMode.entries.size)) { Text(m.label) }
                }
            }
            Text(
                when (mode) {
                    ConnectionMode.AUTO -> "Вдома — напряму по Wi-Fi, поза домом — через хмару Tuya."
                    ConnectionMode.LOCAL -> "Тільки напряму по Wi-Fi. Працює без інтернету."
                    ConnectionMode.CLOUD -> "Тільки через хмару. Потрібні Access ID і Secret."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Номери DP (розширено)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
            if (advanced) {
                DpEditor(dps) { dps = it }
                TextButton(onClick = { dps = DpMap() }) { Text("Скинути до типових") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    scanResults?.let { list ->
        AlertDialog(
            onDismissRequest = { scanResults = null },
            title = { Text("Знайдено в мережі") },
            text = {
                if (list.isEmpty()) {
                    Text("Нічого не знайдено. Переконайся, що телефон у тій самій Wi-Fi мережі, що й розетка. Деякі роутери блокують широкомовні пакети — тоді IP можна подивитись у списку клієнтів роутера.")
                } else Column {
                    list.forEach { d ->
                        ListItem(
                            headlineContent = { Text("${d.ip}  ·  v${d.version}") },
                            supportingContent = { Text(d.deviceId + if (d.deviceId == deviceId) "  ✓ цей пристрій" else "") },
                            modifier = Modifier.clickable {
                                ip = d.ip; version = d.version
                                if (deviceId.isBlank()) deviceId = d.deviceId
                                scanResults = null
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { scanResults = null }) { Text("Закрити") } },
        )
    }

    cloudResults?.let { list ->
        AlertDialog(
            onDismissRequest = { cloudResults = null },
            title = { Text("Пристрої в хмарі") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    list.forEach { d ->
                        ListItem(
                            headlineContent = { Text(d.name.ifBlank { d.id }) },
                            supportingContent = { Text("${d.productName} · ${if (d.online) "онлайн" else "офлайн"}") },
                            modifier = Modifier.clickable { applyCloud(d); cloudResults = null },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { cloudResults = null }) { Text("Закрити") } },
        )
    }

    if (confirmDelete && key != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Видалити «${original.name}»?") },
            text = { Text("Буде видалено налаштування, розклади й історію споживання цієї розетки. Саму розетку в Smart Life це не зачепить.") },
            confirmButton = {
                TextButton(onClick = {
                    app.controller.release(key)
                    app.storage.removeDevice(key)
                    runCatching { app.history.clear(key) }
                    confirmDelete = false
                    nav.popTo(Screen.Home)
                }) { Text("Видалити") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Скасувати") } },
        )
    }
}

@Composable
private fun DpEditor(d: DpMap, onChange: (DpMap) -> Unit) {
    @Composable
    fun num(label: String, value: Int, set: (Int) -> DpMap, modifier: Modifier = Modifier) {
        var text by remember(value) { mutableStateOf(value.toString()) }
        OutlinedTextField(
            text,
            { t -> text = t.filter { it.isDigit() }.take(3); text.toIntOrNull()?.let { onChange(set(it)) } },
            label = { Text(label) }, singleLine = true, modifier = modifier,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            num("Вкл/викл", d.switch, { d.copy(switch = it) }, Modifier.weight(1f))
            num("Таймер", d.countdown, { d.copy(countdown = it) }, Modifier.weight(1f))
            num("Після світла", d.relayStatus, { d.copy(relayStatus = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            num("Потужність", d.power, { d.copy(power = it) }, Modifier.weight(1f))
            num("÷10^", d.powerScale, { d.copy(powerScale = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            num("Напруга", d.voltage, { d.copy(voltage = it) }, Modifier.weight(1f))
            num("÷10^", d.voltageScale, { d.copy(voltageScale = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            num("Струм", d.current, { d.copy(current = it) }, Modifier.weight(1f))
            num("÷10^", d.currentScale, { d.copy(currentScale = it) }, Modifier.weight(1f))
        }
        Text(
            "Типово для розеток Tuya з лічильником: 1 — вкл/викл, 9 — таймер, 18 — струм (мА), 19 — потужність (×10), 20 — напруга (×10).",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
