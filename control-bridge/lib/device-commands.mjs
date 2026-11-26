import { randomUUID } from "node:crypto";
import { getDb } from "./mongodb.mjs";
import { authorize, requireHttps, isConfiguredDeviceId } from "./auth.mjs";

const LEASE_MS = 5 * 60_000;
const MAX_BATCH = 5;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const PHONE_COMMANDS = new Set([
  "/admin", "/sms", "/simcall", "/simcall_cancel", "/simcall_delete", "/ai_status", "/ai_errors",
  "/auto_answer_on", "/auto_answer_off", "/ai_stop", "/call", "/service_on", "/service_off",
  "/campaign_start", "/followup", "/mark_interested", "/pause_customer", "/resume_customer",
]);
const json = (res, status, value) => {
  res.statusCode = status;
  res.setHeader("content-type", "application/json; charset=utf-8");
  res.setHeader("cache-control", "no-store");
  res.setHeader("x-content-type-options", "nosniff");
  res.end(JSON.stringify(value));
};
const clean = (value, max = 3900) => typeof value === "string" ? value.trim().slice(0, max) : "";
const now = () => new Date();
function routeId(req, key) {
  return String(req.query?.[key] || new URL(req.url, "https://vercel.invalid").pathname.match(new RegExp(`/devices/([^/]+)/commands(?:/([^/]+))?/${key === "deviceId" ? "" : "result"}`))?.[key === "deviceId" ? 1 : 2] || "");
}
function deviceTarget() {
  const id = String(process.env.VOICX_TELEGRAM_DEVICE_ID || "");
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(id) || !isConfiguredDeviceId(id)) {
    throw Object.assign(new Error("telegram_device_not_configured"), { status: 503 });
  }
  return id;
}
export function isPhoneCommand(update) {
  if (update?.callback_query) return true;
  const text = String(update?.message?.text || "").trim();
  const command = (text.split(/\s+/, 1)[0] || "").split("@")[0].toLowerCase();
  return PHONE_COMMANDS.has(command);
}

export async function enqueueTelegramDeviceCommand(update) {
  const updateId = Number(update?.update_id);
  if (!Number.isSafeInteger(updateId) || updateId < 0) throw Object.assign(new Error("invalid_update"), { status: 400 });
  const deviceId = deviceTarget();
  const col = (await getDb()).collection("device_commands");
  const commandId = randomUUID();
  await col.updateOne({ sourceUpdateId: updateId }, { $setOnInsert: {
    commandId, source: "telegram", sourceUpdateId: updateId, deviceId, type: "telegram.update",
    payload: update, status: "queued", attempts: 0, availableAt: now(), createdAt: now(), updatedAt: now(),
  } }, { upsert: true });
  const saved = await col.findOne({ sourceUpdateId: updateId });
  if (!saved || saved.deviceId !== deviceId) throw Object.assign(new Error("device_command_conflict"), { status: 409 });
  return { commandId: saved.commandId, status: saved.status };
}

export async function pollDeviceCommands(req, res) {
  if (req.method !== "GET") return json(res, 405, { error: "method_not_allowed" });
  if (!requireHttps(req)) return json(res, 400, { error: "https_required" });
  const deviceId = String(req.query?.deviceId || "");
  const identity = authorize(req);
  if (!identity.ok) return json(res, identity.status, { error: identity.error });
  if (identity.role !== "device" || identity.deviceId !== deviceId) return json(res, 403, { error: "forbidden" });
  try {
    const col = (await getDb()).collection("device_commands"), at = now();
    // An expired lease means the phone disappeared mid-command. It is safe to redeliver only
    // because the Android client persists a local execution receipt keyed by commandId.
    await col.updateMany({ deviceId, status: "running", leaseUntil: { $lte: at } }, {
      $set: { status: "queued", availableAt: at, updatedAt: at }, $unset: { leaseUntil: "", leaseToken: "" },
    });
    const commands = [];
    for (let i = 0; i < MAX_BATCH; i++) {
      const leaseToken = randomUUID();
      const doc = await col.findOneAndUpdate({ deviceId, status: "queued", availableAt: { $lte: at } }, {
        $set: { status: "running", leaseToken, leaseUntil: new Date(at.getTime() + LEASE_MS), updatedAt: at },
        $inc: { attempts: 1 },
      }, { sort: { createdAt: 1 }, returnDocument: "after" });
      if (!doc) break;
      commands.push({ id: doc.commandId, type: doc.type, payload: doc.payload, leaseToken, attempts: doc.attempts });
    }
    return json(res, 200, { ok: true, commands });
  } catch (error) {
    console.error("Device command poll failed", { deviceId, code: error?.code || error?.name || "error" });
    return json(res, 503, { error: "service_unavailable" });
  }
}

