import assert from "node:assert/strict";
import { ObjectId } from "mongodb";

const oldEnv = { nodeEnv: process.env.NODE_ENV, admin: process.env.VOICX_ADMIN_TOKEN };
process.env.NODE_ENV = "production";
process.env.VOICX_ADMIN_TOKEN = "b".repeat(48);

const comparable = value => value instanceof Date ? value.getTime() : value instanceof ObjectId ? value.toString() : value;
function matches(doc, query = {}) {
  return Object.entries(query).every(([key, expected]) => {
    if (key === "$or") return expected.some(part => matches(doc, part));
    const actual = doc[key];
    if (expected && typeof expected === "object" && !(expected instanceof Date) && !(expected instanceof ObjectId)) {
      if ("$lt" in expected && !(comparable(actual) < comparable(expected.$lt))) return false;
      if ("$lte" in expected && !(comparable(actual) <= comparable(expected.$lte))) return false;
      if ("$gt" in expected && !(comparable(actual) > comparable(expected.$gt))) return false;
      if ("$ne" in expected && comparable(actual) === comparable(expected.$ne)) return false;
      if ("$exists" in expected && Boolean(actual !== undefined) !== expected.$exists) return false;
      if ("$regex" in expected && !new RegExp(expected.$regex, expected.$options || "").test(String(actual || ""))) return false;
      return true;
    }
    if (Array.isArray(actual)) return actual.some(value => comparable(value) === comparable(expected));
    return comparable(actual) === comparable(expected);
  });
}
function applyUpdate(doc, update, inserting = false) {
  for (const [key, value] of Object.entries(update.$set || {})) doc[key] = value;
  if (inserting) for (const [key, value] of Object.entries(update.$setOnInsert || {})) if (doc[key] === undefined) doc[key] = value;
  for (const [key, value] of Object.entries(update.$inc || {})) doc[key] = (doc[key] || 0) + value;
  for (const key of Object.keys(update.$unset || {})) delete doc[key];
}
class MemoryCollection {
  rows = [];
  failNextUpdate = false;
  assertUnique(candidate, ignore = null) {
    for (const key of ["idempotencyKey", "reportId", "callReportId"]) {
      if (candidate[key] != null && this.rows.some(row => row !== ignore && row[key] === candidate[key])) {
        const error = new Error(`duplicate key: ${key}`); error.code = 11000; throw error;
      }
    }
  }
  async findOne(query) { return this.rows.find(row => matches(row, query)) || null; }
  find(query = {}) {
    let rows = this.rows.filter(row => matches(row, query));
    const cursor = {
      sort: order => { rows.sort((a, b) => { for (const [key, dir] of Object.entries(order)) { const av = comparable(a[key]); const bv = comparable(b[key]); if (av !== bv) return (av < bv ? -1 : 1) * dir; } return 0; }); return cursor; },
      limit: count => { rows = rows.slice(0, count); return cursor; },
      toArray: async () => rows,
      next: async () => rows[0] || null,
    };
    return cursor;
  }
  async insertOne(document) {
    const row = { ...document, _id: document._id || new ObjectId() };
    this.assertUnique(row);
    this.rows.push(row);
    return { acknowledged: true, insertedId: row._id };
  }
  async replaceOne(query, document) {
    const index = this.rows.findIndex(row => matches(row, query));
    const row = { ...document, _id: query._id || document._id || new ObjectId() };
    this.assertUnique(row, index >= 0 ? this.rows[index] : null);
    if (index >= 0) this.rows[index] = row; else this.rows.push(row);
    return { acknowledged: true, upsertedId: index < 0 ? row._id : null };
  }
  async updateOne(query, update, options = {}) {
    if (this.failNextUpdate) { this.failNextUpdate = false; throw new Error("simulated transient write failure"); }
    let row = this.rows.find(item => matches(item, query));
    let inserted = false;
    if (!row && options.upsert) {
      row = {};
      for (const [key, value] of Object.entries(query)) if (!key.startsWith("$") && (!value || typeof value !== "object" || value instanceof Date || value instanceof ObjectId)) row[key] = value;
      row._id ||= new ObjectId();
      inserted = true;
      this.rows.push(row);
    }
    if (row) applyUpdate(row, update, inserted);
    return { acknowledged: true, matchedCount: row && !inserted ? 1 : 0, upsertedCount: inserted ? 1 : 0 };
  }
  async updateMany(query, update) {
    let matchedCount = 0;
    for (const row of this.rows.filter(item => matches(item, query))) { applyUpdate(row, update); matchedCount++; }
    return { acknowledged: true, matchedCount };
  }
  async findOneAndUpdate(query, update, options = {}) {
    let candidates = this.rows.filter(row => matches(row, query));
    if (options.sort) candidates = this.find(query).sort(options.sort).limit(1) && candidates.sort((a, b) => {
      for (const [key, dir] of Object.entries(options.sort)) { const av = comparable(a[key]); const bv = comparable(b[key]); if (av !== bv) return (av < bv ? -1 : 1) * dir; }
      return 0;
    });
    const row = candidates[0];
    if (!row) return null;
    applyUpdate(row, update);
    return row;
  }
}
class MemoryDb {
  databaseName = "voicx_stage2_test";
  collections = new Map();
  async command() { return { ok: 1 }; }
  collection(name) { if (!this.collections.has(name)) this.collections.set(name, new MemoryCollection()); return this.collections.get(name); }
}
function response() { return { statusCode: 200, headers: {}, body: "", setHeader(k, v) { this.headers[k.toLowerCase()] = v; }, end(body = "") { this.body += body; } }; }
const db = new MemoryDb();
globalThis.__voicxMongo = { client: null, promise: null, db };
const handler = (await import("./api/v1/db.mjs")).default;
const headers = { "x-forwarded-proto": "https", authorization: `Bearer ${process.env.VOICX_ADMIN_TOKEN}` };
async function call(operation, args = {}) {
  const res = response();
  await handler({ method: "POST", headers, body: { operation, args } }, res);
  return { status: res.statusCode, body: res.body ? JSON.parse(res.body) : {} };
}

