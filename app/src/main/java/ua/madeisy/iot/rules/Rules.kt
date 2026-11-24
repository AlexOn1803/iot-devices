package ua.madeisy.iot.rules

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.esp.EspType
import ua.madeisy.iot.inverter.EssSummary
import ua.madeisy.iot.service.Notifications
import java.util.Calendar
import java.util.Locale
import java.util.UUID

// ======================= модель =======================

enum class Op(val sym: String) {
    GT(">"), GE("≥"), LT("<"), LE("≤"), EQ("="), NE("≠");

    companion object { fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: GE }
}

/** Умова: «[metric] пристрою [deviceKey] [op] [value]». Значення — текстом (число, так/ні або текст). */
data class Cond(val deviceKey: String = "", val metric: String = "", val op: Op = Op.GE, val value: String = "") {
    fun toJson(): JSONObject = JSONObject().put("d", deviceKey).put("m", metric).put("op", op.name).put("v", value)

    companion object {
        fun fromJson(o: JSONObject) = Cond(o.optString("d"), o.optString("m"), Op.parse(o.optString("op")), o.optString("v"))
    }
}

enum class ActType(val label: String) {
    ON("Увімкнути"), OFF("Вимкнути"), TOGGLE("Перемкнути"),
    ARM("Активувати правило"), DISARM("Деактивувати правило"), NOTIFY("Сповіщення");

    val needsDevice get() = this == ON || this == OFF || this == TOGGLE
    val needsRule get() = this == ARM || this == DISARM

    companion object { fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: OFF }
}

/** Дія: пристрій [deviceKey] (для плати ESP — компонент [comp], -1 — головний), правило [ruleId] або текст сповіщення. */
data class Action(
    val type: ActType = ActType.OFF,
    val deviceKey: String = "",
    val comp: Int = -1,
    val ruleId: String = "",
    val text: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("t", type.name).put("d", deviceKey).put("c", comp).put("r", ruleId).put("x", text)

    companion object {
        fun fromJson(o: JSONObject) = Action(
            ActType.parse(o.optString("t")), o.optString("d"), o.optInt("c", -1), o.optString("r"), o.optString("x"),
        )
    }
}

/**
 * Правило «якщо → то».
 * Спрацьовує, коли умови стають істинними і тримаються [holdSec] секунд; повторно — лише після того,
 * як умови знову стали хибними і минуло [cooldownSec]. Правило з [needsArm] чекає, доки його
 * активує інше правило (ланцюжок кроків); [disarmAfter] — після спрацювання знову чекати активації.
 */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Правило",
    val enabled: Boolean = true,
    val conds: List<Cond> = listOf(Cond()),
    val all: Boolean = true,
    val holdSec: Int = 10,
    val cooldownSec: Int = 60,
    val needsArm: Boolean = false,
    val disarmAfter: Boolean = true,
    val actions: List<Action> = listOf(Action()),
    /** Часове вікно (хвилини доби); -1 — будь-коли. */
    val fromMin: Int = -1,
    val toMin: Int = -1,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("enabled", enabled)
        .put("conds", JSONArray().apply { conds.forEach { put(it.toJson()) } }).put("all", all)
        .put("hold", holdSec).put("cooldown", cooldownSec).put("needsArm", needsArm).put("disarmAfter", disarmAfter)
        .put("actions", JSONArray().apply { actions.forEach { put(it.toJson()) } })
        .put("from", fromMin).put("to", toMin)

    val devices: Set<String> get() = (conds.map { it.deviceKey } + actions.filter { it.type.needsDevice }.map { it.deviceKey })
        .filter { it.isNotBlank() }.toSet()

    companion object {
        fun fromJson(o: JSONObject): Rule {
            val c = o.optJSONArray("conds") ?: JSONArray()
            val a = o.optJSONArray("actions") ?: JSONArray()
            return Rule(
                o.getString("id"), o.optString("name", "Правило"), o.optBoolean("enabled", true),
                (0 until c.length()).mapNotNull { c.optJSONObject(it)?.let(Cond::fromJson) }, o.optBoolean("all", true),
                o.optInt("hold", 10), o.optInt("cooldown", 60), o.optBoolean("needsArm"), o.optBoolean("disarmAfter", true),
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Action::fromJson) },
                o.optInt("from", -1), o.optInt("to", -1),
            )
        }

        fun listFromJson(s: String?): List<Rule> = runCatching {
            val a = JSONArray(s ?: "[]"); (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        fun listToJson(l: List<Rule>): String = JSONArray().apply { l.forEach { put(it.toJson()) } }.toString()
    }
}

