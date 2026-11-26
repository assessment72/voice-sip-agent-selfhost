import { createHash, timingSafeEqual } from "node:crypto";
import { getDb } from "./mongodb.mjs";
import { executeDbOperation } from "./db-operations.mjs";
import { enqueueTelegramDeviceCommand, isPhoneCommand, retryDeviceCommandReplies } from "./device-commands.mjs";

const MAX_BODY_BYTES = 32 * 1024;
const MAX_ATTEMPTS = 12;
const ADMIN_IDS = () => new Set((process.env.TELEGRAM_ADMIN_IDS || "").split(",").map(x => x.trim()).filter(x => /^\d+$/.test(x)));
const now = () => new Date();
const json = (res, status, body) => {
  res.statusCode = status;
  res.setHeader("content-type", "application/json; charset=utf-8");
  res.setHeader("cache-control", "no-store");
  res.setHeader("x-content-type-options", "nosniff");
  res.end(JSON.stringify(body));
};
function secureEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || !a || !b) return false;
  return timingSafeEqual(createHash("sha256").update(a).digest(), createHash("sha256").update(b).digest());
}
function isHttps(req) {
  return String(req.headers["x-forwarded-proto"] || "").split(",")[0].trim().toLowerCase() === "https" || process.env.NODE_ENV !== "production";
}
async function readBody(req) {
  if (req.body && typeof req.body === "object") return req.body;
  let raw = "";
  for await (const chunk of req) {
    raw += chunk;
    if (Buffer.byteLength(raw) > MAX_BODY_BYTES) throw Object.assign(new Error("request_too_large"), { status: 413 });
  }
  if (!raw) return {};
  try { return JSON.parse(raw); } catch { throw Object.assign(new Error("invalid_json"), { status: 400 }); }
}
function authorizedUpdate(update) {
  const message = update?.message || update?.callback_query?.message;
  const sender = update?.message?.from || update?.callback_query?.from;
  return Boolean(message && sender && message.chat?.type === "private" &&
    String(message.chat.id) === String(sender.id) && ADMIN_IDS().has(String(sender.id)));
}
function commandOf(update) {
  const message = update.message || update.callback_query?.message;
  const text = String(message?.text || "").trim();
  const [raw = "", ...rest] = text.split(/\s+/);
  return { chatId: message?.chat?.id, text, command: raw.split("@")[0].toLowerCase(), argument: rest.join(" ").trim() };
}
function fieldsOf(value) { return value.split("|").map(part => part.trim()); }
function clean(value, limit = 4000) { return typeof value === "string" ? value.trim().slice(0, limit) : ""; }
function scheduledTime(value) {
  const n = Number(value);
  return Number.isFinite(n) && n > Date.now() ? n : null;
}
async function telegram(method, payload) {
  const token = process.env.TELEGRAM_BOT_TOKEN || "";
  if (!/^\d+:[A-Za-z0-9_-]{20,}$/.test(token)) throw new Error("telegram_not_configured");
  const response = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(payload), signal: AbortSignal.timeout(12_000),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok || !data.ok) throw new Error("telegram_delivery_failed");
  return data.result;
}
async function sendText(chatId, text) {
  return telegram("sendMessage", { chat_id: chatId, text: String(text).slice(0, 3900) });
}
function errorReply() { return "تعذر تنفيذ الطلب مؤقتاً. تم حفظه وسيُعاد تلقائياً؛ أرسل /help للاطلاع على الأوامر."; }

