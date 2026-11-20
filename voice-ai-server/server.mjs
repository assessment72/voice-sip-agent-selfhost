import http from "node:http";
import { timingSafeEqual } from "node:crypto";
import { WebSocket, WebSocketServer } from "ws";

const PORT = Number(process.env.PORT ?? 8787);
const API_KEY = process.env.OPENAI_API_KEY ?? "";
const GATEWAY_TOKEN = process.env.VOICE_GATEWAY_TOKEN ?? "";
const MODEL = process.env.OPENAI_REALTIME_MODEL ?? "gpt-realtime-2.1";
const MAX_CONNECTIONS = Number(process.env.MAX_VOICE_CONNECTIONS ?? 10);
const MAX_SESSION_MS = Number(process.env.MAX_SESSION_MS ?? 30 * 60 * 1000);
const MAX_PAYLOAD = 1024 * 1024;

const activeByIp = new Map();
const active = new Set();

function safeEqual(a, b) {
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  return left.length === right.length && timingSafeEqual(left, right);
}

function authorized(req) {
  const header = req.headers.authorization ?? "";
  const prefix = "Bearer ";
  return header.startsWith(prefix) && GATEWAY_TOKEN.length > 0 &&
    safeEqual(header.slice(prefix.length), GATEWAY_TOKEN);
}

function json(res, status, body) {
  res.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
  res.end(JSON.stringify(body));
}

const server = http.createServer((req, res) => {
  if (req.method === "GET" && req.url === "/healthz") {
    return json(res, API_KEY && GATEWAY_TOKEN ? 200 : 503, {
      ok: Boolean(API_KEY && GATEWAY_TOKEN),
      service: "voice-realtime-relay"
    });
  }
  json(res, 404, { error: "not_found" });
});

const clients = new WebSocketServer({ noServer: true, maxPayload: MAX_PAYLOAD, perMessageDeflate: false });

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url ?? "/", "http://localhost");
  const ip = req.socket.remoteAddress ?? "unknown";
  if (url.pathname !== "/v1/realtime") {
    socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }
  if (!API_KEY || !GATEWAY_TOKEN || !authorized(req)) {
    socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }
  const count = activeByIp.get(ip) ?? 0;
  if (active.size >= MAX_CONNECTIONS || count >= 3) {
    socket.write("HTTP/1.1 429 Too Many Requests\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }
  clients.handleUpgrade(req, socket, head, (ws) => clients.emit("connection", ws, req, ip));
});

clients.on("connection", (client, req, ip) => {
  active.add(client);
  activeByIp.set(ip, (activeByIp.get(ip) ?? 0) + 1);
  const upstreamUrl = new URL("wss://api.openai.com/v1/realtime");
  upstreamUrl.searchParams.set("model", MODEL);
  const upstream = new WebSocket(upstreamUrl, {
    headers: { Authorization: `Bearer ${API_KEY}` },
    maxPayload: MAX_PAYLOAD,
    perMessageDeflate: false
  });
  let closed = false;
  const timeout = setTimeout(() => closeBoth(1000, "session_timeout"), MAX_SESSION_MS);
  timeout.unref();

  function closeBoth(code = 1000, reason = "closed") {
    if (closed) return;
    closed = true;
    clearTimeout(timeout);
    if (client.readyState === WebSocket.OPEN) client.close(code, reason);
    if (upstream.readyState === WebSocket.OPEN) upstream.close(code, reason);
    else if (upstream.readyState === WebSocket.CONNECTING) upstream.terminate();
  }

  client.on("message", (data, isBinary) => {
    if (isBinary) return closeBoth(1003, "text_events_only");
    if (upstream.readyState === WebSocket.OPEN) upstream.send(data);
    else closeBoth(1011, "upstream_unavailable");
  });
  upstream.on("message", (data, isBinary) => {
    if (client.readyState === WebSocket.OPEN) client.send(data, { binary: isBinary });
  });
  upstream.on("open", () => {
    // No audio or transcript content is logged. The client sends Realtime session configuration.
  });
  upstream.on("error", () => closeBoth(1011, "upstream_error"));
  client.on("error", () => closeBoth(1011, "client_error"));
  client.on("close", () => closeBoth());
  upstream.on("close", () => closeBoth(1011, "upstream_closed"));

  client.once("close", () => {
    active.delete(client);
    const next = Math.max(0, (activeByIp.get(ip) ?? 1) - 1);
    if (next === 0) activeByIp.delete(ip);
    else activeByIp.set(ip, next);
  });
});

server.listen(PORT, "0.0.0.0", () => {
  // Never print credentials, prompt content, phone numbers, or audio payloads.
  console.log(`voice realtime relay listening on :${PORT}; configured=${Boolean(API_KEY && GATEWAY_TOKEN)}`);
});
