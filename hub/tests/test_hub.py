"""Перевірка хаба на імітаціях: розетки Tuya 3.3 і 3.4 (tools/tuya_test/fake_plug.py) і плата ESP.
Запуск: python3 hub/tests/test_hub.py  (потрібні aiohttp, tinytuya)"""
import asyncio, json, os, subprocess, sys, tempfile, time
import aiohttp
from aiohttp import web

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KEY = "0123456789abcdef"
ESP_STATE = {"comps": [{"i": 0, "t": "relay", "name": "Реле", "on": False}]}


async def fake_esp(port):
    async def state(req):
        if req.headers.get("X-Key") != "espkey":
            return web.Response(status=401)
        return web.json_response(ESP_STATE)

    async def set_(req):
        j = await req.json()
        ESP_STATE["comps"][0]["on"] = bool(j.get("on"))
        return web.json_response({"ok": True})
    app = web.Application()
    app.router.add_get("/api/state", state)
    app.router.add_post("/api/set", set_)
    r = web.AppRunner(app)
    await r.setup()
    await web.TCPSite(r, "127.0.0.1", port).start()


async def main():
    procs = []
    for port, ver in ((17501, "3.3"), (17502, "3.4"), (17503, "3.3")):
        procs.append(subprocess.Popen([sys.executable, os.path.join(ROOT, "tools/tuya_test/fake_plug.py"), str(port), ver, KEY],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    await fake_esp(17510)
    await asyncio.sleep(1)
    cfgdir = tempfile.mkdtemp()
    env = dict(os.environ, IOTHUB_CONFIG=cfgdir + "/config.json", IOTHUB_PORT="17520")
    hub = subprocess.Popen([sys.executable, os.path.join(ROOT, "hub/iothub.py")], env=env)
    procs.append(hub)
    await asyncio.sleep(2)
    token = json.load(open(cfgdir + "/config.json"))["token"]
    ok = True

    def check(name, cond):
        nonlocal ok
        print(("OK   " if cond else "FAIL ") + name, flush=True)
        ok &= bool(cond)

    try:
        async with aiohttp.ClientSession() as s:
            async with s.get("http://127.0.0.1:17520/api/info") as r:
                check("info без токена", (await r.json())["app"] == "iot-hub")
            async with s.get("http://127.0.0.1:17520/api/pair") as r:
                check("пара: токен у домашній мережі", (await r.json()).get("token") == token)
            async with s.ws_connect("http://127.0.0.1:17520/ws?token=bad") as ws:
                pass
    except aiohttp.WSServerHandshakeError as e:
        check("чужий токен відхилено", e.status == 401)

    states = {}
    async with aiohttp.ClientSession() as s:
        async with s.ws_connect("http://127.0.0.1:17520/ws?token=" + token) as ws:
            async def wait_for(pred, timeout=15):
                end = time.time() + timeout
                while time.time() < end:
                    try:
                        m = await ws.receive(timeout=end - time.time())
                    except asyncio.TimeoutError:
                        break
                    j = json.loads(m.data)
                    if j["t"] == "state":
                        states[j["key"]] = j["state"]
                    if j["t"] == "snapshot":
                        states.update(j["states"])
                    if pred(j):
                        return j
                return None

            hello = await wait_for(lambda j: j["t"] == "hello")
            check("hello", hello and hello["version"])
            # тестова розетка з іншим портом: tinytuya підключається на 6668, тож переадресуємо через ip:port не можна —
            # замість цього підміняємо порт у tinytuya через змінну оточення (див. нижче)
            await ws.send_str(json.dumps({"t": "sync", "id": 1, "devices": [
                {"key": "p33", "kind": "TUYA", "deviceId": "bf0000000000000000abcd", "localKey": KEY, "ip": "127.0.0.1:17501", "version": "3.3"},
                {"key": "p34", "kind": "TUYA", "deviceId": "bf0000000000000000abcd", "localKey": KEY, "ip": "127.0.0.1:17502", "version": "3.4"},
                {"key": "e1", "kind": "ESP", "ip": "127.0.0.1:17510", "apiKey": "espkey"},
                {"key": "sh", "kind": "TUYA", "deviceId": "bf0000000000000000abcd", "localKey": KEY, "ip": "127.0.0.1:17503", "version": "3.3", "shared": True},
            ]}))
            check("sync ack", await wait_for(lambda j: j["t"] == "ack" and j["id"] == 1))
            for k in ("p33", "p34"):
                if not (states.get(k) or {}).get("online"):
                    await wait_for(lambda j, k=k: j["t"] == "state" and j["key"] == k and j["state"]["online"])
                check(f"{k} online, dps", (states.get(k) or {}).get("dps", {}).get("1") is False)
                t0 = time.time()
                await ws.send_str(json.dumps({"t": "cmd", "id": 2, "key": k, "dps": {"1": True}}))
                j = await wait_for(lambda j, k=k: j["t"] == "state" and j["key"] == k and j["state"]["dps"].get("1") is True)
                check(f"{k} увімкнено за {int((time.time()-t0)*1000)} мс", j)
            if not (states.get("e1") or {}).get("online"):
                await wait_for(lambda j: j["t"] == "state" and j["key"] == "e1" and j["state"]["online"])
            check("ESP online", states.get("e1", {}).get("state", {}).get("comps", [{}])[0].get("on") is False)
            t0 = time.time()
            await ws.send_str(json.dumps({"t": "cmd", "id": 3, "key": "e1", "esp": {"i": 0, "on": True}}))
            j = await wait_for(lambda j: j["t"] == "state" and j["key"] == "e1" and j["state"]["state"]["comps"][0]["on"])
            check(f"ESP реле увімкнено за {int((time.time()-t0)*1000)} мс", j)
            if not (states.get("sh") or {}).get("online"):
                await wait_for(lambda j: j["t"] == "state" and j["key"] == "sh" and j["state"]["online"])
            t0 = time.time()
            await ws.send_str(json.dumps({"t": "cmd", "id": 5, "key": "sh", "dps": {"1": True}}))
            j = await wait_for(lambda j: j["t"] == "state" and j["key"] == "sh" and j["state"]["online"] and j["state"]["dps"].get("1") is True)
            check(f"спільна з ESP розетка (короткі сесії) увімкнена за {int((time.time()-t0)*1000)} мс", j)
            await ws.send_str(json.dumps({"t": "cmd", "id": 4, "key": "nope", "dps": {"1": True}}))
            j = await wait_for(lambda j: j["t"] == "ack" and j["id"] == 4)
            check("невідомий пристрій — помилка", j and not j["ok"])
    for p in procs:
        p.terminate()
    print("\nALL OK" if ok else "\nFAILED")
    sys.exit(0 if ok else 1)

asyncio.run(main())