async function makeReply(update) {
  const { chatId, command, argument } = commandOf(update);
  const updateId = Number(update.update_id);
  if (isPhoneCommand(update)) {
    await enqueueTelegramDeviceCommand(update);
    const callbackId = update.callback_query?.id;
    if (callbackId) await telegram("answerCallbackQuery", { callback_query_id: callbackId, text: "تم حفظ الطلب؛ سيعالجه الهاتف عند اتصاله.", show_alert: false }).catch(() => {});
    return "تم حفظ الأمر في طابور Vercel. سيُنفّذ تلقائياً عند اتصال الهاتف، وستصلك النتيجة هنا.";
  }
  if (command === "/start" || command === "/help") return "Voicx عبر Vercel. أوامر الخادم: /status /sip /calls /db_status /customer_search <نص> /customer_add رقم | اسم | وظيفة | شركة | تعليمات /schedule_add customerId | رقم | وقت Unix ms | ملاحظة /schedule_list /sms_schedule_add customerId | رقم | وقت Unix ms | رسالة | ملاحظة /sms_schedule_list /campaign_add customerId | رقم | وقت الاتصال Unix ms | وقت SMS Unix ms | الرسالة | ملاحظة /account. أوامر الهاتف المؤجلة عند انقطاعه: /admin /sms /simcall /call /ai_status /service_on /service_off وغيرها.";
  if (command === "/db_status") {
    await executeDbOperation("db.status");
    return "MongoDB متاحة عبر Vercel API.";
  }
  if (command === "/customer_search") {
    if (!argument) return "الاستخدام: /customer_search <نص>";
    const result = await executeDbOperation("customer.search", { query: argument, limit: 20 });
    return result.customers.length ? result.customers.map(c => `${c.id} | ${c.name} | ${c.phone} | ${c.company}`).join("\n") : "لا توجد نتائج.";
  }
  if (command === "/customer_add") {
    const f = fieldsOf(argument);
    if (f.length < 5 || !f[0] || !f[1]) return "الاستخدام: /customer_add رقم | اسم | وظيفة | شركة | تعليمات";
    const result = await executeDbOperation("customer.upsert", { customer: { phone: f[0], name: f[1], role: f[2], company: f[3], instructions: f.slice(4).join(" | ") } });
    return `تم حفظ العميل. المعرّف: ${result.id}`;
  }
  if (command === "/schedule_add") {
    const f = fieldsOf(argument), at = scheduledTime(f[2]);
    if (f.length < 3 || !f[0] || !f[1] || !at) return "الاستخدام: /schedule_add customerId | رقم | وقت Unix ms | ملاحظة";
    const result = await executeDbOperation("schedule.save", { schedule: { customerId: f[0], phone: f[1], runAt: at, status: "pending", note: f[3] || "", idempotencyKey: `telegram:${updateId}:call` } });
    return `تم حفظ موعد الاتصال: ${result.id}`;
  }
  if (command === "/schedule_list") {
    const result = await executeDbOperation("schedule.list", { limit: 20 });
    return result.schedules.length ? result.schedules.map(s => `${s.id} | ${s.phone} | ${s.runAt} | ${s.status} | ${s.note}`).join("\n") : "لا توجد مواعيد.";
  }
  if (command === "/sms_schedule_add") {
    const f = fieldsOf(argument), at = scheduledTime(f[2]);
    if (f.length < 4 || !f[0] || !f[1] || !at || !f[3]) return "الاستخدام: /sms_schedule_add customerId | رقم | وقت Unix ms | رسالة | ملاحظة";
    const result = await executeDbOperation("smsSchedule.save", { schedule: { customerId: f[0], phone: f[1], runAt: at, message: f[3], status: "queued", note: f[4] || "", idempotencyKey: `telegram:${updateId}:sms` } });
    return `تم حفظ موعد SMS: ${result.id}`;
  }
  if (command === "/sms_schedule_list") {
    const result = await executeDbOperation("smsSchedule.list", { limit: 20 });
    return result.schedules.length ? result.schedules.map(s => `${s.id} | ${s.phone} | ${s.runAt} | ${s.status} | ${s.note} | ${s.message.slice(0, 80)}`).join("\n") : "لا توجد رسائل مجدولة.";
  }
  if (command === "/campaign_add") {
    const f = fieldsOf(argument), callAt = scheduledTime(f[2]), smsAt = scheduledTime(f[3]);
    if (f.length < 5 || !f[0] || !f[1] || !callAt || !smsAt || !f[4]) return "الاستخدام: /campaign_add customerId | رقم | وقت الاتصال Unix ms | وقت SMS Unix ms | الرسالة | ملاحظة";
    const campaignId = `telegram-${updateId}`;
    const note = f[5] || "";
    const [call, sms] = await Promise.all([
      executeDbOperation("schedule.save", { schedule: { customerId: f[0], phone: f[1], runAt: callAt, status: "pending", campaignId, note, idempotencyKey: `telegram:${updateId}:campaign-call` } }),
      executeDbOperation("smsSchedule.save", { schedule: { customerId: f[0], phone: f[1], runAt: smsAt, message: f[4], status: "queued", campaignId, note, idempotencyKey: `telegram:${updateId}:campaign-sms` } }),
    ]);
    return `تم إنشاء الحملة ${campaignId}\nالمكالمة: ${call.id}\nSMS: ${sms.id}`;
  }
  if (command === "/status" || command === "/sip" || command === "/calls") {
    const db = await getDb();
    const device = await db.collection("device_status").find({}).sort({ lastSeen: -1 }).limit(1).next();
    if (!device || Date.now() - new Date(device.lastSeen).getTime() > 90_000) return "لا يوجد جهاز Voicx متصل حديثاً بواجهة Vercel.";
    if (command === "/sip") return `SIP: ${device.sipReady ? "جاهز" : "غير جاهز"}\n${clean(device.sipDetail, 200)}`;
    if (command === "/calls") return `المكالمة النشطة: ${device.activeCall ? "نعم" : "لا"}`;
    return `Gateway: ${device.serviceState || "unknown"}\nSIP: ${device.sipReady ? "ready" : "not ready"}\nActive call: ${device.activeCall ? "yes" : "no"}\nBattery: ${device.battery ?? "unknown"}%`;
  }
  if (command === "/account") {
    const db = await getDb();
    const rows = await db.collection("call_summaries").find({}).sort({ createdAt: -1 }).limit(8).toArray();
    return rows.length ? rows.map(r => `${r.name || r.phone || "مكالمة"} | ${r.cause || ""} | ${r.endedAt?.toISOString?.() || ""}\n${clean(r.conversationTranscript || r.outputText, 250)}`).join("\n\n").slice(0, 3800) : "لا توجد ملخصات مكالمات محفوظة.";
  }
  if (command === "/admin") return "إعدادات الإدارة تُدار من التطبيق، ولم تُنقل إلى مخزن Vercel.";
  return "أمر غير معروف أو غير مدعوم عبر Webhook. أرسل /help.";
}