// ======================= дані пристроїв для умов =======================

enum class ValKind { NUM, BOOL, TEXT }

data class RuleMetric(val id: String, val label: String, val unit: String, val kind: ValKind)

/** Які показники пристрою можна використати в умові, і їхні поточні значення. */
object RuleValues {
    private fun app() = IotApp.instance

    data class Source(val key: String, val name: String, val kind: String)

    /** Усі пристрої: розетки, плати, інвертори. */
    fun sources(): List<Source> =
        app().inverters.devices.value.map { Source("inv:" + it.sn, it.alias, "Інвертор") } +
            app().storage.devices.value.map { Source(it.key, it.name, if (it.isEsp) "Плата ESP" else "Розетка") }

    fun nameOf(key: String) = sources().firstOrNull { it.key == key }?.name ?: "?"

    fun metrics(key: String): List<RuleMetric> {
        if (key.startsWith("inv:")) {
            val base = listOf(
                RuleMetric("grid", "Мережа є", "", ValKind.BOOL),
                RuleMetric("mode", "Режим", "", ValKind.TEXT),
                RuleMetric("soc", "Заряд батареї", "%", ValKind.NUM),
                RuleMetric("pv", "Сонце", "Вт", ValKind.NUM),
                RuleMetric("load", "Навантаження", "Вт", ValKind.NUM),
                RuleMetric("loadPct", "Навантаження", "%", ValKind.NUM),
                RuleMetric("gridV", "Напруга мережі", "В", ValKind.NUM),
                RuleMetric("batt", "Батарея (+заряд/−розряд)", "Вт", ValKind.NUM),
                RuleMetric("bv", "Напруга батареї", "В", ValKind.NUM),
            )
            val st = app().inverters.stateOf(key.removePrefix("inv:"))
            return base + st.points.map {
                RuleMetric("pt:" + it.title, EssSummary.titleUa(it.title), it.unit, if (it.number != null) ValKind.NUM else ValKind.TEXT)
            }
        }
        val d = app().storage.device(key) ?: return emptyList()
        if (!d.isEsp) return listOf(
            RuleMetric("on", "Увімкнено", "", ValKind.BOOL),
            RuleMetric("power", "Потужність", "Вт", ValKind.NUM),
            RuleMetric("current", "Струм", "А", ValKind.NUM),
            RuleMetric("voltage", "Напруга", "В", ValKind.NUM),
            RuleMetric("online", "На зв'язку", "", ValKind.BOOL),
        )
        val out = mutableListOf(RuleMetric("online", "На зв'язку", "", ValKind.BOOL))
        for (c in app().controller.espState(key).comps) {
            val p = "c${c.i}"
            when (c.type) {
                EspType.RELAY, EspType.PWM, EspType.WS2812, EspType.TUYA -> out += RuleMetric("$p:on", "${c.name}: увімкнено", "", ValKind.BOOL)
                EspType.DHT, EspType.AHT, EspType.BME, EspType.DS18B20, EspType.SHT21 -> {
                    out += RuleMetric("$p:temp", "${c.name}: температура", "°C", ValKind.NUM)
                    if (c.type != EspType.DS18B20) out += RuleMetric("$p:hum", "${c.name}: вологість", "%", ValKind.NUM)
                    if (c.type == EspType.BME) out += RuleMetric("$p:pres", "${c.name}: тиск", "гПа", ValKind.NUM)
                }
                EspType.ANALOG -> out += RuleMetric("$p:pct", c.name, "%", ValKind.NUM)
                EspType.INPUT, EspType.BUTTON -> out += RuleMetric("$p:active", "${c.name}: активний", "", ValKind.BOOL)
                EspType.THERMO -> out += RuleMetric("$p:temp", "${c.name}: температура", "°C", ValKind.NUM)
                else -> {}
            }
        }
        return out
    }

