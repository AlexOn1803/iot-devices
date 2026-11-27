package ua.madeisy.iot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.data.DeviceKind
import ua.madeisy.iot.data.Schedule
import java.io.IOException
import java.util.Locale

/** Виходи плати, якими може керувати розклад (як OUT_TYPES у панелі). */
private val OUT_TYPES = setOf("relay", "pwm", "ws2812", "tuya")

private fun JSONArray?.objs(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** Рядок розкладу: з хаба (виконує Pi) або з самої плати (поле plan у компоненті). */
private data class HubSchedRow(val s: Schedule, val board: Boolean, val ci: Int = -1, val pi: Int = -1, val compName: String = "")

private fun planTriples(cfg: JSONObject?) = cfg?.optJSONArray("comps").objs().map { c ->
    c.optJSONArray("plan").objs().map { Triple(it.optInt("d"), it.optInt("t"), it.optBoolean("on")) }
}

/**
 * Розклади, коли є хаб Raspberry Pi: показує й редагує те, що зберігається на хабі
 * (розетки Tuya) і на самій платі ESP (працює навіть без Pi), — так само, як вкладка
 * «Термостати й розклади» у веб-панелі.
 */
@Composable
internal fun HubSchedulesCard(deviceKey: String) {
    val app = IotApp.instance
    val hub = app.controller.hub
    val status by hub.status.collectAsState()
    val isEsp = app.storage.device(deviceKey)?.kind == DeviceKind.ESP
    val scope = rememberCoroutineScope()

    var hubList by remember { mutableStateOf<List<JSONObject>?>(null) }
    var espCfg by remember { mutableStateOf<JSONObject?>(null) }
    var clock by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<HubSchedRow?>(null) }

    LaunchedEffect(status.connected, reload) {
        if (!status.connected) { error = "Немає зв'язку з хабом — розклади з'являться, коли хаб буде на зв'язку"; return@LaunchedEffect }
        loading = true
        error = null
        hub.request(JSONObject().put("t", "admin_get"), 10000)
            .onSuccess { hubList = it.optJSONArray("schedules").objs() }
            .onFailure { error = it.message }
        if (isEsp) hub.request(JSONObject().put("t", "esp_config_get").put("key", deviceKey), 15000)
            .onSuccess { espCfg = it.optJSONObject("config"); clock = it.optJSONObject("info")?.optString("clock").orEmpty() }
            .onFailure { error = "Плата: " + it.message }
        loading = false
    }

    val comps = espCfg?.optJSONArray("comps").objs()
    val outputs = comps.mapIndexedNotNull { ci, c -> if (c.optString("t") in OUT_TYPES) ci to c.optString("name", "Вихід $ci") else null }
    val rows = buildList {
        hubList.orEmpty().filter { it.optString("deviceKey") == deviceKey }.forEach { j ->
            runCatching { Schedule.fromJson(j) }.getOrNull()?.let { add(HubSchedRow(it, board = false)) }
        }
        comps.forEachIndexed { ci, c ->
            c.optJSONArray("plan").objs().forEachIndexed { pi, p ->
                val t = p.optInt("t")
                add(HubSchedRow(
                    Schedule(id = "b:$ci:$pi", deviceKey = deviceKey, hour = t / 60, minute = t % 60, days = p.optInt("d"),
                        turnOn = p.optBoolean("on"), comp = ci),
                    board = true, ci = ci, pi = pi, compName = c.optString("name", "Вихід $ci"),
                ))
            }
        }
    }.sortedBy { it.s.hour * 60 + it.s.minute }

    // ---- запис ----
    suspend fun hubSet(transform: (MutableList<JSONObject>) -> Unit) {
        val fresh = hub.request(JSONObject().put("t", "admin_get"), 10000).getOrThrow().optJSONArray("schedules").objs().toMutableList()
        transform(fresh)
        val r = hub.request(JSONObject().put("t", "sched_set").put("schedules", JSONArray(fresh)), 10000).getOrThrow()
        hubList = r.optJSONObject("admin")?.optJSONArray("schedules").objs()
    }

    /** Змінити розклад на платі: прибрати запис [removeCi]/[removePi] і/або додати [entry] у вихід [comp]; перевірити, що плата зберегла. */
    suspend fun boardSet(removeCi: Int, removePi: Int, comp: Int, entry: JSONObject?) {
        val cfg = hub.request(JSONObject().put("t", "esp_config_get").put("key", deviceKey), 15000).getOrThrow().getJSONObject("config")
        val arr = cfg.optJSONArray("comps") ?: throw IOException("Плата не налаштована")
        if (removeCi >= 0) arr.optJSONObject(removeCi)?.optJSONArray("plan")?.let { if (removePi < it.length()) it.remove(removePi) }
        if (entry != null) {
            val c = arr.optJSONObject(comp) ?: throw IOException("Немає такого виходу на платі")
            (c.optJSONArray("plan") ?: JSONArray().also { c.put("plan", it) }).put(entry)
        }
        hub.request(JSONObject().put("t", "esp_config_set").put("key", deviceKey).put("config", cfg), 20000).getOrThrow()
        val back = hub.request(JSONObject().put("t", "esp_config_get").put("key", deviceKey), 15000).getOrThrow()
        val backCfg = back.optJSONObject("config")
        if (planTriples(backCfg) != planTriples(cfg)) throw IOException("Плата не зберегла розклад — спробуй ще раз або онови прошивку плати")
        espCfg = backCfg
        clock = back.optJSONObject("info")?.optString("clock").orEmpty()
    }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { block() }.onFailure { error = it.message ?: "Помилка" }
            busy = false
        }
    }

    fun save(old: HubSchedRow?, n: Schedule) = run {
        if (isEsp) {
            // розклад плати живе на самій платі, як у веб-панелі
            val comp = if (n.comp >= 0) n.comp else outputs.firstOrNull()?.first ?: throw IOException("На платі немає виходів")
            val entry = JSONObject().put("d", n.days).put("t", n.hour * 60 + n.minute).put("on", n.turnOn)
            boardSet(if (old?.board == true) old.ci else -1, old?.pi ?: -1, comp, entry)
            if (old != null && !old.board) hubSet { l -> l.removeAll { it.optString("id") == old.s.id } }   // перенесено з Pi на плату
        } else {
            hubSet { l -> l.removeAll { it.optString("id") == n.id }; l.add(n.toJson()) }
        }
        editing = null
    }

    fun delete(row: HubSchedRow) = run {
        if (row.board) boardSet(row.ci, row.pi, -1, null)
        else hubSet { l -> l.removeAll { it.optString("id") == row.s.id } }
        editing = null
    }

    SectionCard("Розклад") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isEsp) "Розклад плати зберігається на самій платі й працює навіть без Pi та інтернету" +
                    (if (clock.isNotBlank()) " (годинник плати $clock)." else ".")
                else "Розклад виконує Raspberry Pi цілодобово — телефон не потрібен.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (loading || busy) CircularProgressIndicator(Modifier.padding(8.dp).width(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, "Оновити") }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (rows.isEmpty() && !loading && hubList != null) {
            Text("Розкладів немає", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        }
        rows.forEachIndexed { i, row ->
            val sch = row.s
            if (i > 0) HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !busy) { editing = row }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val what = when {
                        row.board -> " · " + row.compName
                        isEsp && sch.comp >= 0 -> " · " + (outputs.firstOrNull { it.first == sch.comp }?.second ?: "вихід ${sch.comp}")
                        else -> ""
                    }
                    Text(
                        String.format(Locale.US, "%02d:%02d", sch.hour, sch.minute) + "  " + (if (sch.turnOn) "увімкнути" else "вимкнути") + what,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (sch.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        formatDays(sch.days) + when {
                            row.board -> " · на платі"
                            isEsp -> " · виконує Pi (збережи, щоб перенести на плату)"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.board) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!row.board) Switch(checked = sch.enabled, enabled = !busy, onCheckedChange = { en ->
                    run { hubSet { l -> l.firstOrNull { it.optString("id") == sch.id }?.put("enabled", en) } }
                })
            }
        }
        TextButton(enabled = status.connected && !busy && (!isEsp || espCfg != null), onClick = {
            editing = HubSchedRow(Schedule(deviceKey = deviceKey, hour = 23, minute = 0, days = 0b1111111, turnOn = false,
                comp = outputs.firstOrNull()?.first ?: -1), board = isEsp)
        }) {
            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Додати")
        }
    }

    editing?.let { e ->
        val existing = rows.any { it.s.id == e.s.id }
        ScheduleDialog(
            initial = e.s,
            isNew = !existing,
            outputs = if (isEsp) outputs else emptyList(),
            requireDays = isEsp,
            onDismiss = { editing = null },
            onSave = { n -> save(if (existing) e else null, n) },
            onDelete = { delete(e) },
        )
    }
}
