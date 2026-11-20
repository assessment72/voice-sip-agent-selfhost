import test from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { WebSocket } from "ws";
import { createCentralMcpServer } from "./server.mjs";

async function setup(t) {
  const app = createCentralMcpServer({
    adminToken: "admin-test-token",
    credentials: { gw01: "token-01", gw02: "token-02" },
    routingPolicy: { gw01: { priority: 1 }, gw02: { priority: 10 } },
    requestTimeoutMs: 1_000
  });
  await new Promise(resolve => app.httpServer.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${app.httpServer.address().port}`;
  const sockets = [];
  t.after(() => new Promise(resolve => {
    for (const ws of sockets) ws.terminate();
    app.httpServer.closeAllConnections();
    app.httpServer.close(resolve);
  }));
  async function connect(id, token, metadata = {}) {
    const ws = new WebSocket(base.replace("http:", "ws:") + "/v1/agent", {
      headers: { authorization: "Bearer " + token, "x-gateway-id": id }
    });
    sockets.push(ws);
    await once(ws, "open");
    ws.send(JSON.stringify({ type: "heartbeat", metadata: {
      sip_ready: true, takeover: true, active_call: false, ...metadata
    }}));
    return ws;
  }
  async function call(name, args = {}) {
    const response = await fetch(base + "/mcp", {
      method: "POST",
      headers: { authorization: "Bearer admin-test-token", "content-type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: args } })
    });
    const body = await response.json();
    const text = body.result.content[0].text;
    return body.result.isError ? { error: text } : JSON.parse(text);
  }
  return { app, connect, call };
}

test("routing preview selects the highest-priority ready idle gateway", async t => {
  const { connect, call } = await setup(t);
  await connect("gw01", "token-01");
  await connect("gw02", "token-02");
  const preview = await call("gateway_route_preview");
  assert.equal(preview.selected_gateway_id, "gw02");
  assert.equal(preview.execution, "none");
});

test("routed dial creates an approval request on exactly one selected gateway", async t => {
  const { connect, call } = await setup(t);
  const gw01 = await connect("gw01", "token-01");
  const gw02 = await connect("gw02", "token-02");
  const commandPromise = once(gw02, "message");
  const resultPromise = call("gateway_request_routed_call", { target: "+967711234567" });
  const [raw] = await commandPromise;
  const command = JSON.parse(raw.toString());
  assert.equal(command.name, "sip_request_call_action");
  assert.equal(command.arguments.action, "DIAL");
  assert.equal(command.arguments.target, "+967711234567");
  gw02.send(JSON.stringify({ type: "command_result", id: command.id, result: {
    ok: true, approval_id: "approval-1", requires_local_approval: true
  }}));
  const result = await resultPromise;
  assert.equal(result.selected_gateway_id, "gw02");
  assert.equal(result.requires_local_approval, true);
  assert.equal(result.call_executed, false);
  assert.equal(gw01.readyState, WebSocket.OPEN);
});

test("routing refuses when all connected gateways are busy or not SIP-ready", async t => {
  const { connect, call } = await setup(t);
  await connect("gw01", "token-01", { active_call: true });
  await connect("gw02", "token-02", { sip_ready: false });
  const result = await call("gateway_request_routed_call", { target: "+967711234567" });
  assert.equal(result.error, "no_eligible_gateway");
});
