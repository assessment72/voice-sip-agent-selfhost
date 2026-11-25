import http from "node:http";
import { randomUUID, createHash, timingSafeEqual } from "node:crypto";
import { mkdir, readFile, writeFile, rename } from "node:fs/promises";
import path from "node:path";
import { handleCrmRoute } from "./crm-routes.mjs";

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || "127.0.0.1";
const STORE = path.resolve(process.env.VOICX_STORE || "./data/voicx-store.json");
const ADMIN_TOKEN = process.env.VOICX_ADMIN_TOKEN || "";
const DEVICE_CONFIG = JSON.parse(process.env.VOICX_DEVICES || "[]");
const TELEGRAM_BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN || "";
const TELEGRAM_ADMIN_IDS = new Set((process.env.TELEGRAM_ADMIN_IDS || "").split(",").map(x => x.trim()).filter(x => /^\\d+$/.test(x)));
const MAX_BODY = 16 * 1024;
const COMMAND_TTL_MS = 60_000;
const DEVICE_ID = /^[a-zA-Z0-9_-]{1,64}$/;
const ALLOWED_COMMANDS = new Set(["service.status", "service.start", "service.stop"]);

if (ADMIN_TOKEN.length < 32) throw new Error("VOICX_ADMIN_TOKEN must be at least 32 characters");
if (!Array.isArray(DEVICE_CONFIG) || DEVICE_CONFIG.length > 20) throw new Error("VOICX_DEVICES must be an array of at most 20 devices");
const configured = new Map();
const configuredTokenHashes = new Set();
for (const d of DEVICE_CONFIG) {
  if (!d || !DEVICE_ID.test(d.id || "") || typeof d.name !== "string" || !d.name.trim() ||
      typeof d.token !== "string" || d.token.length < 32 || configured.has(d.id)) {
    throw new Error("Each device needs a unique id, non-empty name, and token of at least 32 characters");
  }
  const tokenHash = hash(d.token).toString("hex");
  if (configuredTokenHashes.has(tokenHash)) throw new Error("Every phone must have a different device token");
  configuredTokenHashes.add(tokenHash);
  configured.set(d.id, { id: d.id, name: d.name.trim(), tokenHash: hash(d.token) });
}

function hash(value) { return createHash("sha256").update(value).digest(); }
function safeEqual(a, b) { return a.length === b.length && timingSafeEqual(a, b); }
function bearer(req) {
  const value = req.headers.authorization || "";
  return value.startsWith("Bearer ") ? value.slice(7) : "";
}
function json(res, status, value) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify(value));
}
async function body(req) {
  let raw = "";
  for await (const chunk of req) {
    raw += chunk;
    if (Buffer.byteLength(raw) > MAX_BODY) throw Object.assign(new Error("Request body too large"), { status: 413 });
  }
  try { return raw ? JSON.parse(raw) : {}; }
  catch { throw Object.assign(new Error("Invalid JSON"), { status: 400 }); }
}
async function load() {
  try { return JSON.parse(await readFile(STORE, "utf8")); }
  catch (e) {
    if (e.code !== "ENOENT") throw e;
    return { devices: {}, commands: [], audit: [] };
  }
}
let state = await load();
async function save() {
  await mkdir(path.dirname(STORE), { recursive: true });
  const tmp = STORE + ".tmp";
  await writeFile(tmp, JSON.stringify(state, null, 2), { mode: 0o600 });
  await rename(tmp, STORE);
}
function audit(action, actor, details = {}) {
  state.audit.push({ id: randomUUID(), at: new Date().toISOString(), action, actor, ...details });
  if (state.audit.length > 2000) state.audit.splice(0, state.audit.length - 2000);
}
function authAdmin(req) {
  const token = bearer(req);
  return token && safeEqual(hash(token), hash(ADMIN_TOKEN));
}
function authDevice(req, id) {
  const d = configured.get(id);
  const token = bearer(req);
  return !!d && !!token && safeEqual(hash(token), d.tokenHash);
}
function publicDevice(id, d) {
  const config = configured.get(id);
  return {
    id, name: config.name, registered: true,
    online: !!d?.lastSeen && Date.now() - Date.parse(d.lastSeen) < 90_000,
    lastSeen: d?.lastSeen || null, battery: d?.battery ?? null,
    charging: d?.charging ?? null, serviceState: d?.serviceState || "unknown",
    appVersion: d?.appVersion || null
  };
}
function routeMatch(pathname, pattern) { return pathname.match(pattern); }

