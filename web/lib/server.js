import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";

const ROOM_TTL_SECONDS = 10 * 60;
const TOKEN_TTL_SECONDS = ROOM_TTL_SECONDS;
const SIGNAL_TTL_SECONDS = ROOM_TTL_SECONDS;
const ALLOWED_SIGNAL_TYPES = new Set([
  "offer", "answer", "ice-candidate", "ringing", "accepted", "hangup", "error", "dial",
]);

export function json(res, status, body) {
  res.statusCode = status;
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Cache-Control", "no-store");
  res.end(JSON.stringify(body));
}

export function requireMethod(req, res, method) {
  if (req.method !== method) {
    res.setHeader("Allow", method);
    json(res, 405, { error: "method_not_allowed" });
    return false;
  }
  return true;
}

function env(name) {
  const value = process.env[name];
  if (!value) throw new Error("Missing server environment configuration");
  return value;
}

export async function redis(command) {
  const response = await fetch(env("UPSTASH_REDIS_REST_URL"), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env("UPSTASH_REDIS_REST_TOKEN")}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(command),
    cache: "no-store",
  });
  if (!response.ok) throw new Error("Shared state request failed");
  const result = await response.json();
  if (result.error) throw new Error("Shared state command failed");
  return result.result;
}

export function randomToken() {
  return randomBytes(32).toString("base64url");
}

export function tokenHash(token) {
  return createHash("sha256").update(token).digest("hex");
}

export async function authenticatePeer(req) {
  const header = req.headers.authorization || "";
  const match = /^Bearer ([A-Za-z0-9_-]{32,100})$/.exec(header);
  if (!match) return null;
  const record = await redis(["GET", `vsa:peer:${tokenHash(match[1])}`]);
  if (!record) return null;
  try {
    const peer = JSON.parse(record);
    if (!peer.roomId || !["browser", "android"].includes(peer.role)) return null;
    const room = await redis(["GET", `vsa:room:${peer.roomId}`]);
    return room ? peer : null;
  } catch {
    return null;
  }
}

/** Verify Telegram WebApp initData according to Telegram's Web Apps validation algorithm. */
export function verifyTelegramInitData(initData, botToken, ownerId, nowSeconds = Math.floor(Date.now() / 1000)) {
  if (typeof initData !== "string" || initData.length > 8192) return null;
  const params = new URLSearchParams(initData);
  const receivedHash = params.get("hash");
  if (!receivedHash || !/^[a-f0-9]{64}$/i.test(receivedHash)) return null;

  const authDate = Number(params.get("auth_date"));
  if (!Number.isInteger(authDate) || authDate > nowSeconds + 30 || nowSeconds - authDate > 300) return null;

  const entries = [...params.entries()]
    .filter(([key]) => key !== "hash")
    .sort(([a], [b]) => a.localeCompare(b));
  const dataCheckString = entries.map(([key, value]) => `${key}=${value}`).join("\n");
  const secretKey = createHmac("sha256", "WebAppData").update(botToken).digest();
  const calculated = createHmac("sha256", secretKey).update(dataCheckString).digest();
  const received = Buffer.from(receivedHash, "hex");
  if (received.length !== calculated.length || !timingSafeEqual(received, calculated)) return null;

  let user;
  try { user = JSON.parse(params.get("user") || "null"); } catch { return null; }
  if (!user || String(user.id) !== String(ownerId)) return null;
  return { id: String(user.id), firstName: String(user.first_name || "").slice(0, 80) };
}

export function parseSignal(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) return null;
  if (!ALLOWED_SIGNAL_TYPES.has(body.type)) return null;
  if (body.data !== undefined && (body.data === null || typeof body.data !== "object")) return null;
  if (body.type === "dial" && !/^\\+?[0-9*#]{3,32}$/.test(String(body.data?.target || ""))) return null;\n  const payload = JSON.stringify({ type: body.type, data: body.data ?? {} });
  if (Buffer.byteLength(payload, "utf8") > 48 * 1024) return null;
  return { type: body.type, data: body.data ?? {} };
}

export async function sendTelegramInvite(androidToken) {
  const token = env("TELEGRAM_BOT_TOKEN");
  const chatId = env("TELEGRAM_PRIVATE_CHAT_ID");
  const response = await fetch(`https://api.telegram.org/bot${token}/sendMessage`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      chat_id: chatId,
      text: `مكالمة WebRTC واردة. افتح تطبيق Voice SIP Agent لاستلام الدعوة.\n/vsa_join ${androidToken}`,
      disable_web_page_preview: true,
    }),
  });
  const result = await response.json().catch(() => null);
  if (!response.ok || !result?.ok) throw new Error("Telegram invitation delivery failed");
}

export async function createRoom() {
  const roomId = randomBytes(16).toString("hex");
  const browserToken = randomToken();
  const androidToken = randomToken();
  const room = JSON.stringify({ roomId, createdAt: Date.now(), status: "waiting" });
  await redis(["SET", `vsa:room:${roomId}`, room, "EX", ROOM_TTL_SECONDS]);
  await redis(["SET", `vsa:peer:${tokenHash(browserToken)}`,
    JSON.stringify({ roomId, role: "browser" }), "EX", TOKEN_TTL_SECONDS]);
  await redis(["SET", `vsa:peer:${tokenHash(androidToken)}`,
    JSON.stringify({ roomId, role: "android" }), "EX", TOKEN_TTL_SECONDS]);
  try {
    await sendTelegramInvite(androidToken);
  } catch (error) {
    await redis(["DEL", `vsa:room:${roomId}`]);
    await redis(["DEL", `vsa:peer:${tokenHash(browserToken)}`]);
    await redis(["DEL", `vsa:peer:${tokenHash(androidToken)}`]);
    throw error;
  }
  return { roomId, browserToken, expiresIn: ROOM_TTL_SECONDS };
}

export async function configuredIceServers() {
  const urls = (process.env.METERED_TURN_URLS || "").split(",").map(x => x.trim()).filter(Boolean);
  const username = process.env.METERED_TURN_USERNAME || "";
  const credential = process.env.METERED_TURN_PASSWORD || "";
  if (!urls.length || !username || !credential) {
    throw new Error("TURN is not configured on the server");
  }
  return [
    { urls: "stun:stun.relay.metered.ca:80" },
    { urls, username, credential },
  ];
}

export const SIGNAL_TTL = SIGNAL_TTL_SECONDS;