async function claimUpdate(updateId = null) {
  const col = (await getDb()).collection("telegram_updates");
  const at = now(), stale = new Date(at.getTime() - 2 * 60_000);
  const query = {
    attempts: { $lt: MAX_ATTEMPTS },
    $or: [
      { status: { $in: ["queued", "failed"] }, nextAttemptAt: { $lte: at } },
      { status: "processing", lockedAt: { $lt: stale } },
    ],
  };
  if (updateId != null) query.updateId = updateId;
  return col.findOneAndUpdate(query, { $set: { status: "processing", lockedAt: at, updatedAt: at }, $inc: { attempts: 1 } }, { sort: { createdAt: 1 }, returnDocument: "after" });
}
async function processClaimed(doc) {
  const col = (await getDb()).collection("telegram_updates");
  try {
    let reply = doc.replyText;
    if (!reply) {
      reply = await makeReply(doc.payload);
      await col.updateOne({ _id: doc._id, status: "processing" }, { $set: { replyText: reply, actionCompletedAt: now(), updatedAt: now() } });
    }
    const message = doc.payload.message || doc.payload.callback_query?.message;
    const chatId = message?.chat?.id;
    if (chatId != null && reply) await sendText(chatId, reply);
    await col.updateOne({ _id: doc._id, status: "processing" }, { $set: { status: "completed", completedAt: now(), updatedAt: now() }, $unset: { lastError: "", lockedAt: "" } });
    return true;
  } catch (error) {
    const attempts = Number(doc.attempts || 1);
    const delayMs = Math.min(60 * 60_000, 5_000 * 2 ** Math.min(10, attempts - 1));
    await col.updateOne({ _id: doc._id, status: "processing" }, {
      $set: { status: attempts >= MAX_ATTEMPTS ? "dead" : "queued", lastError: "processing_or_delivery_failed", nextAttemptAt: new Date(Date.now() + delayMs), updatedAt: now() },
      $unset: { lockedAt: "" },
    });
    console.error("Telegram update processing failed", { updateId: doc.updateId, attempts, code: error?.code || error?.name || "error" });
    throw error;
  }
}
export async function processTelegramUpdate(updateId) {
  const doc = await claimUpdate(updateId);
  if (!doc) {
    const current = await (await getDb()).collection("telegram_updates").findOne({ updateId });
    return { status: current?.status || "missing", replayed: current?.status === "completed" };
  }
  await processClaimed(doc);
  return { status: "completed" };
}
export async function retryTelegramUpdates(limit = 20) {
  let processed = 0;
  for (let i = 0; i < Math.max(1, Math.min(50, limit)); i++) {
    const doc = await claimUpdate();
    if (!doc) break;
    try { await processClaimed(doc); processed++; }
    catch { /* retained in MongoDB and retried by the next webhook or cron invocation */ }
  }
  return { processed };
}