function queueCommand(deviceId, type, payload = {}, telegramChatId = null) {
  if (!configured.has(deviceId)) throw Object.assign(new Error("device_not_registered"), { status: 404 });
  if (!ALLOWED_COMMANDS.has(type)) throw Object.assign(new Error("unsupported_command"), { status: 400 });
  const c = {
    id: randomUUID(), deviceId, type, payload,
    status: "queued", createdAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + COMMAND_TTL_MS).toISOString(),
    idempotencyKey: null, telegramChatId
  };
  state.commands.push(c);
  if (state.commands.length > 5000) state.commands.splice(0, state.commands.length - 5000);
  audit("command.queued", telegramChatId ? "telegram-admin" : "api-client",
    { commandId: c.id, deviceId, type });
  return c;
}

async function telegram(method, payload) {
  if (!TELEGRAM_BOT_TOKEN) throw new Error("Telegram bot is not configured");
  const response = await fetch("https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + "/" + method, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(35_000)
  });
  const data = await response.json();
  if (!response.ok || !data.ok) throw new Error("Telegram API request failed");
  return data.result;
}

async function telegramMessage(chatId, text, replyMarkup = undefined) {
  const payload = { chat_id: chatId, text: String(text).slice(0, 3900) };
  if (replyMarkup) payload.reply_markup = replyMarkup;
  return telegram("sendMessage", payload);
}

const telegramConfirmations = new Map();
let telegramOffset = 0;

function isTelegramAdmin(update) {
  const message = update.message || update.callback_query?.message;
  const sender = update.message?.from || update.callback_query?.from;
  return !!message && message.chat?.type === "private" &&
    String(message.chat.id) === String(sender?.id) &&
    TELEGRAM_ADMIN_IDS.has(String(sender?.id));
}

async function telegramQueueCommand(chatId, deviceId, type) {
  if (!configured.has(deviceId)) {
    await telegramMessage(chatId, "معرّف الجهاز غير موجود في إعدادات الوسيط.");
    return;
  }
  if (!publicDevice(deviceId, state.devices[deviceId]).online) {
    await telegramMessage(chatId, "الجهاز " + deviceId + " غير متصل حالياً؛ لم أرسل الأمر.");
    return;
  }
  const command = queueCommand(deviceId, type, {}, String(chatId));
  await save();
  await telegramMessage(chatId, "تم إرسال الأمر " + type + " إلى " + deviceId + ". رقم المتابعة: " + command.id.slice(0, 8));
}

async function handleTelegramUpdate(update) {
  if (!isTelegramAdmin(update)) return;
  if (update.callback_query) {
    const callback = update.callback_query;
    await telegram("answerCallbackQuery", { callback_query_id: callback.id });
    const parts = String(callback.data || "").split(":");
    const kind = parts[0], value = parts[1];
    if (kind === "status" && configured.has(value)) {
      await telegramQueueCommand(callback.message.chat.id, value, "service.status");
    } else if (kind === "confirm") {
      const item = telegramConfirmations.get(value);
      telegramConfirmations.delete(value);
      if (item && item.adminId === String(callback.from.id) && item.expiresAt > Date.now()) {
        await telegramQueueCommand(callback.message.chat.id, item.deviceId, item.type);
      } else {
        await telegramMessage(callback.message.chat.id, "انتهت صلاحية التأكيد أو سبق استخدامه.");
      }
    } else if (kind === "cancel") {
      telegramConfirmations.delete(value);
      await telegramMessage(callback.message.chat.id, "تم إلغاء الأمر.");
    }
    return;
  }

  const message = update.message;
  const text = String(message.text || "").trim();
  const parts = text.split(/\s+/, 2);
  const command = (parts[0] || "").split("@")[0].toLowerCase();
  const deviceId = parts[1] || "";
  const chatId = message.chat.id;
  if (command === "/start" || command === "/help") {
    await telegramMessage(chatId,
      "Voicx SIP Control جاهز.\n/devices عرض الهواتف\n/status DEVICE فحص هاتف\n/start_device DEVICE تشغيل بوابة SIP\n/stop_device DEVICE إيقاف بوابة SIP");
  } else if (command === "/devices") {
    const rows = [...configured.keys()].map(id => {
      const device = publicDevice(id, state.devices[id]);
      return [{ text: (device.online ? "🟢 " : "⚪ ") + device.name + " · " + id, callback_data: "status:" + id }];
    });
    await telegramMessage(chatId, rows.length ? "الأجهزة المسجلة (اضغط لفحص الحالة):" : "لا توجد أجهزة مسجلة.",
      rows.length ? { inline_keyboard: rows } : undefined);
  } else if (command === "/status" && deviceId) {
    await telegramQueueCommand(chatId, deviceId, "service.status");
  } else if ((command === "/start_device" || command === "/stop_device") && deviceId) {
    if (!configured.has(deviceId)) {
      await telegramMessage(chatId, "معرّف الجهاز غير موجود في إعدادات الوسيط.");
      return;
    }
    const type = command === "/start_device" ? "service.start" : "service.stop";
    const nonce = randomUUID().replace(/-/g, "").slice(0, 16);
    telegramConfirmations.set(nonce, { adminId: String(message.from.id), deviceId, type, expiresAt: Date.now() + 60_000 });
    await telegramMessage(chatId, "تأكيد " + (type === "service.start" ? "تشغيل" : "إيقاف") +
      " بوابة SIP على " + deviceId + "؟ التأكيد صالح لمدة 60 ثانية ويستخدم مرة واحدة.",
      { inline_keyboard: [[
        { text: "تأكيد", callback_data: "confirm:" + nonce },
        { text: "إلغاء", callback_data: "cancel:" + nonce }
      ]] });
  } else {
    await telegramMessage(chatId, "أمر غير معروف. أرسل /help لعرض الأوامر.");
  }
}

