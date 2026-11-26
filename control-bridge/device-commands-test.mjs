import assert from "node:assert/strict";
import { ObjectId } from "mongodb";

const old = {
  nodeEnv: process.env.NODE_ENV,
  admin: process.env.VOICX_ADMIN_TOKEN,
  ids: process.env.TELEGRAM_ADMIN_IDS,
  webhook: process.env.TELEGRAM_WEBHOOK_SECRET,
  bot: process.env.TELEGRAM_BOT_TOKEN,
  devices: process.env.VOICX_DEVICE_TOKENS,
  telegramDevice: process.env.VOICX_TELEGRAM_DEVICE_ID,
};
process.env.NODE_ENV = "production";
process.env.VOICX_ADMIN_TOKEN = "a".repeat(48);
process.env.TELEGRAM_ADMIN_IDS = "123456789";
process.env.TELEGRAM_WEBHOOK_SECRET = "w".repeat(40);
process.env.TELEGRAM_BOT_TOKEN = "123456789:" + "B".repeat(32);
process.env.VOICX_TELEGRAM_DEVICE_ID = "phone-01";
const deviceToken = "d".repeat(40);
process.env.VOICX_DEVICE_TOKENS = JSON.stringify({ "phone-01": deviceToken });

const comparable = value => value instanceof Date ? value.getTime() : value instanceof ObjectId ? String(value) : value;
function matches(doc, query = {}) {
  return Object.entries(query).every(([key, expected]) => {
    if (key === "$or") return expected.some(part => matches(doc, part));
    const actual = doc[key];
    if (expected && typeof expected === "object" && !(expected instanceof Date) && !(expected instanceof ObjectId)) {
      if ("$in" in expected && !expected.$in.includes(actual)) return false;
      if ("$lte" in expected && !(comparable(actual) <= comparable(expected.$lte))) return false;
      if ("$lt" in expected && !(comparable(actual) < comparable(expected.$lt))) return false;
      return true;
    }
    return comparable(actual) === comparable(expected);
  });
}
function apply(row, update, insert = false) {
  if (insert) for (const [key, value] of Object.entries(update.$setOnInsert || {})) row[key] = value;
  for (const [key, value] of Object.entries(update.$set || {})) row[key] = value;
  for (const [key, value] of Object.entries(update.$inc || {})) row[key] = (row[key] || 0) + value;
  for (const key of Object.keys(update.$unset || {})) delete row[key];
}
class Collection {
  rows = [];
  async findOne(query) { return this.rows.find(row => matches(row, query)) || null; }
  async updateOne(query, update, options = {}) {
    let row = this.rows.find(item => matches(item, query));
    if (!row && options.upsert) { row = { _id: new ObjectId() }; this.rows.push(row); apply(row, update, true); return { matchedCount: 0 }; }
    if (!row) return { matchedCount: 0 };
    apply(row, update); return { matchedCount: 1 };
  }
  async updateMany(query, update) { let count = 0; for (const row of this.rows) if (matches(row, query)) { apply(row, update); count++; } return { matchedCount: count }; }
  async findOneAndUpdate(query, update, options = {}) {
    const candidates = this.rows.filter(row => matches(row, query));
    candidates.sort((a, b) => (a.createdAt?.getTime?.() || 0) - (b.createdAt?.getTime?.() || 0));
    const row = candidates[0]; if (!row) return null; apply(row, update); return row;
  }
}
class Db { collections = new Map(); collection(name) { if (!this.collections.has(name)) this.collections.set(name, new Collection()); return this.collections.get(name); } }
const db = new Db();
globalThis.__voicxMongo = { client: null, promise: null, db };
const webhook = (await import("./api/v1/telegram/webhook.mjs")).default;
const next = (await import("./api/v1/devices/[deviceId]/commands/next.mjs")).default;
const result = (await import("./api/v1/devices/[deviceId]/commands/[commandId]/result.mjs")).default;
function response() { return { statusCode: 200, body: "", setHeader() {}, end(value = "") { this.body += value; } }; }
async function invoke(handler, req) { const res = response(); await handler(req, res); return { status: res.statusCode, body: res.body ? JSON.parse(res.body) : {} }; }
const auth = { "x-forwarded-proto": "https", "x-voicx-device-id": "phone-01", authorization: `Bearer ${deviceToken}` };
let sent = [];
globalThis.fetch = async (_url, init) => { sent.push(JSON.parse(init.body)); return { ok: true, async json() { return { ok: true, result: { message_id: sent.length } }; } }; };
try {
  const update = { update_id: 9001, message: { text: "/sms +15550001111 | hello", from: { id: 123456789 }, chat: { id: 123456789, type: "private" } } };
  const accepted = await invoke(webhook, { method: "POST", headers: { "x-forwarded-proto": "https", "x-telegram-bot-api-secret-token": process.env.TELEGRAM_WEBHOOK_SECRET }, body: update });
  assert.equal(accepted.status, 200);
  const command = db.collection("device_commands").rows[0];
  assert.equal(command.status, "queued");
  let polled = await invoke(next, { method: "GET", query: { deviceId: "phone-01" }, headers: auth });
  assert.equal(polled.status, 200);
  assert.equal(polled.body.commands.length, 1);
  const leased = polled.body.commands[0];
  const empty = await invoke(next, { method: "GET", query: { deviceId: "phone-01" }, headers: auth });
  assert.equal(empty.body.commands.length, 0, "active lease is not delivered twice");
  command.leaseUntil = new Date(0);
  polled = await invoke(next, { method: "GET", query: { deviceId: "phone-01" }, headers: auth });
  assert.equal(polled.body.commands.length, 1, "expired lease is redelivered");
  const redelivered = polled.body.commands[0];
  const done = await invoke(result, { method: "POST", query: { deviceId: "phone-01", commandId: redelivered.id }, headers: auth, body: {
    status: "succeeded", leaseToken: redelivered.leaseToken, result: { text: "تم إرسال SMS" },
  } });
  assert.equal(done.status, 200);
  assert.equal(done.body.replyStatus, "sent");
  const replay = await invoke(result, { method: "POST", query: { deviceId: "phone-01", commandId: redelivered.id }, headers: auth, body: {
    status: "succeeded", leaseToken: redelivered.leaseToken, result: { text: "لا تكرر" },
  } });
  assert.equal(replay.body.replayed, true);
  assert.equal(sent.at(-1).text, "تم إرسال SMS");
  console.log("Device command queue tests passed: atomic lease, expired lease redelivery, result acknowledgement, and replay-safe Telegram delivery.");
} finally {
  globalThis.__voicxMongo.db = null;
  for (const [key, value] of Object.entries({ NODE_ENV: old.nodeEnv, VOICX_ADMIN_TOKEN: old.admin, TELEGRAM_ADMIN_IDS: old.ids, TELEGRAM_WEBHOOK_SECRET: old.webhook, TELEGRAM_BOT_TOKEN: old.bot, VOICX_DEVICE_TOKENS: old.devices, VOICX_TELEGRAM_DEVICE_ID: old.telegramDevice })) {
    if (value === undefined) delete process.env[key]; else process.env[key] = value;
  }
}
