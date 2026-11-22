@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.madeisy.iot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.rules.ActType
import ua.madeisy.iot.rules.Action
import ua.madeisy.iot.rules.Cond
import ua.madeisy.iot.rules.Op
import ua.madeisy.iot.rules.Rule
import ua.madeisy.iot.rules.RuleValues
import ua.madeisy.iot.rules.ValKind

// ======================= список правил =======================

@Composable
fun RulesScreen(nav: Nav) {
    val eng = IotApp.instance.rules
    val rules by eng.rules.collectAsState()
    val status by eng.status.collectAsState()
    val armed by eng.armed.collectAsState()
    var templates by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Правила") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.push(Screen.RuleEdit("")) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Правило") })
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "«Якщо дані пристрою такі — зроби це». Наприклад: інвертор перейшов на батарею → вимкнути бойлер. " +
                    "Правила можна з'єднувати в ланцюжок: одне активує інше. Працюють, поки ввімкнений фоновий моніторинг (Налаштування).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { templates = true }, modifier = Modifier.fillMaxWidth()) { Text("Готові приклади") }
            rules.forEachIndexed { idx, r ->
                Card(Modifier.fillMaxWidth().clickable { nav.push(Screen.RuleEdit(r.id)) }) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.titleMedium)
                                Text(describe(r, rules), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = r.enabled, onCheckedChange = { eng.save(r.copy(enabled = it)) })
                        }
                        val st = status[r.id]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (r.needsArm) (if (r.id in armed) "● активне · " else "○ не активне · ") else "") +
                                    (if (!r.enabled) "вимкнено" else st?.text ?: "…"),
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f),
                                color = if (st?.text?.startsWith("Спрацювало") == true) OnGreen else MaterialTheme.colorScheme.primary,
                            )
                            if (idx > 0) IconButton(onClick = { eng.move(r.id, true) }, Modifier.size(32.dp)) { Icon(Icons.Filled.ArrowUpward, "Вгору") }
                            if (idx < rules.lastIndex) IconButton(onClick = { eng.move(r.id, false) }, Modifier.size(32.dp)) { Icon(Icons.Filled.ArrowDownward, "Вниз") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(72.dp))
        }
    }
    if (templates) TemplatesDialog { templates = false }
}

private fun condText(c: Cond): String {
    val m = RuleValues.metrics(c.deviceKey).firstOrNull { it.id == c.metric }
    return "${RuleValues.nameOf(c.deviceKey)}: ${m?.label ?: c.metric} ${c.op.sym} ${c.value}${m?.unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}"
}

private fun actionText(a: Action, rules: List<Rule>): String = when {
    a.type.needsDevice -> "${a.type.label} ${RuleValues.nameOf(a.deviceKey)}" + compName(a)
    a.type.needsRule -> "${a.type.label} «${rules.firstOrNull { it.id == a.ruleId }?.name ?: "?"}»"
    else -> "Сповіщення"
}

private fun compName(a: Action): String {
    if (a.comp < 0) return ""
    val c = IotApp.instance.controller.espState(a.deviceKey).comps.firstOrNull { it.i == a.comp } ?: return " (№${a.comp})"
    return " (${c.name})"
}

private fun describe(r: Rule, rules: List<Rule>): String =
    "Якщо " + r.conds.joinToString(if (r.all) " і " else " або ") { condText(it) } +
        (if (r.holdSec > 0) " (≥ ${r.holdSec} с)" else "") + " → " + r.actions.joinToString(", ") { actionText(it, rules) }

// ======================= приклади =======================

