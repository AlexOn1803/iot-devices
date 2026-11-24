"""Правила «якщо → то» на хабі (той самий формат JSON, що й у додатку — ua.madeisy.iot.rules.Rule).

Правило спрацьовує, коли умови стають істинними й тримаються hold секунд; повторно — лише після того,
як умови знову стали хибними і минула пауза cooldown. Правило з needsArm чекає активації іншим правилом.
"""
import asyncio
import logging
import time

log = logging.getLogger("iothub.rules")

TRUE_WORDS = {"так", "true", "1", "є", "увімк", "yes", "on"}


def test(v, op: str, target: str) -> bool:
    if v is None:
        return False
    t = (target or "").strip()
    if isinstance(v, bool):
        want = t.lower() in TRUE_WORDS
        return v != want if op == "NE" else v == want
    if isinstance(v, (int, float)):
        try:
            y = float(t.replace(",", "."))
        except ValueError:
            return False
        x = float(v)
        return {"GT": x > y, "GE": x >= y, "LT": x < y, "LE": x <= y, "EQ": x == y, "NE": x != y}.get(op, False)
    eq = str(v).strip().lower() == t.lower()
    return not eq if op == "NE" else eq


def mode_ua(m: str) -> str:
    s = (m or "").lower()
    if any(w in s for w in ("line", "grid", "bypass")):
        return "Від мережі"
    if "battery" in s:
        return "Від батареї"
    if "standby" in s:
        return "Очікування"
    if "fault" in s:
        return "Аварія"
    if "power on" in s:
        return "Увімкнення"
    if "charg" in s:
        return "Заряд"
    return m


def _num(p):
    try:
        return float(str(p.get("val", "")).replace(",", "."))
    except ValueError:
        return None


def inverter_summary(points: list) -> dict:
    """Головні показники інвертора з різних назв полів (як EssSummary в додатку)."""
    m = {" ".join(str(p.get("title", "")).lower().split()): p for p in points}

    def num(*keys):
        for k in keys:
            if k in m:
                v = _num(m[k])
                if v is not None:
                    return v
        return None

    def watts(p):
        v = _num(p) if p else None
        if v is None:
            return None
        return v * 1000 if str(p.get("unit", "")).lower() == "kw" else v

    def w(*keys):
        for k in keys:
            if k in m:
                v = watts(m[k])
                if v is not None:
                    return v
        return None

    import re
    pv = w("pv total power", "pv power", "total pv power", "pv input power", "solar power", "dc output power")
    if pv is None:
        parts = [watts(p) for t, p in m.items() if re.fullmatch(r"pv\d (input |charging |charger )?power", t)]
        parts = [x for x in parts if x is not None]
        pv = sum(parts) if parts else None
    bv = num("battery voltage", "bms battery voltage")
    chg, dis = num("battery charging current", "pv charging current"), num("battery discharge current")
    bw = w("battery power")
    if bw is None and bv is not None and (chg is not None or dis is not None):
        bw = bv * ((chg or 0) - (dis or 0))
    grid_v = num("ac input voltage", "grid voltage", "mains voltage")
    mode = None
    for k in ("working state", "operating mode", "work state", "work mode", "mode"):
        if k in m and str(m[k].get("val", "")).strip():
            mode = mode_ua(str(m[k]["val"]))
            break
    return {
        "pv": pv, "load": w("output active power", "ac output active power", "active power", "load power", "load active power"),
        "loadPct": num("ac output load", "load percent", "output load percent", "load percentage", "output load"),
        "soc": num("battery capacity", "soc", "state of charge", "battery soc", "bms soc"),
        "bv": bv, "batt": bw, "gridV": grid_v, "grid": (grid_v > 100) if grid_v is not None else None,
        "gridW": w("grid power", "ac input power", "ac input active power"), "mode": mode,
    }


