"""Хаб як сервер: правила на Pi, плата через MQTT-брокер Pi, вхід у веб-панель PIN-кодом.
Потрібні: aiohttp, tinytuya, paho-mqtt, amqtt (брокер для тесту)."""
import asyncio, json, os, subprocess, sys, tempfile, time
import aiohttp
from aiohttp import web
import paho.mqtt.client as mqtt

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KEY = "0123456789abcdef"
ok = True


def check(name, cond):
    global ok
    print(("OK   " if cond else "FAIL ") + name, flush=True)
    ok &= bool(cond)


async def main():
    procs = []
    # брокер MQTT (на Pi це Mosquitto)
    bconf = tempfile.mktemp(suffix=".yaml")
    open(bconf, "w").write("listeners:\n  default:\n    type: tcp\n    bind: 127.0.0.1:17883\nauth:\n  allow-anonymous: true\n")
    procs.append(subprocess.Popen(["amqtt", "-c", bconf], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    procs.append(subprocess.Popen([sys.executable, os.path.join(ROOT, "tools/tuya_test/fake_plug.py"), "17701", "3.3", KEY],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    await asyncio.sleep(2)

    # «плата ESP»: HTTP API + публікує стан у MQTT і виконує команди з /set
    esp = {"comps": [{"i": 0, "t": "relay", "name": "Реле", "on": False}]}
    got_cmds = []
    pub = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="fake-esp")
    def on_msg(c, u, m):
        j = json.loads(m.payload)
        got_cmds.append(j)
        if j.get("key") == "espkey":
            for cmd in j["cmds"]:
                esp["comps"][0]["on"] = bool(cmd.get("on"))
            c.publish("iotdev/abc123/state", json.dumps(esp))
    pub.on_message = on_msg
    pub.connect("127.0.0.1", 17883); pub.subscribe("iotdev/abc123/set"); pub.loop_start()

    async def state(req): return web.json_response(esp)
    provisioned = []
    async def info(req): return web.json_response({"mqttHost": "cloud.example", "mqttLocal": ""})
    async def set_mqtt(req):
        provisioned.append(await req.json()); return web.json_response({})
    app = web.Application(); app.router.add_get("/api/state", state); app.router.add_get("/api/info", info)
    app.router.add_post("/api/mqtt", set_mqtt)
    r = web.AppRunner(app); await r.setup(); await web.TCPSite(r, "127.0.0.1", 17710).start()

    d = tempfile.mkdtemp()
    json.dump({"token": "t" * 32, "mqtt": {"host": "127.0.0.1", "port": 17883}}, open(d + "/config.json", "w"))
    hub = subprocess.Popen([sys.executable, os.path.join(ROOT, "hub/iothub.py")],
                           env=dict(os.environ, IOTHUB_CONFIG=d + "/config.json", IOTHUB_PORT="17720"))
    procs.append(hub)
    await asyncio.sleep(2.5)
    cfg = json.load(open(d + "/config.json"))
    try:
        async with aiohttp.ClientSession() as s:
            # веб-панель
            async with s.get("http://127.0.0.1:17720/") as r:
                check("веб-панель віддається", r.status == 200 and "IoT Хаб" in await r.text())
            async with s.post("http://127.0.0.1:17720/api/login", json={"pin": "000000x"}) as r:
                check("неправильний PIN відхилено", r.status == 403)
            async with s.post("http://127.0.0.1:17720/api/login", json={"pin": cfg["pin"]}) as r:
                check("PIN приймається, видає сесію", r.status == 200 and "hub" in r.cookies)

            async with s.ws_connect("http://127.0.0.1:17720/ws?token=" + "t" * 32) as ws:
                states, msgs = {}, []

                async def until(pred, timeout=12):
                    end = time.time() + timeout
                    while time.time() < end:
                        try:
                            m = await ws.receive(timeout=end - time.time())
                        except asyncio.TimeoutError:
                            return None
                        j = json.loads(m.data); msgs.append(j)
                        if j["t"] == "state": states[j["key"]] = j["state"]
                        if j["t"] == "snapshot": states.update(j["states"])
                        if pred(j): return j
                    return None

                await ws.send_str(json.dumps({"t": "sync", "id": 1, "devices": [
                    {"key": "plug", "kind": "TUYA", "name": "Бойлер", "deviceId": "bf0000000000000000abcd", "localKey": KEY,
                     "ip": "127.0.0.1:17701", "version": "3.3", "dps": {"switch": 1}},
                    {"key": "esp", "kind": "ESP", "name": "Плата", "deviceId": "abc123", "ip": "127.0.0.1:17710", "apiKey": "espkey"},
                ]}))
                h = await until(lambda j: j["t"] == "hello" and len(j.get("devices", [])) == 2)
                check("hello з переліком пристроїв", h and h["devices"][0]["name"] in ("Бойлер", "Плата"))
                # плата публікує стан через брокер — хаб має прийняти його без опитування
                await asyncio.sleep(1)
                esp["comps"][0]["on"] = True
                pub.publish("iotdev/abc123/state", json.dumps(esp))
                j = await until(lambda j: j["t"] == "state" and j["key"] == "esp" and j["state"].get("via") == "mqtt"
                                and j["state"]["state"]["comps"][0]["on"] is True)
                check("стан плати прийшов через MQTT", j)
                check("плата отримала брокер Pi як основний (хмарний — резерв)",
                      any(p.get("slot") == "local" and p.get("port") == 17883 for p in provisioned))
                # команда — через MQTT із ключем плати
                t0 = time.time()
                await ws.send_str(json.dumps({"t": "switch", "id": 2, "key": "esp", "on": False}))
                j = await until(lambda j: j["t"] == "state" and j["key"] == "esp" and j["state"]["state"]["comps"][0]["on"] is False)
                check(f"команда плати через MQTT ({int((time.time()-t0)*1000)} мс)", j and got_cmds and got_cmds[-1]["key"] == "espkey")

                # розетка: значення рахує хаб
                if not (states.get("plug") or {}).get("online"):
                    await until(lambda j: j["t"] == "state" and j["key"] == "plug" and j["state"]["online"])
                check("розетка: значення (vals) від хаба", (states.get("plug") or {}).get("vals", {}).get("on") is False)

                # правило на хабі: якщо реле плати увімкнулось → увімкнути розетку
                rule = {"id": "r1", "name": "Тест", "enabled": True, "all": True, "hold": 0, "cooldown": 0,
                        "conds": [{"d": "esp", "m": "c0:on", "op": "EQ", "v": "так"}],
                        "actions": [{"t": "ON", "d": "plug", "c": -1}], "from": -1, "to": -1}
                await ws.send_str(json.dumps({"t": "rules_set", "id": 3, "rules": [rule]}))
                check("правила збережено", await until(lambda j: j["t"] == "rules" and len(j["rules"]) == 1))
                esp["comps"][0]["on"] = True
                pub.publish("iotdev/abc123/state", json.dumps(esp))
                j = await until(lambda j: j["t"] == "state" and j["key"] == "plug" and j["state"].get("vals", {}).get("on") is True, 15)
                check("правило спрацювало на хабі: розетку увімкнено", j)
                rs = await until(lambda j: j["t"] == "rule_status" and "Спрацювало" in j["status"]["text"], 5) \
                    or next((m for m in msgs if m["t"] == "rule_status" and "Спрацювало" in m["status"]["text"]), None)
                check("статус правила «Спрацювало»", rs)
        saved = json.load(open(d + "/config.json"))
        check("правила й пристрої збережено на диску", saved.get("rules") and len(saved.get("devices", [])) == 2)
    finally:
        pub.loop_stop()
        for p in procs:
            p.terminate()
    print("\nALL OK" if ok else "\nFAILED")
    sys.exit(0 if ok else 1)

asyncio.run(main())
