import test from "node:test";
import assert from "node:assert/strict";
import { createCentralMcpServer } from "./server.mjs";
import { WebSocket } from "ws";
import { once } from "node:events";

async function start(t, options = {}) {
  const app = createCentralMcpServer({ adminToken: "admin-test-token", credentials: { gw01: "device-test-token", gw02: "device-test-token-2" }, ...options });
  await new Promise(resolve => app.httpServer.listen(0, "127.0.0.1", resolve));
  const port = app.httpServer.address().port;
  t.after(() => new Promise(resolve => {
    for (const entry of app.gateways.values()) entry.ws.terminate();
    app.httpServer.closeAllConnections();
    app.httpServer.close(resolve);
  }));
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
  assert.deepEqual(await response.json(), { ok: true, service: "voice-sip-central-mcp", connected_gateways: 0, state_backend: "instance_memory", multi_instance_safe: false });
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
  assert.ok(names.includes("gateway_calls_list"));
  assert.ok(names.includes("gateway_set_takeover"));
  assert.ok(names.includes("gateway_restart_sip"));
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

test("multiple gateways stay isolated and commands are sent only to the selected device", async t => {
  const { url } = await start(t, { requestTimeoutMs: 1500 });
  const wsUrl = url.replace("http://", "ws://") + "/v1/agent";
  const connect = async gatewayId => {
    const ws = new WebSocket(wsUrl, { headers: {
      authorization: "Bearer " + (gatewayId === "gw02" ? "device-test-token-2" : "device-test-token"),
      "x-gateway-id": gatewayId
    }});
    await once(ws, "open");
    t.after(() => { if (ws.readyState === WebSocket.OPEN) ws.close(); });
    return ws;
  };
  const gw01 = await connect("gw01");
  const gw02 = await connect("gw02");
  const listed = await (await mcp(url, "tools/call", { name: "gateways_list", arguments: {} })).json();
  const ids = JSON.parse(listed.result.content[0].text).gateways.map(g => g.gateway_id).sort();
  assert.deepEqual(ids, ["gw01", "gw02"]);

  const commandOn02 = once(gw02, "message");
  const statusPromise = mcp(url, "tools/call", { name: "gateway_status", arguments: { gateway_id: "gw02" } });
  const [raw] = await commandOn02;
  const command = JSON.parse(raw.toString());
  assert.equal(command.type, "command");
  assert.equal(command.name, "sip_gateway_status");
  gw01.send(JSON.stringify({ type: "command_result", id: command.id, result: { ok: true, gateway_id: "spoofed-gw01" } }));
  await new Promise(resolve => setTimeout(resolve, 25));
  gw02.send(JSON.stringify({ type: "command_result", id: command.id, result: { ok: true, gateway_id: "gw02" } }));
  const status = await (await statusPromise).json();
  assert.equal(JSON.parse(status.result.content[0].text).gateway_id, "gw02");
  assert.equal(gw01.readyState, WebSocket.OPEN);
});

test("supports a mounted path for Vercel catch-all routing", async t => {
  const { url } = await start(t, { basePath: "/api" });
  const health = await fetch(url + "/api/healthz");
  assert.equal(health.status, 200);
  const body = await health.json();
  assert.equal(body.state_backend, "instance_memory");
  assert.equal(body.multi_instance_safe, false);
  assert.equal((await fetch(url + "/healthz")).status, 404);
  const denied = await fetch(url + "/api/mcp", { method: "POST" });
  assert.equal(denied.status, 401);
});

test("accepts the Vercel-mounted device WebSocket path", async t => {
  const { url } = await start(t, { basePath: "/api" });
  const ws = new WebSocket(url.replace("http://", "ws://") + "/api/v1/agent", {
    headers: { authorization: "Bearer device-test-token", "x-gateway-id": "gw01" }
  });
  await once(ws, "open");
  assert.equal(ws.readyState, WebSocket.OPEN);
  ws.close();
});

test("fails closed on Vercel health when persistent storage is required but missing", async t => {
  const { url } = await start(t, { basePath: "/api", requirePersistentStore: true, store: null });
  const response = await fetch(url + "/api/healthz");
  assert.equal(response.status, 503);
  assert.equal((await response.json()).multi_instance_safe, false);
});


test("Arabic dashboard is served and admin APIs require authentication", async t => {
  const { url } = await start(t, { basePath: "/api" });
  const page = await fetch(url + "/api/dashboard");
  assert.equal(page.status, 200);
  assert.match(page.headers.get("content-type"), /text\/html/);
  assert.match(await page.text(), /مركز إدارة بوابات SIP/);
  assert.equal((await fetch(url + "/api/admin/gateways")).status, 401);
  assert.equal((await fetch(url + "/api/admin/audit?gateway_id=gw01", {
    headers: { authorization: "Bearer admin-test-token" }
  })).status, 200);
});

test("dashboard gateway API exposes allowlisted metadata only", async t => {
  const { url } = await start(t, { basePath: "/api" });
  const ws = new WebSocket(url.replace("http://", "ws://") + "/api/v1/agent", {
    headers: { authorization: "Bearer device-test-token", "x-gateway-id": "gw01" }
  });
  await once(ws, "open");
  t.after(() => { if (ws.readyState === WebSocket.OPEN) ws.close(); });
  ws.send(JSON.stringify({ type: "heartbeat", metadata: {
    manufacturer: "HONOR", model: "MTN-NX1", sip_ready: true, takeover: true,
    sip_password: "must-never-leak", device_token: "must-never-leak"
  }}));
  await new Promise(resolve => setTimeout(resolve, 20));
  const response = await fetch(url + "/api/admin/gateways", {
    headers: { authorization: "Bearer admin-test-token" }
  });
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.gateways.length, 1);
  assert.equal(body.gateways[0].gateway_id, "gw01");
  assert.equal(body.gateways[0].metadata.manufacturer, "HONOR");
  assert.equal(body.gateways[0].metadata.sip_ready, true);
  assert.equal("sip_password" in body.gateways[0].metadata, false);
  assert.equal("device_token" in body.gateways[0].metadata, false);
  assert.equal("instance_id" in body.gateways[0], false);
});