async function telegramPoll() {
  if (!TELEGRAM_BOT_TOKEN || TELEGRAM_ADMIN_IDS.size === 0) {
    console.log("Telegram control disabled (bot token/admin allowlist not configured)");
    return;
  }
  console.log("Telegram control enabled for " + TELEGRAM_ADMIN_IDS.size + " admin account(s)");
  while (true) {
    try {
      const updates = await telegram("getUpdates", {
        offset: telegramOffset, timeout: 25,
        allowed_updates: ["message", "callback_query"]
      });
      for (const update of updates) {
        telegramOffset = Math.max(telegramOffset, update.update_id + 1);
        try { await handleTelegramUpdate(update); }
        catch { console.error("Telegram update handler failed"); }
      }
    } catch {
      console.error("Telegram polling failed; retrying");
      await new Promise(resolve => setTimeout(resolve, 3000));
    }
  }
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, "http://localhost");
    const p = url.pathname;

    if (req.method === "GET" && p === "/health") return json(res, 200, { ok: true, service: "voicx-control-bridge" });

    if (req.method === "GET" && p === "/api/v1/devices") {
      if (!authAdmin(req)) return json(res, 401, { error: "unauthorized" });
      return json(res, 200, { devices: [...configured.keys()].map(id => publicDevice(id, state.devices[id])) });
    }

    let m = routeMatch(p, /^\/api\/v1\/devices\/([^/]+)\/heartbeat$/);
    if (req.method === "POST" && m) {
      const id = decodeURIComponent(m[1]);
      if (!authDevice(req, id)) return json(res, 401, { error: "unauthorized" });
      const b = await body(req);
      if (b.battery != null && (!Number.isInteger(b.battery) || b.battery < 0 || b.battery > 100)) return json(res, 400, { error: "battery must be an integer from 0 to 100" });
      if (b.charging != null && typeof b.charging !== "boolean") return json(res, 400, { error: "charging must be boolean" });
      if (b.serviceState != null && !["running", "stopped", "starting", "unknown"].includes(b.serviceState)) return json(res, 400, { error: "invalid serviceState" });
      const previous = state.devices[id] || {};
      state.devices[id] = { ...previous, lastSeen: new Date().toISOString(),
        battery: b.battery ?? previous.battery ?? null, charging: b.charging ?? previous.charging ?? null,
        serviceState: b.serviceState ?? previous.serviceState ?? "unknown",
        appVersion: typeof b.appVersion === "string" ? b.appVersion.slice(0, 40) : previous.appVersion ?? null };
      audit("device.heartbeat", id);
      await save();
      return json(res, 200, { ok: true, serverTime: new Date().toISOString() });
    }

    m = routeMatch(p, /^\/api\/v1\/devices\/([^/]+)\/commands\/next$/);
    if (req.method === "GET" && m) {
      const id = decodeURIComponent(m[1]);
      if (!authDevice(req, id)) return json(res, 401, { error: "unauthorized" });
      const now = Date.now();
      let changed = false;
      for (const c of state.commands) {
        if (c.deviceId !== id) continue;
        if (["delivered", "running"].includes(c.status)) {
          const lastAttempt = Date.parse(c.updatedAt || c.deliveredAt || c.createdAt);
          if (now - lastAttempt > 30_000) {
            if (Date.parse(c.expiresAt) <= now || (c.deliveryAttempts || 0) >= 3) {
              c.status = Date.parse(c.expiresAt) <= now ? "expired" : "failed";
              c.error = "device_delivery_timeout";
              c.updatedAt = new Date().toISOString();
              if (c.telegramChatId) {
                telegramMessage(c.telegramChatId, "انتهت مهلة الأمر على الجهاز " + id + " ولم يصل تأكيد التنفيذ.").catch(() => {});
              }
            } else {
              c.status = "queued";
              delete c.deliveredAt;
            }
            changed = true;
          }
        }
        if (c.status === "queued" && Date.parse(c.expiresAt) <= now) {
          c.status = "expired";
          c.updatedAt = new Date().toISOString();
          if (c.telegramChatId) {
            telegramMessage(c.telegramChatId, "انتهت مهلة الأمر على الجهاز " + id + " قبل استلامه.").catch(() => {});
          }
          changed = true;
        }
      }
      const pending = state.commands.filter(c => c.deviceId === id && c.status === "queued" && Date.parse(c.expiresAt) > now).slice(0, 10);
      for (const c of pending) {
        c.status = "delivered";
        c.deliveredAt = new Date().toISOString();
        c.deliveryAttempts = (c.deliveryAttempts || 0) + 1;
      }
      if (pending.length) audit("command.delivered", id, { commandIds: pending.map(c => c.id) });
      if (changed || pending.length) await save();
      return json(res, 200, { commands: pending });
    }

    m = routeMatch(p, /^\/api\/v1\/devices\/([^/]+)\/commands\/([^/]+)\/result$/);
    if (req.method === "POST" && m) {
      const id = decodeURIComponent(m[1]), commandId = decodeURIComponent(m[2]);
      if (!authDevice(req, id)) return json(res, 401, { error: "unauthorized" });
      const c = state.commands.find(x => x.id === commandId && x.deviceId === id);
      if (!c) return json(res, 404, { error: "command_not_found" });
      if (!["delivered", "running"].includes(c.status)) return json(res, 409, { error: "command_not_pending" });
      const b = await body(req);
      if (!["succeeded", "failed", "running"].includes(b.status)) return json(res, 400, { error: "invalid status" });
      c.status = b.status; c.updatedAt = new Date().toISOString();
      if (b.result != null) c.result = JSON.stringify(b.result).slice(0, 2000);
      if (b.error != null) c.error = String(b.error).slice(0, 500);
      audit("command.result", id, { commandId, status: c.status });
      await save();
      if (c.telegramChatId && b.status !== "running") {
        const outcome = b.status === "succeeded" ? "اكتمل الأمر بنجاح" : "فشل تنفيذ الأمر";
        const details = c.result ? String(c.result).slice(0, 1200) : String(b.error || "");
        telegramMessage(c.telegramChatId, outcome + " على " + id + ".\\n" + details).catch(() => {});
      }
      return json(res, 200, { ok: true });
    }

    if (req.method === "POST" && p === "/api/v1/devices/command") {
      if (!authAdmin(req)) return json(res, 401, { error: "unauthorized" });
      const b = await body(req);
      if (!configured.has(b.deviceId)) return json(res, 404, { error: "device_not_registered" });
      if (!ALLOWED_COMMANDS.has(b.type)) return json(res, 400, { error: "unsupported_command" });
      const idem = typeof req.headers["idempotency-key"] === "string" ? req.headers["idempotency-key"].slice(0, 128) : null;
      if (idem) {
        const old = state.commands.find(c => c.deviceId === b.deviceId && c.idempotencyKey === idem);
        if (old) return json(res, 200, { command: old, reused: true });
      }
      const c = queueCommand(b.deviceId, b.type, {}, null);
      c.idempotencyKey = idem;
      await save();
      return json(res, 202, { command: c });
    }

    m = routeMatch(p, /^\/api\/v1\/commands\/([^/]+)$/);
    if (req.method === "GET" && m) {
      if (!authAdmin(req)) return json(res, 401, { error: "unauthorized" });
      const c = state.commands.find(x => x.id === decodeURIComponent(m[1]));
      return c ? json(res, 200, { command: c }) : json(res, 404, { error: "command_not_found" });
    }

    if (req.method === "GET" && p === "/api/v1/audit") {
      if (!authAdmin(req)) return json(res, 401, { error: "unauthorized" });
      return json(res, 200, { events: state.audit.slice(-200).reverse() });
    }

    if (req.method === "POST" && /^\/api\/v1\/devices\/[^/]+\/calls$/.test(p)) {
      if (!authAdmin(req)) return json(res, 401, { error: "unauthorized" });
      return json(res, 501, { error: "outbound_calling_not_implemented", message: "No SIP call is started by this API yet." });
    }

    if (await handleCrmRoute(req, res, url, {
      state, save, audit, authAdmin, body
    })) return;

    return json(res, 404, { error: "not_found" });
  } catch (e) {
    return json(res, e.status || 500, { error: e.status ? e.message : "internal_error" });
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Voicx Control Bridge listening on http://${HOST}:${PORT}`);
  void telegramPoll();
});
