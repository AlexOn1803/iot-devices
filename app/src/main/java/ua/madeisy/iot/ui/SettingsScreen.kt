@file:OptIn(ExperimentalMaterial3Api::class)

package ua.madeisy.iot.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import ua.madeisy.iot.BuildConfig
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.data.CloudSettings
import ua.madeisy.iot.data.DeviceConfig
import ua.madeisy.iot.service.MonitorService
import ua.madeisy.iot.tuya.CloudDevice
import ua.madeisy.iot.tuya.CloudRegion

@Composable
fun SettingsScreen(nav: Nav) {
    val app = IotApp.instance
    val ctx = LocalContext.current
    val cloud by app.storage.cloud.collectAsState()
    val settings by app.storage.settings.collectAsState()
    var accessId by remember { mutableStateOf(cloud.accessId) }
    var secret by remember { mutableStateOf(cloud.secret) }
    var region by remember { mutableStateOf(cloud.region) }
    var showSecret by remember { mutableStateOf(false) }
    var regionMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var importList by remember { mutableStateOf<List<CloudDevice>?>(null) }
    var tariffText by remember { mutableStateOf(settings.tariff.toString()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun saveCloud() = app.storage.saveCloud(CloudSettings(accessId, secret, region))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Налаштування") },
                navigationIcon = {
                    IconButton(onClick = { saveCloud(); nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Хмара Tuya") {
                Text(
                    "Ключі з iot.tuya.com → Cloud → твій проєкт → Overview. Зберігаються лише на цьому телефоні.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(accessId, { accessId = it.trim() }, label = { Text("Access ID / Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    secret, { secret = it.trim() }, label = { Text("Access Secret") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Показати")
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton(onClick = { regionMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Дата-центр: ${region.label}", modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = regionMenu, onDismissRequest = { regionMenu = false }) {
                        CloudRegion.entries.forEach { r ->
                            DropdownMenuItem(text = { Text(r.label) }, onClick = { region = r; regionMenu = false })
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy && accessId.isNotBlank() && secret.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            saveCloud(); busy = true
                            scope.launch {
                                val r = runCatching { app.controller.cloudOrThrow().testConnection() }
                                busy = false
                                snackbar.showSnackbar(r.fold({ "Підключено успішно ✓" }, { it.message ?: "Помилка" }))
                            }
                        },
                    ) { Text("Перевірити") }
                    Button(
                        enabled = !busy && accessId.isNotBlank() && secret.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            saveCloud(); busy = true
                            scope.launch {
                                val r = runCatching { app.controller.cloudOrThrow().listDevices() }
                                busy = false
                                r.onSuccess {
                                    if (it.isEmpty()) snackbar.showSnackbar("Пристроїв немає — прив'яжи акаунт Smart Life у проєкті (Devices → Link App Account)")
                                    else importList = it
                                }.onFailure { snackbar.showSnackbar(it.message ?: "Помилка") }
                            }
                        },
                    ) { Text("Імпорт") }
                }
            }

            MqttCard(snackbar)

            SectionCard("Загальне") {
                OutlinedTextField(
                    tariffText,
                    { t ->
                        tariffText = t.replace(',', '.').filter { it.isDigit() || it == '.' }.take(6)
                        tariffText.toDoubleOrNull()?.let { app.storage.saveSettings(settings.copy(tariff = it)) }
                    },
                    label = { Text("Тариф, грн за кВт·год") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Фоновий моніторинг", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Тримає зв'язок з розетками й записує графік, навіть коли додаток закрито. Показує потужність у сповіщенні. Трохи витрачає батарею.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = settings.backgroundMonitor, onCheckedChange = { on ->
                        app.storage.saveSettings(settings.copy(backgroundMonitor = on))
                        if (on) {
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            MonitorService.start(ctx)
                        } else MonitorService.stop(ctx)
                    })
                }
                if (!canExactAlarms(ctx)) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                        )
                    }, modifier = Modifier.fillMaxWidth()) { Text("Дозволити точні будильники для розкладу") }
                }
            }

            OutlinedButton(onClick = { nav.push(Screen.Help) }, modifier = Modifier.fillMaxWidth()) { Text("Як отримати ключі Tuya") }
            Text(
                "IoT Devices ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }

    importList?.let { list -> ImportDialog(list, onDismiss = { importList = null }) { count ->
        importList = null
        scope.launch { snackbar.showSnackbar("Додано/оновлено: $count") }
    } }
}

@Composable
private fun MqttCard(snackbar: SnackbarHostState) {
    val app = IotApp.instance
    val saved by app.storage.mqtt.collectAsState()
    val status by app.controller.mqtt.status.collectAsState()
    var host by remember { mutableStateOf(saved.host) }
    var port by remember { mutableStateOf(saved.port.toString()) }
    var user by remember { mutableStateOf(saved.user) }
    var pass by remember { mutableStateOf(saved.pass) }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun current() = ua.madeisy.iot.esp.MqttSettings(host.trim(), port.toIntOrNull() ?: 8883, user.trim(), pass)

    SectionCard("Плати ESP через інтернет (MQTT)") {
        Text(
            "Щоб керувати платами поза домом. Безкоштовно: hivemq.com → Sign up → Create Serverless Cluster → Access Management → додай логін і пароль (Publish and Subscribe). Адресу кластера скопіюй з Overview (…hivemq.cloud), порт 8883.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(host, { host = it.trim() }, label = { Text("Адреса брокера") }, singleLine = true,
            placeholder = { Text("xxxx.s1.eu.hivemq.cloud") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(port, { port = it.filter { c -> c.isDigit() }.take(5) }, label = { Text("Порт") }, singleLine = true,
                modifier = Modifier.width(96.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(user, { user = it }, label = { Text("Логін") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Показати") } },
        )
        Spacer(Modifier.height(4.dp))
        Text("Стан: $status", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && host.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
                busy = true
                scope.launch {
                    val err = ua.madeisy.iot.esp.EspMqtt.test(current())
                    busy = false
                    snackbar.showSnackbar(err?.let { "Не вдалося: $it" } ?: "Брокер працює ✓")
                }
            }) { Text("Перевірити") }
            Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                app.storage.saveMqtt(current())
                scope.launch { snackbar.showSnackbar(if (host.isBlank()) "MQTT вимкнено" else "Збережено. Плати отримають налаштування, щойно додаток побачить їх удома.") }
            }) { Text("Зберегти") }
        }
        Text(
            "Плата отримує ці налаштування від додатку, коли ви в одній мережі. Ключ доступу плати підписує кожну команду, тож чужі команди через брокер вона не виконає.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun canExactAlarms(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < 31 || (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

@Composable
private fun ImportDialog(list: List<CloudDevice>, onDismiss: () -> Unit, onDone: (Int) -> Unit) {
    val app = IotApp.instance
    val existing = app.storage.devices.value.map { it.deviceId }.toSet()
    // Розетки Tuya мають категорії cz (розетка) / pc (подовжувач); їх відмічаємо за замовчуванням.
    val checked = remember { mutableStateListOf<String>().apply { addAll(list.filter { it.category in setOf("cz", "pc") || it.id in existing }.map { it.id }) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Імпорт з хмари") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                list.forEach { d ->
                    ListItem(
                        headlineContent = { Text(d.name.ifBlank { d.id }) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    d.productName.ifBlank { null },
                                    if (d.online) "онлайн" else "офлайн",
                                    if (d.id in existing) "вже додано — оновить ключ" else null,
                                ).joinToString(" · ")
                            )
                        },
                        leadingContent = {
                            Checkbox(checked = d.id in checked, onCheckedChange = { c -> if (c) checked += d.id else checked -= d.id })
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                var n = 0
                list.filter { it.id in checked }.forEach { d ->
                    val old = app.storage.devices.value.firstOrNull { it.deviceId == d.id }
                    val cfg = (old ?: DeviceConfig(name = d.name.ifBlank { "Розетка" })).copy(
                        deviceId = d.id,
                        localKey = d.localKey.ifBlank { old?.localKey ?: "" },
                    )
                    app.storage.upsertDevice(cfg)
                    app.controller.restart(cfg.key)
                    n++
                }
                onDone(n)
            }) { Text("Додати") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

@Composable
fun HelpScreen(nav: Nav) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Як отримати ключі") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val steps = listOf(
                "Зареєструйся на iot.tuya.com (зручніше з комп'ютера).",
                "Cloud → Development → Create Cloud Project. Тип — Smart Home, дата-центр — Central Europe. На запит API погоджуйся на IoT Core та Authorization.",
                "У проєкті відкрий Overview і скопіюй Access ID та Access Secret. Встав їх у налаштуваннях цього додатку.",
                "Devices → Link App Account → Add App Account. Відскануй QR-код у Smart Life: Я → значок сканера вгорі праворуч.",
                "Повернись у додаток → Налаштування → «Імпорт». Розетки додадуться разом з local key.",
                "Відкрий розетку → редагування → «В мережі», щоб знайти її IP і версію протоколу. Далі вдома все працюватиме напряму, навіть без інтернету.",
            )
            steps.forEachIndexed { i, t ->
                Row {
                    Text("${i + 1}.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(28.dp))
                    Text(t, style = MaterialTheme.typography.bodyLarge)
                }
            }
            SectionCard("Важливо") {
                Text(
                    "• Якщо видалити розетку зі Smart Life і додати знову, local key зміниться — просто зроби «Імпорт» ще раз.\n" +
                        "• Безкоштовний доступ до хмари Tuya треба раз на кілька місяців продовжувати в кабінеті (Cloud → Cloud Services → IoT Core → Extend).\n" +
                        "• Локальне керування працює без хмари і без продовження.\n" +
                        "• Закріпи за розетками постійні IP у роутері (DHCP reservation) — тоді з'єднання буде найстабільнішим.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