class RuleEngine:
    def __init__(self, hub):
        self.hub = hub
        self.true_since = {}
        self.latched = set()
        self.last_fired = {}
        self.status = {}

    @property
    def rules(self):
        return self.hub.cfg.data.get("rules", [])

    @property
    def armed(self):
        return set(self.hub.cfg.data.get("armed", []))

    def set_armed(self, rid, on):
        a = self.armed
        a.add(rid) if on else a.discard(rid)
        self.hub.cfg.data["armed"] = sorted(a)
        self.hub.cfg.save()
        if on:
            self.latched.discard(rid)
            self.true_since.pop(rid, None)

    def set_rules(self, rules):
        self.hub.cfg.data["rules"] = rules
        ids = {r["id"] for r in rules}
        self.hub.cfg.data["armed"] = [a for a in self.hub.cfg.data.get("armed", []) if a in ids]
        self.hub.cfg.save()
        self.true_since.clear()
        self.latched.clear()

    def _status(self, rid, text, fired=False):
        prev = self.status.get(rid, {})
        st = {"text": text, "at": int(time.time() * 1000), "firedAt": int(time.time() * 1000) if fired else prev.get("firedAt", 0)}
        if prev.get("text") != text or fired:
            self.status[rid] = st
            self.hub.broadcast({"t": "rule_status", "id": rid, "status": st, "armed": sorted(self.armed)})

    @staticmethod
    def _in_window(r):
        a, b = r.get("from", -1), r.get("to", -1)
        if a < 0 or b < 0:
            return True
        t = time.localtime()
        m = t.tm_hour * 60 + t.tm_min
        return a <= m < b if a <= b else (m >= a or m < b)

    async def run(self):
        while True:
            try:
                await self.tick()
            except asyncio.CancelledError:
                raise
            except Exception as e:
                log.warning("rules tick: %s", e)
            await asyncio.sleep(2)

    async def tick(self):
        now = time.time()
        armed = self.armed
        for r in self.rules:
            rid = r["id"]
            if not r.get("enabled", True):
                continue
            if r.get("needsArm") and rid not in armed:
                self.true_since.pop(rid, None)
                self.latched.discard(rid)
                self._status(rid, "Чекає активації іншим правилом")
                continue
            if not self._in_window(r):
                self.true_since.pop(rid, None)
                self._status(rid, "Поза часовим вікном")
                continue
            conds = [c for c in r.get("conds", []) if c.get("d") and c.get("m")]
            res = [test(self.hub.value(c["d"], c["m"]), c.get("op", "GE"), c.get("v", "")) for c in conds]
            ok = bool(res) and (all(res) if r.get("all", True) else any(res))
            if not ok:
                self.true_since.pop(rid, None)
                self.latched.discard(rid)
                self._status(rid, "Активне, чекає умов" if r.get("needsArm") else "Чекає умов")
                continue
            if rid in self.latched:
                self._status(rid, "Спрацювало, чекає, поки умови зміняться")
                continue
            since = self.true_since.setdefault(rid, now)
            left = r.get("hold", 10) - (now - since)
            if left > 0:
                self._status(rid, "Умови виконуються, спрацює через %d с" % (int(left) + 1))
                continue
            if now - self.last_fired.get(rid, 0) < r.get("cooldown", 60):
                self._status(rid, "Пауза після спрацювання")
                continue
            await self.fire(r)
            self.last_fired[rid] = now
            self.latched.add(rid)

    async def fire(self, r):
        errors = []
        for a in r.get("actions", []):
            t = a.get("t")
            try:
                if t in ("ON", "OFF", "TOGGLE"):
                    await self.hub.switch(a.get("d", ""), {"ON": True, "OFF": False}.get(t), a.get("c", -1))
                elif t == "ARM":
                    self.set_armed(a.get("r", ""), True)
                elif t == "DISARM":
                    self.set_armed(a.get("r", ""), False)
                elif t == "NOTIFY":
                    self.hub.broadcast({"t": "notify", "title": r.get("name", "Правило"), "text": a.get("x") or "Правило спрацювало"})
            except Exception as e:
                errors.append("%s %s: %s" % (t, self.hub.name_of(a.get("d", "")), e))
        if r.get("needsArm") and r.get("disarmAfter", True):
            self.set_armed(r["id"], False)
        ts = time.strftime("%H:%M:%S")
        self._status(r["id"], "Спрацювало о %s" % ts + (" з помилками: " + "; ".join(errors) if errors else ""), fired=True)
        log.info("rule %s fired%s", r.get("name"), " with errors" if errors else "")
        if errors:
            self.hub.broadcast({"t": "notify", "title": r.get("name", "Правило"), "text": "Не всі дії виконано: " + "; ".join(errors)})
