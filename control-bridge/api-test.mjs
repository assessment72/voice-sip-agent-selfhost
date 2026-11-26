import assert from "node:assert/strict";
import handler from "./api/v1/db.mjs";
import { ObjectId } from "mongodb";

function mockRes() {
  return { headers: {}, statusCode: 200, body: "", setHeader(key, value) { this.headers[key.toLowerCase()] = value; }, end(value = "") { this.body += value; } };
}
async function call({ method = "POST", headers = {}, body = {} } = {}) {
  const req = { method, headers, body };
  const res = mockRes();
  await handler(req, res);
  return { status: res.statusCode, body: res.body ? JSON.parse(res.body) : {}, headers: res.headers };
}
function same(a, b) { return a instanceof ObjectId || b instanceof ObjectId ? String(a) === String(b) : a === b; }
function matches(doc, query = {}) {
  return Object.entries(query).every(([key, value]) => {
    if (key === "$or") return value.some(part => matches(doc, part));
    const actual = doc[key];
    if (value && typeof value === "object" && "$regex" in value) return new RegExp(value.$regex, value.$options || "").test(String(actual || ""));
    if (Array.isArray(actual)) return actual.some(item => same(item, value));
    return same(actual, value);
  });
}
class MemoryCustomers {
  rows = new Map();
  async findOne(query) { return [...this.rows.values()].find(row => matches(row, query)) || null; }
  async replaceOne(query, document) { const id = String(query._id); this.rows.set(id, { _id: query._id, ...document }); return { acknowledged: true }; }
  find(query) {
    let results = [...this.rows.values()].filter(row => matches(row, query));
    const cursor = {
      sort: order => { const [key, dir] = Object.entries(order)[0] || []; results.sort((a,b) => (a[key] < b[key] ? -1 : a[key] > b[key] ? 1 : 0) * dir); return cursor; },
      limit: count => { results = results.slice(0, count); return cursor; },
      toArray: async () => results,
    };
    return cursor;
  }
}

const oldEnv = { nodeEnv: process.env.NODE_ENV, admin: process.env.VOICX_ADMIN_TOKEN };
process.env.NODE_ENV = "production";
process.env.VOICX_ADMIN_TOKEN = "a".repeat(48);
const customers = new MemoryCustomers();
const db = { databaseName: "voicx_sip_test", async command() { return { ok: 1 }; }, collection(name) { assert.equal(name, "customers"); return customers; } };
const mongoCache = globalThis.__voicxMongo;
mongoCache.db = db;
const adminHeaders = { "x-forwarded-proto": "https", authorization: `Bearer ${process.env.VOICX_ADMIN_TOKEN}` };
try {
  let result = await call({ method: "GET" });
  assert.equal(result.status, 405);

  result = await call({ headers: { "x-forwarded-proto": "https" }, body: { operation: "db.status" } });
  assert.equal(result.status, 401);
  assert.equal(result.body.error, "unauthorized");

  result = await call({ headers: { "x-forwarded-proto": "http", authorization: adminHeaders.authorization }, body: { operation: "db.status" } });
  assert.equal(result.status, 400);
  assert.equal(result.body.error, "https_required");

  result = await call({ headers: adminHeaders, body: { operation: "unknown.operation" } });
  assert.equal(result.status, 400);
  assert.equal(result.body.error, "unsupported_operation");

  result = await call({ headers: adminHeaders, body: { operation: "customer.upsert", args: { customer: {
    phone: "+15550001111", name: "Test Customer", instructions: "Greet them by name", groups: ["pilot"], createdAt: Date.now(), updatedAt: Date.now(),
  } } } });
  assert.equal(result.status, 200);
  assert.ok(ObjectId.isValid(result.body.id));
  const id = result.body.id;

  result = await call({ headers: adminHeaders, body: { operation: "customer.findByPhone", args: { phone: "+15550001111" } } });
  assert.equal(result.status, 200);
  assert.equal(result.body.customer.id, id);
  assert.equal(result.body.customer.instructions, "Greet them by name");

  result = await call({ headers: adminHeaders, body: { operation: "customer.findById", args: { id } } });
  assert.equal(result.body.customer.phone, "+15550001111");

  result = await call({ headers: adminHeaders, body: { operation: "customer.upsert", args: { customer: {
    id, phone: "+15550001111", name: "Updated Customer", instructions: "Updated", groups: [], createdAt: Date.now(), updatedAt: Date.now(),
  } } } });
  assert.equal(result.body.id, id);
  result = await call({ headers: adminHeaders, body: { operation: "customer.search", args: { query: "updated", limit: 20 } } });
  assert.equal(result.body.customers.length, 1);
  assert.equal(result.body.customers[0].name, "Updated Customer");

  assert.ok(!JSON.stringify(result).includes(process.env.VOICX_ADMIN_TOKEN));
  console.log("Vercel DB API auth, HTTPS enforcement, and customer read/write contract tests passed.");
} finally {
  mongoCache.db = null;
  if (oldEnv.nodeEnv === undefined) delete process.env.NODE_ENV; else process.env.NODE_ENV = oldEnv.nodeEnv;
  if (oldEnv.admin === undefined) delete process.env.VOICX_ADMIN_TOKEN; else process.env.VOICX_ADMIN_TOKEN = oldEnv.admin;
}