export async function telegramWebhook(req, res) {
  if (req.method !== "POST") return json(res, 405, { error: "method_not_allowed" });
  if (!isHttps(req)) return json(res, 400, { error: "https_required" });
  const expected = process.env.TELEGRAM_WEBHOOK_SECRET || "";
  const supplied = req.headers["x-telegram-bot-api-secret-token"] || "";
  if (expected.length < 32 || !/^[A-Za-z0-9_-]+$/.test(expected) || !secureEqual(supplied, expected)) return json(res, 401, { error: "unauthorized" });
  try {
    const update = await readBody(req);
    if (!Number.isSafeInteger(update?.update_id) || update.update_id < 0) return json(res, 400, { error: "invalid_update" });
    if (!authorizedUpdate(update)) return json(res, 200, { ok: true, ignored: true });
    const col = (await getDb()).collection("telegram_updates");
    await col.updateOne({ updateId: update.update_id }, { $setOnInsert: {
      updateId: update.update_id, payload: update, status: "queued", attempts: 0, nextAttemptAt: now(), createdAt: now(), updatedAt: now(),
    } }, { upsert: true });
    const result = await processTelegramUpdate(update.update_id);
    if (result.status === "completed") return json(res, 200, { ok: true, replayed: result.replayed });
    if (result.status === "processing") return json(res, 200, { ok: true, queued: true });
    return json(res, 503, { error: "update_pending_retry" });
  } catch (error) {
    const status = Number.isInteger(error?.status) ? error.status : 503;
    if (status >= 500) console.error("Telegram webhook unavailable", { code: error?.code || error?.name || "error" });
    return json(res, status, { error: status >= 500 ? "temporarily_unavailable" : String(error.message || "invalid_request") });
  }
}

export async function telegramRetryEndpoint(req, res) {
  if (req.method !== "POST" && req.method !== "GET") return json(res, 405, { error: "method_not_allowed" });
  if (!isHttps(req)) return json(res, 400, { error: "https_required" });
  const secret = process.env.CRON_SECRET || "";
  const authorization = req.headers.authorization || "";
  if (secret.length < 32 || !secureEqual(authorization, `Bearer ${secret}`)) return json(res, 401, { error: "unauthorized" });
  try {
    const updates = await retryTelegramUpdates();
    const deviceReplies = await retryDeviceCommandReplies();
    return json(res, 200, { ok: true, ...updates, deviceReplies: deviceReplies.processed });
  }
  catch (error) {
    console.error("Telegram retry sweep failed", { code: error?.code || error?.name || "error" });
    return json(res, 503, { error: "temporarily_unavailable" });
  }
}
