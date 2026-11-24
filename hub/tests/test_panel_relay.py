"""Веб-панель поза домом через ретранслятор: сторінка, PIN, керування. Спершу `npx wrangler dev` у relay/."""
import asyncio, json, os, subprocess, sys, tempfile, time
import aiohttp
from aiohttp import web
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RELAY = os.environ.get("RELAY", "http://127.0.0.1:8787")
ESP = {"comps": [{"i": 0, "t": "relay", "name": "Реле", "on": False}]}
ok = True


def check(name, cond):
    global ok
    print(("OK   " if cond else "FAIL ") + name)
    ok = ok and bool(cond)


async def main():
    async def state(req): return web.json_response(ESP)
    async def set_(req):
        ESP["comps"][0]["on"] = bool((await req.json()).get("on")); return web.json_response({"ok": True})
    async def info(req): return web.json_response({})
    app = web.Application(); app.router.add_get("/api/state", state); app.router.add_post("/api/set", set_)
    app.router.add_get("/api/info", info)
    r = web.AppRunner(app); await r.setup(); await web.TCPSite(r, "127.0.0.1", 17710).start()
    d = tempfile.mkdtemp()
    json.dump({"token": "p" * 32, "relay": RELAY, "pin": "123456",
               "devices": [{"key": "e1", "kind": "ESP", "name": "Плата", "ip": "127.0.0.1:17710", "apiKey": "k"}]},
              open(d + "/config.json", "w"))
    hub = subprocess.Popen([sys.executable, os.path.join(ROOT, "hub/iothub.py")],
                           env=dict(os.environ, IOTHUB_CONFIG=d + "/config.json", IOTHUB_PORT="17720"),
                           stderr=subprocess.DEVNULL)
    await asyncio.sleep(4)
    try:
        async with aiohttp.ClientSession(cookie_jar=aiohttp.CookieJar(unsafe=True)) as s:
            async with s.get(RELAY + "/") as r:
                html = await r.text()
                check("сторінка панелі з ретранслятора", r.status == 200 and "<html" in html.lower())
            async with s.get(RELAY + "/ws") as r:
                check("без входу — 401", r.status == 401)
            async with s.post(RELAY + "/api/login", json={"pin": "000000"}) as r:
                check("невірний PIN — відмова", r.status == 403)
            async with s.post(RELAY + "/api/login", json={"pin": "123456"}) as r:
                check("вірний PIN — вхід", r.status == 200 and "hub" in r.headers.get("Set-Cookie", ""))
                sess = r.cookies["hub"].value
            hdr = {"Cookie": "hub=" + sess}
            async with s.ws_connect(RELAY.replace("http", "ws") + "/ws", headers=hdr) as ws:
                async def until(pred, timeout=10):
                    end = time.time() + timeout
                    while time.time() < end:
                        try:
                            m = await ws.receive(timeout=end - time.time())
                        except asyncio.TimeoutError:
                            return None
                        if m.type != aiohttp.WSMsgType.TEXT or m.data == "pong":
                            continue
                        j = json.loads(m.data)
                        if pred(j):
                            return j
                    return None
                check("панель отримала пристрої", await until(lambda j: j.get("t") == "hello" and j.get("devices")))
                await asyncio.sleep(3)   # хай хаб опитає плату
                t0 = time.time()
                await ws.send_str(json.dumps({"t": "cmd", "id": 1, "key": "e1", "esp": {"i": 0, "on": True}}))
                j = await until(lambda j: j.get("t") == "state" and j.get("key") == "e1"
                                and (j["state"].get("state") or {}).get("comps", [{}])[0].get("on"))
                check(f"реле увімкнено з панелі поза домом за {int((time.time()-t0)*1000)} мс", j and ESP["comps"][0]["on"])
    finally:
        hub.terminate()
    print("ALL OK" if ok else "FAILED")
    sys.exit(0 if ok else 1)

asyncio.run(main())
