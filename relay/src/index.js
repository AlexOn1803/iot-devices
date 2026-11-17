// IoT Devices — ретранслятор WebSocket між хабом (Raspberry Pi) і додатком.
// Кімната = SHA-256 від токена хаба: хто не знає токена, не потрапить у кімнату.
// Повідомлення не зберігаються й не розбираються — лише пересилаються.
//   wss://…/hub?token=…  — Raspberry Pi (одне з'єднання)
//   wss://…/app?token=…  — телефони (скільки завгодно)

async function roomName(token) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("iot-hub:" + token));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.pathname === "/" || url.pathname === "/health") return new Response("iot-hub relay ok\n");
    const role = url.pathname.replace(/^\//, "");
    if (role !== "hub" && role !== "app") return new Response("not found", { status: 404 });
    if (req.headers.get("Upgrade") !== "websocket") return new Response("websocket expected", { status: 426 });
    const token = url.searchParams.get("token") || req.headers.get("X-Hub-Token") || "";
    if (token.length < 16) return new Response("bad token", { status: 401 });
    const id = env.ROOM.idFromName(await roomName(token));
    return env.ROOM.get(id).fetch(new Request("https://room/" + role, req));
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
    const role = new URL(req.url).pathname.slice(1);
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
    if (typeof msg !== "string" || msg.length > 256 * 1024) return;
    const role = this.state.getTags(ws)[0];
    if (msg === "ping") { ws.send("pong"); return; }
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