try {
  const customer = await call("customer.upsert", { customer: { phone: "+15550002222", name: "Stage Two", instructions: "Use the account notes", groups: [] } });
  assert.equal(customer.status, 200);
  const id = customer.body.id;
  assert.ok(ObjectId.isValid(id));

  await call("memory.add", { customerId: id, text: "Prefers an afternoon callback", source: "test" });
  let context = await call("customer.callContext", { phone: "+15550002222" });
  assert.equal(context.body.customer.instructions, "Use the account notes");
  assert.deepEqual(context.body.memories, ["Prefers an afternoon callback"]);
  assert.deepEqual((await call("memory.forPhone", { phone: "+15550002222" })).body.memories, ["Prefers an afternoon callback"]);

  const calls = db.collection("call_summaries"), memories = db.collection("customer_memories");
  memories.failNextUpdate = true;
  const report = { number: "+15550002222", name: "Stage Two", endedAt: Date.now(), cause: "NORMAL", conversationTranscript: "Customer asked for a callback tomorrow." };
  let saved = await call("call.report", { reportId: "stable-gemini-report-001", report });
  assert.equal(saved.status, 500, "simulated partial failure should be retried by the Android outbox");
  saved = await call("call.report", { reportId: "stable-gemini-report-001", report });
  assert.equal(saved.status, 200);
  assert.equal(calls.rows.length, 1, "report retries must not duplicate summaries");
  assert.equal(memories.rows.filter(row => row.callReportId === "stable-gemini-report-001").length, 1,
    "retry must complete the customer-memory side effect exactly once");

  const due = Date.now() - 1000;
  const scheduleArgs = { schedule: { customerId: id, phone: "+15550002222", runAt: due, status: "pending", idempotencyKey: "call-key-1" } };
  const [callSaveA, callSaveB] = await Promise.all([call("schedule.save", scheduleArgs), call("schedule.save", scheduleArgs)]);
  assert.equal(callSaveA.body.id, callSaveB.body.id, "same call schedule idempotency key must return one ID");
  const claims = await Promise.all([call("schedule.claim", { now: Date.now() }), call("schedule.claim", { now: Date.now() })]);
  assert.equal(claims.filter(result => result.body.schedule).length, 1, "atomic claim must grant a due call to one worker only");
  const callId = claims.find(result => result.body.schedule).body.schedule.id;
  await call("schedule.update", { id: callId, status: "completed" });
  assert.equal(db.collection("call_schedules").rows[0].status, "completed");

  const smsArgs = { schedule: { customerId: id, phone: "+15550002222", runAt: due, message: "Hello", status: "queued", idempotencyKey: "sms-key-1" } };
  const sms1 = await call("smsSchedule.save", smsArgs), sms2 = await call("smsSchedule.save", smsArgs);
  assert.equal(sms1.body.id, sms2.body.id, "SMS schedule retries must keep a stable message identity");
  let sms = (await call("smsSchedule.claim", { now: Date.now() })).body.schedule;
  assert.ok(sms && sms.attempts === 1);
  let retry = await call("smsSchedule.retry", { id: sms.id, error: "timeout" });
  assert.equal(retry.body.status, "queued");
  assert.equal(retry.body.attempts, 1, "claim, not retry bookkeeping, increments the delivery attempt count");
  sms = (await call("smsSchedule.claim", { now: Date.now() + 61_000 })).body.schedule;
  assert.equal(sms.attempts, 2);
  retry = await call("smsSchedule.retry", { id: sms.id, error: "timeout again" });
  assert.equal(retry.body.status, "queued");
  sms = (await call("smsSchedule.claim", { now: Date.now() + 6 * 60_000 })).body.schedule;
  assert.equal(sms.attempts, 3);
  retry = await call("smsSchedule.retry", { id: sms.id, error: "final failure" });
  assert.equal(retry.body.status, "failed");
  assert.equal(db.collection("sms_schedules").rows[0].attempts, 3);

  console.log("Stage 2 DB contract tests passed: cached call context, memory, idempotent reports, atomic schedule claim, SMS identity/retry accounting.");
} finally {
  globalThis.__voicxMongo.db = null;
  if (oldEnv.nodeEnv === undefined) delete process.env.NODE_ENV; else process.env.NODE_ENV = oldEnv.nodeEnv;
  if (oldEnv.admin === undefined) delete process.env.VOICX_ADMIN_TOKEN; else process.env.VOICX_ADMIN_TOKEN = oldEnv.admin;
}