@Composable
private fun TemplatesDialog(onDismiss: () -> Unit) {
    val eng = IotApp.instance.rules
    val src = RuleValues.sources()
    val inv = src.firstOrNull { it.key.startsWith("inv:") }?.key ?: ""
    val plugs = src.filterNot { it.key.startsWith("inv:") }
    val p1 = plugs.getOrNull(0)?.key ?: ""
    val p2 = plugs.getOrNull(1)?.key ?: ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Готові приклади") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Створю правило з твоїми пристроями — потім підправ під себе.", style = MaterialTheme.typography.bodySmall)
                ListItem(
                    headlineContent = { Text("Зникла мережа → вимкнути") },
                    supportingContent = { Text("Інвертор перейшов на батарею (мережі немає 10 с) → вимкнути розетку") },
                    modifier = Modifier.clickable {
                        eng.save(Rule(name = "Немає мережі — вимкнути", conds = listOf(Cond(inv, "grid", Op.EQ, "ні")),
                            holdSec = 10, cooldownSec = 60, actions = listOf(Action(ActType.OFF, p1))))
                        onDismiss()
                    },
                )
                ListItem(
                    headlineContent = { Text("Мережа повернулась → увімкнути") },
                    supportingContent = { Text("Мережа є 2 хв → увімкнути розетку назад") },
                    modifier = Modifier.clickable {
                        eng.save(Rule(name = "Мережа є — увімкнути", conds = listOf(Cond(inv, "grid", Op.EQ, "так")),
                            holdSec = 120, cooldownSec = 60, actions = listOf(Action(ActType.ON, p1))))
                        onDismiss()
                    },
                )
                ListItem(
                    headlineContent = { Text("Ланцюжок за струмом (2 кроки)") },
                    supportingContent = { Text("Крок 1: струм ≥ 6 А → активує крок 2. Крок 2: струм ≤ 0,5 А 30 с → вимкнути одну розетку й увімкнути іншу") },
                    modifier = Modifier.clickable {
                        val step2 = Rule(
                            name = "Крок 2: струм упав", conds = listOf(Cond(p1, "current", Op.LE, "0.5")), holdSec = 30, cooldownSec = 10,
                            needsArm = true, disarmAfter = true,
                            actions = listOf(Action(ActType.OFF, p1), Action(ActType.ON, p2)),
                        )
                        val step1 = Rule(
                            name = "Крок 1: струм ≥ 6 А", conds = listOf(Cond(p1, "current", Op.GE, "6")), holdSec = 5, cooldownSec = 10,
                            actions = listOf(Action(ActType.ARM, ruleId = step2.id)),
                        )
                        eng.save(step1); eng.save(step2)
                        onDismiss()
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрити") } },
    )
}

// ======================= редактор =======================

