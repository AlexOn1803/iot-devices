"""Керування хабом з веб-панелі (і з додатка): пристрої, плати ESP, інвертор, PIN, термостати.

Після цього телефон не потрібен: усе налаштовується й зберігається на Pi.
Повідомлення: {"t": "<дія>", "id": N, ...} → {"t": "ack", "id": N, "ok": true, ...} або {"ok": false, "error"}.
Секрети (local key розеток, ключі плат, пароль SmartESS) ніколи не надсилаються назад у панель.
"""
import asyncio
import hashlib
import json
import logging
import os
import re
import secrets
import shutil
import time

import aiohttp

log = logging.getLogger("iothub.admin")

REPO = "AlexOn1803/iot-devices"
PUBLIC = ("key", "kind", "name", "ip", "version", "deviceId", "dps", "measured", "shared", "board", "fw")


def device_public(d: dict) -> dict:
    out = {k: d[k] for k in PUBLIC if k in d}
    out["hasKey"] = bool(d.get("localKey") or d.get("apiKey"))
    return out


def cpu_temp():
    try:
        with open("/sys/class/thermal/thermal_zone0/temp") as f:
            return round(int(f.read().strip()) / 1000, 1)
    except (OSError, ValueError):
        return None


def posix_tz():
    """Часовий пояс Pi у форматі POSIX (останній рядок файлу зони), напр. EET-2EEST,M3.5.0/3,M10.5.0/4."""
    try:
        with open("/etc/localtime", "rb") as f:
            tail = f.read().rstrip(b"\n").rsplit(b"\n", 1)[-1].decode()
        if tail and len(tail) < 64 and not tail.startswith("TZif"):
            return tail
    except OSError:
        pass
    return "EET-2EEST,M3.5.0/3,M10.5.0/4"


def tuya_dps_from_mapping(mapping: dict) -> dict:
    """Номери DP і масштаби з опису пристрою в хмарі Tuya (як у додатку — DpMap)."""
    out = {"switch": 1, "countdown": 9, "energy": 17, "current": 18, "power": 19, "voltage": 20,
           "relayStatus": 38, "powerScale": 1, "voltageScale": 1, "currentScale": 3, "energyScale": 3}
    want = {"switch": ("switch_1", "switch"), "countdown": ("countdown_1", "countdown"), "energy": ("add_ele",),
            "current": ("cur_current",), "power": ("cur_power",), "voltage": ("cur_voltage",),
            "relayStatus": ("relay_status",)}
    by_code = {}
    for dp, m in (mapping or {}).items():
        if isinstance(m, dict) and m.get("code"):
            by_code[m["code"]] = (dp, m)
    for name, codes in want.items():
        for c in codes:
            if c in by_code:
                dp, m = by_code[c]
                try:
                    out[name] = int(dp)
                except ValueError:
                    continue
                vals = m.get("values") if isinstance(m.get("values"), dict) else {}
                if "scale" in vals and name + "Scale" in out:
                    out[name + "Scale"] = int(vals["scale"])
                break
    return out


