#!/usr/bin/env python3
"""
IoT Devices — домашній хаб на Raspberry Pi.

Тримає зв'язок з усіма пристроями й віддає їхній стан додатку миттєво:
  * розетки Tuya — постійне локальне з'єднання (tinytuya), зміни приходять одразу;
  * плати ESP — локальний HTTP API (опитування раз на 2 с, команди напряму);
  * інвертор SmartESS — хмара DessMonitor раз на хвилину.
Додаток підключається:
  * вдома — напряму ws://<pi>:8380/ws?token=…
  * поза домом — через ретранслятор Cloudflare Worker (wss://iot-hub.<акаунт>.workers.dev/app?token=…),
    до якого хаб сам тримає з'єднання.

Протокол (JSON-повідомлення):
  хаб → додаток: {"t":"hello","name","version","keys":[…]}, {"t":"snapshot","states":{key: state}},
                 {"t":"state","key","state"}, {"t":"ack","id","ok","error"}
  додаток → хаб: {"t":"sync","devices":[…],"inverter":{…}}, {"t":"cmd","id","key","dps"|"esp"}, {"t":"get"}
"""
import asyncio
import hashlib
import json
import logging
import os
import select
import socket
import threading
import time
import urllib.parse

import sys

import aiohttp
from aiohttp import web

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

VERSION = "1.0"
log = logging.getLogger("iothub")

CONFIG_PATH = os.environ.get("IOTHUB_CONFIG", "/etc/iot-hub/config.json")
PORT = int(os.environ.get("IOTHUB_PORT", "8380"))


def lan_ip() -> str:
    """IP Pi в домашній мережі — щоб удома додаток підключався напряму."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return ""


def now_ms() -> int:
    return int(time.time() * 1000)


class Config:
    """Налаштування хаба: токен, адреса ретранслятора і пристрої, які надіслав додаток."""

    def __init__(self, path: str):
        self.path = path
        self.data = {}
        try:
            with open(path) as f:
                self.data = json.load(f)
        except FileNotFoundError:
            pass
        if not self.data.get("token"):
            self.data["token"] = hashlib.sha256(os.urandom(32)).hexdigest()[:32]
            self.save()

    @property
    def token(self) -> str:
        return self.data["token"]

    @property
    def relay(self) -> str:
        return self.data.get("relay", "")

    @property
    def name(self) -> str:
        return self.data.get("name") or socket.gethostname()

    def save(self):
        os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.data, f, ensure_ascii=False, indent=1)
        os.chmod(tmp, 0o600)  # тут local key розеток і ключі плат
        os.replace(tmp, self.path)


# ---------------------------------------------------------------- Tuya

class SoftClose(IOError):
    """Розетка закрила з'єднання — перепідключаємось одразу, це не збій."""