@Composable
fun RuleEditScreen(id: String, nav: Nav) {
    val eng = IotApp.instance.rules
    val rules by eng.rules.collectAsState()
    val existing = rules.firstOrNull { it.id == id }
    var r by remember(id) { mutableStateOf(existing ?: Rule(name = "Нове правило")) }
    // живі значення пристроїв у підказках
    IotApp.instance.controller.states.collectAsState().value
    IotApp.instance.controller.espStates.collectAsState().value
    IotApp.instance.inverters.states.collectAsState().value
    r.devices.forEach { k -> if (k.startsWith("inv:")) KeepInverters() else KeepConnected(k) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Нове правило" else "Правило") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    if (existing != null) IconButton(onClick = { eng.delete(id); nav.pop() }) { Icon(Icons.Filled.Delete, "Видалити") }
                },
            )
        },
        bottomBar = {
            Button(onClick = { eng.save(r); nav.pop() }, modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) { Text("Зберегти") }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(r.name, { r = r.copy(name = it) }, label = { Text("Назва") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Увімкнено", Modifier.weight(1f))
                Switch(checked = r.enabled, onCheckedChange = { r = r.copy(enabled = it) })
            }

            SectionCard("Якщо") {
                if (r.conds.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = r.all, onClick = { r = r.copy(all = true) }, label = { Text("усі умови (і)") })
                    FilterChip(selected = !r.all, onClick = { r = r.copy(all = false) }, label = { Text("будь-яка (або)") })
                }
                r.conds.forEachIndexed { i, c ->
                    CondEditor(c, onChange = { nc -> r = r.copy(conds = r.conds.toMutableList().also { it[i] = nc }) },
                        onRemove = if (r.conds.size > 1) ({ r = r.copy(conds = r.conds.filterIndexed { j, _ -> j != i }) }) else null)
                    if (i < r.conds.lastIndex) Text(if (r.all) "і" else "або", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { r = r.copy(conds = r.conds + Cond()) }) { Text("+ Умова") }
            }

            SectionCard("То") {
                r.actions.forEachIndexed { i, a ->
                    ActionEditor(a, rules.filter { it.id != r.id }, onChange = { na -> r = r.copy(actions = r.actions.toMutableList().also { it[i] = na }) },
                        onRemove = if (r.actions.size > 1) ({ r = r.copy(actions = r.actions.filterIndexed { j, _ -> j != i }) }) else null)
                }
                TextButton(onClick = { r = r.copy(actions = r.actions + Action()) }) { Text("+ Дія") }
            }

            SectionCard("Тонкі налаштування") {
                NumberField("Умови мають триматися, с", r.holdSec) { r = r.copy(holdSec = it) }
                Text("Захист від випадкових стрибків: спрацює, лише якщо умови виконуються стільки секунд поспіль.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NumberField("Пауза між спрацюваннями, с", r.cooldownSec) { r = r.copy(cooldownSec = it) }
                Text("Повторно спрацює, лише коли умови спершу стануть хибними, а потім знову виконаються.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                TimeWindow(r) { r = it }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                CheckRow("Крок ланцюжка: працює лише після активації іншим правилом", r.needsArm) { r = r.copy(needsArm = it) }
                if (r.needsArm) CheckRow("Після спрацювання знову чекати активації", r.disarmAfter) { r = r.copy(disarmAfter = it) }
                if (r.needsArm) Text("Щоб активувати: в іншому правилі додай дію «Активувати правило» → «${r.name}».",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NumberField(label: String, v: Int, onChange: (Int) -> Unit) {
    var t by remember(v) { mutableStateOf(v.toString()) }
    OutlinedTextField(
        t, { s -> t = s.filter { it.isDigit() }.take(6); t.toIntOrNull()?.let(onChange) },
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun TimeWindow(r: Rule, onChange: (Rule) -> Unit) {
    val ctx = LocalContext.current
    val on = r.fromMin >= 0 && r.toMin >= 0
    CheckRow("Лише в певний час доби", on) { onChange(if (it) r.copy(fromMin = 8 * 60, toMin = 22 * 60) else r.copy(fromMin = -1, toMin = -1)) }
    if (on) Row(verticalAlignment = Alignment.CenterVertically) {
        fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
        TextButton(onClick = {
            android.app.TimePickerDialog(ctx, { _, h, m -> onChange(r.copy(fromMin = h * 60 + m)) }, r.fromMin / 60, r.fromMin % 60, true).show()
        }) { Text("з ${hm(r.fromMin)}") }
        TextButton(onClick = {
            android.app.TimePickerDialog(ctx, { _, h, m -> onChange(r.copy(toMin = h * 60 + m)) }, r.toMin / 60, r.toMin % 60, true).show()
        }) { Text("до ${hm(r.toMin)}") }
    }
}

/** Діалог вибору зі списку (ключ, підпис, пояснення). */
@Composable
private fun Picker(title: String, options: List<Triple<String, String, String>>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (options.isEmpty()) Text("Немає варіантів")
                options.forEach { (k, label, sub) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        supportingContent = if (sub.isNotBlank()) ({ Text(sub) }) else null,
                        modifier = Modifier.clickable { onPick(k); onDismiss() },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

@Composable
private fun CondEditor(c: Cond, onChange: (Cond) -> Unit, onRemove: (() -> Unit)?) {
    var pickDevice by remember { mutableStateOf(false) }
    var pickMetric by remember { mutableStateOf(false) }
    val metrics = if (c.deviceKey.isBlank()) emptyList() else RuleValues.metrics(c.deviceKey)
    val m = metrics.firstOrNull { it.id == c.metric }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDevice = true }, modifier = Modifier.weight(1f)) {
                    Text(if (c.deviceKey.isBlank()) "Пристрій…" else RuleValues.nameOf(c.deviceKey), maxLines = 1)
                }
                if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Прибрати") }
            }
            if (c.deviceKey.isNotBlank()) OutlinedButton(onClick = { pickMetric = true }, modifier = Modifier.fillMaxWidth()) {
                Text(m?.let { it.label + if (it.unit.isNotBlank()) ", ${it.unit}" else "" } ?: "Що перевіряти…", maxLines = 1)
            }
            if (m != null) {
                val ops = if (m.kind == ValKind.NUM) Op.entries else listOf(Op.EQ, Op.NE)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ops.forEach { op -> FilterChip(selected = c.op == op, onClick = { onChange(c.copy(op = op)) }, label = { Text(op.sym) }) }
                }
                when (m.kind) {
                    ValKind.BOOL -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = c.value == "так", onClick = { onChange(c.copy(value = "так")) }, label = { Text("так") })
                        FilterChip(selected = c.value == "ні", onClick = { onChange(c.copy(value = "ні")) }, label = { Text("ні") })
                    }
                    ValKind.NUM -> OutlinedTextField(
                        c.value, { s -> onChange(c.copy(value = s.replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' })) },
                        label = { Text("Значення" + if (m.unit.isNotBlank()) ", ${m.unit}" else "") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                    )
                    ValKind.TEXT -> OutlinedTextField(c.value, { onChange(c.copy(value = it)) }, label = { Text("Текст") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                val now = RuleValues.value(c.deviceKey, c.metric)
                val met = RuleValues.test(now, c.op, c.value)
                Text(
                    "Зараз: ${RuleValues.format(now, m.unit)}" + if (now != null && c.value.isNotBlank()) (if (met) " — умова виконується" else " — не виконується") else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (met) OnGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (pickDevice) Picker(
        "Пристрій",
        RuleValues.sources().map { Triple(it.key, it.name, it.kind) },
        { k -> onChange(Cond(deviceKey = k)) },
    ) { pickDevice = false }
    if (pickMetric) Picker(
        "Що перевіряти",
        metrics.map { Triple(it.id, it.label + if (it.unit.isNotBlank()) ", ${it.unit}" else "", "зараз: " + RuleValues.format(RuleValues.value(c.deviceKey, it.id), it.unit)) },
        { id ->
            val k = metrics.firstOrNull { it.id == id }?.kind
            onChange(c.copy(metric = id, op = if (k == ValKind.NUM) Op.GE else Op.EQ, value = if (k == ValKind.BOOL) "так" else ""))
        },
    ) { pickMetric = false }
}

@Composable
private fun ActionEditor(a: Action, otherRules: List<Rule>, onChange: (Action) -> Unit, onRemove: (() -> Unit)?) {
    var pickType by remember { mutableStateOf(false) }
    var pickDevice by remember { mutableStateOf(false) }
    var pickComp by remember { mutableStateOf(false) }
    var pickRule by remember { mutableStateOf(false) }
    val app = IotApp.instance
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickType = true }, modifier = Modifier.weight(1f)) { Text(a.type.label) }
                if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Прибрати") }
            }
            when {
                a.type.needsDevice -> {
                    OutlinedButton(onClick = { pickDevice = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (a.deviceKey.isBlank()) "Пристрій…" else RuleValues.nameOf(a.deviceKey), maxLines = 1)
                    }
                    if (app.storage.device(a.deviceKey)?.isEsp == true) OutlinedButton(onClick = { pickComp = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (a.comp < 0) "Головний вихід плати" else "Компонент:" + compName(a))
                    }
                }
                a.type.needsRule -> OutlinedButton(onClick = { pickRule = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(otherRules.firstOrNull { it.id == a.ruleId }?.name ?: "Правило…", maxLines = 1)
                }
                else -> OutlinedTextField(a.text, { onChange(a.copy(text = it)) }, label = { Text("Текст сповіщення") }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    if (pickType) Picker("Дія", ActType.entries.map { Triple(it.name, it.label, "") }, { onChange(a.copy(type = ActType.parse(it))) }) { pickType = false }
    if (pickDevice) Picker(
        "Пристрій",
        RuleValues.sources().filterNot { it.key.startsWith("inv:") }.map { Triple(it.key, it.name, it.kind) },
        { onChange(a.copy(deviceKey = it, comp = -1)) },
    ) { pickDevice = false }
    if (pickComp) Picker(
        "Компонент плати",
        listOf(Triple("-1", "Головний вихід", "")) + app.controller.espState(a.deviceKey).comps.filter { it.isSwitchable }.map { Triple(it.i.toString(), it.name, it.type.label) },
        { onChange(a.copy(comp = it.toInt())) },
    ) { pickComp = false }
    if (pickRule) Picker(
        "Правило",
        otherRules.map { Triple(it.id, it.name, if (it.needsArm) "крок ланцюжка" else "") },
        { onChange(a.copy(ruleId = it)) },
    ) { pickRule = false }
}