class MemoryEnrollmentStore {
  constructor(){this.enrollments=new Map();this.gateways=new Map();this.audit=[];}
  async connect(){return this;}
  watchCommands(){return ()=>{};}
  async close(){}
  async getEnrollment(id){return this.enrollments.get(id)||null;}
  async createEnrollment(doc){
    if(this.enrollments.has(doc.gateway_id)){const e=new Error("duplicate");e.code=11000;throw e;}
    const now=new Date();this.enrollments.set(doc.gateway_id,{gateway_id:doc.gateway_id,token_hash:doc.token_hash,enabled:true,credential_source:"database",created_at:now,updated_at:now,token_rotated_at:now});
    this.audit.push({gateway_id:doc.gateway_id,event_type:"gateway_enrolled",created_at:now});
  }
  async setEnrollmentEnabled(id,enabled){
    const current=this.enrollments.get(id)||{gateway_id:id,token_hash:null,credential_source:"environment",created_at:new Date()};
    Object.assign(current,{enabled,updated_at:new Date()});this.enrollments.set(id,current);
    this.audit.push({gateway_id:id,event_type:enabled?"gateway_enabled":"gateway_disabled",created_at:new Date()});
  }
  async rotateEnrollmentToken(id,hash){
    const current=this.enrollments.get(id)||{gateway_id:id,created_at:new Date()};
    Object.assign(current,{token_hash:hash,credential_source:"database",enabled:true,updated_at:new Date(),token_rotated_at:new Date()});this.enrollments.set(id,current);
    this.audit.push({gateway_id:id,event_type:"gateway_token_rotated",created_at:new Date()});
  }
  async listEnrollments(){return [...this.enrollments.values()].map(({gateway_id,enabled,credential_source,created_at,updated_at,token_rotated_at})=>({gateway_id,enabled,credential_source,created_at,updated_at,token_rotated_at}));}
  async listGateways(){return [...this.gateways.values()];}
  async registerGateway(doc){this.gateways.set(doc.gateway_id,{...doc,connected:true});}
  async heartbeat(id,instance,metadata,lastSeen){const g=this.gateways.get(id);if(g){g.metadata=metadata;g.last_seen=lastSeen;}}
  async disconnectGateway(id,instance,at){const g=this.gateways.get(id);if(g){g.connected=false;g.last_seen=at;}}
  async listAudit(id,limit=50){return this.audit.filter(x=>x.gateway_id===id).slice(-limit).reverse();}
}

test("admin can enroll, rotate and disable a gateway without storing raw tokens", async t => {
  const store=new MemoryEnrollmentStore();
  const {url}=await start(t,{basePath:"/api",store});
  const headers={authorization:"Bearer admin-test-token","content-type":"application/json"};
  const created=await fetch(url+"/api/admin/gateways/enroll",{method:"POST",headers,body:JSON.stringify({gateway_id:"gw-new"})});
  assert.equal(created.status,201);
  const first=await created.json();
  assert.equal(first.token_shown_once,true);
  assert.equal(typeof first.device_token,"string");
  assert.notEqual(store.enrollments.get("gw-new").token_hash,first.device_token);
  assert.equal(store.enrollments.get("gw-new").token_hash.length,64);

  const connected=new WebSocket(url.replace("http://","ws://")+"/api/v1/agent",{headers:{authorization:"Bearer "+first.device_token,"x-gateway-id":"gw-new"}});
  await once(connected,"open");
  t.after(()=>{if(connected.readyState===WebSocket.OPEN)connected.close();});

  const disabled=await fetch(url+"/api/admin/gateways/gw-new/status",{method:"PATCH",headers,body:JSON.stringify({enabled:false})});
  assert.equal(disabled.status,200);
  assert.equal((await disabled.json()).enabled,false);
  await once(connected,"close");

  const rejected=new WebSocket(url.replace("http://","ws://")+"/api/v1/agent",{headers:{authorization:"Bearer "+first.device_token,"x-gateway-id":"gw-new"}});
  const rejection=await once(rejected,"unexpected-response");
  assert.equal(rejection[1].statusCode,401);

  const rotated=await fetch(url+"/api/admin/gateways/gw-new/rotate-token",{method:"POST",headers});
  assert.equal(rotated.status,200);
  const second=await rotated.json();
  assert.notEqual(second.device_token,first.device_token);
  assert.equal(store.enrollments.get("gw-new").token_hash.length,64);
  assert.equal(store.enrollments.get("gw-new").enabled,true);

  const next=new WebSocket(url.replace("http://","ws://")+"/api/v1/agent",{headers:{authorization:"Bearer "+second.device_token,"x-gateway-id":"gw-new"}});
  await once(next,"open");
  next.close();
});

test("gateway management endpoints reject unauthenticated and malformed requests", async t => {
  const store=new MemoryEnrollmentStore();
  const {url}=await start(t,{basePath:"/api",store});
  assert.equal((await fetch(url+"/api/admin/gateways/enroll",{method:"POST",body:"{}"})).status,401);
  const invalid=await fetch(url+"/api/admin/gateways/enroll",{method:"POST",headers:{authorization:"Bearer admin-test-token","content-type":"application/json"},body:JSON.stringify({gateway_id:"bad id"})});
  assert.equal(invalid.status,400);
});
