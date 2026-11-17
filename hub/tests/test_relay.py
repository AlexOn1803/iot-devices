"""Хаб ↔ ретранслятор (wrangler dev на :8787) ↔ «телефон». Запуск: спершу `npx wrangler dev` у relay/."""
import asyncio, json, os, subprocess, sys, tempfile, time
import aiohttp
from aiohttp import web
sys.path.insert(0, os.path.dirname(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RELAY = os.environ.get("RELAY", "http://127.0.0.1:8787")
ESP = {"comps": [{"i": 0, "t": "relay", "name": "Реле", "on": False}]}


async def main():
    async def state(req): return web.json_response(ESP)
    async def set_(req):
        ESP["comps"][0]["on"] = bool((await req.json()).get("on")); return web.json_response({"ok": True})
    app = web.Application(); app.router.add_get("/api/state", state); app.router.add_post("/api/set", set_)
    r = web.AppRunner(app); await r.setup(); await web.TCPSite(r, "127.0.0.1", 17610).start()
    d = tempfile.mkdtemp()
    json.dump({"token": "t" * 32, "relay": RELAY}, open(d + "/config.json", "w"))
    hub = subprocess.Popen([sys.executable, os.path.join(ROOT, "hub/iothub.py")],
                           env=dict(os.environ, IOTHUB_CONFIG=d + "/config.json", IOTHUB_PORT="17620"),
                           stderr=subprocess.DEVNULL)
    await asyncio.sleep(3)
    ok = True
    try:
        async with aiohttp.ClientSession() as s:
            async with s.ws_connect(RELAY + "/app?token=" + "t" * 32) as ws:
                async def until(pred, timeout=10):
                    end = time.time() + timeout
                    while time.time() < end:
                        m = await ws.receive(timeout=end - time.time())
                        j = json.loads(m.data)
                        if pred(j): return j
                j = await until(lambda j: j.get("t") == "hub")
                print("hub online:", j); ok &= j["online"]
                await ws.send_str(json.dumps({"t": "sync", "id": 1, "devices": [{"key": "e1", "kind": "ESP", "ip": "127.0.0.1:17610"}]}))
                print("ack:", bool(await until(lambda j: j.get("t") == "ack")))
                await until(lambda j: j.get("t") == "state" and j["key"] == "e1" and j["state"]["online"])
                t0 = time.time()
                await ws.send_str(json.dumps({"t": "cmd", "id": 2, "key": "e1", "esp": {"i": 0, "on": True}}))
                j = await until(lambda j: j.get("t") == "state" and j["key"] == "e1" and j["state"]["state"]["comps"][0]["on"])
                print("через ретранслятор увімкнено за %d мс" % ((time.time() - t0) * 1000)); ok &= bool(j)
            async with s.ws_connect(RELAY + "/app?token=" + "x" * 32) as ws2:
                j = json.loads((await ws2.receive(timeout=5)).data)
                print("чужа кімната:", j); ok &= j == {"t": "hub", "online": False}
    finally:
        hub.terminate()
    print("ALL OK" if ok else "FAILED"); sys.exit(0 if ok else 1)

asyncio.run(main())
