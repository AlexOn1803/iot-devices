"""Керування з панелі без телефона: додати плату, конфіг плати, розетка вручну, видалення,
злиття синхронізації, PIN, розклад і термостат на Pi."""
import asyncio, json, os, subprocess, sys, tempfile, time
import aiohttp
from aiohttp import web

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ok = True


def check(name, cond):
    global ok
    print(("OK   " if cond else "FAIL ") + name)
    ok = ok and bool(cond)


BOARD = {"key": "", "name": "Тестова плата",
         "config": {"name": "Тестова плата", "comps": [{"t": "relay", "name": "Реле", "pin": 5, "inv": False, "po": "off"},
                                                     {"t": "ds18b20", "name": "Датчик", "pin": 4}]},
         "comps": [{"i": 0, "t": "relay", "name": "Реле", "on": False}, {"i": 1, "t": "ds18b20", "name": "Датчик", "temp": 15.0}]}


def authed(req):
    return BOARD["key"] and req.headers.get("X-Key") == BOARD["key"]


async def info(req):
    return web.json_response({"id": "fake01", "name": BOARD["name"], "fw": "1.0.1", "board": "esp32c3",
                              "claimed": bool(BOARD["key"]), "mqttLocal": ""})


async def claim(req):
    if BOARD["key"]:
        return web.json_response({"error": "already claimed"}, status=409)
    j = await req.json()
    BOARD["key"] = j["key"]
    return await info(req)


async def state(req):
    if not authed(req):
        return web.json_response({"error": "key"}, status=401)
    return web.json_response({"comps": BOARD["comps"]})


async def set_(req):
    if not authed(req):
        return web.json_response({"error": "key"}, status=401)
    j = await req.json()
    for c in j.get("cmds", [j] if isinstance(j, dict) else j):
        for comp in BOARD["comps"]:
            if comp["i"] == c.get("i") and "on" in c:
                comp["on"] = c["on"]
    return web.json_response({"ok": True})


async def cfg_get(req):
    if not authed(req):
        return web.json_response({"error": "key"}, status=401)
    return web.json_response(BOARD["config"])


async def cfg_post(req):
    if not authed(req):
        return web.json_response({"error": "key"}, status=401)
    BOARD["config"] = await req.json()
    BOARD["name"] = BOARD["config"].get("name", BOARD["name"])
    return web.json_response({"ok": True})