class Admin:
    def __init__(self, hub):
        self.hub = hub
        self.cloud_cache = {}        # id -> пристрій з хмари Tuya (разом із local key — лишається на Pi)
        self.ota_busy = set()

    @property
    def data(self):
        return self.hub.cfg.data

    def changed(self, devices=True):
        self.hub.cfg.save()
        if devices:
            self.hub.apply_devices()
            self.hub.broadcast(self.hub.hello())
            self.hub.broadcast(self.hub.snapshot())

    async def handle(self, t, msg, reply) -> bool:
        fn = getattr(self, "on_" + t, None)
        if fn is None:
            return False
        try:
            res = await fn(msg) or {}
            await reply(dict({"t": "ack", "id": msg.get("id"), "ok": True}, **res))
        except Exception as e:
            log.info("%s: %s", t, e)
            await reply({"t": "ack", "id": msg.get("id"), "ok": False, "error": str(e) or type(e).__name__})
        return True

    # ------------------------------------------------------------ огляд
    def snapshot(self):
        inv = self.data.get("inverter") or {}
        tc = self.data.get("tuyaCloud") or {}
        mq = self.data.get("mqtt") or {}
        return {
            "devices": [device_public(d) for d in self.data.get("devices", [])],
            "inverter": {"user": inv.get("user", ""), "server": inv.get("server", "DESS"),
                         "devices": inv.get("devices", [])},
            "tuyaCloud": {"region": tc.get("region", "eu"), "accessId": tc.get("accessId", ""), "hasSecret": bool(tc.get("secret"))},
            "thermostats": self.data.get("thermostats", []),
            "thermoStatus": self.hub.thermo.status,
            "schedules": self.data.get("schedules", []),
            "hub": {"name": self.hub.cfg.name, "version": self.hub.version, "lan": self.hub.lan(), "relay": self.hub.cfg.relay,
                    "cpuTemp": cpu_temp(), "uptime": int(time.time() - self.hub.started),
                    "mqtt": bool(self.hub.mqtt and self.hub.mqtt.connected) if mq else None,
                    "channel": self.data.get("channel", "beta")},
        }

    async def on_admin_get(self, msg):
        return self.snapshot()

    # ------------------------------------------------------------ пристрої
    async def on_device_save(self, msg):
        d = dict(msg.get("device") or {})
        kind = "ESP" if d.get("kind") == "ESP" else "TUYA"
        key = d.get("key") or ("esp-" if kind == "ESP" else "tuya-") + secrets.token_hex(4)
        old = self.hub.device(key) or {}
        new = dict(old)
        for k, v in d.items():
            if k in ("localKey", "apiKey") and not v:
                continue            # порожнє поле — лишаємо старий секрет
            new[k] = v
        new["key"], new["kind"] = key, kind
        new["name"] = (new.get("name") or "").strip() or ("Плата" if kind == "ESP" else "Розетка")
        if kind == "TUYA":
            if not new.get("deviceId") or not new.get("localKey"):
                raise ValueError("Потрібні ID пристрою й local key")
            new.setdefault("dps", tuya_dps_from_mapping({}))
            new.setdefault("version", "3.3")
            new.setdefault("ip", "")
            dm = new.get("dps") or {}
            new["measured"] = [dm.get("current", 18), dm.get("power", 19), dm.get("voltage", 20)]
        else:
            if not new.get("ip") or not new.get("apiKey"):
                raise ValueError("Потрібні IP і ключ плати")
        devs = [x for x in self.data.get("devices", []) if x.get("key") != key]
        idx = next((i for i, x in enumerate(self.data.get("devices", [])) if x.get("key") == key), len(devs))
        devs.insert(idx, new)
        self.data["devices"] = devs
        self.changed()
        return {"key": key, "admin": self.snapshot()}

    async def on_device_remove(self, msg):
        key = msg.get("key", "")
        self.data["devices"] = [x for x in self.data.get("devices", []) if x.get("key") != key]
        dash = self.data.get("dash") or {}
        if isinstance(dash.get("order"), list):
            dash["order"] = [k for k in dash["order"] if k != key]
        self.changed()
        return {"admin": self.snapshot()}

    async def on_device_move(self, msg):
        keys = msg.get("keys") or []
        devs = self.data.get("devices", [])
        pos = {k: i for i, k in enumerate(keys)}
        self.data["devices"] = sorted(devs, key=lambda d: pos.get(d.get("key"), len(pos)))
        self.changed()
        return {"admin": self.snapshot()}

    # ------------------------------------------------------------ хмара Tuya (лише щоб отримати local key)
    async def on_tuya_cloud_list(self, msg):
        tc = dict(self.data.get("tuyaCloud") or {})
        for k in ("region", "accessId", "secret"):
            if msg.get(k):
                tc[k] = msg[k].strip()
        if not tc.get("accessId") or not tc.get("secret"):
            raise ValueError("Введи Access ID і Access Secret з iot.tuya.com")

        def fetch():
            import tinytuya
            c = tinytuya.Cloud(apiRegion=tc.get("region", "eu"), apiKey=tc["accessId"], apiSecret=tc["secret"],
                               configFile=os.devnull)
            if getattr(c, "error", None):
                raise IOError(json.dumps(c.error, ensure_ascii=False))
            r = c.getdevices(verbose=False, include_map=True)
            if isinstance(r, dict) and r.get("Error"):
                raise IOError(r.get("Payload") or r.get("Error"))
            return r

        devs = await asyncio.get_event_loop().run_in_executor(None, fetch)
        self.data["tuyaCloud"] = tc
        self.hub.cfg.save()
        self.cloud_cache = {d["id"]: d for d in devs if isinstance(d, dict) and d.get("id")}
        have = {d.get("deviceId") for d in self.data.get("devices", [])}
        return {"cloud": [{"id": d["id"], "name": d.get("name", ""), "category": d.get("category", ""),
                           "product": d.get("product_name", ""), "added": d["id"] in have}
                          for d in self.cloud_cache.values()]}

    async def on_tuya_cloud_add(self, msg):
        added = []
        for did in msg.get("ids") or []:
            c = self.cloud_cache.get(did)
            if not c or not c.get("key"):
                continue
            existing = next((d for d in self.data.get("devices", []) if d.get("deviceId") == did), None)
            dps = tuya_dps_from_mapping(c.get("mapping") or {})
            dev = {"key": existing["key"] if existing else "tuya-" + did[-8:], "kind": "TUYA", "deviceId": did,
                   "localKey": c["key"], "name": (existing or {}).get("name") or c.get("name") or "Розетка",
                   "ip": (existing or {}).get("ip", ""), "version": (existing or {}).get("version") or str(c.get("version") or "3.3"),
                   "dps": dps, "measured": [dps["current"], dps["power"], dps["voltage"]]}
            await self.on_device_save({"device": dev})
            added.append(dev["key"])
        return {"added": added, "admin": self.snapshot()}

    # ------------------------------------------------------------ плати ESP
    async def on_esp_scan(self, msg):
        """Плати в мережі (mDNS _iotdev._tcp) через avahi-browse."""
        if not shutil.which("avahi-browse"):
            raise IOError("Немає avahi-browse — онови хаб командою встановлення")
        p = await asyncio.create_subprocess_exec("avahi-browse", "-rtpk", "_iotdev._tcp",
                                                 stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
        try:
            out, _ = await asyncio.wait_for(p.communicate(), 8)
        except asyncio.TimeoutError:
            p.kill()
            out = b""
        found = {}
        for line in out.decode(errors="replace").splitlines():
            f = line.split(";")
            if len(f) > 9 and f[0] == "=" and f[2] == "IPv4":
                txt = dict(re.findall(r'"([^"=]+)=([^"]*)"', f[9]))
                found[f[7]] = {"name": f[3].replace("\\032", " "), "ip": f[7], "port": f[8], "id": txt.get("id", ""),
                               "fw": txt.get("fw", "")}
        have = {d.get("ip") for d in self.data.get("devices", [])} | {d.get("deviceId") for d in self.data.get("devices", [])}
        for v in found.values():
            v["added"] = v["ip"] in have or (v["id"] and v["id"] in have)
        return {"found": list(found.values())}

    async def _esp_call(self, ip, key, method, path, body=None, timeout=6):
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=timeout)) as s:
            async with s.request(method, "http://%s%s" % (ip, path), headers={"X-Key": key or ""},
                                 json=body if body is not None else None) as r:
                txt = await r.text()
                try:
                    j = json.loads(txt) if txt else {}
                except ValueError:
                    j = {"raw": txt}
                if r.status == 401:
                    raise IOError("Плата не прийняла ключ")
                if r.status >= 300:
                    raise IOError((j.get("error") if isinstance(j, dict) else None) or "Плата відповіла %d" % r.status)
                return j

    def _esp(self, key):
        d = self.hub.device(key)
        if not d or d.get("kind") != "ESP":
            raise KeyError("Плату не знайдено")
        return d

    async def on_esp_add(self, msg):
        ip = (msg.get("ip") or "").strip()
        if not ip:
            raise ValueError("Вкажи IP плати")
        info = await self._esp_call(ip, "", "GET", "/api/info")
        key = (msg.get("apiKey") or "").strip()
        if info.get("claimed"):
            if not key:
                raise ValueError("Плата вже прив'язана — введи її ключ (видно в додатку або скинь плату)")
        else:
            key = secrets.token_hex(8)
            info = await self._esp_call(ip, "", "POST", "/api/claim", {"key": key, "name": msg.get("name") or info.get("name", "")})
        await self._esp_call(ip, key, "GET", "/api/state")      # перевірити ключ
        existing = next((d for d in self.data.get("devices", []) if d.get("kind") == "ESP"
                         and (d.get("deviceId") == info.get("id") or d.get("ip") == ip)), None)
        dev = {"key": (existing or {}).get("key") or "esp-" + str(info.get("id") or secrets.token_hex(3)), "kind": "ESP",
               "ip": ip, "apiKey": key, "name": msg.get("name") or info.get("name") or "Плата",
               "deviceId": info.get("id", ""), "board": info.get("board", ""), "fw": info.get("fw", "")}
        return await self.on_device_save({"device": dev})

    async def on_esp_info(self, msg):
        d = self._esp(msg.get("key", ""))
        info = await self._esp_call(d["ip"], d.get("apiKey"), "GET", "/api/info")
        return {"info": info, "latest": await self.latest_fw()}

    async def on_esp_config_get(self, msg):
        d = self._esp(msg.get("key", ""))
        cfg = await self._esp_call(d["ip"], d.get("apiKey"), "GET", "/api/config")
        info = await self._esp_call(d["ip"], d.get("apiKey"), "GET", "/api/info")
        # ключі розеток Tuya не віддаємо в панель
        for c in cfg.get("comps", []) if isinstance(cfg, dict) else []:
            if c.get("t") == "tuya" and c.get("key"):
                c["key"] = ""
                c["hasKey"] = True
        return {"config": cfg, "info": info}

    async def on_esp_config_set(self, msg):
        d = self._esp(msg.get("key", ""))
        cfg = msg.get("config") or {}
        old = await self._esp_call(d["ip"], d.get("apiKey"), "GET", "/api/config")
        old_tuya = [c for c in old.get("comps", []) if c.get("t") == "tuya"]
        for c in cfg.get("comps", []):
            c.pop("hasKey", None)
            if c.get("t") != "tuya":
                continue
            src = self.hub.device(c.get("dev", ""))
            if src:   # розетку вибрали зі списку хаба — підставляємо її параметри
                c.update({"id": src.get("deviceId", ""), "key": src.get("localKey", ""), "ip": src.get("ip", ""),
                          "ver": src.get("version", "3.3"), "dp": (src.get("dps") or {}).get("switch", 1)})
            elif not c.get("key"):
                prev = next((o for o in old_tuya if o.get("id") == c.get("id")), None)
                c["key"] = (prev or {}).get("key", "")
        cfg.setdefault("tz", posix_tz())    # годинник плати — за місцевим часом Pi (для розкладу)
        res = await self._esp_call(d["ip"], d.get("apiKey"), "POST", "/api/config", cfg, timeout=10)
        if cfg.get("name") and cfg["name"] != d.get("name"):
            await self.on_device_save({"device": {"key": d["key"], "kind": "ESP", "name": cfg["name"]}})
        if d["key"] in self.hub.esp:
            self.hub.esp[d["key"]].kick.set()
        return {"result": res}

    async def on_esp_reboot(self, msg):
        d = self._esp(msg.get("key", ""))
        try:
            await self._esp_call(d["ip"], d.get("apiKey"), "POST", "/api/reboot", {})
        except (aiohttp.ClientError, asyncio.TimeoutError):
            pass   # плата перезавантажується й рве з'єднання
        return {}

    async def latest_fw(self):
        ch = self.data.get("channel", "beta")
        url = "https://github.com/%s/releases/download/%s/version.json" % (REPO, ch)
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
                async with s.get(url) as r:
                    return (json.loads(await r.text())).get("fw")
        except Exception:
            return None

    async def on_esp_ota(self, msg):
        """Pi сама завантажує прошивку з GitHub і надсилає на плату."""
        d = self._esp(msg.get("key", ""))
        if d["key"] in self.ota_busy:
            raise IOError("Оновлення вже йде")
        self.ota_busy.add(d["key"])
        try:
            info = await self._esp_call(d["ip"], d.get("apiKey"), "GET", "/api/info")
            board = info.get("board") or ""
            asset = "iot-%s-ota.bin" % board if board and board != "unknown" else "esp32c3-ota.bin"
            url = "https://github.com/%s/releases/download/%s/%s" % (REPO, self.data.get("channel", "beta"), asset)
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=120)) as s:
                async with s.get(url) as r:
                    if r.status >= 300:
                        raise IOError("Не вдалося завантажити прошивку (%d)" % r.status)
                    blob = await r.read()
                if not blob or blob[0] not in (0xE9, 0x1F):
                    raise IOError("Завантажено не прошивку")
                form = aiohttp.FormData()
                form.add_field("firmware", blob, filename="firmware.bin", content_type="application/octet-stream")
                async with s.post("http://%s/update" % d["ip"], data=form, headers={"X-Key": d.get("apiKey", "")}) as r:
                    txt = await r.text()
                    if r.status >= 300:
                        raise IOError("Плата не прийняла прошивку: %s" % txt[:120])
            if d["key"] in self.hub.esp:
                self.hub.esp[d["key"]].provisioned = False   # нова прошивка — знову надіслати брокер Pi
            return {"board": board, "bytes": len(blob)}
        finally:
            self.ota_busy.discard(d["key"])

    # ------------------------------------------------------------ інвертор SmartESS
    async def on_inv_login(self, msg):
        InverterWorker = self.hub.InverterWorker
        user = (msg.get("user") or "").strip()
        pwd = msg.get("password") or ""
        if not user or not pwd:
            raise ValueError("Введи логін і пароль SmartESS")
        acc = {"server": msg.get("server") or "DESS", "user": user, "pwdSha1": hashlib.sha1(pwd.encode()).hexdigest(),
               "companyKey": (msg.get("companyKey") or "").strip() or "bnrl_frRFjEz8Mkn", "devices": []}
        w = InverterWorker.__new__(InverterWorker)
        w.hub, w.acc, w.token = self.hub, acc, None
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=20)) as s:
            await w.login(s)
            out = []
            plants = ((await w.request(s, "queryPlants", [("pagesize", 50)])).get("dat") or {}).get("plant") or []
            for p in plants:
                pid = p.get("pid")
                for page in range(10):
                    try:
                        dat = (await w.request(s, "webQueryCollectorsEs", [("pid", pid), ("page", page), ("pagesize", 50)])).get("dat") or {}
                    except IOError:
                        break
                    cols = dat.get("collector") or []
                    for c in cols:
                        pn = c.get("pn")
                        if not pn or any(x["pn"] == pn for x in out):
                            continue
                        try:
                            devs = ((await w.request(s, "queryCollectorDevices", [("pn", pn)])).get("dat") or {}).get("dev") or []
                        except IOError:
                            continue
                        for o in devs:
                            if o.get("sn"):
                                out.append({"pn": pn, "sn": o["sn"], "devcode": int(o.get("devcode") or 0),
                                            "devaddr": int(o.get("devaddr") or 0),
                                            "alias": o.get("devalias") or o.get("alias") or "Інвертор " + o["sn"][-4:]})
                    if len(cols) < 50:
                        break
        old = {d["sn"]: d for d in (self.data.get("inverter") or {}).get("devices", [])}
        for d in out:
            if d["sn"] in old and old[d["sn"]].get("alias"):
                d["alias"] = old[d["sn"]]["alias"]
        acc["devices"] = out
        self.data["inverter"] = acc
        self.changed()
        return {"found": len(out), "admin": self.snapshot()}

    async def on_inv_rename(self, msg):
        inv = self.data.get("inverter") or {}
        for d in inv.get("devices", []):
            if d["sn"] == msg.get("sn"):
                d["alias"] = (msg.get("alias") or "").strip() or d["alias"]
        self.changed()
        return {"admin": self.snapshot()}

    async def on_inv_logout(self, msg):
        self.data.pop("inverter", None)
        self.changed()
        return {"admin": self.snapshot()}

    # ------------------------------------------------------------ хаб
    async def on_pin_set(self, msg):
        pin = str(msg.get("pin") or "").strip()
        if not re.fullmatch(r"\d{6}", pin):
            raise ValueError("PIN — рівно 6 цифр")
        self.data["pin"] = pin
        self.hub.cfg.save()
        await self.hub.send_panel_auth()
        return {}

    async def on_channel_set(self, msg):
        ch = msg.get("channel")
        if ch not in ("beta", "latest"):
            raise ValueError("Канал: beta або latest")
        self.data["channel"] = ch
        self.hub.cfg.save()
        return {"admin": self.snapshot()}

    async def on_thermo_set(self, msg):
        lst = msg.get("thermostats")
        if not isinstance(lst, list):
            raise ValueError("Немає списку термостатів")
        self.data["thermostats"] = lst
        self.hub.cfg.save()
        return {"admin": self.snapshot()}

    async def on_sched_set(self, msg):
        lst = msg.get("schedules")
        if not isinstance(lst, list):
            raise ValueError("Немає списку розкладів")
        self.data["schedules"] = lst
        self.hub.cfg.save()
        return {"admin": self.snapshot()}

    # ------------------------------------------------------------ прошивка через USB
    FW_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fw")
    CHIP_BOARD = {"ESP32-C3": "esp32c3", "ESP32-S3": "esp32s3", "ESP32-S2": "esp32s2", "ESP32": "esp32", "ESP8266": "esp8266"}

    async def fw_file(self, board):
        """Повна прошивка (з завантажувачем) з GitHub, кешується на Pi на годину."""
        if board not in ("esp32c3", "esp32", "esp32s3", "esp32s2", "esp8266", "esp01"):
            raise ValueError("Невідома плата: %s" % board)
        ch = self.data.get("channel", "beta")
        os.makedirs(self.FW_DIR, exist_ok=True)
        path = os.path.join(self.FW_DIR, "%s-%s-full.bin" % (ch, board))
        if os.path.exists(path) and time.time() - os.path.getmtime(path) < 3600:
            return path
        url = "https://github.com/%s/releases/download/%s/iot-%s-full.bin" % (REPO, ch, board)
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=120)) as s:
            async with s.get(url) as r:
                if r.status >= 300:
                    raise IOError("Не вдалося завантажити прошивку (%d)" % r.status)
                blob = await r.read()
        if len(blob) < 100000 or blob[0] != 0xE9:
            raise IOError("Завантажено не прошивку")
        with open(path + ".tmp", "wb") as f:
            f.write(blob)
        os.replace(path + ".tmp", path)
        return path

    async def on_fw_get(self, msg):
        """Шматок прошивки для браузерної прошивки (Web Serial) — працює й через ретранслятор."""
        import base64
        path = await self.fw_file(msg.get("board", ""))
        off, n = int(msg.get("off", 0)), 192 * 1024
        with open(path, "rb") as f:
            f.seek(off)
            chunk = f.read(n)
        return {"size": os.path.getsize(path), "off": off, "data": base64.b64encode(chunk).decode()}

    async def on_usb_ports(self, msg):
        try:
            from serial.tools import list_ports
            ports = [{"port": p.device, "desc": p.description or "", "vid": p.vid} for p in list_ports.comports()
                     if "ttyUSB" in p.device or "ttyACM" in p.device]
        except ImportError:
            import glob
            ports = [{"port": p, "desc": ""} for p in sorted(glob.glob("/dev/ttyUSB*") + glob.glob("/dev/ttyACM*"))]
        return {"ports": ports}

    async def _esptool(self, args, progress=None, timeout=300):
        import sys
        p = await asyncio.create_subprocess_exec(sys.executable, "-m", "esptool", *args,
                                                 stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
        out, buf = [], b""
        async def read():
            nonlocal buf
            while True:
                ch = await p.stdout.read(256)
                if not ch:
                    break
                buf += ch
                parts = re.split(rb"[\r\n]", buf)
                buf = parts.pop()
                for line in parts:
                    t = line.decode(errors="replace").strip()
                    if not t:
                        continue
                    out.append(t)
                    if progress:
                        progress(t)
        try:
            await asyncio.wait_for(asyncio.gather(read(), p.wait()), timeout)
        except asyncio.TimeoutError:
            p.kill()
            raise IOError("esptool не відповідає")
        if buf:
            out.append(buf.decode(errors="replace").strip())
        if p.returncode != 0:
            tail = [l for l in out if l][-3:]
            raise IOError("Не вдалося: " + " / ".join(tail))
        return "\n".join(out)

    async def on_usb_flash(self, msg):
        """Прошити плату, під'єднану до USB Raspberry Pi."""
        port = msg.get("port") or ""
        if not re.fullmatch(r"/dev/tty(USB|ACM)\d+", port):
            raise ValueError("Вибери порт плати")
        if getattr(self, "_flashing", False):
            raise IOError("Прошивка вже йде")
        self._flashing = True
        try:
            def say(text, pct=None):
                self.hub.broadcast({"t": "flash", "text": text, "pct": pct})
            say("Визначаю плату…")
            info = await self._esptool(["--port", port, "flash_id"], timeout=60)
            m = re.search(r"Chip is (ESP32-C3|ESP32-S3|ESP32-S2|ESP32|ESP8266)", info) or \
                re.search(r"Detecting chip type[.\s]*(ESP32-C3|ESP32-S3|ESP32-S2|ESP32|ESP8266)", info)
            if not m:
                raise IOError("Не вдалося визначити плату")
            board = self.CHIP_BOARD[m.group(1)]
            size = re.search(r"Detected flash size: (\d+)MB", info)
            if board == "esp8266" and size and int(size.group(1)) <= 1:
                board = "esp01"
            say("Плата %s — завантажую прошивку…" % board)
            path = await self.fw_file(board)
            say("Прошиваю…", 0)
            last = [-1]

            def prog(line):
                mm = re.search(r"\((\d+) ?%\)", line)
                if mm and int(mm.group(1)) != last[0]:
                    last[0] = int(mm.group(1))
                    say("Прошиваю… %d%%" % last[0], last[0])
            args = ["--port", port, "-b", "460800", "write_flash"] + (["--erase-all"] if msg.get("erase") else []) + ["0x0", path]
            await self._esptool(args, prog, timeout=400)
            say("Готово — плата перезапускається", 100)
            return {"board": board}
        finally:
            self._flashing = False

    # ------------------------------------------------------------ оновлення хаба й консоль
    def _installed(self):
        try:
            with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "VERSION.json")) as f:
                return json.load(f)
        except (OSError, ValueError):
            return {}

    async def _gh(self, s, path):
        async with s.get("https://api.github.com/repos/%s/%s" % (REPO, path), headers={"Accept": "application/vnd.github+json"}) as r:
            if r.status >= 300:
                raise IOError("GitHub відповів %d" % r.status)
            return await r.json()

    async def on_hub_update_check(self, msg):
        inst = self._installed()
        branch = "main" if self.data.get("channel") == "latest" else "dev"
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=20)) as s:
            head = await self._gh(s, "commits/" + branch)
            latest = head["sha"]
            changes = []
            if inst.get("sha") and re.fullmatch(r"[0-9a-f]{40}", inst["sha"]) and inst["sha"] != latest:
                try:
                    cmp = await self._gh(s, "compare/%s...%s" % (inst["sha"], latest))
                    changes = [c["commit"]["message"].split("\n")[0] for c in cmp.get("commits", [])][-30:]
                except IOError:
                    pass
        return {"installed": inst, "branch": branch, "latest": latest,
                "latestDate": head.get("commit", {}).get("committer", {}).get("date"),
                "upToDate": inst.get("sha") == latest, "changes": changes}

    async def on_hub_update(self, msg):
        sha = msg.get("sha") or ""
        if not re.fullmatch(r"[0-9a-f]{7,40}", sha):
            raise ValueError("Невідома версія")
        with open("/etc/iot-hub/update-target", "w") as f:
            f.write(sha)
        p = await asyncio.create_subprocess_exec("sudo", "-n", "/usr/bin/systemctl", "start", "--no-block", "iot-hub-update.service",
                                                 stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
        out, _ = await p.communicate()
        if p.returncode != 0:
            raise IOError("Не вдалося запустити оновлення (%s). Один раз онови хаб командою встановлення по SSH." % out.decode(errors="replace").strip()[:160])
        log.warning("hub update to %s started from panel", sha)
        return {}

    async def on_hub_update_log(self, msg):
        try:
            with open("/var/log/iot-hub-update.log", encoding="utf-8", errors="replace") as f:
                lines = f.read().splitlines()[-80:]
        except OSError:
            lines = []
        # прибрати кольорові коди терміналу
        return {"log": "\n".join(re.sub(r"\x1b\[[0-9;]*m", "", l) for l in lines)}

    @staticmethod
    def _root_allowed():
        return os.path.exists("/etc/sudoers.d/iot-hub-console")

    async def on_console(self, msg):
        cmd = (msg.get("cmd") or "").strip()
        if not cmd:
            raise ValueError("Порожня команда")
        root = bool(msg.get("root"))
        if root and not self._root_allowed():
            raise PermissionError("Root вимкнено. Дозволити можна лише на самій Pi: sudo iot-hub-console-root on")
        if re.search(r"hub/install\.sh|systemctl\s+(restart|stop)\s+iot-hub\b", cmd):
            raise ValueError("Ця команда перезапускає хаб і обірветься разом з консоллю. Для оновлення — кнопка «Оновити хаб» вище.")
        log.warning("console%s: %s", " (root)" if root else "", cmd)
        argv = (["sudo", "-n", "/bin/bash", "-c", cmd] if root else ["/bin/bash", "-c", cmd])
        p = await asyncio.create_subprocess_exec(*argv, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
                                                 stdin=asyncio.subprocess.DEVNULL, cwd="/tmp")
        try:
            out, _ = await asyncio.wait_for(p.communicate(), int(msg.get("timeout") or 60))
            code = p.returncode
        except asyncio.TimeoutError:
            p.kill()
            out, code = "(час вийшов — команду зупинено)".encode(), -1
        text = re.sub(r"\x1b\[[0-9;?]*[A-Za-z]", "", out.decode(errors="replace"))   # кольори терміналу
        if len(text) > 60000:
            text = "…\n" + text[-60000:]
        return {"out": text, "code": code, "rootAllowed": self._root_allowed()}

    async def on_console_info(self, msg):
        return {"rootAllowed": self._root_allowed(), "user": os.environ.get("USER") or "iothub"}
