@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ua.madeisy.iot.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.data.DeviceConfig
import ua.madeisy.iot.data.DeviceKind
import ua.madeisy.iot.esp.EspClient
import ua.madeisy.iot.esp.EspDiscovery
import ua.madeisy.iot.esp.EspUpdater
import ua.madeisy.iot.flash.Chip
import ua.madeisy.iot.flash.EspLoader
import ua.madeisy.iot.flash.FlashException
import ua.madeisy.iot.flash.UsbFlash
import ua.madeisy.iot.flash.UsbLoaderPort

private enum class Step { PICK, CONNECTING, MANUAL, READY, FLASHING, WIFI, PROVISION, SEARCH, DONE, ERROR, MONITOR }

/** Плати й образи IoT Devices для кожного чипа. */
private fun boardsFor(chip: Chip): List<Pair<String, String>> = when (chip) {
    Chip.ESP8266 -> listOf("esp8266" to "Wemos D1 mini, NodeMCU (4 МБ)", "esp01" to "ESP-01 / ESP-01S (1 МБ)")
    Chip.ESP32 -> listOf("esp32" to "ESP32 (DevKit, D1 R32, D1 mini ESP32)")
    Chip.ESP32S2 -> listOf("esp32s2" to "ESP32-S2 (S2 mini)")
    Chip.ESP32S3 -> listOf("esp32s3" to "ESP32-S3")
    Chip.ESP32C3 -> listOf("esp32c3" to "ESP32-C3")
}

