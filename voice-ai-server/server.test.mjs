import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import net from "node:net";
import http from "node:http";
import { once } from "node:events";

async function freePort() {
  const server = net.createServer();
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const { port } = server.address();
  await new Promise((resolve) => server.close(resolve));
  return port;
}

test("health endpoint exposes readiness without secrets", async (t) => {
  const port = await freePort();
  const child = spawn(process.execPath, ["server.mjs"], {
    cwd: new URL(".", import.meta.url),
    env: { ...process.env, PORT: String(port), OPENAI_API_KEY: "test-key", VOICE_GATEWAY_TOKEN: "test-token" },
    stdio: "ignore"
  });
  t.after(() => child.kill("SIGTERM"));

  let response;
  for (let attempt = 0; attempt < 50; attempt++) {
    try {
      response = await fetch(`http://127.0.0.1:${port}/healthz`);
      break;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
  }
  assert.ok(response, "server did not start");
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.deepEqual(body, { ok: true, service: "voice-realtime-relay" });
  assert.equal(JSON.stringify(body).includes("test-key"), false);
});

test("websocket upgrade rejects missing gateway bearer token", async (t) => {
  const port = await freePort();
  const child = spawn(process.execPath, ["server.mjs"], {
    cwd: new URL(".", import.meta.url),
    env: { ...process.env, PORT: String(port), OPENAI_API_KEY: "test-key", VOICE_GATEWAY_TOKEN: "test-token" },
    stdio: "ignore"
  });
  t.after(() => child.kill("SIGTERM"));

  for (let attempt = 0; attempt < 50; attempt++) {
    try { await fetch(`http://127.0.0.1:${port}/healthz`); break; }
    catch { await new Promise((resolve) => setTimeout(resolve, 50)); }
  }

  const status = await new Promise((resolve, reject) => {
    const req = http.request({
      host: "127.0.0.1", port, path: "/v1/realtime", headers: {
        Connection: "Upgrade", Upgrade: "websocket", "Sec-WebSocket-Version": "13",
        "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ=="
      }
    });
    req.on("upgrade", () => reject(new Error("unauthenticated websocket unexpectedly upgraded")));
    req.on("response", (res) => { res.resume(); resolve(res.statusCode); });
    req.on("error", reject);
    req.end();
  });
  assert.equal(status, 401);
});
