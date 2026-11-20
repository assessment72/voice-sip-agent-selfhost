import test from "node:test";
import assert from "node:assert/strict";
import { createCentralMcpServer } from "./server.mjs";

async function start(t, options = {}) {
  const app = createCentralMcpServer({ adminToken: "admin-test-token", credentials: { gw01: "device-test-token" }, ...options });
  await new Promise(resolve => app.httpServer.listen(0, "127.0.0.1", resolve));
  const port = app.httpServer.address().port;
  t.after(() => new Promise(resolve => app.httpServer.close(resolve)));
  return { app, url: `http://127.0.0.1:${port}` };
}
async function mcp(url, method, params = {}, token = "admin-test-token") {
  return fetch(url + "/mcp", {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params })
  });
}

test("health is readable and reveals no credentials", async t => {
  const { url } = await start(t);
  const response = await fetch(url + "/healthz");
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true, service: "voice-sip-central-mcp", connected_gateways: 0 });
});
test("MCP endpoint rejects missing admin authentication", async t => {
  const { url } = await start(t);
  const response = await mcp(url, "tools/list", {}, "wrong");
  assert.equal(response.status, 401);
});
test("MCP initialize and tools/list expose central multi-gateway tools", async t => {
  const { url } = await start(t);
  const init = await (await mcp(url, "initialize")).json();
  assert.equal(init.result.serverInfo.name, "voice-sip-central-mcp");
  const listing = await (await mcp(url, "tools/list")).json();
  const names = listing.result.tools.map(tool => tool.name);
  assert.ok(names.includes("gateways_list"));
  assert.ok(names.includes("gateway_request_call_action"));
  assert.ok(names.includes("gateway_execute_approved_action"));
});
test("gateway commands fail closed when target gateway is offline", async t => {
  const { url } = await start(t);
  const response = await mcp(url, "tools/call", { name: "gateway_status", arguments: { gateway_id: "gw01" } });
  const body = await response.json();
  assert.equal(body.result.isError, true);
  assert.equal(body.result.content[0].text, "gateway_offline");
});
test("call requests require explicit gateway and valid action data", async t => {
  const { url } = await start(t);
  const response = await mcp(url, "tools/call", { name: "gateway_request_call_action", arguments: { gateway_id: "gw01", action: "DIAL" } });
  const body = await response.json();
  assert.equal(body.result.isError, true);
  assert.equal(body.result.content[0].text, "dial_target_required");
});
