import assert from "node:assert/strict";
import { ObjectId } from "mongodb";

const oldEnv = { nodeEnv: process.env.NODE_ENV, admin: process.env.VOICX_ADMIN_TOKEN, ids: process.env.TELEGRAM_ADMIN_IDS,
  webhook: process.env.TELEGRAM_WEBHOOK_SECRET, cron: process.env.CRON_SECRET, bot: process.env.TELEGRAM_BOT_TOKEN,
  deviceTokens: process.env.VOICX_DEVICE_TOKENS };
process.env.NODE_ENV = "production";
process.env.VOICX_ADMIN_TOKEN = "c".repeat(48);
process.env.TELEGRAM_ADMIN_IDS = "123456789";
process.env.TELEGRAM_WEBHOOK_SECRET = "webhook-secret-" + "d".repeat(32);
process.env.CRON_SECRET = "cron-secret-" + "e".repeat(32);
process.env.TELEGRAM_BOT_TOKEN = "123456789:" + "A".repeat(32);
process.env.VOICX_DEVICE_TOKENS = JSON.stringify({ "phone-01": "phone-device-token-" + "f".repeat(32) });
const comparable = value => value instanceof Date ? value.getTime() : value instanceof ObjectId ? String(value) : value;
function matches(doc, query = {}) {
  return Object.entries(query).every(([key, expected]) => {
    if (key === "$or") return expected.some(part => matches(doc, part));
    const actual = doc[key];
    if (expected && typeof expected === "object" && !(expected instanceof Date) && !(expected instanceof ObjectId)) {
      if ("$in" in expected && !expected.$in.includes(actual)) return false;
      if ("$lt" in expected && !(comparable(actual) < comparable(expected.$lt))) return false;
      if ("$lte" in expected && !(comparable(actual) <= comparable(expected.$lte))) return false;
      if ("$exists" in expected && Boolean(actual !== undefined) !== expected.$exists) return false;
      return true;
    }
    return comparable(actual) === comparable(expected);
  });
}
function apply(row, update, insert = false) {
  for (const [key, value] of Object.entries(update.$set || {})) row[key] = value;
  if (insert) for (const [key, value] of Object.entries(update.$setOnInsert || {})) if (row[key] === undefined) row[key] = value;
  for (const [key, value] of Object.entries(update.$inc || {})) row[key] = (row[key] || 0) + value;
  for (const key of Object.keys(update.$unset || {})) delete row[key];
}
class Collection {
  rows = [];
  async findOne(q) { return this.rows.find(r => matches(r, q)) || null; }
  async updateOne(q, update, options = {}) {
    let row = this.rows.find(r => matches(r, q)); let inserted = false;
    if (!row && options.upsert) {
      row = {};
      for (const [key, value] of Object.entries(q)) if (!key.startsWith("$") && (!value || typeof value !== "object" || value instanceof Date || value instanceof ObjectId)) row[key] = value;
      row._id ||= new ObjectId(); inserted = true; this.rows.push(row);
    }
    if (row) apply(row, update, inserted);
    return { matchedCount: row && !inserted ? 1 : 0, upsertedCount: inserted ? 1 : 0 };
  }
  find(q = {}) {
    let rows = this.rows.filter(r => matches(r, q));
    const cursor = {
      sort: order => { rows.sort((a, b) => { for (const [k, dir] of Object.entries(order)) { const av = comparable(a[k]), bv = comparable(b[k]); if (av !== bv) return (av < bv ? -1 : 1) * dir; } return 0; }); return cursor; },
      limit: n => { rows = rows.slice(0, n); return cursor; },
      next: async () => rows[0] || null,
      toArray: async () => rows,
    };
    return cursor;
  }
  async findOneAndUpdate(q, update, options = {}) {
    let rows = this.rows.filter(r => matches(r, q));
    if (options.sort) rows.sort((a, b) => (a.createdAt || 0) < (b.createdAt || 0) ? -1 : 1);
    const row = rows[0]; if (!row) return null; apply(row, update); return row;
  }
  async replaceOne(q, doc) {
    const index = this.rows.findIndex(r => matches(r, q)); const row = { ...doc, _id: q._id || doc._id || new ObjectId() };
    if (index < 0) this.rows.push(row); else this.rows[index] = row;
  }
  async insertOne(doc) { const row = { ...doc, _id: doc._id || new ObjectId() }; this.rows.push(row); return { insertedId: row._id }; }
}
class Db {
  databaseName = "telegram_test"; collections = new Map();
  async command() { return { ok: 1 }; }
  collection(name) { if (!this.collections.has(name)) this.collections.set(name, new Collection()); return this.collections.get(name); }
}
function res() { return { statusCode: 200, headers: {}, body: "", setHeader(k, v) { this.headers[k.toLowerCase()] = v; }, end(v = "") { this.body += v; } }; }
const db = new Db();
globalThis.__voicxMongo = { client: null, promise: null, db };
const webhook = (await import("./api/v1/telegram/webhook.mjs")).default;
const retry = (await import("./api/v1/telegram/retry.mjs")).default;
const heartbeat = (await import("./api/v1/devices/[deviceId]/heartbeat.mjs")).default;
const update = { update_id: 777, message: { message_id: 4, text: "/customer_add +15550003333 | Webhook Customer | manager | Voicx | Call after 5pm", from: { id: 123456789 }, chat: { id: 123456789, type: "private" } } };
let failFirstTelegramSend = true;
const sent = [];
globalThis.fetch = async (_url, init) => {
  const body = JSON.parse(init.body); sent.push(body);
  if (failFirstTelegramSend) { failFirstTelegramSend = false; throw new Error("simulated Telegram network outage"); }
  return { ok: true, async json() { return { ok: true, result: { message_id: sent.length } }; } };
};
async function call(handler, { headers, body = update } = {}) {
  const response = res();
  await handler({ method: "POST", headers: { "x-forwarded-proto": "https", ...headers }, body }, response);
  return { status: response.statusCode, body: response.body ? JSON.parse(response.body) : {} };
}
try {
  let result = await call(webhook, { headers: { "x-telegram-bot-api-secret-token": "wrong" } });
  assert.equal(result.status, 401);
  result = await call(webhook, { headers: { "x-telegram-bot-api-secret-token": process.env.TELEGRAM_WEBHOOK_SECRET } });
  assert.equal(result.status, 503, "failed reply delivery must leave the update pending and make Telegram retry");
  const updateRow = await db.collection("telegram_updates").findOne({ updateId: update.update_id });
  assert.equal(updateRow.status, "queued");
  assert.equal(updateRow.attempts, 1);
  assert.ok(updateRow.replyText.includes("تم حفظ العميل"));
  assert.equal(db.collection("customers").rows.length, 1);

  await db.collection("telegram_updates").updateOne({ updateId: update.update_id }, { $set: { nextAttemptAt: new Date(0) } });
  result = await call(retry, { headers: { authorization: `Bearer ${process.env.CRON_SECRET}` }, body: {} });
  assert.equal(result.status, 200);
  assert.equal(result.body.processed, 1);
  assert.equal(updateRow.status, "completed");
  assert.equal(updateRow.attempts, 2);
  assert.equal(db.collection("customers").rows.length, 1, "retry must not duplicate the database side effect");
  assert.equal(sent.length, 2, "saved reply is redelivered exactly once after recovery");

  result = await call(webhook, { headers: { "x-telegram-bot-api-secret-token": process.env.TELEGRAM_WEBHOOK_SECRET } });
  assert.equal(result.status, 200);
  assert.equal(result.body.replayed, true);
  assert.equal(sent.length, 2, "duplicate Telegram update must not resend a completed reply");

  const heartbeatResponse = res();
  await heartbeat({ method: "POST", query: { deviceId: "phone-01" }, headers: { "x-forwarded-proto": "https",
    "x-voicx-device-id": "phone-01", authorization: `Bearer ${JSON.parse(process.env.VOICX_DEVICE_TOKENS)["phone-01"]}` },
    body: { serviceState: "running", sipReady: true, sipDetail: "registered", activeCall: false, takeover: true, battery: 72 } }, heartbeatResponse);
  assert.equal(heartbeatResponse.statusCode, 200);
  const sipUpdate = structuredClone(update); sipUpdate.update_id = 778; sipUpdate.message.text = "/sip";
  result = await call(webhook, { headers: { "x-telegram-bot-api-secret-token": process.env.TELEGRAM_WEBHOOK_SECRET }, body: sipUpdate });
  assert.equal(result.status, 200);
  assert.ok(sent.at(-1).text.includes("SIP: جاهز"));

  const unauthorized = structuredClone(update); unauthorized.update_id++; unauthorized.message.chat.type = "group";
  result = await call(webhook, { headers: { "x-telegram-bot-api-secret-token": process.env.TELEGRAM_WEBHOOK_SECRET }, body: unauthorized });
  assert.equal(result.status, 200);
  assert.equal(result.body.ignored, true);
  assert.equal(db.collection("telegram_updates").rows.length, 2);

  console.log("Telegram webhook tests passed: HTTPS secret, admin/private-chat filtering, durable update logging, idempotent command replay, and retry sweep.");
} finally {
  globalThis.__voicxMongo.db = null;
  if (oldEnv.nodeEnv === undefined) delete process.env.NODE_ENV; else process.env.NODE_ENV = oldEnv.nodeEnv;
  for (const [key, value] of [["VOICX_ADMIN_TOKEN", oldEnv.admin], ["TELEGRAM_ADMIN_IDS", oldEnv.ids], ["TELEGRAM_WEBHOOK_SECRET", oldEnv.webhook], ["CRON_SECRET", oldEnv.cron], ["TELEGRAM_BOT_TOKEN", oldEnv.bot], ["VOICX_DEVICE_TOKENS", oldEnv.deviceTokens]]) {
    if (value === undefined) delete process.env[key]; else process.env[key] = value;
  }
}
