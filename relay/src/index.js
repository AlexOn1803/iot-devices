// IoT Devices — ретранслятор WebSocket між хабом (Raspberry Pi) і додатком.
// Кімната = SHA-256 від токена хаба: хто не знає токена, не потрапить у кімнату.
// Повідомлення не зберігаються й не розбираються — лише пересилаються.
//   wss://…/hub?token=…  — Raspberry Pi (одне з'єднання)
//   wss://…/app?token=…  — телефони (скільки завгодно)
//   https://…/           — веб-панель (сторінку й хеш PIN-коду Pi надсилає сама при підключенні)

const COOKIE = "hub";

function cookie(req, name) {
  const m = (req.headers.get("Cookie") || "").match(new RegExp("(?:^|;\\s*)" + name + "=([^;]+)"));
  return m ? m[1] : "";
}

async function sha256(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Кімната хаба для веб-панелі: її ім'я записує сам хаб, коли підключається. */
async function panelRoom(env) {
  const dir = env.ROOM.get(env.ROOM.idFromName("directory"));
  const name = await (await dir.fetch("https://room/dir/get")).text();
  return name ? env.ROOM.get(env.ROOM.idFromName(name)) : null;
}

async function roomName(token) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("iot-hub:" + token));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.pathname === "/health") return new Response("iot-hub relay ok\n");
    if (url.pathname === "/" && req.method === "GET") {
      const room = await panelRoom(env);
      const html = room ? await (await room.fetch("https://room/panel")).text() : "";
      if (!html) return new Response("Raspberry Pi ще не підключалась до ретранслятора", { status: 503,
        headers: { "Content-Type": "text/plain; charset=utf-8" } });
      return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-cache" } });
    }
    if (url.pathname === "/api/login" && req.method === "POST") {
      const room = await panelRoom(env);
      if (!room) return Response.json({ error: "Raspberry Pi ще не підключалась" }, { status: 503 });
      return room.fetch(new Request("https://room/login", { method: "POST", body: await req.text() }));
    }
    if (url.pathname === "/ws") {
      // веб-панель: доступ за cookie, яку видає /api/login
      const room = await panelRoom(env);
      const sess = cookie(req, COOKIE);
      if (!room || !sess) return new Response("login", { status: 401 });
      const ok = await (await room.fetch(new Request("https://room/check", { method: "POST", body: sess }))).text();
      if (ok !== "1") return new Response("login", { status: 401 });
      if (req.headers.get("Upgrade") !== "websocket") return new Response("websocket expected", { status: 426 });
      return room.fetch(new Request("https://room/app", req));
    }
    const role = url.pathname.replace(/^\//, "");
    if (role !== "hub" && role !== "app") return new Response("not found", { status: 404 });
    if (req.headers.get("Upgrade") !== "websocket") return new Response("websocket expected", { status: 426 });
    const token = url.searchParams.get("token") || req.headers.get("X-Hub-Token") || "";
    if (token.length < 16) return new Response("bad token", { status: 401 });
    const name = await roomName(token);
    if (role === "hub") {
      const dir = env.ROOM.get(env.ROOM.idFromName("directory"));
      await dir.fetch(new Request("https://room/dir/set", { method: "POST", body: name }));
    }
    return env.ROOM.get(env.ROOM.idFromName(name)).fetch(new Request("https://room/" + role, req));
  },
};

export class Room {
  constructor(state) {
    this.state = state;
  }

  sockets(tag) {
    return this.state.getWebSockets(tag);
  }

  async fetch(req) {
    const path = new URL(req.url).pathname;
    const st = this.state.storage;
    if (path === "/dir/set") { await st.put("room", await req.text()); return new Response("ok"); }
    if (path === "/dir/get") return new Response((await st.get("room")) || "");
    if (path === "/panel") return new Response((await st.get("panel")) || "");
    if (path === "/check") {
      const a = await st.get("auth");
      return new Response(a && a.session && (await req.text()) === a.session ? "1" : "0");
    }
    if (path === "/login") {
      const now = Date.now();
      const fails = ((await st.get("fails")) || []).filter((t) => now - t < 300000);
      if (fails.length >= 10) return Response.json({ error: "Забагато спроб, зачекай 5 хв" }, { status: 429 });
      let pin = "";
      try { pin = String(JSON.parse(await req.text()).pin || "").trim(); } catch (_) {}
      const a = await st.get("auth");
      if (!a) return Response.json({ error: "Raspberry Pi ще не передала PIN — перевір, чи вона на зв'язку" }, { status: 503 });
      if ((await sha256("pin:" + pin)) !== a.pin) {
        fails.push(now); await st.put("fails", fails);
        return Response.json({ error: "Невірний PIN" }, { status: 403 });
      }
      return new Response(JSON.stringify({ ok: true }), { headers: {
        "Content-Type": "application/json",
        "Set-Cookie": `${COOKIE}=${a.session}; Path=/; Max-Age=31536000; HttpOnly; Secure; SameSite=Strict`,
      } });
    }
    const role = path.slice(1);
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    if (role === "hub") {
      // новий хаб витісняє старий (перепідключення після обриву)
      for (const old of this.sockets("hub")) try { old.close(4000, "replaced"); } catch (_) {}
    }
    this.state.acceptWebSocket(server, [role]);
    if (role === "hub") {
      server.send(JSON.stringify({ t: "clients", n: this.sockets("app").length }));
      this.broadcastApps({ t: "hub", online: true });
    } else {
      server.send(JSON.stringify({ t: "hub", online: this.sockets("hub").length > 0 }));
      this.toHub({ t: "clients", n: this.sockets("app").length });
      this.toHub({ t: "get" });   // хай хаб надішле новому клієнту повний стан
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  broadcastApps(msg) {
    const s = typeof msg === "string" ? msg : JSON.stringify(msg);
    for (const ws of this.sockets("app")) try { ws.send(s); } catch (_) {}
  }

  toHub(msg) {
    const s = typeof msg === "string" ? msg : JSON.stringify(msg);
    for (const ws of this.sockets("hub")) try { ws.send(s); } catch (_) {}
  }

  async webSocketMessage(ws, msg) {
    if (typeof msg !== "string" || msg.length > 512 * 1024) return;
    const role = this.state.getTags(ws)[0];
    if (msg === "ping") { ws.send("pong"); return; }
    if (role === "hub" && msg.startsWith('{"t": "panel_html"')) {
      try { await this.state.storage.put("panel", String(JSON.parse(msg).html)); } catch (_) {}
      return;
    }
    if (role === "hub" && msg.startsWith('{"t": "panel_auth"')) {
      // хеш PIN-коду й сесія веб-панелі — зберігаємо, нікому не пересилаємо
      try {
        const j = JSON.parse(msg);
        await this.state.storage.put("auth", { pin: String(j.pin), session: String(j.session) });
      } catch (_) {}
      return;
    }
    if (role === "hub") this.broadcastApps(msg);
    else if (this.sockets("hub").length) this.toHub(msg);
    else ws.send(JSON.stringify({ t: "hub", online: false }));
  }

  async webSocketClose(ws) {
    const role = this.state.getTags(ws)[0];
    if (role === "hub") {
      if (this.sockets("hub").filter((s) => s !== ws).length === 0) this.broadcastApps({ t: "hub", online: false });
    } else {
      this.toHub({ t: "clients", n: this.sockets("app").filter((s) => s !== ws).length });
    }
  }

  async webSocketError(ws) {
    await this.webSocketClose(ws);
  }
}