    /** Поточне значення (Double / Boolean / String) або null, якщо даних немає або вони застарілі. */
    fun value(key: String, metric: String): Any? {
        val now = System.currentTimeMillis()
        if (key.startsWith("inv:")) {
            val st = app().inverters.stateOf(key.removePrefix("inv:"))
            if (st.points.isEmpty() || now - st.updatedAt > 15 * 60_000) return null
            val s = st.summary
            return when (metric) {
                "grid" -> s.gridPresent
                "mode" -> s.mode?.let { EssSummary.modeUa(it) }
                "soc" -> s.socPct
                "pv" -> s.pvW
                "load" -> s.loadW
                "loadPct" -> s.loadPct
                "gridV" -> s.gridV
                "batt" -> s.batteryW
                "bv" -> s.batteryV
                else -> if (metric.startsWith("pt:")) st.points.firstOrNull { it.title == metric.removePrefix("pt:") }?.let { it.number ?: it.value } else null
            }
        }
        val d = app().storage.device(key) ?: return null
        if (!d.isEsp) {
            val s = app().controller.state(key)
            if (metric == "online") return s.online
            if (!s.online && now - s.updatedAt > 5 * 60_000) return null
            return when (metric) {
                "on" -> s.on
                "power" -> if (s.on == false) 0.0 else s.powerW
                "current" -> if (s.on == false) 0.0 else s.currentA
                "voltage" -> s.voltageV
                else -> null
            }
        }
        val st = app().controller.espState(key)
        if (metric == "online") return st.online
        if (!st.online && now - st.updatedAt > 5 * 60_000) return null
        val i = metric.substringAfter('c').substringBefore(':').toIntOrNull() ?: return null
        val c = st.comps.firstOrNull { it.i == i } ?: return null
        return when (metric.substringAfter(':')) {
            "on" -> c.on
            "temp" -> c.temp
            "hum" -> c.hum
            "pres" -> c.pres
            "pct" -> c.pct?.toDouble()
            "active" -> c.active
            else -> null
        }
    }

    fun format(v: Any?, unit: String = ""): String = when (v) {
        null -> "немає даних"
        is Boolean -> if (v) "так" else "ні"
        is Double -> (if (v == Math.floor(v) && kotlin.math.abs(v) < 1e6) v.toLong().toString() else String.format(Locale.US, "%.2f", v)) +
            (if (unit.isNotBlank()) " $unit" else "")
        else -> v.toString()
    }

    /** Порівняння значення з умовою. */
    fun test(v: Any?, op: Op, target: String): Boolean {
        if (v == null) return false
        val t = target.trim()
        return when (v) {
            is Boolean -> {
                val want = t.lowercase() in setOf("так", "true", "1", "є", "увімк", "yes", "on")
                if (op == Op.NE) v != want else v == want
            }
            is Number -> {
                val x = v.toDouble(); val y = t.replace(',', '.').toDoubleOrNull() ?: return false
                when (op) { Op.GT -> x > y; Op.GE -> x >= y; Op.LT -> x < y; Op.LE -> x <= y; Op.EQ -> x == y; Op.NE -> x != y }
            }
            else -> {
                val eq = v.toString().trim().equals(t, ignoreCase = true)
                if (op == Op.NE) !eq else eq
            }
        }
    }
}

// ======================= двигун =======================

data class RuleStatus(val text: String, val at: Long, val firedAt: Long = 0)

/**
 * Перевіряє правила кожні 5 с. Поки є увімкнені правила, тримає зв'язок із потрібними пристроями.
 * Цілодобово працює з фоновим моніторингом (або на хабі Raspberry Pi — пізніше).
 */