class TuyaWorker:
    """Одна розетка: постійне з'єднання в окремому потоці, події — в asyncio."""

    def __init__(self, hub: "Hub", cfg: dict):
        self.hub = hub
        self.cfg = cfg
        self.key = cfg["key"]
        self.state = {"kind": "tuya", "online": False, "dps": {}, "at": 0}
        self.cmds = []
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.wake_r, self.wake_w = os.pipe()
        os.set_blocking(self.wake_r, False)
        self.thread = threading.Thread(target=self.run, name="tuya-" + self.key, daemon=True)
        self.thread.start()

    def command(self, dps: dict):
        with self.lock:
            self.cmds.append((dps, time.time()))
            # одразу показуємо новий стан у додатку й панелі; розетка підтвердить своїм повідомленням
            self.state["dps"].update({str(k): v for k, v in dps.items()})
        self.publish()
        try:
            os.write(self.wake_w, b"x")   # будимо потік, щоб команда пішла одразу
        except OSError:
            pass

    def _sent(self, t0):
        log.info("tuya %s: command sent in %d ms", self.key, (time.time() - t0) * 1000)

    def publish(self, **changes):
        self.state.update(changes)
        self.state["at"] = now_ms()
        self.hub.emit_threadsafe(self.key, dict(self.state, dps=dict(self.state["dps"])))

    def _device(self, ip):
        import tinytuya
        ver = self.cfg.get("version") or "3.3"
        try:
            ver_f = float(ver)
        except ValueError:
            ver_f = 3.3
        host, _, port = ip.partition(":")   # "ip:порт" — для тестів
        d = tinytuya.OutletDevice(self.cfg["deviceId"], host, self.cfg["localKey"], version=ver_f,
                                  port=int(port or 6668), connection_retry_limit=1, connection_retry_delay=1)
        d.set_socketPersistent(True)
        d.set_socketTimeout(5)
        d.set_socketRetryLimit(1)
        return d

    def _find_ip(self):
        import tinytuya
        try:
            r = tinytuya.find_device(self.cfg["deviceId"])
            ip = r.get("ip") if isinstance(r, dict) else None
            if ip:
                log.info("tuya %s found at %s", self.key, ip)
                self.cfg["ip"] = ip
                if r.get("version"):
                    self.cfg["version"] = str(r["version"])
                self.hub.remember_ip(self.key, ip, self.cfg.get("version"))
            return ip
        except Exception as e:
            log.debug("find_device failed: %s", e)
            return None

    def run_shared(self):
        """Розеткою керує ще й плата ESP: з'єднуємося ненадовго, щоб не займати її єдине з'єднання."""
        while not self.stop.is_set():
            ip = self.cfg.get("ip") or self._find_ip()
            try:
                if not ip:
                    raise IOError("Розетку не знайдено в мережі")
                d = self._device(ip)
                d.set_socketPersistent(False)
                with self.lock:
                    cmds, self.cmds = self.cmds, []
                for dps, t0 in cmds:
                    d.set_multiple_values({str(k): v for k, v in dps.items()}, nowait=True)
                    self._sent(t0)
                if cmds:
                    self.publish(online=True, error=None)
                st = d.status()
                if not st or "dps" not in st:
                    raise IOError((st or {}).get("Error", "немає відповіді"))
                self.state["dps"].update(st["dps"])
                self.publish(online=True, error=None)
            except Exception as e:
                self.publish(online=False, error=str(e))
            r, _, _ = select.select([self.wake_r], [], [], 10)
            if r:
                try:
                    os.read(self.wake_r, 64)
                except OSError:
                    pass

    def run(self):
        if self.cfg.get("shared"):
            return self.run_shared()
        fails = 0
        while not self.stop.is_set():
            ip = self.cfg.get("ip") or self._find_ip()
            if not ip:
                self.publish(online=False, error="Розетку не знайдено в мережі")
                self.stop.wait(30)
                continue
            try:
                d = self._device(ip)
                st = d.status()
                if not st or "Error" in st or "dps" not in st:
                    raise IOError((st or {}).get("Error", "немає відповіді"))
                fails = 0
                self.publish(online=True, error=None, dps=dict(st["dps"]))
                last_hb = time.time()
                last_refresh = 0.0
                while not self.stop.is_set():
                    with self.lock:
                        cmds, self.cmds = self.cmds, []
                    if cmds and getattr(d, "socket", None) is None:
                        with self.lock:
                            self.cmds[:0] = cmds   # з'єднання вже закрите — надішлемо одразу після перепідключення
                        raise SoftClose("з'єднання закрито")
                    for i, (dps, t0) in enumerate(cmds):
                        # не чекаємо відповіді (до 5 с у частини розеток) — вона прийде через receive() нижче
                        try:
                            d.set_multiple_values({str(k): v for k, v in dps.items()}, nowait=True)
                        except Exception:
                            with self.lock:
                                self.cmds[:0] = cmds[i:]
                            raise
                        self._sent(t0)
                    if time.time() - last_hb > 9:
                        d.heartbeat(nowait=True)
                        last_hb = time.time()
                    if time.time() - last_refresh > 10:
                        # потужність/напругу частина розеток надсилає лише після запиту оновлення
                        try:
                            d.updatedps(self.cfg.get("measured") or [18, 19, 20], nowait=True)
                        except Exception:
                            pass
                        last_refresh = time.time()
                    # чекаємо або даних від розетки, або команди (через pipe) — без затримок
                    sock = getattr(d, "socket", None)
                    if sock is None:
                        raise SoftClose("з'єднання закрито")
                    r, _, _ = select.select([sock, self.wake_r], [], [], 1.0)
                    if self.wake_r in r:
                        try:
                            os.read(self.wake_r, 64)
                        except OSError:
                            pass
                    if sock not in r:
                        continue
                    data = d.receive()
                    if data and "dps" in data:
                        self.state["dps"].update(data["dps"])
                        self.publish(online=True, error=None)
                    elif data and "Error" in data and data.get("Err") not in ("904",):
                        # 904 — «нічого не прийшло», це нормально
                        if data.get("Err") in ("901", "905", "914"):
                            raise IOError(data["Error"])
            except SoftClose as e:
                # розетка просто закрила з'єднання: одразу знову, стан «онлайн» не чіпаємо
                log.debug("tuya %s: %s", self.key, e)
                self.stop.wait(0.2)
            except Exception as e:
                fails += 1
                log.info("tuya %s: %s", self.key, e)
                self.publish(online=False, error=str(e))
                if fails >= 3:
                    self.cfg["ip"] = ""   # можливо, змінилась IP — шукаємо заново
                    fails = 0
                # пауза перед новою спробою, але команда з додатка перериває її — пробуємо одразу
                with self.lock:
                    pending = bool(self.cmds)
                if not pending:
                    r, _, _ = select.select([self.wake_r], [], [], 1 if fails == 1 else min(30, 3 * fails))
                    if r:
                        try:
                            os.read(self.wake_r, 64)
                        except OSError:
                            pass


