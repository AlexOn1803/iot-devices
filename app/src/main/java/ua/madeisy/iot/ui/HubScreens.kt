package ua.madeisy.iot.ui

import androidx.compose.foundation.clickable
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
