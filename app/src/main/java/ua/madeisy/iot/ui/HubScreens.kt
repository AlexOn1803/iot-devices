package ua.madeisy.iot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.esp.EspDiscovery
import ua.madeisy.iot.esp.EspFound
import ua.madeisy.iot.hub.HubVia

/** Хаб Raspberry Pi: підключення і стан. */
@Composable
fun HubCard(snackbar: SnackbarHostState) {
    val hub = IotApp.instance.controller.hub
    val s by hub.settings.collectAsState()
    val st by hub.status.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<EspFound>?>(null) }
    var manual by remember { mutableStateOf("") }

    fun pair(host: String) {
        busy = true
        scope.launch {
            val r = hub.pair(host.trim())
            busy = false
            r.onSuccess {
                hub.save(it)
                snackbar.showSnackbar("Хаб «${it.name.ifBlank { host }}» підключено")
            }.onFailure { snackbar.showSnackbar(it.message ?: "Не вдалося підключитися") }
        }
    }

    SectionCard("Хаб Raspberry Pi") {
        if (s.configured) {
            Text(
                when {
                    st.connected && st.via == HubVia.LAN -> "Підключено напряму (вдома) · ${s.name}"
                    st.connected -> "Підключено через інтернет · ${s.name}"
                    st.connecting -> "Підключення…"
                    else -> st.error ?: "Хаб недоступний"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (st.connected) OnGreen else MaterialTheme.colorScheme.error,
            )
            Text(
                "Pi тримає зв'язок з усіма пристроями й одразу надсилає зміни. Удома — ${s.lanHost.ifBlank { "—" }}, " +
                    "поза домом — ${if (s.relay.isNotBlank()) "через Cloudflare" else "не налаштовано"}. " +
                    "Коли Pi недоступний, додаток працює як раніше — напряму й через хмари.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { hub.reconnectNow() }) { Text("Перепідключити") }
                // хаб перевстановили/скинули — новий токен: взяти його заново (вдома, у вікно підключення)
                OutlinedButton(enabled = !busy && s.lanHost.isNotBlank(), onClick = { pair(s.lanHost) }) { Text("Підключити заново") }
                TextButton(onClick = { hub.forget() }) { Text("Від'єднати") }
            }
            HubUpdateSection(snackbar)
        } else {
            Text(
                "Raspberry Pi як домашній сервер: миттєвий стан розеток, плат і інвертора вдома й поза домом. " +
                    "Встанови хаб на Pi однією командою (по SSH):",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainerText("curl -fsSL https://raw.githubusercontent.com/AlexOn1803/iot-devices/dev/hub/install.sh | sudo bash")
            Text(
                "Потім, протягом 15 хвилин і в домашньому Wi-Fi, натисни «Знайти хаб».",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                scope.launch {
                    found = runCatching { EspDiscovery.scan(ctx, 4000, "_iothub._tcp.") }.getOrDefault(emptyList())
                    busy = false
                    if (found!!.size == 1) pair(found!![0].host)
                }
            }) { Text(if (busy) "Шукаю…" else "Знайти хаб") }
            found?.let { list ->
                if (list.isEmpty()) Text(
                    "Не знайдено. Введи IP Pi (команда install.sh показує його в кінці):",
                    style = MaterialTheme.typography.bodySmall,
                )
                list.forEach { f ->
                    ListItem(
                        headlineContent = { Text(f.name) }, supportingContent = { Text(f.host) },
                        modifier = Modifier.clickable { pair(f.host) },
                    )
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    manual, { manual = it.trim() }, label = { Text("IP Raspberry Pi") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f),
                )
                TextButton(enabled = !busy && manual.isNotBlank(), onClick = { pair(manual) }) { Text("OK") }
            }
        }
    }
}

@Composable
private fun SelectionContainerText(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), modifier = Modifier.padding(8.dp))
        }
    }
}


/** Оновлення хаба на Raspberry Pi: перевірити нову версію на GitHub і встановити (як у веб-панелі). */
@Composable
private fun HubUpdateSection(snackbar: SnackbarHostState) {
    val hub = IotApp.instance.controller.hub
    val status by hub.status.collectAsState()
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<String?>(null) }
    var polling by remember { mutableStateOf(false) }

    // поки йде оновлення — читаємо журнал; хаб перезапускається, тож помилки зв'язку пропускаємо
    LaunchedEffect(polling) {
        while (polling) {
            kotlinx.coroutines.delay(3000)
            hub.request(org.json.JSONObject().put("t", "hub_update_log"), 8000).onSuccess { j ->
                log = j.optString("log")
                val m = Regex("Код завершення: (\\d+)").find(log ?: "")
                if (m != null) {
                    polling = false
                    info = null
                    snackbar.showSnackbar(if (m.groupValues[1] == "0") "Хаб оновлено" else "Оновлення завершилось з помилкою — див. журнал")
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Text("Оновлення хаба", style = MaterialTheme.typography.titleSmall)
    Text("Pi сама завантажить нову версію з GitHub і перевстановиться; налаштування зберігаються. " +
        "Зв'язок з хабом пропаде приблизно на хвилину.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedButton(enabled = !busy && !polling && status.connected, onClick = {
            busy = true
            scope.launch {
                hub.request(org.json.JSONObject().put("t", "hub_update_check"), 30_000)
                    .onSuccess { info = it }
                    .onFailure { snackbar.showSnackbar(it.message ?: "Не вдалося перевірити") }
                busy = false
            }
        }) { Text(if (busy && !polling) "Перевіряю…" else "Перевірити") }
        val i = info
        if (i != null && !i.optBoolean("upToDate")) {
            Button(enabled = !busy && !polling, onClick = {
                busy = true
                scope.launch {
                    hub.request(org.json.JSONObject().put("t", "hub_update").put("sha", i.optString("latest")), 15_000)
                        .onSuccess { log = "Оновлення запущено…"; polling = true }
                        .onFailure { snackbar.showSnackbar(it.message ?: "Не вдалося запустити оновлення") }
                    busy = false
                }
            }) { Text("Оновити") }
        }
        TextButton(enabled = status.connected && !polling, onClick = {
            scope.launch {
                hub.request(org.json.JSONObject().put("t", "hub_update_log"), 8000)
                    .onSuccess { log = it.optString("log").ifBlank { "(журнал порожній)" } }
                    .onFailure { snackbar.showSnackbar(it.message ?: "Помилка") }
            }
        }) { Text("Журнал") }
    }
    if (!status.connected) Text("Потрібен зв'язок з хабом", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error)
    info?.let { i ->
        val inst = i.optJSONObject("installed") ?: org.json.JSONObject()
        Spacer(Modifier.height(6.dp))
        Text(
            "Встановлено: ${inst.optString("sha", "?").take(7)} (${inst.optString("branch", "?")})  ·  " +
                "остання: ${i.optString("latest").take(7)}  —  " + if (i.optBoolean("upToDate")) "актуально" else "є оновлення",
            style = MaterialTheme.typography.bodyMedium,
        )
        val ch = i.optJSONArray("changes")
        if (ch != null && ch.length() > 0) {
            Text("Що нового:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            for (k in ch.length() - 1 downTo 0) Text("• " + ch.optString(k), style = MaterialTheme.typography.bodySmall)
        }
    }
    log?.let { l ->
        Spacer(Modifier.height(6.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
            Text(l, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState(Int.MAX_VALUE)).padding(8.dp))
        }
    }
}