@Composable
fun FlashScreen(nav: Nav) {
    val ctx = LocalContext.current
    val app = IotApp.instance
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(Step.PICK) }
    var drivers by remember { mutableStateOf<List<UsbSerialDriver>>(emptyList()) }
    var driver by remember { mutableStateOf<UsbSerialDriver?>(null) }
    var port by remember { mutableStateOf<UsbLoaderPort?>(null) }
    var loader by remember { mutableStateOf<EspLoader?>(null) }
    var chip by remember { mutableStateOf<Chip?>(null) }
    var autoReset by remember { mutableStateOf(true) }
    var board by remember { mutableStateOf("") }
    var customUri by remember { mutableStateOf<Uri?>(null) }
    var customOffset by remember { mutableStateOf("0x0") }
    var stage by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    val logLines = remember { mutableStateListOf<String>() }
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var flashedIot by remember { mutableStateOf(false) }
    var failedAt by remember { mutableStateOf(Step.PICK) }
    var monitorJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val monitorLines = remember { mutableStateListOf<String>() }
    var monitorBaud by remember { mutableStateOf(115200) }
    // налаштування, які записуються разом із прошивкою
    var setupName by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    val setupComps = remember { mutableStateListOf<ua.madeisy.iot.esp.EspCompConfig>() }
    var setupLed by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var setupFor by remember { mutableStateOf("") }
    var pendingKey by remember { mutableStateOf<String?>(null) }
    var mqttIncluded by remember { mutableStateOf("") }
    if (board.isNotEmpty() && setupFor != board) {
        setupFor = board
        setupLed = ua.madeisy.iot.esp.EspCompConfig.defaultLed(board)
    }

    fun log(s: String) { scope.launch(Dispatchers.Main) { logLines += s; if (logLines.size > 60) logLines.removeAt(0) } }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) customUri = uri }

    fun refresh() { drivers = UsbFlash.drivers(ctx) }
    LaunchedEffect(Unit) { while (step == Step.PICK) { refresh(); delay(1500) } }
    DisposableEffect(Unit) { onDispose { monitorJob?.cancel(); port?.close() } }

    fun fail(e: Throwable) {
        failedAt = step
        error = e.message ?: e.toString()
        log("Помилка: $error")
        step = Step.ERROR
    }

    /** Підключення до завантажувача: спершу автоматично (DTR/RTS), потім — вручну. */
    fun connect(d: UsbSerialDriver, manual: Boolean) {
        monitorJob?.cancel(); monitorJob = null
        driver = d
        error = null
        step = if (manual) Step.MANUAL else Step.CONNECTING
        scope.launch {
            try {
                if (!UsbFlash.requestPermission(ctx, d.device)) throw FlashException("Немає дозволу на USB-пристрій")
                val ok = withContext(Dispatchers.IO) {
                    port?.close()
                    val p = UsbFlash.open(ctx, d)
                    port = p
                    val l = EspLoader(p) { log(it) }
                    loader = l
                    if (manual) {
                        // користувач тримає GPIO0 на GND і перезавантажує плату — пробуємо ~20 с
                        var got = false
                        val until = System.currentTimeMillis() + 20_000
                        while (!got && System.currentTimeMillis() < until && step == Step.MANUAL) got = l.connect(false, false, 1)
                        got
                    } else {
                        log("Перезавантажую плату в режим прошивки…")
                        l.connect(true, UsbFlash.isUsbJtag(d))
                    }
                }
                if (!ok) {
                    if (manual) throw FlashException("Плата не відповідає. Перевір підключення TX↔RX і що GPIO0 на GND під час перезавантаження.")
                    autoReset = false
                    log("Автоматично не вийшло — потрібен ручний режим")
                    step = Step.MANUAL
                    connect(d, true)
                    return@launch
                }
                autoReset = !manual
                val c = withContext(Dispatchers.IO) { loader!!.detectChip() }
                chip = c
                board = boardsFor(c).first().first
                log("Знайдено чип ${c.label}")
                step = Step.READY
            } catch (e: Exception) { fail(e) }
        }
    }

    /** Після прошивки з Wi-Fi: шукаємо плату в мережі за ключем, який записали в налаштування. */
    fun findFlashed() {
        val key = pendingKey ?: return
        step = Step.SEARCH
        error = null
        scope.launch {
            try {
                port?.close(); port = null   // звільняємо USB — плата живе своїм життям
                stage = "Плата підключається до Wi-Fi"
                val deadline = System.currentTimeMillis() + 90_000
                var found: Pair<String, ua.madeisy.iot.esp.EspInfo>? = null
                val known = app.storage.devices.value.filter { it.isEsp }.associateBy { it.deviceId }
                while (found == null && System.currentTimeMillis() < deadline) {
                    progress = 1f - (deadline - System.currentTimeMillis()) / 90_000f
                    val list = runCatching { EspDiscovery.scan(ctx, 4000) }.getOrDefault(emptyList())
                    for (f in list) {
                        // «наша» плата — та, що приймає щойно згенерований ключ
                        if (known[f.id]?.apiKey == key) { found = f.host to EspClient(f.host, key).info(); break }
                        val ok = runCatching { EspClient(f.host, key).state() }.isSuccess
                        if (ok) { found = f.host to EspClient(f.host, key).info(); break }
                    }
                }
                val (host, info) = found ?: throw FlashException(
                    "Плата не з'явилась у мережі за 1,5 хв. Подивись на світлодіод: мигає — не може підключитися (пароль? мережа 2.4 ГГц?), світить постійно — роздає IoT-Setup, тоді підключись до неї й введи Wi-Fi там."
                )
                log("Знайдено ${info.id} на $host")
                val old = app.storage.devices.value.firstOrNull { it.isEsp && it.deviceId == info.id }
                val cfg = (old ?: DeviceConfig(name = info.name.ifBlank { chip?.label ?: "ESP" }, kind = DeviceKind.ESP))
                    .copy(deviceId = info.id, ip = host, apiKey = key, kind = DeviceKind.ESP, mqttApplied = mqttIncluded,
                        name = setupName.trim().ifEmpty { old?.name ?: info.name.ifBlank { "ESP" } })
                app.storage.upsertDevice(cfg)
                nav.replace(Screen.Device(cfg.key))
            } catch (e: Exception) { fail(e) }
        }
    }

    fun flash() {
        val l = loader ?: return
        val c = chip ?: return
        val d = driver ?: return
        step = Step.FLASHING
        error = null
        scope.launch {
            try {
                val image: ByteArray
                val offset: Int
                if (customUri != null) {
                    stage = "Читаю файл"
                    image = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(customUri!!)!!.use { it.readBytes() } }
                    offset = customOffset.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0
                    flashedIot = false
                } else {
                    stage = "Завантажую прошивку"; progress = 0f
                    val f = EspUpdater.download(ctx, "iot-$board-full.bin") { progress = it }
                    image = withContext(Dispatchers.IO) { f.readBytes() }
                    offset = 0
                    flashedIot = true
                }
                log("Образ ${image.size / 1024} КБ, адреса 0x%X".format(offset))
                withContext(Dispatchers.IO) {
                    // ESP32-сімейство через міст USB-UART можна прискорити
                    if (c != Chip.ESP8266 && !UsbFlash.isUsbJtag(d)) {
                        runCatching { l.changeBaud(460800); log("Швидкість 460800") }.onFailure {
                            port?.setBaud(115200); l.sync()
                        }
                    }
                    l.prepareFlash()
                    stage = "Запис"; progress = 0f
                    l.flash(image, offset) { progress = it }
                    log("Записано успішно")
                    if (flashedIot) {
                        // блок налаштувань в окремий сектор: плата застосує його на першому старті
                        val key = EspClient.newKey()
                        val j = org.json.JSONObject().put("key", key)
                        if (setupName.isNotBlank()) j.put("name", setupName.trim())
                        j.put("tz", runCatching { ua.madeisy.iot.esp.PosixTz.current() }.getOrDefault("UTC0"))
                        if (ssid.isNotBlank()) j.put("ssid", ssid.trim()).put("pass", pass)
                        if (setupComps.isNotEmpty()) j.put("comps", org.json.JSONArray(setupComps.map { it.toJson() }))
                        setupLed?.let { j.put("led", it) }
                        val mq = app.storage.mqtt.value
                        if (mq.configured) j.put("mqtt", org.json.JSONObject().put("host", mq.host).put("port", mq.port).put("user", mq.user).put("pass", mq.pass))
                        val text = ("IOTCFG1\n" + j.toString()).toByteArray(Charsets.UTF_8) + byteArrayOf(0)
                        if (text.size > 4000) throw FlashException("Забагато налаштувань — прибери кілька компонентів")
                        val blob = text + ByteArray(4096 - text.size) { 0xFF.toByte() }
                        stage = "Запис налаштувань"; progress = 0f
                        l.flash(blob, ua.madeisy.iot.esp.EspCompConfig.settingsAddress(board)) { progress = it }
                        pendingKey = key
                        mqttIncluded = if (mq.configured) mq.signature else ""
                        log("Налаштування записано")
                    }
                    if (autoReset) l.hardReset() else l.runApp()
                }
                if (!flashedIot) { step = Step.DONE; return@launch }
                if (ssid.isNotBlank()) findFlashed() else step = Step.WIFI
            } catch (e: Exception) { fail(e) }
        }
    }

    fun stopMonitor() { monitorJob?.cancel(); monitorJob = null }

    /** Монітор порту: показує, що плата пише при старті (115200 — прошивка, 74880 — завантажувач ESP8266). */
    fun startMonitor(d: UsbSerialDriver, baud: Int = monitorBaud) {
        stopMonitor()
        driver = d
        monitorBaud = baud
        step = Step.MONITOR
        monitorJob = scope.launch(Dispatchers.IO) {
            try {
                if (!UsbFlash.requestPermission(ctx, d.device)) throw FlashException("Немає дозволу на USB-пристрій")
                var p = port ?: UsbFlash.open(ctx, d, baud).also { port = it }
                p.setBaud(baud)
                p.setDtr(false); p.setRts(false)   // відпустити EN і GPIO0, якщо адаптер їх тримав
                val sb = StringBuilder()
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val b = try { p.read(200) } catch (e: FlashException) {
                        // плата з вбудованим USB після скидання перепідключається
                        if (!UsbFlash.isUsbJtag(d)) throw e
                        p.close()
                        withContext(Dispatchers.Main) { monitorLines += "— USB перепідключається… —" }
                        p = UsbFlash.reopen(ctx, d.device.vendorId, baud) ?: throw e
                        port = p
                        continue
                    }
                    if (b.isEmpty()) continue
                    sb.append(String(b, Charsets.UTF_8))
                    val lines = mutableListOf<String>()
                    while (true) {
                        val nl = sb.indexOf("\n"); if (nl < 0) break
                        lines += sb.substring(0, nl).trimEnd('\r'); sb.delete(0, nl + 1)
                    }
                    if (sb.length > 300) { lines += sb.toString(); sb.clear() }
                    if (lines.isNotEmpty()) withContext(Dispatchers.Main) {
                        monitorLines += lines
                        while (monitorLines.size > 200) monitorLines.removeAt(0)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { monitorLines += "— ${e.message} —" }
            }
        }
    }

    fun provision() {
        val d = driver ?: return
        stopMonitor()
        step = Step.PROVISION
        scope.launch {
            try {
                stage = "Передаю Wi-Fi платі"
                val p = withContext(Dispatchers.IO) {
                    if (UsbFlash.isUsbJtag(d)) {
                        // плата з вбудованим USB перепідключилась після скидання
                        port?.close()
                        delay(2500)
                        UsbFlash.reopen(ctx, d.device.vendorId)
                    } else {
                        port?.setBaud(115200); port
                    }
                } ?: throw FlashException("Не вдалося знову відкрити USB після перезавантаження плати")
                port = p
                val id = UsbFlash.provisionWifi(p, ssid.trim(), pass) { log(it) }
                    ?: throw FlashException("Плата не відповіла по USB. Можна налаштувати Wi-Fi через точку доступу IoT-Setup-xxxx.")
                log("Wi-Fi збережено, плата перезапускається")
                stage = "Шукаю плату в мережі"
                var found: ua.madeisy.iot.esp.EspFound? = null
                repeat(8) {
                    if (found != null) return@repeat
                    delay(3000)
                    found = runCatching { EspDiscovery.scan(ctx, 3000) }.getOrNull()?.firstOrNull { it.id == id }
                }
                val f = found ?: throw FlashException("Плата $id не з'явилась у мережі. Перевір пароль Wi-Fi (і що мережа 2.4 ГГц).")
                val info = EspClient(f.host, pendingKey ?: "").info()
                val old = app.storage.devices.value.firstOrNull { it.isEsp && it.deviceId == id }
                // ключ уже записано разом із прошивкою; якщо ні — прив'язуємо зараз
                val key = pendingKey ?: EspClient.newKey().also { k -> if (!info.claimed) EspClient(f.host, "").claim(k, info.name) }
                val cfg = (old ?: DeviceConfig(name = info.name.ifBlank { chip?.label ?: "ESP" }, kind = DeviceKind.ESP))
                    .copy(deviceId = id, ip = f.host, apiKey = if (pendingKey == null && info.claimed) (old?.apiKey ?: "") else key,
                        kind = DeviceKind.ESP, mqttApplied = mqttIncluded)
                app.storage.upsertDevice(cfg)
                port?.close(); port = null
                nav.replace(Screen.Device(cfg.key))
            } catch (e: Exception) { fail(e) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Прошивка через USB") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                Step.PICK -> {
                    SectionCard("1. Підключи плату") {
                        Text(
                            "Через OTG-перехідник і USB-кабель з даними (не лише для заряджання). Підтримуються ESP8266 (Wemos D1 mini, NodeMCU, ESP-01 через адаптер), ESP32, S2, S3, C3.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (drivers.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Чекаю на USB-пристрій…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    drivers.forEach { d ->
                        ListItem(
                            leadingContent = { Icon(Icons.Filled.Usb, null) },
                            headlineContent = { Text(UsbFlash.describe(d)) },
                            supportingContent = { Text("VID %04X · PID %04X · натисни, щоб прошити".format(d.device.vendorId, d.device.productId)) },
                            trailingContent = { TextButton(onClick = { startMonitor(d) }) { Text("Монітор") } },
                            modifier = Modifier.clickable { connect(d, false) },
                        )
                    }
                    SectionCard("ESP-01 і адаптери лише з TX/RX") {
                        Text(
                            "Перемикач адаптера — 3.3V. TX→RX, RX→TX, GND, 3V3→VCC і EN. Для прошивки GPIO0 з'єднай з GND і подай живлення. Додаток спершу спробує автоматично, потім попросить зробити це вручну.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Step.CONNECTING -> Busy("Підключаюсь до завантажувача…")

                Step.MANUAL -> SectionCard("Переведи плату в режим прошивки") {
                    Text(
                        "1. З'єднай GPIO0 з GND (на платах з кнопками — утримуй BOOT/FLASH).\n" +
                            "2. Перезавантаж плату: від'єднай і знову подай 3.3 В, або на мить замкни RST на GND (кнопка RESET/EN).\n" +
                            "3. Відпусти BOOT. Додаток чекає ~20 секунд.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Слухаю плату…")
                    }
                }

                Step.READY -> {
                    val c = chip!!
                    SectionCard("Знайдено: ${c.label}") {
                        Text("Що записати:", style = MaterialTheme.typography.labelLarge)
                        boardsFor(c).forEach { (b, label) ->
                            Row(Modifier.fillMaxWidth().clickable { board = b; customUri = null }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = customUri == null && board == b, onClick = { board = b; customUri = null })
                                Text("IoT Devices — $label")
                            }
                        }
                        Row(Modifier.fillMaxWidth().clickable { pickFile.launch("*/*") }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = customUri != null, onClick = { pickFile.launch("*/*") })
                            Text(customUri?.lastPathSegment?.let { "Свій файл: $it" } ?: "Свій .bin файл…")
                        }
                        if (customUri != null) {
                            OutlinedTextField(
                                customOffset, { customOffset = it.trim() }, label = { Text("Адреса запису") }, singleLine = true,
                                supportingText = { Text("Повний образ (merged/factory) — 0x0; лише програма ESP32 — 0x10000") },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        if (customUri != null) Button(onClick = { flash() }, modifier = Modifier.fillMaxWidth()) { Text("Прошити") }
                    }
                    if (customUri == null) {
                        SectionCard("Налаштування плати") {
                            Text(
                                "Усе це запишеться разом із прошивкою: плата одразу підключиться до Wi-Fi і з'явиться в додатку вже налаштованою. Можна лишити порожнім і налаштувати потім.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(setupName, { setupName = it }, label = { Text("Назва плати") }, singleLine = true,
                                placeholder = { Text("Напр. Теплиця") }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(ssid, { ssid = it }, label = { Text("Wi-Fi: назва мережі (2.4 ГГц)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                pass, { pass = it }, label = { Text("Wi-Fi: пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                visualTransformation = if (showPass) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = { TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Сховати" else "Показати") } },
                            )
                            if (app.storage.mqtt.collectAsState().value.configured) Text(
                                "Налаштування MQTT (доступ через інтернет) теж запишуться.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            ua.madeisy.iot.ui.ComponentsEditor(setupComps, board)
                            Spacer(Modifier.height(12.dp))
                            setupLed?.let { led -> ua.madeisy.iot.ui.LedEditor(led, board, setupComps) { setupLed = it } }
                        }
                        Button(onClick = { flash() }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (ssid.isNotBlank()) "Прошити й підключити" else "Прошити")
                        }
                    }
                }

                Step.SEARCH -> {
                    Busy("$stage…")
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Кабель можна від'єднати. Світлодіод має мигати, доки плата підключається, і згаснути, коли вона в мережі.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { step = Step.WIFI }) { Text("Не чекати — передати Wi-Fi кабелем") }
                }

                Step.FLASHING, Step.PROVISION -> {
                    Busy("$stage…")
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).toInt()} % · не від'єднуй кабель", style = MaterialTheme.typography.bodySmall)
                }

                Step.WIFI -> SectionCard("Прошито! Тепер Wi-Fi") {
                    if (!autoReset) {
                        Text(
                            "Спершу запусти прошивку: від'єднай GPIO0 від GND (відпусти BOOT) і перезавантаж плату — вимкни й увімкни живлення 3.3 В або замкни RST на GND. Потім натисни «Підключити до Wi-Fi».",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        "Введи домашню мережу (2.4 ГГц) — додаток передасть її платі тим самим кабелем, знайде плату в мережі й прив'яже до себе.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(ssid, { ssid = it }, label = { Text("Назва мережі") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        pass, { pass = it }, label = { Text("Пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(enabled = ssid.isNotBlank(), onClick = { provision() }, modifier = Modifier.fillMaxWidth()) { Text("Підключити до Wi-Fi") }
                    TextButton(onClick = { driver?.let { startMonitor(it) } }) { Text("Монітор порту — подивитись, чи плата стартує") }
                    TextButton(onClick = { port?.close(); port = null; nav.replace(Screen.AddEsp) }) {
                        Text("Пропустити — налаштую через точку доступу IoT-Setup")
                    }
                }

                Step.DONE -> SectionCard("Готово") {
                    Text("Файл записано. Плату перезапущено.", style = MaterialTheme.typography.bodyMedium)
                    if (!autoReset) Text("Від'єднай GPIO0 від GND і перезавантаж плату, якщо вона не запустилась сама.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { port?.close(); port = null; step = Step.PICK; logLines.clear() }) { Text("Прошити ще одну") }
                }

                Step.MONITOR -> SectionCard("Монітор порту · $monitorBaud") {
                    Text(
                        "Перезавантаж плату (RST або живлення) — тут має з'явитись «IoT Devices …» і «IOTDEV {…}». Для ESP8266 повідомлення завантажувача видно на 74880.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(115200, 74880).forEach { b ->
                            androidx.compose.material3.FilterChip(selected = monitorBaud == b, onClick = { driver?.let { startMonitor(it, b) } }, label = { Text("$b") })
                        }
                        androidx.compose.material3.AssistChip(onClick = {
                            scope.launch(Dispatchers.IO) { port?.let { it.setRts(true); delay(150); it.setRts(false) } }
                        }, label = { Text("Скинути (RTS)") })
                        androidx.compose.material3.AssistChip(onClick = {
                            scope.launch(Dispatchers.IO) { runCatching { port?.write("\nINFO\n".toByteArray()) } }
                        }, label = { Text("INFO") })
                        androidx.compose.material3.AssistChip(onClick = { monitorLines.clear() }, label = { Text("Очистити") })
                    }
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                    ) {
                        val st = rememberScrollState()
                        LaunchedEffect(monitorLines.size) { st.scrollTo(st.maxValue) }
                        Column(Modifier.verticalScroll(st).padding(8.dp)) {
                            if (monitorLines.isEmpty()) Text("Поки тихо…", style = MaterialTheme.typography.bodySmall)
                            monitorLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { stopMonitor(); step = Step.WIFI }) { Text("Передати Wi-Fi") }
                        OutlinedButton(onClick = { stopMonitor(); driver?.let { connect(it, false) } }) { Text("Прошити") }
                    }
                    TextButton(onClick = { stopMonitor(); port?.close(); port = null; step = Step.PICK }) { Text("Інший пристрій") }
                }

                Step.ERROR -> SectionCard("Не вдалося") {
                    Text(error ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    if (failedAt == Step.PROVISION) {
                        Text(
                            "Відкрий монітор порту й перезавантаж плату. Якщо там порожньо або «сміття» — прошивка не стартує (GPIO0 досі на GND?). Якщо текст постійно повторюється з «rst cause» / «brownout» — бракує живлення 3.3 В: живи плату від окремого стабілізатора (AMS1117) зі спільним GND.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            when {
                                failedAt == Step.PROVISION -> step = Step.WIFI
                                failedAt == Step.SEARCH -> findFlashed()
                                driver != null -> connect(driver!!, false)
                                else -> step = Step.PICK
                            }
                        }) {
                            Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Ще раз")
                        }
                        if (failedAt != Step.PROVISION) OutlinedButton(onClick = { driver?.let { connect(it, true) } }) { Text("Ручний режим") }
                        OutlinedButton(onClick = { driver?.let { startMonitor(it) } }) { Text("Монітор") }
                    }
                    TextButton(onClick = { port?.close(); port = null; step = Step.PICK }) { Text("Інший пристрій") }
                }
            }

            if (logLines.isNotEmpty()) {
                SectionCard("Журнал") {
                    logLines.takeLast(14).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