# ---------------------------------------------------------------- ESP

class EspWorker:
    def __init__(self, hub: "Hub", cfg: dict):
        self.hub = hub
        self.cfg = cfg
        self.key = cfg["key"]
        self.state = {"kind": "esp", "online": False, "state": None, "at": 0}
        self.task = asyncio.get_event_loop().create_task(self.run())
        self.last_json = None
        self.kick = asyncio.Event()
        self.mqtt_at = 0.0          # коли востаннє прийшов стан по MQTT
        self.provisioned = False

    def on_mqtt_state(self, j: dict):
        """Плата сама надіслала стан на брокер Pi — миттєво, без опитування."""
        self.mqtt_at = time.time()
        txt = json.dumps(j, sort_keys=True)
        if txt != self.last_json or not self.state["online"]:
            self.last_json = txt
            self.state.update(online=True, state=j, error=None, at=now_ms(), via="mqtt")
            self.hub.emit(self.key, dict(self.state))

    def on_mqtt_offline(self):
        self.mqtt_at = 0
        self.kick.set()   # перевірити через HTTP

    async def provision(self, s):
        """Додати плату брокер Pi як основний (хмарний лишається резервом), якщо ще не налаштовано."""
        mq = self.hub.cfg.data.get("mqtt") or {}
        ip = lan_ip()
        if not mq or not ip or not self.cfg.get("apiKey"):
            return
        try:
            async with s.get(self.base() + "/api/info", headers=self.headers()) as r:
                info = await r.json(content_type=None)
            if "mqttLocal" not in info:
                # стара прошивка знає лише один брокер — не забираємо в неї хмарний резерв;
                # Pi опитує таку плату напряму по Wi-Fi
                self.provisioned = True
                log.info("esp %s: old firmware, keeping its cloud broker (update firmware for instant updates)", self.key)
                return
            if info.get("mqttLocal") == ip:
                self.provisioned = True
                return
            # брокер Pi — основний, хмарний на платі лишається резервом
            body = {"slot": "local", "host": ip, "port": mq.get("port", 1883), "user": mq.get("user", ""), "pass": mq.get("pass", "")}
            async with s.post(self.base() + "/api/mqtt", headers=self.headers(), json=body) as r:
                if r.status < 300:
                    self.provisioned = True
                    log.info("esp %s now reports to the Pi broker", self.key)
        except Exception as e:
            log.info("esp %s provisioning: %s", self.key, e)

    def base(self):
        return "http://%s" % self.cfg["ip"]

    def headers(self):
        return {"X-Key": self.cfg.get("apiKey", "")}

    async def run(self):
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=4)) as s:
            self.session = s
            while True:
                # поки плата сама шле стан по MQTT — HTTP лише зрідка, для перевірки
                if time.time() - self.mqtt_at < 90:
                    self.kick.clear()
                    try:
                        await asyncio.wait_for(self.kick.wait(), 30)
                    except asyncio.TimeoutError:
                        pass
                    if time.time() - self.mqtt_at < 90:
                        continue
                try:
                    async with s.get(self.base() + "/api/state", headers=self.headers()) as r:
                        if r.status == 401:
                            raise IOError("Плата не прийняла ключ доступу")
                        j = await r.json(content_type=None)
                    txt = json.dumps(j, sort_keys=True)
                    if txt != self.last_json or not self.state["online"]:
                        self.last_json = txt
                        self.state.update(online=True, state=j, error=None, at=now_ms(), via="http")
                        self.hub.emit(self.key, dict(self.state))
                    if not self.provisioned:
                        await self.provision(s)
                    delay = 2
                except asyncio.CancelledError:
                    raise
                except Exception as e:
                    if self.state["online"] or self.state.get("error") != str(e):
                        self.state.update(online=False, error=str(e) or "немає відповіді", at=now_ms())
                        self.hub.emit(self.key, dict(self.state))
                    delay = 10
                self.kick.clear()
                try:
                    await asyncio.wait_for(self.kick.wait(), delay)
                except asyncio.TimeoutError:
                    pass

    async def command(self, body):
        # плата на зв'язку з брокером Pi — команда через MQTT (миттєво), інакше — HTTP
        if time.time() - self.mqtt_at < 90 and self.cfg.get("deviceId") and self.hub.mqtt and self.hub.mqtt.connected:
            cmds = body if isinstance(body, list) else [body]
            self.hub.mqtt.publish("iotdev/%s/set" % self.cfg["deviceId"], {"key": self.cfg.get("apiKey", ""), "cmds": cmds})
            return
        async with self.session.post(self.base() + "/api/set", headers=self.headers(), json=body) as r:
            txt = await r.text()
            if r.status >= 300:
                raise IOError("Плата відповіла %d: %s" % (r.status, txt[:100]))
        self.kick.set()   # одразу перечитати стан


