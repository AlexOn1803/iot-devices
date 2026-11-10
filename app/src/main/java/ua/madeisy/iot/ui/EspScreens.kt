@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ua.madeisy.iot.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.data.DeviceConfig
import ua.madeisy.iot.data.DeviceKind
import ua.madeisy.iot.esp.EspClient
import ua.madeisy.iot.esp.EspComp
import ua.madeisy.iot.esp.EspCompConfig
import ua.madeisy.iot.esp.EspDiscovery
import ua.madeisy.iot.esp.EspFound
import ua.madeisy.iot.esp.EspState
import ua.madeisy.iot.esp.EspType
import java.util.Locale

private const val FIRMWARE_URL = "https://github.com/AlexOn1803/iot-devices/releases/tag/latest"
private const val FLASHER_URL = "https://espressif.github.io/esptool-js/"

// ======================= керування =======================

@Composable
fun EspDeviceScreen(key: String, nav: Nav) {
    val app = IotApp.instance
    val devices by app.storage.devices.collectAsState()
    val cfg = devices.firstOrNull { it.key == key }
    if (cfg == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    KeepConnected(key)
    val states by app.controller.espStates.collectAsState()
    val s = states[key] ?: EspState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pinDialog by remember { mutableStateOf(false) }
    if (pinDialog) ua.madeisy.iot.widget.PinWidgetDialog(cfg) { pinDialog = false }

    fun send(vararg cmd: JSONObject) {
        scope.launch {
            app.controller.espSet(key, *cmd).onFailure { snackbar.showSnackbar(it.message ?: "Не вдалося") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cfg.name) },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    IconButton(onClick = { pinDialog = true }) { Icon(Icons.Filled.Widgets, "Додати віджет") }
                    IconButton(onClick = { nav.push(Screen.Edit(key)) }) { Icon(Icons.Filled.Settings, "Налаштування") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EspStatusLine(cfg, s)
            UpdateBanner(cfg, s.online) { nav.push(Screen.Edit(key)) }
            if (s.online && s.comps.isEmpty()) {
                SectionCard("Плата ще не налаштована") {
                    Text(
                        "Вкажи, що підключено до яких пінів — реле, датчики, світло.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { nav.push(Screen.Edit(key)) }) { Text("Налаштувати компоненти") }
                }
            }
            s.comps.filter { it.type != EspType.BUTTON && it.type != EspType.UNKNOWN }.forEach { c ->
                EspCompCard(c, s) { send(it.put("i", c.i)) }
            }
            if (s.comps.any { it.type == EspType.RELAY }) SchedulesCard(key)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EspStatusLine(cfg: DeviceConfig, s: EspState) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Box(
            Modifier.size(8.dp).background(
                when { s.online -> OnGreen; s.connecting -> Color(0xFFE0A030); else -> MaterialTheme.colorScheme.outline },
                CircleShape,
            )
        )
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                s.online && s.remote -> "Через інтернет (MQTT)" + (s.rssi?.let { " · $it dBm" } ?: "")
                s.online -> "Напряму · ${cfg.ip}" + (s.rssi?.let { " · $it dBm" } ?: "")
                s.connecting -> "Підключення…"
                else -> s.error ?: "Офлайн"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (s.online || s.connecting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun EspCompCard(c: EspComp, s: EspState, send: (JSONObject) -> Unit) {
    when (c.type) {
        EspType.RELAY -> SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium)
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(c.countdown, s.updatedAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
                    val left = s.countdownLeft(c, now)
                    Text(
                        when {
                            left != null -> (if (c.on == true) "Вимкнеться через " else "Увімкнеться через ") + formatDuration(left)
                            c.on == true -> "Увімкнено"
                            else -> "Вимкнено"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = c.on == true, onCheckedChange = { send(JSONObject().put("on", it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = OnGreen),
                )
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.countdown > 0) AssistChip(onClick = { send(JSONObject().put("cd", 0)) }, label = { Text("Скасувати таймер") })
                else listOf("15 хв" to 900, "30 хв" to 1800, "1 год" to 3600, "2 год" to 7200).forEach { (l, sec) ->
                    AssistChip(onClick = { send(JSONObject().put("cd", sec)) }, label = { Text(l) })
                }
            }
        }
        EspType.PWM, EspType.WS2812 -> SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = c.on == true, onCheckedChange = { send(JSONObject().put("on", it)) })
            }
            var bri by remember(c.bri) { mutableFloatStateOf((c.bri ?: 255).toFloat()) }
            Text("Яскравість ${(bri / 2.55f).toInt()} %", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = bri, onValueChange = { bri = it }, valueRange = 1f..255f,
                onValueChangeFinished = { send(JSONObject().put("bri", bri.toInt())) })
            if (c.type == EspType.WS2812) {
                val colors = listOf(
                    Triple(255, 255, 255), Triple(255, 170, 80), Triple(255, 0, 0), Triple(255, 120, 0),
                    Triple(255, 230, 0), Triple(0, 220, 60), Triple(0, 180, 255), Triple(40, 60, 255), Triple(200, 0, 255),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { (r, g, b) ->
                        val sel = c.rgb == Triple(r, g, b)
                        Box(
                            Modifier.size(36.dp).background(Color(r, g, b), CircleShape)
                                .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { send(JSONObject().put("rgb", org.json.JSONArray(listOf(r, g, b))).put("fx", "none")) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val fx = listOf("none" to "Статично", "breathe" to "Дихання", "rainbow" to "Веселка")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    fx.forEachIndexed { i, (v, l) ->
                        SegmentedButton(selected = (c.fx ?: "none") == v, onClick = { send(JSONObject().put("fx", v)) },
                            shape = SegmentedButtonDefaults.itemShape(i, fx.size)) { Text(l) }
                    }
                }
            }
        }
        EspType.DHT, EspType.AHT, EspType.DS18B20, EspType.BME -> SectionCard {
            Text(c.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(c.temp?.let { String.format(Locale.US, "%.1f °C", it) } ?: "—", style = MaterialTheme.typography.displaySmall)
                c.hum?.let {
                    Spacer(Modifier.width(16.dp))
                    Text("${it.toInt()} %", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            c.pres?.let {
                Text(
                    String.format(Locale.US, "Тиск %.1f гПа · %.0f мм рт. ст.", it, it * 0.750062) + (c.model?.let { m -> " · $m" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (c.temp == null) Text("Датчик не відповідає — перевір підключення і пін", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        EspType.ANALOG -> SectionCard {
            Text(c.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${c.pct ?: 0} %", style = MaterialTheme.typography.displaySmall)
            Text("Сире значення: ${c.raw ?: 0}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        EspType.THERMO -> SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium)
                    val relay = s.comps.firstOrNull { it.i == c.target }
                    Text(
                        (if (c.heat) "Обігрів" else "Охолодження") + " · " + (relay?.name ?: "реле ?") + ": " + when (c.thermoState) {
                            "on" -> "працює"; "off" -> "вимкнено"; "nosensor" -> "немає даних з датчика"; else -> "термостат вимкнено"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (c.thermoState) { "on" -> OnGreen; "nosensor" -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                    )
                }
                Switch(checked = c.enabled == true, onCheckedChange = { send(JSONObject().put("enabled", it)) })
            }
            Text(c.temp?.let { String.format(Locale.US, "%.1f °C", it) } ?: "—", style = MaterialTheme.typography.displaySmall)
            val on = c.onAt ?: 18.0
            val off = c.offAt ?: 19.0
            TempStepper(if (c.heat) "Увімкнути, коли ≤" else "Увімкнути, коли ≥", on) { v ->
                if (if (c.heat) v < off else v > off) send(JSONObject().put("onAt", v))
            }
            TempStepper(if (c.heat) "Вимкнути, коли ≥" else "Вимкнути, коли ≤", off) { v ->
                if (if (c.heat) v > on else v < on) send(JSONObject().put("offAt", v))
            }
            Text(
                "Працює на самій платі — навіть без телефона й Wi-Fi.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EspType.INPUT -> SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (c.active == true) "Активно" else "Спокій",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (c.active == true) OnGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {}
    }
}

// ======================= додавання плати =======================

@Composable
fun EspAddScreen(nav: Nav) {
    val app = IotApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<EspFound>?>(null) }
    var manualIp by remember { mutableStateOf("") }
    var keyPrompt by remember { mutableStateOf<EspFound?>(null) }

    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    fun connect(f: EspFound, existingKey: String? = null) {
        busy = true
        scope.launch {
            try {
                val info = EspClient(f.host, existingKey ?: "").info()
                val key = when {
                    existingKey != null -> { EspClient(f.host, existingKey).state(); existingKey }
                    !info.claimed -> { val k = EspClient.newKey(); EspClient(f.host, "").claim(k, info.name); k }
                    else -> { keyPrompt = f; busy = false; return@launch }
                }
                val old = app.storage.devices.value.firstOrNull { it.isEsp && it.deviceId == info.id }
                val cfg = (old ?: DeviceConfig(name = info.name.ifBlank { "ESP32" }, kind = DeviceKind.ESP))
                    .copy(deviceId = info.id, ip = f.host, apiKey = key, kind = DeviceKind.ESP)
                app.storage.upsertDevice(cfg)
                app.controller.restart(cfg.key)
                busy = false
                nav.replace(Screen.Device(cfg.key))
            } catch (e: Exception) {
                busy = false
                snackbar.showSnackbar(e.message ?: "Не вдалося підключитися")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Плата ESP") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("1. Прошивка") {
                Text(
                    "Найпростіше — прошити прямо з телефона: підключи плату через OTG-кабель. Підходять ESP8266 (D1 mini, NodeMCU, ESP-01), ESP32, S2, S3, C3. Після прошивки додаток сам передасть плату Wi-Fi і додасть її. Далі оновлення йдуть по Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open(FIRMWARE_URL) }, modifier = Modifier.weight(1f)) { Text("Файли") }
                    OutlinedButton(onClick = { open(FLASHER_URL) }, modifier = Modifier.weight(1f)) { Text("З комп'ютера") }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { nav.push(Screen.Flash) }, modifier = Modifier.fillMaxWidth()) { Text("Прошити з телефона через USB") }
            }
            SectionCard("2. Wi-Fi") {
                Text(
                    "Після прошивки плата створить мережу «IoT-Setup-xxxx». Підключись до неї телефоном — відкриється сторінка, де треба вибрати домашній Wi-Fi (2.4 ГГц) і ввести пароль. Якщо сторінка не відкрилась — зайди на 192.168.4.1.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            SectionCard("3. Знайти плату") {
                Text("Повернись у домашній Wi-Fi і натисни «Знайти».", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        scope.launch {
                            found = runCatching { EspDiscovery.scan(ctx, 5000) }.getOrDefault(emptyList())
                            busy = false
                        }
                    },
                ) { Icon(Icons.Filled.Search, null); Spacer(Modifier.width(6.dp)); Text("Знайти в мережі") }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Зачекай…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                found?.let { list ->
                    if (list.isEmpty()) Text(
                        "Нічого не знайдено. Перевір, що плата підключилась (сторінка налаштування зникла), або введи її IP вручну.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    list.forEach { f ->
                        val known = app.storage.devices.value.any { it.isEsp && it.deviceId == f.id }
                        ListItem(
                            headlineContent = { Text(f.name) },
                            supportingContent = { Text("${f.id} · ${f.host}" + if (known) " · вже додано" else "") },
                            modifier = Modifier.clickable(enabled = !busy) { connect(f) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        manualIp, { manualIp = it.trim() }, label = { Text("або IP вручну") }, singleLine = true,
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(enabled = manualIp.isNotBlank() && !busy, onClick = { connect(EspFound("", "", manualIp)) }) { Text("OK") }
                }
            }
        }
    }

    keyPrompt?.let { f ->
        var k by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { keyPrompt = null },
            title = { Text("Плата вже прив'язана") },
            text = {
                Column {
                    Text("Введи її ключ доступу (є в налаштуваннях плати на іншому телефоні) або скинь плату: утримуй BOOT 5 секунд.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(k, { k = it.trim() }, label = { Text("Ключ") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(enabled = k.length >= 8, onClick = { keyPrompt = null; connect(f, k) }) { Text("Підключити") } },
            dismissButton = { TextButton(onClick = { keyPrompt = null }) { Text("Скасувати") } },
        )
    }
}

// ======================= налаштування компонентів =======================

@Composable
fun EspConfigScreen(key: String, nav: Nav) {
    val app = IotApp.instance
    val original = remember(key) { app.storage.device(key) }
    if (original == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var name by remember { mutableStateOf(original.name) }
    val comps = remember { mutableStateListOf<EspCompConfig>() }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    val client = remember(original) { EspClient(original.ip, original.apiKey) }
    var board by remember { mutableStateOf("esp32c3") }

    LaunchedEffect(key) {
        runCatching { client.info().board.takeIf { it.isNotBlank() && it != "unknown" }?.let { board = it } }
        try {
            val j = client.getConfig()
            comps.clear(); comps.addAll(EspCompConfig.listFromJson(j))
            j.optString("name").takeIf { it.isNotBlank() }?.let { if (name == original.name) name = it }
            loaded = true
        } catch (e: Exception) {
            loadError = e.message
        }
    }

    fun save() {
        busy = true
        scope.launch {
            try {
                if (loaded) client.postConfig(EspCompConfig.listToJson(name.trim(), comps.toList()))
                app.storage.updateDevice(key) { it.copy(name = name.trim().ifEmpty { "ESP32" }) }
                app.controller.restart(key)
                busy = false
                nav.pop()
            } catch (e: Exception) {
                busy = false
                snackbar.showSnackbar(e.message ?: "Не вдалося зберегти")
            }
        }
    }

    val usedPins = comps.flatMap { it.pins }.groupingBy { it }.eachCount()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Налаштування плати") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Видалити") }
                    IconButton(enabled = !busy, onClick = { save() }) { Icon(Icons.Filled.Check, "Зберегти") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Назва") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!loaded) {
                if (loadError != null) Text("Плата недоступна: $loadError. Компоненти можна змінити, коли вона буде онлайн.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text("Читаю конфігурацію плати…")
                }
            } else {
                Text("Компоненти", style = MaterialTheme.typography.titleMedium)
                if (comps.isEmpty()) Text(
                    "Ще нічого. Додай, що підключено до плати. Можна почати з шаблону:",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (comps.isEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // типові піни під кожну плату
                    val relayPin = if (board == "esp01") 0 else 5
                    val buttonPin = when (board) { "esp8266" -> 12; "esp01" -> 2; else -> 4 }
                    val dhtPin = when (board) { "esp8266" -> 14; "esp01" -> 2; "esp32c3" -> 3; else -> 4 }
                    val stripPin = when (board) { "esp8266" -> 13; "esp01" -> 2; "esp32" -> 18; else -> 7 }
                    AssistChip(onClick = { comps.add(EspCompConfig(EspType.RELAY, "Реле", pin = relayPin, inv = board == "esp01")) }, label = { Text("Реле") })
                    AssistChip(onClick = {
                        comps.add(EspCompConfig(EspType.RELAY, "Реле", pin = relayPin, inv = board == "esp01"))
                        comps.add(EspCompConfig(EspType.BUTTON, "Кнопка", pin = buttonPin, target = comps.lastIndex))
                    }, label = { Text("Реле + кнопка") })
                    AssistChip(onClick = { comps.add(EspCompConfig(EspType.DHT, "Температура", pin = dhtPin)) }, label = { Text("DHT22") })
                    AssistChip(onClick = { comps.add(EspCompConfig(EspType.WS2812, "Стрічка", pin = stripPin, count = 30)) }, label = { Text("Стрічка WS2812") })
                    AssistChip(onClick = { comps.add(EspCompConfig(EspType.PWM, "LED на платі", pin = if (board == "esp32c3") 8 else 2, inv = board == "esp32c3" || board.startsWith("esp8266"))) }, label = { Text("LED на платі") })
                    AssistChip(onClick = { comps.add(EspCompConfig(EspType.BME, "Клімат", sda = if (board.startsWith("esp8266")) 4 else 6, scl = if (board.startsWith("esp8266")) 5 else 7)) }, label = { Text("BME280") })
                    if (board == "esp01") AssistChip(onClick = {
                        comps.add(EspCompConfig(EspType.RELAY, "Реле", pin = 0, inv = true))
                    }, label = { Text("ESP-01S реле") })
                }
                comps.forEachIndexed { i, c ->
                    val conflict = c.pins.any { (usedPins[it] ?: 0) > 1 }
                    ListItem(
                        headlineContent = { Text("$i. ${c.name}") },
                        supportingContent = {
                            Text(
                                c.type.label + " · " + when (c.type) {
                                    EspType.AHT, EspType.BME -> "SDA ${c.sda}, SCL ${c.scl}"
                                    EspType.THERMO -> "датчик №${c.sensor} → реле №${c.target}"
                                    else -> EspCompConfig.pinLabel(c.pin, board)
                                } +
                                    (if (c.type == EspType.BUTTON) " → №${c.target}" else "") +
                                    (if (conflict) " · ⚠ пін зайнятий" else ""),
                                color = if (conflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                comps.removeAt(i)
                                // посилання на інші компоненти — за індексом, тож зсуваємо їх
                                fun fix(x: Int) = when { x == i -> -1; x > i -> x - 1; else -> x }
                                for (k in comps.indices) {
                                    val o = comps[k]
                                    if (o.type == EspType.BUTTON || o.type == EspType.THERMO) comps[k] = o.copy(target = fix(o.target), sensor = fix(o.sensor))
                                }
                            }) { Icon(Icons.Filled.Delete, "Прибрати") }
                        },
                        modifier = Modifier.clickable { editIndex = i },
                    )
                }
                Box {
                    OutlinedButton(onClick = { addMenu = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Додати компонент") }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        EspType.addable.forEach { t ->
                            DropdownMenuItem(
                                text = { Column { Text(t.label); Text(t.hint, style = MaterialTheme.typography.bodySmall) } },
                                onClick = {
                                    addMenu = false
                                    val free = EspCompConfig.pinsFor(board).firstOrNull { it !in usedPins && EspCompConfig.pinHint(it, board)?.contains("strap") != true } ?: 0
                                    comps.add(
                                        if (t == EspType.THERMO) EspCompConfig(
                                            t,
                                            sensor = comps.indexOfFirst { it.type in EspType.tempSensors },
                                            target = comps.indexOfFirst { it.type == EspType.RELAY },
                                        ) else EspCompConfig(t, pin = free, max = EspCompConfig.adcMax(board))
                                    )
                                    editIndex = comps.lastIndex
                                },
                            )
                        }
                    }
                }
                Text(
                    when (board) {
                        "esp8266" -> "Wemos D1 mini: найбезпечніші D1, D2, D5, D6, D7. D3/D4/D8 впливають на завантаження, D4 — світлодіод. A0 — аналог до 1 В (на D1 mini — до 3.3 В через дільник). I2C: D2 = SDA, D1 = SCL."
                        "esp01" -> "ESP-01: доступні GPIO0, GPIO2 і (якщо не потрібен UART) RX=3, TX=1. GPIO0 і GPIO2 мають бути HIGH при старті. На ESP-01S Relay реле на GPIO0, вмикається низьким рівнем (якщо навпаки — зніми «Інвертувати»)."
                        "esp32" -> "ESP32: 34–39 лише входи. 0, 2, 12, 15 впливають на завантаження. Аналогові — 32–39."
                        "esp32s2", "esp32s3" -> "ESP32-S2/S3: аналогові 1–10. 0 — BOOT, 19/20 зайняті USB."
                        else -> "ESP32-C3: піни 0–10, 20, 21. Аналогові — лише 0–4. 9 — кнопка BOOT, 8 — світлодіод на більшості плат, 2/8/9 впливають на завантаження."
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            Text("Плата", style = MaterialTheme.typography.titleMedium)
            var mqttInfo by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(key) { runCatching { mqttInfo = client.info().mqtt } }
            Text(
                "ID: ${original.deviceId}\nIP: ${original.ip}" + (mqttInfo?.let { "\nMQTT: " + when {
                    it == "off" -> "вимкнено"
                    it == "connected" -> "підключено"
                    it.startsWith("error") -> "помилка ($it) — перевір логін/пароль"
                    else -> it
                } } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ключ: " + if (showKey) original.apiKey else "••••••••••••••••",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Сховати" else "Показати") }
            }
            Text(
                "Ключ потрібен для веб-сторінки плати (http://${original.ip}/) і другого телефону. Скинути плату: утримуй BOOT 5 секунд.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FirmwareSection(original)
            OutlinedButton(onClick = {
                scope.launch { runCatching { client.reboot() }.onFailure { snackbar.showSnackbar(it.message ?: "Помилка") } }
            }) { Text("Перезавантажити плату") }
            Spacer(Modifier.height(32.dp))
        }
    }

    editIndex?.let { i ->
        if (i in comps.indices) {
            EspCompDialog(
                initial = comps[i], index = i, all = comps.toList(), board = board,
                onDismiss = { editIndex = null },
                onSave = { comps[i] = it; editIndex = null },
            )
        } else {
            editIndex = null
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Видалити «${original.name}» з додатку?") },
            text = { Text("Налаштування на самій платі залишаться. Щоб прив'язати її знову, потрібен ключ або скидання кнопкою BOOT.") },
            confirmButton = {
                TextButton(onClick = {
                    app.controller.release(key); app.storage.removeDevice(key); confirmDelete = false; nav.popTo(Screen.Home)
                }) { Text("Видалити") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Скасувати") } },
        )
    }
}

@Composable
private fun EspCompDialog(initial: EspCompConfig, index: Int, all: List<EspCompConfig>, board: String, onDismiss: () -> Unit, onSave: (EspCompConfig) -> Unit) {
    var c by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.type.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(c.name, { c = c.copy(name = it) }, label = { Text("Назва") }, singleLine = true)
                if (c.type == EspType.AHT || c.type == EspType.BME) {
                    PinPicker("SDA", c.sda, board) { c = c.copy(sda = it) }
                    PinPicker("SCL", c.scl, board) { c = c.copy(scl = it) }
                    Text("Усі I2C-датчики на платі ділять одну шину — вкажи однакові SDA/SCL.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (c.type != EspType.THERMO) {
                    PinPicker("Пін", c.pin, board, analogOnly = c.type == EspType.ANALOG) { c = c.copy(pin = it) }
                }
                when (c.type) {
                    EspType.RELAY -> {
                        CheckRow("Інвертувати (реле вмикається низьким рівнем)", c.inv) { c = c.copy(inv = it) }
                        Text("Після подачі живлення", style = MaterialTheme.typography.labelLarge)
                        val opts = listOf("off" to "Вимк.", "on" to "Увімк.", "memory" to "Як було")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            opts.forEachIndexed { k, (v, l) ->
                                SegmentedButton(selected = c.powerOn == v, onClick = { c = c.copy(powerOn = v) },
                                    shape = SegmentedButtonDefaults.itemShape(k, opts.size)) { Text(l) }
                            }
                        }
                    }
                    EspType.BUTTON -> {
                        Text("Що перемикає", style = MaterialTheme.typography.labelLarge)
                        all.forEachIndexed { k, o ->
                            if (k != index && (o.type == EspType.RELAY || o.type == EspType.PWM || o.type == EspType.WS2812)) {
                                Row(Modifier.fillMaxWidth().clickable { c = c.copy(target = k) }, verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.RadioButton(selected = c.target == k, onClick = { c = c.copy(target = k) })
                                    Text("$k. ${o.name}")
                                }
                            }
                        }
                        CheckRow("Підтяжка до 3.3 В (кнопка замикає на GND)", c.pull) { c = c.copy(pull = it) }
                    }
                    EspType.PWM -> CheckRow("Інвертувати (світлодіод на платі)", c.inv) { c = c.copy(inv = it) }
                    EspType.WS2812 -> NumField("Кількість світлодіодів", c.count) { c = c.copy(count = it.coerceIn(1, 600)) }
                    EspType.DHT -> {
                        val m = listOf(11, 22)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            m.forEachIndexed { k, v ->
                                SegmentedButton(selected = c.model == v, onClick = { c = c.copy(model = v) },
                                    shape = SegmentedButtonDefaults.itemShape(k, m.size)) { Text("DHT$v") }
                            }
                        }
                    }
                    EspType.ANALOG -> {
                        NumField("0 % при значенні", c.min) { c = c.copy(min = it) }
                        NumField("100 % при значенні (макс. ${EspCompConfig.adcMax(board)})", c.max) { c = c.copy(max = it) }
                        CheckRow("Інвертувати (напр. ґрунт: сухо = високе значення)", c.inv) { c = c.copy(inv = it) }
                    }
                    EspType.INPUT -> {
                        CheckRow("Підтяжка до 3.3 В", c.pull) { c = c.copy(pull = it) }
                        CheckRow("Інвертувати", c.inv) { c = c.copy(inv = it) }
                    }
                    EspType.THERMO -> {
                        Text("Датчик температури", style = MaterialTheme.typography.labelLarge)
                        val sensors = all.withIndex().filter { it.value.type in EspType.tempSensors }
                        if (sensors.isEmpty()) Text("Спершу додай датчик DHT, DS18B20 або AHT", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        sensors.forEach { (k, o) ->
                            Row(Modifier.fillMaxWidth().clickable { c = c.copy(sensor = k) }, verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.RadioButton(selected = c.sensor == k, onClick = { c = c.copy(sensor = k) })
                                Text("$k. ${o.name}")
                            }
                        }
                        Text("Реле", style = MaterialTheme.typography.labelLarge)
                        val relays = all.withIndex().filter { it.value.type == EspType.RELAY }
                        if (relays.isEmpty()) Text("Спершу додай реле", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        relays.forEach { (k, o) ->
                            Row(Modifier.fillMaxWidth().clickable { c = c.copy(target = k) }, verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.RadioButton(selected = c.target == k, onClick = { c = c.copy(target = k) })
                                Text("$k. ${o.name}")
                            }
                        }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf(true to "Обігрів", false to "Охолодження").forEachIndexed { k, (h, l) ->
                                SegmentedButton(selected = c.heat == h, onClick = {
                                    c = if (c.heat == h) c else c.copy(heat = h, onAt = c.offAt, offAt = c.onAt)
                                }, shape = SegmentedButtonDefaults.itemShape(k, 2)) { Text(l) }
                            }
                        }
                        TempStepper(if (c.heat) "Увімкнути, коли ≤" else "Увімкнути, коли ≥", c.onAt) { c = c.copy(onAt = it) }
                        TempStepper(if (c.heat) "Вимкнути, коли ≥" else "Вимкнути, коли ≤", c.offAt) { c = c.copy(offAt = it) }
                        if (!c.thermoValid) Text(
                            "Вибери датчик і реле; поріг вимкнення має бути " + if (c.heat) "вищим за поріг увімкнення" else "нижчим за поріг увімкнення",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Плата перемикає реле не частіше ніж раз на 30 с. Якщо датчик не відповідає хвилину, обігрів вимикається.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {}
                }
            }
        },
        confirmButton = { TextButton(enabled = c.type != EspType.THERMO || c.thermoValid, onClick = { onSave(c) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

@Composable
private fun PinPicker(label: String, value: Int, board: String, analogOnly: Boolean = false, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${EspCompConfig.pinLabel(value, board)}" + (EspCompConfig.pinHint(value, board)?.let { " ($it)" } ?: ""), modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (if (analogOnly) EspCompConfig.analogPins(board) else EspCompConfig.pinsFor(board)).forEach { p ->
                DropdownMenuItem(
                    text = { Text(EspCompConfig.pinLabel(p, board) + (EspCompConfig.pinHint(p, board)?.let { " · $it" } ?: "")) },
                    onClick = { onPick(p); open = false },
                )
            }
        }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NumField(label: String, value: Int, onChange: (Int) -> Unit) {
    var t by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        t, { s -> t = s.filter { it.isDigit() }.take(4); t.toIntOrNull()?.let(onChange) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}


// ======================= оновлення прошивки =======================

/** Повідомлення на екрані плати, якщо в релізі є новіша прошивка. */
@Composable
private fun UpdateBanner(cfg: DeviceConfig, online: Boolean, onOpen: () -> Unit) {
    var newer by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(cfg.key, online) {
        if (!online) return@LaunchedEffect
        runCatching {
            val cur = EspClient(cfg.ip, cfg.apiKey).info().fw
            val latest = ua.madeisy.iot.esp.EspUpdater.latestVersion()
            if (ua.madeisy.iot.esp.EspUpdater.isNewer(latest, cur)) newer = cur to latest
        }
    }
    newer?.let { (cur, latest) ->
        androidx.compose.material3.Card(
            onClick = onOpen,
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Доступна нова прошивка $latest", style = MaterialTheme.typography.titleSmall)
                Text("На платі $cur. Натисни, щоб оновити по Wi-Fi.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FirmwareSection(cfg: DeviceConfig) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<String?>(null) }
    var board by remember { mutableStateOf("") }
    var latest by remember { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val updater = ua.madeisy.iot.esp.EspUpdater

    LaunchedEffect(cfg.key) {
        runCatching { EspClient(cfg.ip, cfg.apiKey).info().let { current = it.fw; board = it.board } }
        runCatching { latest = updater.latestVersion(force = true) }.onFailure { error = it.message }
    }

    fun run() {
        error = null
        scope.launch {
            try {
                stage = "Завантаження"; progress = 0f
                val file = updater.download(ctx, updater.otaAsset(board)) { progress = it }
                stage = "Запис на плату"; progress = 0f
                updater.upload(cfg.ip, cfg.apiKey, file) { progress = it }
                stage = "Перезавантаження"; progress = 0f
                // чекаємо, поки плата повернеться з новою версією
                val client = EspClient(cfg.ip, cfg.apiKey)
                var ok = false
                repeat(30) {
                    if (ok) return@repeat
                    delay(2000)
                    runCatching { client.info() }.getOrNull()?.let { info ->
                        current = info.fw
                        if (latest == null || !updater.isNewer(latest!!, info.fw)) ok = true
                    }
                }
                stage = null
                if (!ok) error = "Плата не повернулась з новою версією — перевір її живлення і Wi-Fi"
                IotApp.instance.controller.restart(cfg.key)
            } catch (e: Exception) {
                stage = null
                error = e.message ?: "Помилка оновлення"
            }
        }
    }

    Text("Прошивка", style = MaterialTheme.typography.titleMedium)
    Text(
        "На платі: ${current ?: "…"} · Остання: ${latest ?: "…"}",
        style = MaterialTheme.typography.bodySmall,
    )
    stage?.let {
        Text("$it… ${(progress * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
        androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    val canUpdate = latest != null && current != null && updater.isNewer(latest!!, current!!)
    Button(
        enabled = stage == null && latest != null && current != null,
        onClick = { confirm = true },
    ) { Text(if (canUpdate) "Оновити до $latest" else "Перепрошити ще раз") }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Оновити прошивку?") },
            text = { Text("Займе близько хвилини. Не вимикай плату під час запису. Налаштування й ключ збережуться.") },
            confirmButton = { TextButton(onClick = { confirm = false; run() }) { Text("Оновити") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Скасувати") } },
        )
    }
}