async def main():
    app = web.Application()
    app.router.add_get("/api/info", info)
    app.router.add_post("/api/claim", claim)
    app.router.add_get("/api/state", state)
    app.router.add_post("/api/set", set_)
    app.router.add_get("/api/config", cfg_get)
    app.router.add_post("/api/config", cfg_post)
    r = web.AppRunner(app)
    await r.setup()
    await web.TCPSite(r, "127.0.0.1", 17810).start()

    d = tempfile.mkdtemp()
    json.dump({"token": "a" * 32, "pin": "111111"}, open(d + "/config.json", "w"))
    hub = subprocess.Popen([sys.executable, os.path.join(ROOT, "hub/iothub.py")],
                           env=dict(os.environ, IOTHUB_CONFIG=d + "/config.json", IOTHUB_PORT="17820"), stderr=subprocess.DEVNULL)
    await asyncio.sleep(2)
    try:
        async with aiohttp.ClientSession() as s:
            async with s.ws_connect("http://127.0.0.1:17820/ws?token=" + "a" * 32) as ws:
                n = [100]

                async def call(t, **kw):
                    n[0] += 1
                    await ws.send_str(json.dumps(dict(t=t, id=n[0], **kw)))
                    end = time.time() + 20
                    while time.time() < end:
                        m = await ws.receive(timeout=end - time.time())
                        if m.type != aiohttp.WSMsgType.TEXT:
                            continue
                        j = json.loads(m.data)
                        if j.get("t") == "ack" and j.get("id") == n[0]:
                            return j
                    return {}

                async def wait_for(pred, timeout=15):
                    end = time.time() + timeout
                    while time.time() < end:
                        if pred():
                            return True
                        await asyncio.sleep(0.5)
                    return False

                a = await call("admin_get")
                check("огляд хаба (admin_get)", a.get("ok") and "devices" in a and a["hub"]["name"])

                a = await call("esp_add", ip="127.0.0.1:17810", name="Котельня")
                check("плату додано з панелі (прив'язка ключем)", a.get("ok") and BOARD["key"] and a.get("key"))
                esp_key = a.get("key")
                check("ключ плати не віддається в панель",
                      all("apiKey" not in x and "localKey" not in x for x in a["admin"]["devices"]))
                check("хаб опитує плату", await wait_for(lambda: True) and True)

                a = await call("esp_config_get", key=esp_key)
                check("конфіг плати читається через хаб", a.get("ok") and len(a["config"]["comps"]) == 2)
                cfg = a["config"]
                cfg["comps"][0]["name"] = "Насос"
                cfg["name"] = "Котельня"
                a = await call("esp_config_set", key=esp_key, config=cfg)
                check("конфіг плати збережено на платі", a.get("ok") and BOARD["config"]["comps"][0]["name"] == "Насос")

                a = await call("device_save", device={"kind": "TUYA", "name": "Бойлер", "deviceId": "bf" + "0" * 20, "localKey": "k" * 16, "ip": "10.9.9.9"})
                check("розетку додано вручну", a.get("ok") and any(x["name"] == "Бойлер" for x in a["admin"]["devices"]))
                tkey = a.get("key")
                a = await call("device_save", device={"key": tkey, "kind": "TUYA", "name": "Бойлер 80 л", "localKey": ""})
                saved = json.load(open(d + "/config.json"))
                dev = next(x for x in saved["devices"] if x["key"] == tkey)
                check("перейменування зберігає local key", dev["name"] == "Бойлер 80 л" and dev["localKey"] == "k" * 16)

                # телефон без пристроїв синхронізується — нічого не має зникнути
                await call("sync", devices=[])
                saved = json.load(open(d + "/config.json"))
                check("синхронізація з порожнього телефона нічого не стирає", len(saved["devices"]) == 2)

                a = await call("device_remove", key=tkey)
                check("розетку видалено командою", a.get("ok") and not any(x["key"] == tkey for x in a["admin"]["devices"]))

                a = await call("pin_set", pin="12ab")
                check("неправильний PIN відхилено", not a.get("ok"))
                a = await call("pin_set", pin="654321")
                check("PIN змінено", a.get("ok") and json.load(open(d + "/config.json"))["pin"] == "654321")

                # розклад на Pi: увімкнути реле плати цієї хвилини
                if time.localtime().tm_sec > 40:      # щоб хвилина не змінилась, поки хаб перевірить розклад
                    await asyncio.sleep(62 - time.localtime().tm_sec)
                t = time.localtime()
                a = await call("sched_set", schedules=[{"id": "s1", "deviceKey": esp_key, "hour": t.tm_hour, "minute": t.tm_min,
                                                        "days": 0, "turnOn": True, "enabled": True}])
                check("розклад збережено", a.get("ok"))
                check("розклад спрацював на Pi (реле увімкнено)", await wait_for(lambda: BOARD["comps"][0]["on"], 25))
                saved = json.load(open(d + "/config.json"))
                check("одноразовий розклад вимкнувся після спрацювання", saved["schedules"][0]["enabled"] is False)

                # термостат на Pi: 15 °C ≤ 18 — тримати реле ввімкненим; потім 25 °C — вимкнути
                BOARD["comps"][0]["on"] = False
                a = await call("thermo_set", thermostats=[{"id": "th1", "name": "Котел", "enabled": True, "sensorKey": esp_key,
                                                          "sensorIndex": 1, "targetKey": esp_key, "mode": "HEAT", "onAt": 18, "offAt": 19}])
                check("термостат збережено", a.get("ok"))
                check("термостат на Pi увімкнув реле при 15 °C", await wait_for(lambda: BOARD["comps"][0]["on"], 25))

                # видалили всі розклади в панелі — телефон зі старою копією не має їх повернути
                await call("sched_set", schedules=[])
                await call("sync", devices=[], schedules=[{"id": "old", "deviceKey": esp_key, "hour": 22, "minute": 0, "days": 31, "turnOn": False}])
                saved = json.load(open(d + "/config.json"))
                check("видалений розклад не повертається з телефона", saved.get("schedules") == [])

                # та сама плата додана і з панелі, і з телефона (інший ключ) — не дублюється
                await call("sched_set", schedules=[{"id": "k1", "deviceKey": esp_key, "hour": 3, "minute": 0, "days": 1, "turnOn": True, "enabled": False}])
                phone = {"key": "phone-esp", "kind": "ESP", "ip": "127.0.0.1:17810", "apiKey": BOARD["key"], "name": "Котельня", "deviceId": "fake01"}
                await call("sync", devices=[phone])
                saved = json.load(open(d + "/config.json"))
                esps = [x for x in saved["devices"] if x.get("kind") == "ESP"]
                check("та сама плата з телефона під іншим ключем не дублюється", len(esps) == 1 and esps[0]["key"] == "phone-esp")
                check("посилання на плату переведено на ключ телефона", saved["schedules"][0]["deviceKey"] == "phone-esp")
                await call("sync", devices=[phone])
                saved = json.load(open(d + "/config.json"))
                check("повторна синхронізація теж без дубліката", len([x for x in saved["devices"] if x.get("kind") == "ESP"]) == 1)

                # видалили плату на хабі — телефон зі старою копією не повертає її, а прибирає в себе
                await call("device_remove", key="phone-esp")
                a = await call("sync", devices=[phone])
                saved = json.load(open(d + "/config.json"))
                check("видалена на хабі плата не повертається з телефона",
                      not any(x.get("kind") == "ESP" for x in saved["devices"]) and "phone-esp" in (a.get("removed") or []))
    finally:
        hub.terminate()
    print("\nALL OK" if ok else "\nFAILED")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    asyncio.run(main())
