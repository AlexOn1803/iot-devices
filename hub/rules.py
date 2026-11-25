"""Правила «якщо → то» на хабі (той самий формат JSON, що й у додатку — ua.madeisy.iot.rules.Rule).

Правило спрацьовує, коли умови стають істинними й тримаються hold секунд. Поки умови істинні, правило
постійно стежить за своїми діями «увімкнути/вимкнути»: якщо пристрій перемкнули інакше (вручну, з іншого
правила, після збою живлення), через hold секунд (і не раніше ніж через cooldown після попереднього
спрацювання) воно знову ставить потрібний стан. Сповіщення, «перемкнути» й активації — лише раз, поки
умови знову не стануть хибними. Правило з needsArm чекає активації іншим правилом.
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
        self.dev_since = {}      # rid -> коли пристрій вперше відхилився від стану, який тримає правило
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
        self.dev_since.clear()

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
                self.dev_since.pop(rid, None)
                self._status(rid, "Активне, чекає умов" if r.get("needsArm") else "Чекає умов")
                continue
            if rid in self.latched:
                await self._keep(r, now)
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

    def _violations(self, r):
        """Дії «увімкнути/вимкнути», чий пристрій зараз не в потрібному стані."""
        bad = []
        for a in r.get("actions", []):
            if a.get("t") not in ("ON", "OFF"):
                continue
            cur = self.hub.is_on(a.get("d", ""), a.get("c", -1))
            if cur is not None and cur != (a["t"] == "ON"):
                bad.append(a)
        return bad

    async def _keep(self, r, now):
        """Умови досі істинні: тримаємо стан пристроїв, який задає правило."""
        rid = r["id"]
        bad = self._violations(r)
        fired_at = time.strftime("%H:%M", time.localtime(self.last_fired.get(rid, now)))
        if not bad:
            self.dev_since.pop(rid, None)
            has_keep = any(a.get("t") in ("ON", "OFF") for a in r.get("actions", []))
            self._status(rid, ("Спрацювало о %s, тримає стан, поки умови виконуються" if has_keep
                               else "Спрацювало о %s, чекає, поки умови зміняться") % fired_at)
            return
        since = self.dev_since.setdefault(rid, now)
        wait = max(r.get("hold", 10) - (now - since), r.get("cooldown", 60) - (now - self.last_fired.get(rid, 0)))
        names = ", ".join(self.hub.name_of(a.get("d", "")) for a in bad)
        if wait > 0:
            self._status(rid, "%s перемкнули інакше — поверну через %d с" % (names, int(wait) + 1))
            return
        await self.fire(r, only=bad)
        self.last_fired[rid] = now
        self.dev_since.pop(rid, None)

    async def fire(self, r, only=None):
        errors = []
        for a in (only if only is not None else r.get("actions", [])):
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
        ts = time.strftime("%H:%M:%S")
        if only is not None:
            self._status(r["id"], "Повернуло стан о %s" % ts + (" з помилками: " + "; ".join(errors) if errors else ""), fired=True)
            log.info("rule %s re-applied", r.get("name"))
            return
        if r.get("needsArm") and r.get("disarmAfter", True):
            self.set_armed(r["id"], False)
        self._status(r["id"], "Спрацювало о %s" % ts + (" з помилками: " + "; ".join(errors) if errors else ""), fired=True)
        log.info("rule %s fired%s", r.get("name"), " with errors" if errors else "")
        if errors:
            self.hub.broadcast({"t": "notify", "title": r.get("name", "Правило"), "text": "Не всі дії виконано: " + "; ".join(errors)})


class ThermoEngine:
    """Термостати з додатка (датчик плати ESP → розетка/реле), тепер на Pi.
    Обігрів: t ≤ onAt — увімкнути, t ≥ offAt — вимкнути; охолодження — навпаки.
    Датчик пропав понад 5 хв — обігрів вимикаємо з міркувань безпеки."""

    MIN_SWITCH = 60
    STALE = 5 * 60

    def __init__(self, hub):
        self.hub = hub
        self.last = {}        # id -> (стан, коли)
        self.tripped = set()
        self.status = {}

    async def run(self):
        while True:
            try:
                await self.tick()
            except asyncio.CancelledError:
                raise
            except Exception as e:
                log.warning("thermo tick: %s", e)
            await asyncio.sleep(5)

    async def tick(self):
        now = time.time()
        for t in self.hub.cfg.data.get("thermostats", []):
            if not t.get("enabled", True) or not t.get("sensorKey") or not t.get("targetKey"):
                continue
            tid, heat = t.get("id", ""), t.get("mode", "HEAT") != "COOL"
            on_at, off_at = float(t.get("onAt", 18)), float(t.get("offAt", 19))
            st = self.hub.states.get(t["sensorKey"]) or {}
            temp = self.hub.value(t["sensorKey"], "c%d:temp" % int(t.get("sensorIndex", 0)))
            target_on = self.hub.is_on(t["targetKey"], -1)
            if temp is None:
                lost = now - st.get("at", 0) / 1000
                if heat and target_on and st.get("at") and lost > self.STALE and tid not in self.tripped:
                    await self.hub.switch(t["targetKey"], False)
                    self.tripped.add(tid)
                    self.hub.broadcast({"t": "notify", "title": t.get("name", "Термостат"),
                                        "text": "Датчик температури недоступний — обігрів вимкнено"})
                self.status[tid] = "Немає даних з датчика"
                continue
            self.tripped.discard(tid)
            if heat:
                want = True if temp <= on_at else False if temp >= off_at else None
            else:
                want = True if temp >= on_at else False if temp <= off_at else None
            last = self.last.get(tid)
            if want is not None and want != target_on and (not last or now - last[1] > self.MIN_SWITCH):
                try:
                    await self.hub.switch(t["targetKey"], want)
                    self.status[tid] = "%s при %.1f °C" % ("Увімкнено" if want else "Вимкнено", temp)
                except Exception as e:
                    self.status[tid] = "Помилка: %s" % e
                self.last[tid] = (want, now)
            elif tid not in self.status:
                self.status[tid] = "Працює" if target_on else "Очікує"


class ScheduleEngine:
    """Розклади розеток і плат (о 07:30 у будні — увімкнути). days: біт 0 — понеділок … біт 6 — неділя; 0 — один раз."""

    def __init__(self, hub):
        self.hub = hub
        self.done = set()      # (id, "YYYY-MM-DD HH:MM") — щоб не спрацювати двічі за хвилину

    async def run(self):
        while True:
            try:
                await self.tick()
            except asyncio.CancelledError:
                raise
            except Exception as e:
                log.warning("schedule tick: %s", e)
            await asyncio.sleep(10)

    async def tick(self):
        t = time.localtime()
        stamp = time.strftime("%Y-%m-%d %H:%M", t)
        changed = False
        for s in self.hub.cfg.data.get("schedules", []):
            if not s.get("enabled", True) or s.get("hour") != t.tm_hour or s.get("minute") != t.tm_min:
                continue
            days = int(s.get("days", 0))
            if days and not (days >> t.tm_wday) & 1:
                continue
            k = (s.get("id"), stamp)
            if k in self.done:
                continue
            self.done.add(k)
            try:
                await self.hub.switch(s.get("deviceKey", ""), bool(s.get("turnOn")), s.get("comp", -1))
                log.info("schedule %s: %s %s", s.get("id"), s.get("deviceKey"), "on" if s.get("turnOn") else "off")
            except Exception as e:
                self.hub.broadcast({"t": "notify", "title": "Розклад", "text": "%s: %s" % (self.hub.name_of(s.get("deviceKey", "")), e)})
            if not days:
                s["enabled"] = False
                changed = True
        if changed:
            self.hub.cfg.save()
        if len(self.done) > 500:
            self.done = {k for k in self.done if k[1] == stamp}