# ---------------------------------------------------------------- SmartESS

ESS_SERVERS = {
    "DESS": ("https://api.dessmonitor.com/public/", "authSource", "1"),
    "SHINE": ("https://ios.shinemonitor.com/public/", "auth", "0"),
}


def ess_action(action: str, params: list) -> str:
    return "&action=" + action + "".join("&%s=%s" % (k, urllib.parse.quote_plus(str(v))) for k, v in params)


def sha1(s: str) -> str:
    return hashlib.sha1(s.encode()).hexdigest()


class InverterWorker:
    def __init__(self, hub: "Hub", acc: dict):
        self.hub = hub
        self.acc = acc
        self.token = None
        self.task = asyncio.get_event_loop().create_task(self.run())

    async def request(self, s, action, params, auth=False):
        base, _, _ = ESS_SERVERS.get(self.acc.get("server", "DESS"), ESS_SERVERS["DESS"])
        salt = str(now_ms())
        act = ess_action(action, params)
        if auth or not self.token:
            sign = sha1(salt + self.acc["pwdSha1"] + act)
            url = "%s?sign=%s&salt=%s%s" % (base, sign, salt, act)
        else:
            sign = sha1(salt + self.token[1] + self.token[0] + act)
            url = "%s?sign=%s&salt=%s&token=%s%s" % (base, sign, salt, self.token[0], act)
        async with s.get(url) as r:
            j = await r.json(content_type=None)
        if j.get("err", 0) != 0:
            raise IOError("SmartESS: %s" % (j.get("desc") or j.get("err")))
        return j

    async def login(self, s):
        _, action, source = ESS_SERVERS.get(self.acc.get("server", "DESS"), ESS_SERVERS["DESS"])
        j = await self.request(s, action, [
            ("usr", self.acc["user"]), ("company-key", self.acc.get("companyKey") or "bnrl_frRFjEz8Mkn"),
            ("source", source), ("_app_client_", "web"), ("_app_id_", "ua.madeisy.iot.hub"), ("_app_version_", VERSION),
        ], auth=True)
        self.token = (j["dat"]["token"], j["dat"]["secret"])

    async def run(self):
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=20)) as s:
            while True:
                for d in self.acc.get("devices", []):
                    key = "inv:" + d["sn"]
                    try:
                        if not self.token:
                            await self.login(s)
                        params = [("pn", d["pn"]), ("devcode", d["devcode"]), ("devaddr", d["devaddr"]),
                                  ("sn", d["sn"]), ("i18n", "en")]
                        try:
                            j = await self.request(s, "queryDeviceLastData", params)
                        except IOError:
                            await self.login(s)
                            j = await self.request(s, "queryDeviceLastData", params)
                        pts = [{"title": p.get("title", ""), "val": p.get("val", ""), "unit": p.get("unit", "")}
                               for p in (j.get("dat") or []) if isinstance(p, dict)]
                        self.hub.emit(key, {"kind": "inv", "online": True, "points": pts, "at": now_ms()})
                    except asyncio.CancelledError:
                        raise
                    except Exception as e:
                        self.hub.emit(key, {"kind": "inv", "online": False, "error": str(e), "at": now_ms()})
                await asyncio.sleep(60)