async function readJson(req) {
  if (req.body && typeof req.body === "object") return req.body;
  let raw = "";
  for await (const chunk of req) {
    raw += chunk;
    if (Buffer.byteLength(raw) > 16 * 1024) throw Object.assign(new Error("request_too_large"), { status: 413 });
  }
  try { return JSON.parse(raw || "{}"); } catch { throw Object.assign(new Error("invalid_json"), { status: 400 }); }
}

function replyMarkup(buttons) {
  if (!Array.isArray(buttons) || buttons.length > 8) return undefined;
  const rows = buttons.slice(0, 8).map(row => Array.isArray(row) ? row.slice(0, 4).map(button => ({
    text: clean(button?.text, 64), callback_data: clean(button?.callbackData, 64),
  })).filter(button => button.text && button.callback_data) : []).filter(row => row.length);
  return rows.length ? { inline_keyboard: rows } : undefined;
}

async function sendCommandReply(doc) {
  const token = process.env.TELEGRAM_BOT_TOKEN || "";
  const chatId = doc.payload?.message?.chat?.id || doc.payload?.callback_query?.message?.chat?.id;
  const result = doc.commandResult || {};
  const text = clean(result.text || (doc.status === "succeeded" ? "تم تنفيذ الأمر." : "تعذر تنفيذ الأمر على الهاتف."));
  if (!chatId || !text) throw new Error("missing_command_reply");
  const payload = { chat_id: chatId, text };
  const markup = replyMarkup(result.buttons);
  if (markup) payload.reply_markup = markup;
  if (!/^\d+:[A-Za-z0-9_-]{20,}$/.test(token)) throw new Error("telegram_not_configured");
  const response = await fetch(`https://api.telegram.org/bot${token}/sendMessage`, {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(payload), signal: AbortSignal.timeout(12_000),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok || !data.ok) throw new Error("telegram_delivery_failed");
  return data.result;
}

export async function reportDeviceCommand(req, res) {
  if (req.method !== "POST") return json(res, 405, { error: "method_not_allowed" });
  if (!requireHttps(req)) return json(res, 400, { error: "https_required" });
  const deviceId = String(req.query?.deviceId || ""), commandId = String(req.query?.commandId || "");
  const identity = authorize(req);
  if (!identity.ok) return json(res, identity.status, { error: identity.error });
  if (identity.role !== "device" || identity.deviceId !== deviceId) return json(res, 403, { error: "forbidden" });
  if (!UUID.test(commandId)) return json(res, 400, { error: "invalid_command_id" });
  try {
    const body = await readJson(req), status = String(body.status || "");
    if (!new Set(["running", "succeeded", "failed"]).has(status)) return json(res, 400, { error: "invalid_status" });
    const col = (await getDb()).collection("device_commands"), at = now();
    const filter = { commandId, deviceId, status: "running" };
    if (typeof body.leaseToken !== "string" || body.leaseToken.length < 30) return json(res, 400, { error: "invalid_lease" });
    filter.leaseToken = body.leaseToken;
    if (status === "running") {
      const update = await col.updateOne(filter, { $set: { leaseUntil: new Date(at.getTime() + LEASE_MS), updatedAt: at } });
      if (!update.matchedCount) return json(res, 409, { error: "lease_expired" });
      return json(res, 200, { ok: true, status: "running" });
    }
    const commandResult = body.result && typeof body.result === "object" ? {
      text: clean(body.result.text),
      buttons: Array.isArray(body.result.buttons) ? body.result.buttons.slice(0, 8).map(row => Array.isArray(row) ? row.slice(0, 4).map(button => ({ text: clean(button?.text, 64), callbackData: clean(button?.callbackData, 64) })) : []) : [],
    } : { text: "" };
    if (status === "failed") commandResult.text ||= `تعذر تنفيذ الأمر على الهاتف (${clean(body.error, 120) || "unknown_error"}).`;
    const update = await col.updateOne(filter, { $set: {
      status, commandResult, completedAt: at, replyStatus: "queued", replyAttempts: 0, replyNextAttemptAt: at, updatedAt: at,
      ...(status === "failed" ? { lastError: clean(body.error, 500) || "device_command_failed" } : {}),
    }, $unset: { leaseUntil: "", leaseToken: "" } });
    if (!update.matchedCount) {
      const existing = await col.findOne({ commandId, deviceId });
      return existing && ["succeeded", "failed"].includes(existing.status)
        ? json(res, 200, { ok: true, replayed: true, status: existing.status })
        : json(res, 409, { error: "lease_expired" });
    }
    const doc = await col.findOne({ commandId, deviceId });
    try {
      const sent = await sendCommandReply(doc);
      await col.updateOne({ _id: doc._id, replyStatus: "queued" }, { $set: { replyStatus: "sent", telegramMessageId: sent?.message_id || null, replySentAt: now(), updatedAt: now() } });
      return json(res, 200, { ok: true, status, replyStatus: "sent" });
    } catch (error) {
      const delay = 30_000;
      await col.updateOne({ _id: doc._id, replyStatus: "queued" }, { $set: { replyAttempts: 1, replyNextAttemptAt: new Date(Date.now() + delay), replyError: "telegram_delivery_failed", updatedAt: now() } });
      console.error("Device command reply queued", { commandId, code: error?.code || error?.name || "error" });
      return json(res, 200, { ok: true, status, replyStatus: "queued" });
    }
  } catch (error) {
    const status = Number.isInteger(error?.status) ? error.status : 503;
    if (status >= 500) console.error("Device command result failed", { commandId, code: error?.code || error?.name || "error" });
    return json(res, status, { error: status >= 500 ? "service_unavailable" : String(error.message || "request_failed") });
  }
}

export async function retryDeviceCommandReplies(limit = 20) {
  const col = (await getDb()).collection("device_commands"), at = now();
  let processed = 0;
  for (let i = 0; i < Math.max(1, Math.min(50, limit)); i++) {
    const doc = await col.findOneAndUpdate({ status: { $in: ["succeeded", "failed"] }, replyStatus: "queued", replyNextAttemptAt: { $lte: at } }, {
      $set: { replyStatus: "sending", replyLockedAt: at, updatedAt: at }, $inc: { replyAttempts: 1 },
    }, { sort: { replyNextAttemptAt: 1 }, returnDocument: "after" });
    if (!doc) break;
    try {
      const sent = await sendCommandReply(doc);
      await col.updateOne({ _id: doc._id, replyStatus: "sending" }, { $set: { replyStatus: "sent", telegramMessageId: sent?.message_id || null, replySentAt: now(), updatedAt: now() }, $unset: { replyLockedAt: "", replyError: "" } });
      processed++;
    } catch {
      const delay = Math.min(60 * 60_000, 30_000 * 2 ** Math.min(7, (doc.replyAttempts || 1) - 1));
      await col.updateOne({ _id: doc._id, replyStatus: "sending" }, { $set: { replyStatus: "queued", replyNextAttemptAt: new Date(Date.now() + delay), replyError: "telegram_delivery_failed", updatedAt: now() }, $unset: { replyLockedAt: "" } });
    }
  }
  return { processed };
}
