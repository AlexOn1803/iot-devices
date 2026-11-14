@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.madeisy.iot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.inverter.EssDevice
import ua.madeisy.iot.inverter.EssServer
import ua.madeisy.iot.inverter.EssSummary
import ua.madeisy.iot.inverter.InverterState
import ua.madeisy.iot.inverter.SmartEssClient
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun w(v: Double?): String = when {
    v == null -> "—"
    kotlin.math.abs(v) >= 1000 -> String.format(Locale.US, "%.2f кВт", v / 1000)
    else -> "${v.toInt()} Вт"
}

private fun pct(v: Double?) = v?.let { "${it.toInt()} %" } ?: "—"

/** Опитує сервер SmartESS, поки composable на екрані. */
@Composable
fun KeepInverters() {
    DisposableEffect(Unit) {
        val r = IotApp.instance.inverters
        r.acquire()
        onDispose { r.release() }
    }
}

// ======================= налаштування =======================

@Composable
fun SmartEssCard(snackbar: SnackbarHostState) {
    val repo = IotApp.instance.inverters
    val acc by repo.account.collectAsState()
    val devices by repo.devices.collectAsState()
    val scope = rememberCoroutineScope()
    var user by remember(acc.user) { mutableStateOf(acc.user) }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var server by remember(acc.server) { mutableStateOf(acc.server) }
    var company by remember(acc.companyKey) { mutableStateOf(acc.companyKey) }
    var advanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    SectionCard("Інвертор (SmartESS)") { if (acc.configured) {
            Text(
                "Під'єднано: ${acc.user} · ${acc.server.label}\nІнверторів: ${devices.size}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val r = runCatching { repo.rediscover() }
                        busy = false
                        snackbar.showSnackbar(r.fold({ "Знайдено інверторів: $it" }, { it.message ?: "Помилка" }))
                    }
                }) { Text("Шукати ще") }
                TextButton(onClick = { repo.disconnect() }) { Text("Від'єднати") }
            }
        } else {
        Text(
            "Логін і пароль від застосунку SmartESS. Додаток бачитиме дані інвертора (сонце, батарея, навантаження, мережа) " +
                "через ту саму хмару, що й SmartESS. Пароль не зберігається — лише його відбиток для підпису запитів.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(user, { user = it.trim() }, label = { Text("Логін SmartESS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    Icon(if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Показати пароль")
                }
            },
        )
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Сховати додаткові" else "Додатково (сервер, ключ компанії)") }
        if (advanced) {
            EssServer.entries.forEach { s ->
                Row(Modifier.fillMaxWidth().clickable { server = s }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = server == s, onClick = { server = s })
                    Text(s.label)
                }
            }
            OutlinedTextField(company, { company = it.trim() }, label = { Text("Company key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(
                "Змінюй, лише якщо інсталятор дав інший ключ. Типовий: ${SmartEssClient.DEFAULT_COMPANY_KEY}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !busy && user.isNotBlank() && pass.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                scope.launch {
                    val r = runCatching { repo.connect(server, user, pass, company) }
                    busy = false
                    r.onSuccess {
                        pass = ""
                        snackbar.showSnackbar(if (it == 0) "Вхід успішний, але інверторів не знайдено" else "Знайдено інверторів: $it")
                    }.onFailure { snackbar.showSnackbar(it.message ?: "Помилка входу") }
                }
            },
        ) { Text(if (busy) "Підключення…" else "Підключити") }
    } }
}

// ======================= головний екран =======================

@Composable
fun InverterCard(d: EssDevice, s: InverterState, onClick: () -> Unit) {
    val sum = s.summary
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(d.alias, style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    s.error != null && s.points.isEmpty() -> s.error
                    s.points.isEmpty() -> "Завантаження…"
                    else -> listOfNotNull(
                        "Інвертор",
                        sum.mode?.let { EssSummary.modeUa(it) },
                        sum.gridPresent?.let { if (it) "мережа є" else "мережі немає" },
                    ).joinToString(" · ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (s.error != null && s.points.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.points.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MiniStat("Сонце", w(sum.pvW))
                    MiniStat("Батарея", pct(sum.socPct))
                    MiniStat("Навантаження", w(sum.loadW))
                }
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

// ======================= екран інвертора =======================

@Composable
fun InverterScreen(sn: String, nav: Nav) {
    val repo = IotApp.instance.inverters
    val devices by repo.devices.collectAsState()
    val states by repo.states.collectAsState()
    val d = devices.firstOrNull { it.sn == sn }
    val s = states[sn] ?: InverterState()
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf(false) }
    var pinWidget by remember { mutableStateOf(false) }
    KeepInverters()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(d?.alias ?: "Інвертор") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    if (d != null) {
                        IconButton(onClick = { scope.launch { repo.refresh(d) } }) { Icon(Icons.Filled.Refresh, "Оновити") }
                        IconButton(onClick = { pinWidget = true }) { Icon(Icons.Filled.Widgets, "Віджет на головний екран") }
                        IconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, "Перейменувати") }
                    }
                },
            )
        },
    ) { pad ->
        if (d == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Інвертор не знайдено") }
        } else {
        val sum = s.summary
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s.loading && s.points.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Сонце", w(sum.pvW), Modifier.weight(1f))
                StatTile("Навантаження", w(sum.loadW), Modifier.weight(1f), sub = sum.loadPct?.let { "${it.toInt()} % від макс." })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    "Батарея", pct(sum.socPct), Modifier.weight(1f),
                    sub = listOfNotNull(
                        sum.batteryV?.let { String.format(Locale.US, "%.1f В", it) },
                        sum.batteryW?.let { if (it > 5) "заряд ${w(it)}" else if (it < -5) "розряд ${w(-it)}" else null },
                    ).joinToString(" · ").ifBlank { null },
                )
                StatTile(
                    "Мережа",
                    when (sum.gridPresent) { true -> sum.gridV?.let { "${it.toInt()} В" } ?: "є"; false -> "немає"; null -> "—" },
                    Modifier.weight(1f),
                    sub = sum.gridW?.let { w(it) },
                )
            }
            sum.mode?.let { StatTile("Режим", EssSummary.modeUa(it), Modifier.fillMaxWidth()) }
            if (s.updatedAt > 0) Text(
                "Оновлено о ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.updatedAt))} · " +
                    "інвертор передає дані в хмару приблизно раз на 1–5 хв",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.points.isNotEmpty()) SectionCard("Усі дані") {
                s.points.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(EssSummary.titleUa(p.title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            (if (p.title.contains("state", true) || p.title.contains("mode", true)) EssSummary.modeUa(p.value) else p.value) +
                                (if (p.unit.isNotBlank()) " ${p.unit}" else ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Text(
                "Серійний номер ${d.sn} · збирач даних ${d.pn}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        }
    }
    if (pinWidget) ua.madeisy.iot.widget.PinInverterWidgetDialog(sn) { pinWidget = false }
    if (rename && d != null) {
        var name by remember { mutableStateOf(d.alias) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Назва інвертора") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { repo.rename(d.sn, name); rename = false }) { Text("Зберегти") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Скасувати") } },
        )
    }
}
