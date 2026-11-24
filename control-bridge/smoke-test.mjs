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

  console.log("Voicx bridge smoke test passed");
} finally {
  child.kill("SIGTERM");
  await rm(dir, { recursive: true, force: true });
}
