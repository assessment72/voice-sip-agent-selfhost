import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { randomBytes } from "node:crypto";
import assert from "node:assert/strict";

const secret = () => randomBytes(32).toString("hex");
const admin = secret();
const deviceToken = secret();
const dir = await mkdtemp(path.join(tmpdir(), "voicx-bridge-"));
const port = 19000 + Math.floor(Math.random() * 10000);
const child = spawn(process.execPath, [new URL("./server.mjs", import.meta.url).pathname], {
  env: { ...process.env, HOST: "127.0.0.1", PORT: String(port),
    VOICX_ADMIN_TOKEN: admin,
    VOICX_DEVICES: JSON.stringify([{ id: "test-01", name: "Test handset", token: deviceToken }]),
    VOICX_STORE: path.join(dir, "store.json") },
  stdio: "ignore"
});
const base = `http://127.0.0.1:${port}`;
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
async function request(pathname, token, options = {}) {
  return fetch(base + pathname, { ...options, headers: {
    authorization: `Bearer ${token}`, ...(options.body ? { "content-type": "application/json" } : {}),
    ...(options.headers || {})
  }});
}
try {
  let ready = false;
  for (let i = 0; i < 50; i++) {
    try { if ((await fetch(base + "/health")).ok) { ready = true; break; } } catch {}
    await wait(100);
  }
  assert.equal(ready, true, "bridge did not start");

  assert.equal((await request("/api/v1/devices", "wrong")).status, 401);
  let r = await request("/api/v1/devices/command", admin, {
    method: "POST", body: JSON.stringify({ deviceId: "test-01", type: "service.status" }),
    headers: { "idempotency-key": "smoke-1" }
  });
  assert.equal(r.status, 202);
  const { command } = await r.json();

  r = await request("/api/v1/devices/test-01/heartbeat", deviceToken, {
    method: "POST", body: JSON.stringify({ battery: 88, charging: true, serviceState: "running" })
  });
  assert.equal(r.status, 200);

  r = await request("/api/v1/devices/test-01/commands/next", deviceToken);
  assert.equal(r.status, 200);
  const polled = await r.json();
  assert.equal(polled.commands.length, 1);
  assert.equal(polled.commands[0].id, command.id);

  r = await request(`/api/v1/devices/test-01/commands/${command.id}/result`, deviceToken, {
    method: "POST", body: JSON.stringify({ status: "succeeded", result: { serviceState: "running" } })
  });
  assert.equal(r.status, 200);

  r = await request(`/api/v1/commands/${command.id}`, admin);
  assert.equal((await r.json()).command.status, "succeeded");

  r = await request("/api/v1/devices", admin);
  const devices = await r.json();
  assert.equal(devices.devices[0].online, true);
  assert.equal(devices.devices[0].battery, 88);

  r = await request("/api/v1/devices/test-01/calls", admin, { method: "POST", body: "{}" });
  assert.equal(r.status, 501, "real call route must remain disabled");

  r = await request("/api/v1/crm/contacts", admin, {
    method: "POST", body: JSON.stringify({ name: "Test Customer", phone: "+967700000001",
      email: "customer@example.test", jobTitle: "Engineer", company: "Example Co",
      professionalEmail: "work@example.test", previousMessages: [], previousCallSummary: "Initial call",
      nextSendAt: "2026-12-01T10:00:00Z" })
  });
  assert.equal(r.status, 201, "CRM contact creation");
  const { contact } = await r.json();
  assert.equal(contact.phone, "+967700000001");

  r = await request("/api/v1/crm/contacts/search", admin, {
    method: "POST", body: JSON.stringify({ query: "Test Customer" })
  });
  assert.equal(r.status, 200);
  assert.equal((await r.json()).contacts.length, 1);

  r = await request("/api/v1/crm/sms", admin, {
    method: "POST", body: JSON.stringify({ contactId: contact.id, message: "Hello" })
  });
  assert.equal(r.status, 202, "SMS should be queued, not falsely reported as sent");
  const sms = await r.json();
  assert.equal(sms.job.status, "queued");
  assert.equal(sms.job.provider, "not_configured");

  r = await request("/api/v1/crm/tasks", admin, {
    method: "POST", body: JSON.stringify({ title: "Follow up", dueAt: new Date().toISOString(), status: "open" })
  });
  assert.equal(r.status, 201, "daily task creation");

  r = await request("/api/v1/crm/tasks/today", admin);
  assert.equal(r.status, 200);
  assert.equal((await r.json()).tasks.length, 1);

  r = await request("/api/v1/crm/marketing/status", admin);
  assert.equal(r.status, 200);
  const marketing = await r.json();
  assert.equal(marketing.contacts, 1);
  assert.equal(marketing.sms, 1);

  r = await request("/api/v1/crm/contacts/" + contact.id + "/messages", admin);
  assert.equal(r.status, 200, "contact message history route");
  assert.ok(Array.isArray((await r.json()).messages));

  r = await request("/api/v1/crm/campaigns", admin, {
    method: "POST", body: JSON.stringify({ name: "Winter follow-up", channel: "sms", message: "Hello {{name}}", status: "draft" })
  });
  assert.equal(r.status, 201, "campaign creation");

  r = await request("/api/v1/crm/templates", admin, {
    method: "POST", body: JSON.stringify({ name: "Welcome", channel: "whatsapp", message: "Welcome {{name}}", enabled: true })
  });
  assert.equal(r.status, 201, "message template creation");

  r = await request("/api/v1/crm/dashboard", admin);
  assert.equal(r.status, 200, "marketing dashboard");
  const dashboard = await r.json();
  assert.equal(dashboard.contacts, 1);
  assert.ok(Array.isArray(dashboard.dueToday));

  r = await request("/api/v1/crm/reports/generate", admin, {
    method: "POST", body: JSON.stringify({ periodStart: new Date(Date.now() - 86400000).toISOString(), periodEnd: new Date(Date.now() + 1000).toISOString() })
  });
  assert.equal(r.status, 201, "marketing report generation");
  assert.ok((await r.json()).report.metrics);

  r = await request("/api/v1/crm/publications/haraj", admin, {
    method: "POST", body: JSON.stringify({ title: "Listing", content: "Draft content" })
  });
  assert.equal(r.status, 202, "Haraj publishing must remain draft/manual review");

  console.log("Voicx bridge smoke test passed");
} finally {
  child.kill("SIGTERM");
  await rm(dir, { recursive: true, force: true });
}