# ---------------------------------------------------------------- хаб

class Hub:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.loop = asyncio.get_event_loop()
        self.states = {}
        self.clients = set()       # локальні WebSocket-клієнти
        self.relay_ws = None
        self.relay_clients = 0
        self.tuya = {}
        self.esp = {}
        self.inverter = None
        self.pair_until = time.time() + 15 * 60
        if not self.cfg.data.get("pin"):
            import random
            self.cfg.data["pin"] = "%06d" % random.SystemRandom().randint(0, 999999)
            self.cfg.save()
        from rules import RuleEngine
        self.rules = RuleEngine(self)
        self.mqtt = None
        self.apply_devices()

    # ---- пристрої
    def apply_devices(self):
        devices = self.cfg.data.get("devices", [])
        want_tuya = {d["key"]: d for d in devices if d.get("kind", "TUYA") != "ESP" and d.get("localKey") and d.get("deviceId")}
        want_esp = {d["key"]: d for d in devices if d.get("kind") == "ESP" and d.get("ip")}
        for k in list(self.tuya):
            if k not in want_tuya or self.tuya[k].cfg != want_tuya[k]:
                self.tuya.pop(k).stop.set()
        for k, d in want_tuya.items():
            if k not in self.tuya:
                self.tuya[k] = TuyaWorker(self, dict(d))
        for k in list(self.esp):
            if k not in want_esp or self.esp[k].cfg != want_esp[k]:
                self.esp.pop(k).task.cancel()
        for k, d in want_esp.items():
            if k not in self.esp:
                self.esp[k] = EspWorker(self, dict(d))
        inv = self.cfg.data.get("inverter")
        if self.inverter and (not inv or self.inverter.acc != inv):
            self.inverter.task.cancel()
            self.inverter = None
        if inv and inv.get("user") and not self.inverter:
            self.inverter = InverterWorker(self, inv)
        keep = set(want_tuya) | set(want_esp) | {"inv:" + d["sn"] for d in (inv or {}).get("devices", [])}
        for k in list(self.states):
            if k not in keep:
                del self.states[k]

    # ---- дані пристроїв (для правил і панелі)
    def device(self, key):
        return next((d for d in self.cfg.data.get("devices", []) if d.get("key") == key), None)

    def name_of(self, key):
        if key.startswith("inv:"):
            for d in (self.cfg.data.get("inverter") or {}).get("devices", []):
                if "inv:" + d["sn"] == key:
                    return d.get("alias") or "Інвертор"
        d = self.device(key)
        return (d or {}).get("name") or key

    def tuya_values(self, key):
        d, st = self.device(key) or {}, self.states.get(key) or {}
        dps, m = st.get("dps") or {}, d.get("dps") or {}

        def num(dp, scale):
            v = dps.get(str(m.get(dp[0], dp[1])))
            return v / 10 ** m.get(scale[0], scale[1]) if isinstance(v, (int, float)) and not isinstance(v, bool) else None
        on = dps.get(str(m.get("switch", 1)))
        on = on if isinstance(on, bool) else None
        power, cur = num(("power", 19), ("powerScale", 1)), num(("current", 18), ("currentScale", 3))
        return {"online": bool(st.get("online")), "on": on, "power": 0.0 if on is False else power,
                "current": 0.0 if on is False else cur, "voltage": num(("voltage", 20), ("voltageScale", 1))}

    def value(self, key, metric):
        st = self.states.get(key)
        if not st:
            return None
        fresh = time.time() * 1000 - st.get("at", 0) < 15 * 60_000
        if key.startswith("inv:"):
            if not st.get("online") or not fresh:
                return None
            pts = st.get("points") or []
            if metric.startswith("pt:"):
                p = next((p for p in pts if p.get("title") == metric[3:]), None)
                if not p:
                    return None
                try:
                    return float(str(p.get("val")).replace(",", "."))
                except ValueError:
                    return p.get("val")
            from rules import inverter_summary
            return inverter_summary(pts).get(metric)
        if metric == "online":
            return bool(st.get("online"))
        if not st.get("online"):
            return None
        if st.get("kind") == "tuya":
            return self.tuya_values(key).get(metric)
        # плата ESP: c<i>:<поле>
        try:
            i, field = metric[1:].split(":", 1)
            i = int(i)
        except ValueError:
            return None
        comp = next((c for c in ((st.get("state") or {}).get("comps") or []) if c.get("i") == i), None)
        if not comp:
            return None
        v = comp.get(field)
        return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else v

    async def switch(self, key, on, comp=-1):
        """on=True/False, None — перемкнути. Для плати — компонент comp (-1 — перший вихід)."""
        if key in self.tuya:
            d = self.device(key) or {}
            dp = str((d.get("dps") or {}).get("switch", 1))
            if on is None:
                on = not bool(((self.states.get(key) or {}).get("dps") or {}).get(dp))
            self.tuya[key].command({dp: bool(on)})
            return
        if key in self.esp:
            if comp is None or comp < 0:
                comps = ((self.states.get(key) or {}).get("state") or {}).get("comps") or []
                out = next((c for c in comps if c.get("t") in ("relay", "pwm", "ws2812", "tuya")), None)
                if out is None:
                    raise IOError("у плати немає виходу")
                comp = out["i"]
            cmd = {"i": comp, "toggle": True} if on is None else {"i": comp, "on": bool(on)}
            await self.esp[key].command(cmd)
            return
        raise KeyError("хаб не керує пристроєм " + self.name_of(key))

    def devices_meta(self):
        out = [{"key": d["key"], "name": d.get("name") or d["key"], "kind": "esp" if d.get("kind") == "ESP" else "tuya"}
               for d in self.cfg.data.get("devices", [])]
        for d in (self.cfg.data.get("inverter") or {}).get("devices", []):
            out.insert(0, {"key": "inv:" + d["sn"], "name": d.get("alias") or "Інвертор", "kind": "inv"})
        return out

    def remember_ip(self, key, ip, version):
        def upd():
            for d in self.cfg.data.get("devices", []):
                if d.get("key") == key:
                    d["ip"] = ip
                    if version:
                        d["version"] = str(version)
            self.cfg.save()
        self.loop.call_soon_threadsafe(upd)

    # ---- розсилка
    def emit_threadsafe(self, key, state):
        self.loop.call_soon_threadsafe(self.emit, key, state)

    def emit(self, key, state):
        self.states[key] = state
        if state.get("kind") == "tuya":
            state["vals"] = self.tuya_values(key)
        self.broadcast({"t": "state", "key": key, "state": state})

    def broadcast(self, msg):
        s = json.dumps(msg, ensure_ascii=False)
        for ws in list(self.clients):
            self.loop.create_task(self._send(ws, s))
        if self.relay_ws is not None and self.relay_clients > 0:
            self.loop.create_task(self._send(self.relay_ws, s))

    async def _send(self, ws, s):
        try:
            await ws.send_str(s)
        except Exception:
            pass

    def hello(self):
        return {"t": "hello", "name": self.cfg.name, "version": VERSION, "keys": sorted(self.states),
                "lan": lan_ip(), "port": PORT, "devices": self.devices_meta(), "dash": self.cfg.data.get("dash") or {}}

    def rules_msg(self):
        return {"t": "rules", "rules": self.rules.rules, "armed": sorted(self.rules.armed), "status": self.rules.status}

    def snapshot(self):
        return {"t": "snapshot", "states": self.states}

    # ---- повідомлення від додатку
    async def handle(self, msg: dict, reply):
        t = msg.get("t")
        if t == "get":
            await reply(self.hello())
            await reply(self.snapshot())
            await reply(self.rules_msg())
        elif t == "rules_get":
            await reply(self.rules_msg())
        elif t == "rules_set":
            if isinstance(msg.get("rules"), list):
                self.rules.set_rules(msg["rules"])
            await reply({"t": "ack", "id": msg.get("id"), "ok": True})
            self.broadcast(self.rules_msg())
        elif t == "arm":
            self.rules.set_armed(msg.get("rule", ""), bool(msg.get("on")))
            self.broadcast(self.rules_msg())
        elif t == "dash_set":
            self.cfg.data["dash"] = msg.get("dash") or {}
            self.cfg.save()
            await reply({"t": "ack", "id": msg.get("id"), "ok": True})
            self.broadcast(self.hello())
        elif t == "switch":
            try:
                await self.switch(msg.get("key", ""), msg.get("on"), msg.get("comp", -1))
                await reply({"t": "ack", "id": msg.get("id"), "ok": True})
            except Exception as e:
                await reply({"t": "ack", "id": msg.get("id"), "ok": False, "error": str(e)})
        elif t == "sync":
            if isinstance(msg.get("devices"), list):
                self.cfg.data["devices"] = msg["devices"]
            if "inverter" in msg:
                self.cfg.data["inverter"] = msg["inverter"]
            if isinstance(msg.get("rules"), list):
                self.rules.set_rules(msg["rules"])
            self.cfg.save()
            self.apply_devices()
            await reply({"t": "ack", "id": msg.get("id"), "ok": True})
            self.broadcast(self.hello())
            await reply(self.snapshot())
            self.broadcast(self.rules_msg())
        elif t == "cmd":
            key = msg.get("key", "")
            try:
                if "dps" in msg and key in self.tuya:
                    self.tuya[key].command(msg["dps"])
                elif "esp" in msg and key in self.esp:
                    await self.esp[key].command(msg["esp"])
                else:
                    raise KeyError("Хаб не керує пристроєм " + key)
                await reply({"t": "ack", "id": msg.get("id"), "ok": True})
            except Exception as e:
                await reply({"t": "ack", "id": msg.get("id"), "ok": False, "error": str(e)})

    # ---- локальний сервер
    def web_session(self):
        return hashlib.sha256(("web:" + self.cfg.token).encode()).hexdigest()

    def authorized(self, req):
        if req.query.get("token") == self.cfg.token or req.headers.get("X-Hub-Token") == self.cfg.token:
            return True
        if req.cookies.get("hub") == self.web_session():
            return True
        # екран самого Pi (кіоск) — без входу
        return req.remote in ("127.0.0.1", "::1") and not req.headers.get("CF-Connecting-IP")

    async def ws_handler(self, req):
        if not self.authorized(req):
            return web.Response(status=401, text="bad token")
        ws = web.WebSocketResponse(heartbeat=20)
        await ws.prepare(req)
        self.clients.add(ws)
        try:
            await ws.send_str(json.dumps(self.hello(), ensure_ascii=False))
            await ws.send_str(json.dumps(self.snapshot(), ensure_ascii=False))
            await ws.send_str(json.dumps(self.rules_msg(), ensure_ascii=False))

            async def reply(m):
                await ws.send_str(json.dumps(m, ensure_ascii=False))
            async for m in ws:
                if m.type == aiohttp.WSMsgType.TEXT:
                    if m.data == "ping":
                        await ws.send_str("pong")
                        continue
                    try:
                        await self.handle(json.loads(m.data), reply)
                    except json.JSONDecodeError:
                        pass
        finally:
            self.clients.discard(ws)
        return ws

    async def pair_handler(self, req):
        """Підключення додатку: віддаємо токен, лише поки відкрите вікно пари (15 хв після запуску
        або після `sudo systemctl kill -s USR1 iot-hub`) і лише в домашній мережі."""
        peer = req.remote or ""
        local = peer.startswith(("192.168.", "10.", "127.")) or any(peer.startswith("172.%d." % i) for i in range(16, 32))
        if not local:
            return web.json_response({"error": "лише з домашньої мережі"}, status=403)
        if time.time() > self.pair_until:
            return web.json_response({"error": "Вікно підключення закрите. На Pi виконай: sudo systemctl kill -s USR1 iot-hub"}, status=403)
        return web.json_response({"app": "iot-hub", "name": self.cfg.name, "token": self.cfg.token,
                                  "relay": self.cfg.relay, "lan": lan_ip(), "port": PORT})

    def open_pairing(self, minutes=15):
        self.pair_until = time.time() + minutes * 60
        log.info("pairing open for %d min", minutes)

    async def login_handler(self, req):
        """Вхід у веб-панель PIN-кодом (його показує install.sh і `sudo iot-hub-pin`)."""
        try:
            pin = str((await req.json()).get("pin", "")).strip()
        except Exception:
            pin = ""
        now = time.time()
        self._fails = [t for t in getattr(self, "_fails", []) if now - t < 300]
        if len(self._fails) >= 10:
            return web.json_response({"error": "Забагато спроб, зачекай 5 хв"}, status=429)
        if pin != self.cfg.data.get("pin"):
            self._fails.append(now)
            return web.json_response({"error": "Невірний PIN"}, status=403)
        resp = web.json_response({"ok": True})
        resp.set_cookie("hub", self.web_session(), max_age=365 * 86400, httponly=True, samesite="Strict")
        return resp

    async def index_handler(self, req):
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web", "index.html")
        return web.FileResponse(path, headers={"Cache-Control": "no-cache"})

    async def info_handler(self, req):
        # без токена — лише щоб додаток знайшов хаб у мережі
        return web.json_response({"app": "iot-hub", "name": self.cfg.name, "version": VERSION})

    # ---- ретранслятор (поза домом)
    async def relay_loop(self):
        delay = 2
        while True:
            relay = self.cfg.relay
            if not relay:
                await asyncio.sleep(30)
                continue
            url = relay.rstrip("/") + "/hub?token=" + self.cfg.token
            try:
                async with aiohttp.ClientSession() as s:
                    async with s.ws_connect(url, heartbeat=25, timeout=15) as ws:
                        log.info("relay connected")
                        self.relay_ws = ws
                        delay = 2

                        async def reply(m):
                            await ws.send_str(json.dumps(m, ensure_ascii=False))
                        async for m in ws:
                            if m.type != aiohttp.WSMsgType.TEXT or m.data == "pong":
                                continue
                            try:
                                msg = json.loads(m.data)
                            except json.JSONDecodeError:
                                continue
                            if msg.get("t") == "clients":
                                self.relay_clients = int(msg.get("n", 0))
                            elif msg.get("t") == "hub":
                                continue
                            else:
                                await self.handle(msg, reply)
            except asyncio.CancelledError:
                raise
            except Exception as e:
                log.info("relay: %s", e)
            self.relay_ws = None
            self.relay_clients = 0
            await asyncio.sleep(delay)
            delay = min(60, delay * 2)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    cfg = Config(CONFIG_PATH)
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    hub = Hub(cfg)
    app = web.Application()
    app.router.add_get("/ws", hub.ws_handler)
    app.router.add_get("/api/info", hub.info_handler)
    app.router.add_get("/api/pair", hub.pair_handler)
    app.router.add_post("/api/login", hub.login_handler)
    app.router.add_get("/", hub.index_handler)
    try:
        import signal
        loop.add_signal_handler(signal.SIGUSR1, hub.open_pairing)
    except (NotImplementedError, AttributeError):
        pass
    loop.create_task(hub.relay_loop())
    loop.create_task(hub.rules.run())
    if cfg.data.get("mqtt"):
        from mqttbridge import MqttBridge
        hub.mqtt = MqttBridge(hub, cfg.data["mqtt"])
        hub.mqtt.start()
    log.info("IoT hub %s on :%d, relay %s", VERSION, PORT, cfg.relay or "—")
    web.run_app(app, port=PORT, loop=loop, print=None)


if __name__ == "__main__":
    main()