class RuleEngine(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("rules", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _rules = MutableStateFlow(Rule.listFromJson(prefs.getString("list", null)))
    val rules: StateFlow<List<Rule>> = _rules

    /** Правила, активовані іншими правилами (зберігаються, щоб пережити перезапуск). */
    private val _armed = MutableStateFlow(prefs.getStringSet("armed", emptySet())!!.toSet())
    val armed: StateFlow<Set<String>> = _armed

    private val _status = MutableStateFlow<Map<String, RuleStatus>>(emptyMap())
    val status: StateFlow<Map<String, RuleStatus>> = _status

    private fun hubMode() = runCatching { IotApp.instance.controller.hubMode() }.getOrDefault(false)
    private val hub get() = IotApp.instance.controller.hub

    init {
        // з Raspberry Pi: правила зберігаються й виконуються на ній, тут — лише перегляд і редагування
        scope.launch {
            kotlinx.coroutines.delay(500)
            hub.rules.collect { j ->
                if (j == null || !hubMode()) return@collect
                val list = Rule.listFromJson(j.optJSONArray("rules")?.toString())
                if (list.isEmpty() && _rules.value.isNotEmpty() && !migrated) {
                    // перший раз: перенести правила з телефона на Pi
                    migrated = true
                    pushToHub(_rules.value)
                    return@collect
                }
                migrated = true
                if (prefs.getBoolean("dirty", false)) {
                    // правила змінили, поки Pi була недоступна — надсилаємо їй версію з телефона
                    if (pushToHub(_rules.value)) prefs.edit().putBoolean("dirty", false).apply()
                    return@collect
                }
                _rules.value = list
                prefs.edit().putString("list", Rule.listToJson(list)).apply()
                val a = j.optJSONArray("armed")
                _armed.value = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet()
                val st = j.optJSONObject("status")
                if (st != null) _status.value = st.keys().asSequence().associateWith { k ->
                    val o = st.getJSONObject(k)
                    RuleStatus(o.optString("text"), o.optLong("at"), o.optLong("firedAt"))
                }
            }
        }
    }
    @Volatile private var migrated = false

    private fun pushToHub(l: List<Rule>): Boolean = hub.status.value.connected &&
        hub.send(JSONObject().put("t", "rules_set").put("rules", org.json.JSONArray(Rule.listToJson(l))))

    private val trueSince = HashMap<String, Long>()
    private val latched = HashSet<String>()      // уже спрацювало, чекаємо, поки умови стануть хибними
    private val lastFired = HashMap<String, Long>()
    private val held = HashSet<String>()
    private var invHeld = false

    fun save(r: Rule) {
        val l = _rules.value.toMutableList()
        val i = l.indexOfFirst { it.id == r.id }
        if (i >= 0) l[i] = r else l += r
        store(l)
        latched -= r.id; trueSince -= r.id
    }

    fun delete(id: String) {
        store(_rules.value.filterNot { it.id == id })
        setArmed(id, false)
    }

    fun move(id: String, up: Boolean) {
        val l = _rules.value.toMutableList()
        val i = l.indexOfFirst { it.id == id }; val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in l.indices) return
        l[i] = l[j].also { l[j] = l[i] }
        store(l)
    }

    private fun store(l: List<Rule>) {
        prefs.edit().putString("list", Rule.listToJson(l)).apply()
        _rules.value = l
        if (hubMode() && !pushToHub(l)) prefs.edit().putBoolean("dirty", true).apply()
    }

    fun setArmed(id: String, on: Boolean) {
        if (hubMode()) { hub.send(JSONObject().put("t", "arm").put("rule", id).put("on", on)); return }
        val s = if (on) _armed.value + id else _armed.value - id
        prefs.edit().putStringSet("armed", s).apply()
        _armed.value = s
        if (on) { latched -= id; trueSince -= id }
    }

    private fun setStatus(id: String, text: String, fired: Boolean = false) {
        val prev = _status.value[id]
        _status.value = _status.value + (id to RuleStatus(text, System.currentTimeMillis(), if (fired) System.currentTimeMillis() else prev?.firedAt ?: 0))
    }

    fun start() {
        scope.launch {
            while (true) {
                try { tick() } catch (e: Exception) { if (e is CancellationException) throw e }
                delay(5_000)
            }
        }
    }

    private fun inWindow(r: Rule): Boolean {
        if (r.fromMin < 0 || r.toMin < 0) return true
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (r.fromMin <= r.toMin) m in r.fromMin until r.toMin else m >= r.fromMin || m < r.toMin
    }

    private suspend fun tick() {
        // з Raspberry Pi правила виконує вона — цілодобово, без телефона
        val list = if (hubMode()) emptyList() else _rules.value.filter { it.enabled }
        val app = IotApp.instance
        // тримаємо з'єднання з пристроями правил
        val need = list.flatMap { it.devices }.filterNot { it.startsWith("inv:") }.toSet()
        val needInv = list.any { r -> r.devices.any { it.startsWith("inv:") } }
        withContext(Dispatchers.Main) {
            (need - held).forEach { app.controller.acquire(it); held += it }
            (held - need).toList().forEach { app.controller.release(it); held -= it }
            if (needInv && !invHeld) { app.inverters.acquire(); invHeld = true }
            if (!needInv && invHeld) { app.inverters.release(); invHeld = false }
        }
        val now = System.currentTimeMillis()
        for (r in list) {
            if (r.needsArm && r.id !in _armed.value) {
                trueSince -= r.id; latched -= r.id
                setStatus(r.id, "Чекає активації іншим правилом")
                continue
            }
            if (!inWindow(r)) { trueSince -= r.id; setStatus(r.id, "Поза часовим вікном"); continue }
            val results = r.conds.filter { it.deviceKey.isNotBlank() && it.metric.isNotBlank() }
                .map { c -> RuleValues.test(RuleValues.value(c.deviceKey, c.metric), c.op, c.value) }
            val ok = results.isNotEmpty() && (if (r.all) results.all { it } else results.any { it })
            if (!ok) {
                trueSince -= r.id
                latched -= r.id
                setStatus(r.id, if (r.needsArm) "Активне, чекає умов" else "Чекає умов")
                continue
            }
            if (r.id in latched) { setStatus(r.id, "Спрацювало, чекає, поки умови зміняться"); continue }
            val since = trueSince.getOrPut(r.id) { now }
            val left = r.holdSec * 1000L - (now - since)
            if (left > 0) { setStatus(r.id, "Умови виконуються, спрацює через ${(left + 999) / 1000} с"); continue }
            val last = lastFired[r.id] ?: 0
            if (now - last < r.cooldownSec * 1000L) { setStatus(r.id, "Пауза після спрацювання"); continue }
            fire(r)
            lastFired[r.id] = now
            latched += r.id
        }
    }

    private suspend fun fire(r: Rule) {
        val app = IotApp.instance
        val errors = mutableListOf<String>()
        for (a in r.actions) {
            try {
                when (a.type) {
                    ActType.ON, ActType.OFF, ActType.TOGGLE -> {
                        val d = app.storage.device(a.deviceKey) ?: throw IllegalStateException("пристрій не знайдено")
                        val res = if (d.isEsp && a.comp >= 0) {
                            val cmd = JSONObject().put("i", a.comp)
                            if (a.type == ActType.TOGGLE) cmd.put("toggle", true) else cmd.put("on", a.type == ActType.ON)
                            app.controller.espSet(a.deviceKey, cmd)
                        } else {
                            val on = when (a.type) {
                                ActType.ON -> true
                                ActType.OFF -> false
                                else -> app.controller.summary(d).on != true
                            }
                            app.controller.setSwitch(a.deviceKey, on)
                        }
                        res.exceptionOrNull()?.let { throw it }
                    }
                    ActType.ARM -> setArmed(a.ruleId, true)
                    ActType.DISARM -> setArmed(a.ruleId, false)
                    ActType.NOTIFY -> Notifications.alert(ctx, r.name, a.text.ifBlank { "Правило спрацювало" })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                errors += "${a.type.label} ${RuleValues.nameOf(a.deviceKey)}: ${e.message}"
            }
        }
        if (r.needsArm && r.disarmAfter) setArmed(r.id, false)
        val t = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        setStatus(r.id, if (errors.isEmpty()) "Спрацювало о $t" else "Спрацювало о $t з помилками: ${errors.joinToString("; ")}", fired = true)
        if (errors.isNotEmpty()) Notifications.alert(ctx, r.name, "Не всі дії виконано: ${errors.joinToString("; ")}")
    }
}
