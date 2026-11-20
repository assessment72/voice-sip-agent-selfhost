import http from "node:http";
import { timingSafeEqual, randomUUID } from "node:crypto";
import { MongoGatewayStore } from "./mongo-store.mjs";
import { WebSocket, WebSocketServer } from "ws";
import { selectGateway } from "./routing.mjs";

const MAX_BODY = 64 * 1024;
const MAX_AGENT_MESSAGE = 256 * 1024;
const DEFAULT_TIMEOUT_MS = 12_000;
const SAFE_ID = /^[A-Za-z0-9._-]{1,64}$/;
const ACTIONS = new Set(["DIAL", "ANSWER", "REJECT", "HANGUP", "DTMF"]);

function equalSecret(a, b) {
  const left = Buffer.from(String(a));
  const right = Buffer.from(String(b));
  return left.length === right.length && timingSafeEqual(left, right);
}
function json(res, status, body) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify(body));
}
function bearer(req) {
  const value = req.headers.authorization ?? "";
  return value.startsWith("Bearer ") ? value.slice(7) : "";
}
function parseJsonEnv(value, fallback) {
  if (!value) return fallback;
  const parsed = JSON.parse(value);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("credential JSON must be an object");
  return parsed;
}
function normalizeBasePath(value = "") {
  const parts = String(value).split("/").filter(Boolean);
  return parts.length ? `/${parts.join("/")}` : "";
}

export function createCentralMcpServer(options = {}) {
  const adminToken = options.adminToken ?? process.env.MCP_ADMIN_TOKEN ?? "";
  const credentials = options.credentials ?? parseJsonEnv(process.env.GATEWAY_CREDENTIALS_JSON, {});
  const routingPolicy = options.routingPolicy ?? parseJsonEnv(process.env.GATEWAY_ROUTING_POLICY_JSON, {});
  const requestTimeoutMs = options.requestTimeoutMs ?? DEFAULT_TIMEOUT_MS;
  const basePath = normalizeBasePath(options.basePath ?? process.env.CENTRAL_BASE_PATH ?? "");
  const instanceId = options.instanceId ?? randomUUID();
  const store = options.store ?? (process.env.MONGODB_URI ? new MongoGatewayStore({ uri: process.env.MONGODB_URI, database: process.env.MONGODB_DATABASE ?? "voice_sip_central" }) : null);
  const requirePersistentStore = options.requirePersistentStore ?? false;
  let storeReady = null;
  let stopCommandWatch = null;
  async function ensureStore() {
    if (!store) { if (requirePersistentStore) throw new Error("persistent_storage_not_configured"); return null; }
    if (!storeReady) storeReady = (async () => { await store.connect(); stopCommandWatch = store.watchCommands(instanceId, async command => { const entry = gateways.get(command.gateway_id); if (!entry || entry.ws.readyState !== WebSocket.OPEN) return store.completeCommand(command.command_id, command.gateway_id, null, "gateway_connection_not_on_this_instance"); if (!(await store.claimCommand(command.command_id))) return; try { entry.ws.send(JSON.stringify({ type: "command", id: command.command_id, name: command.name, arguments: command.arguments })); } catch (error) { await store.completeCommand(command.command_id, command.gateway_id, null, error.message); } }); return store; })().catch(error => { storeReady = null; throw error; });
    return storeReady;
  }
  const gateways = new Map();
  const pending = new Map();
  let nextId = 1;
  async function chooseGateway() {
    const db = await ensureStore();
    if (!db) return selectGateway(gateways, routingPolicy);
    const docs = await db.listGateways();
    const candidates = new Map();
    for (const doc of docs) {
      const lease = await db.getLease(doc.gateway_id);
      candidates.set(doc.gateway_id, { ws: { readyState: doc.connected && doc.last_seen && Date.now() - new Date(doc.last_seen).getTime() <= 60_000 ? WebSocket.OPEN : WebSocket.CLOSED }, lastSeen: doc.last_seen, metadata: doc.metadata ?? {}, lastAssignedAt: doc.last_assigned_at ? new Date(doc.last_assigned_at).getTime() : 0, routingReservedUntil: lease ? new Date(lease.expires_at).getTime() : 0 });
    }
    return selectGateway(candidates, routingPolicy);
  }

  function authorizedAdmin(req) {
    const token = bearer(req);
    return Boolean(adminToken && token && equalSecret(token, adminToken));
  }
  function registeredGateway(req) {
    const id = req.headers["x-gateway-id"];
    if (typeof id !== "string" || !SAFE_ID.test(id)) return null;
    const expected = credentials[id];
    const token = bearer(req);
    return typeof expected === "string" && token && equalSecret(token, expected) ? id : null;
  }
  function settlePending(id, result, error, sourceGatewayId = null) {
    const item = pending.get(id);
    if (!item || (sourceGatewayId && item.gatewayId !== sourceGatewayId)) return;
    pending.delete(id);
    clearTimeout(item.timer);
    if (error) item.reject(new Error(String(error)));
    else item.resolve(result ?? {});
  }
  async function sendToGateway(gatewayId, name, args) {
    const db = await ensureStore();
    if (db) {
      const gateway = await db.getGateway(gatewayId);
      if (!gateway || !gateway.connected || !gateway.instance_id || !gateway.last_seen || Date.now() - new Date(gateway.last_seen).getTime() > 60_000) throw new Error("gateway_offline");
      const commandId = await db.createCommand({ gatewayId, instanceId: gateway.instance_id, name, args, timeoutMs: requestTimeoutMs });
      return await db.waitForCommand(commandId, requestTimeoutMs);
    }
    const entry = gateways.get(gatewayId);
    if (!entry || entry.ws.readyState !== WebSocket.OPEN) throw new Error("gateway_offline");
    const id = `cmd_${nextId++}`;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error("gateway_command_timeout"));
      }, requestTimeoutMs);
      pending.set(id, { resolve, reject, timer, gatewayId });
      try {
        entry.ws.send(JSON.stringify({ type: "command", id, name, arguments: args }));
      } catch (error) {
        clearTimeout(timer);
        pending.delete(id);
        reject(error);
      }
    });
  }
  async function listGateways() {
    const db = await ensureStore();
    if (db) return (await db.listGateways()).map(({ gateway_id, connected, connected_at, last_seen, metadata }) => ({ gateway_id, connected: Boolean(connected && last_seen && Date.now() - new Date(last_seen).getTime() <= 60_000), connected_at, last_seen, metadata }));
    return [...gateways.entries()].map(([gateway_id, entry]) => ({
      gateway_id,
      connected: entry.ws.readyState === WebSocket.OPEN,
      connected_at: entry.connectedAt,
      last_seen: entry.lastSeen,
      metadata: entry.metadata
    }));
  }
  const tools = [
    { name: "gateways_list", description: "List all independently registered Android gateways and their online status. Read-only.", inputSchema: { type: "object", properties: {}, additionalProperties: false } },
    { name: "gateway_route_preview", description: "Preview the best currently eligible gateway using heartbeat freshness, SIP readiness, takeover state, idle state and configured priority. Read-only; never initiates a call.", inputSchema: { type: "object", properties: {}, additionalProperties: false } },
    { name: "gateway_request_routed_call", description: "Select one eligible gateway and create a DIAL approval request on that phone. The phone must obtain local human approval; this tool never executes the call.", inputSchema: { type: "object", properties: { target: { type: "string", minLength: 1, maxLength: 64 }, route: { type: "string", default: "auto" } }, required: ["target"], additionalProperties: false } },
    { name: "gateway_status", description: "Read status from one specific gateway. No call is started.", inputSchema: { type: "object", properties: { gateway_id: { type: "string", pattern: "^[A-Za-z0-9._-]{1,64}$" } }, required: ["gateway_id"], additionalProperties: false } },
    { name: "gateway_calls_list", description: "List active calls and recent call history from one selected gateway.", inputSchema: { type: "object", properties: { gateway_id: { type: "string" }, limit: { type: "integer", minimum: 1, maximum: 500 } }, required: ["gateway_id"], additionalProperties: false } },
    { name: "gateway_set_takeover", description: "Enable or disable call takeover on one selected gateway. This changes only that phone's local gateway mode.", inputSchema: { type: "object", properties: { gateway_id: { type: "string" }, enabled: { type: "boolean" } }, required: ["gateway_id", "enabled"], additionalProperties: false } },
    { name: "gateway_restart_sip", description: "Restart SIP registration on one selected gateway. Does not initiate a call.", inputSchema: { type: "object", properties: { gateway_id: { type: "string" } }, required: ["gateway_id"], additionalProperties: false } },
    { name: "gateway_set_earpiece_mute", description: "Set local earpiece mute preference on one selected gateway.", inputSchema: { type: "object", properties: { gateway_id: { type: "string" }, muted: { type: "boolean" } }, required: ["gateway_id", "muted"], additionalProperties: false } },
    { name: "gateway_pending_call_approvals", description: "Read pending call approvals on one specific gateway.", inputSchema: { type: "object", properties: { gateway_id: { type: "string" } }, required: ["gateway_id"], additionalProperties: false } },
    { name: "gateway_request_call_action", description: "Request a call action on one gateway. The Android app must display the exact request and obtain local human approval; this request does not itself execute a call.", inputSchema: { type: "object", properties: { gateway_id: { type: "string" }, action: { type: "string", enum: ["DIAL", "ANSWER", "REJECT", "HANGUP", "DTMF"] }, call_id: { type: "string" }, target: { type: "string" }, route: { type: "string", default: "auto" }, digit: { type: "string", minLength: 1, maxLength: 1 } }, required: ["gateway_id", "action"], additionalProperties: false } },
    { name: "gateway_execute_approved_action", description: "Ask the selected Android gateway to execute a previously locally approved one-time action by approval_id.", inputSchema: { type: "object", properties: { gateway_id: { type: "string" }, approval_id: { type: "string" } }, required: ["gateway_id", "approval_id"], additionalProperties: false } },
    { name: "gateway_call_audit", description: "Read call-action approval audit records from one gateway.", inputSchema: { type: "object", properties: { gateway_id: { type: "string" }, limit: { type: "integer", minimum: 1, maximum: 500 } }, required: ["gateway_id"], additionalProperties: false } }
  ];

  async function callTool(name, args) {
    if (name === "gateways_list") return { gateways: await listGateways() };
    if (name === "gateway_route_preview") {
      const selected = await chooseGateway();
      return { selected_gateway_id: selected, eligible: selected !== null, policy: "priority_then_oldest_assignment", execution: "none" };
    }
    if (name === "gateway_request_routed_call") {
      if (typeof args.target !== "string" || !args.target.trim() || args.target.length > 64) throw new Error("valid_dial_target_required");
      const route = args.route ?? "auto";
      if (typeof route !== "string" || !/^[A-Za-z0-9._-]{1,64}$/.test(route)) throw new Error("invalid_route");
      const gatewayId = await chooseGateway();
      if (!gatewayId) throw new Error("no_eligible_gateway");
      const db = await ensureStore();
      const leaseToken = db ? await db.acquireLease(gatewayId, instanceId, 90_000) : null;
      const entry = gateways.get(gatewayId);
      if (db && !leaseToken) throw new Error("gateway_already_reserved");
      if (!db && (!entry || entry.routingReservedUntil > Date.now())) throw new Error("gateway_already_reserved");
      if (!db) entry.routingReservedUntil = Date.now() + 90_000;
      try {
        const approval = await sendToGateway(gatewayId, "sip_request_call_action", { action: "DIAL", target: args.target.trim(), route });
        const assignedAt = new Date();
        if (db) await db.markAssigned(gatewayId, assignedAt);
        else entry.lastAssignedAt = assignedAt.getTime();
        return { selected_gateway_id: gatewayId, approval, requires_local_approval: true, call_executed: false };
      } catch (error) {
        if (db && leaseToken) await db.releaseLease(gatewayId, leaseToken);
        if (!db) entry.routingReservedUntil = 0;
        throw error;
      }
    }
    const gatewayId = args.gateway_id;
    if (typeof gatewayId !== "string" || !SAFE_ID.test(gatewayId)) throw new Error("valid_gateway_id_required");
    if (name === "gateway_status") return await sendToGateway(gatewayId, "sip_gateway_status", {});
    if (name === "gateway_calls_list") return await sendToGateway(gatewayId, "sip_calls_list", { limit: args.limit ?? 100 });
    if (name === "gateway_set_takeover") {
      if (typeof args.enabled !== "boolean") throw new Error("enabled_boolean_required");
      return await sendToGateway(gatewayId, "sip_set_takeover", { enabled: args.enabled });
    }
    if (name === "gateway_restart_sip") return await sendToGateway(gatewayId, "sip_restart", {});
    if (name === "gateway_set_earpiece_mute") {
      if (typeof args.muted !== "boolean") throw new Error("muted_boolean_required");
      return await sendToGateway(gatewayId, "sip_set_earpiece_mute", { muted: args.muted });
    }
    if (name === "gateway_pending_call_approvals") return await sendToGateway(gatewayId, "sip_pending_call_approvals", {});
    if (name === "gateway_call_audit") { const db = await ensureStore(); if (db) return { records: await db.listAudit(gatewayId, args.limit ?? 100) }; return await sendToGateway(gatewayId, "sip_call_audit", { limit: args.limit ?? 100 }); }
    if (name === "gateway_request_call_action") {
      if (!ACTIONS.has(args.action)) throw new Error("invalid_call_action");
      if (args.action === "DIAL" && (typeof args.target !== "string" || !args.target.trim())) throw new Error("dial_target_required");
      if (args.action !== "DIAL" && (typeof args.call_id !== "string" || !args.call_id.trim())) throw new Error("call_id_required");
      if (args.action === "DTMF" && (typeof args.digit !== "string" || !/^[0-9*#ABCD]$/i.test(args.digit))) throw new Error("valid_dtmf_digit_required");
      if (args.action !== "DTMF" && args.digit !== undefined) throw new Error("digit_only_valid_for_dtmf");
      const route = args.route ?? "auto";
      if (typeof route !== "string" || !/^[A-Za-z0-9._-]{1,64}$/.test(route)) throw new Error("invalid_route");
      return await sendToGateway(gatewayId, "sip_request_call_action", {
        action: args.action, call_id: args.call_id, target: args.target, route, digit: args.digit
      });
    }
    if (name === "gateway_execute_approved_action") {
      if (typeof args.approval_id !== "string" || !args.approval_id.trim()) throw new Error("approval_id_required");
      return await sendToGateway(gatewayId, "sip_execute_approved_action", { approval_id: args.approval_id });
    }
    throw new Error("unknown_tool");
  }

  async function handleMcp(message) {
    const id = message.id;
    const method = message.method;
    if (method === "initialize") return { jsonrpc: "2.0", id, result: {
      protocolVersion: "2025-03-26",
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: "voice-sip-central-mcp", version: "0.1.0" }
    } };
    if (method === "notifications/initialized") return null;
    if (method === "ping") return { jsonrpc: "2.0", id, result: {} };
    if (method === "tools/list") return { jsonrpc: "2.0", id, result: { tools } };
    if (method === "tools/call") {
      const name = message.params?.name;
      const args = message.params?.arguments ?? {};
      try {
        if (!args || typeof args !== "object" || Array.isArray(args)) throw new Error("arguments_must_be_object");
        const result = await callTool(name, args);
        return { jsonrpc: "2.0", id, result: { content: [{ type: "text", text: JSON.stringify(result) }], isError: false } };
      } catch (error) {
        return { jsonrpc: "2.0", id, result: { content: [{ type: "text", text: error.message }], isError: true } };
      }
    }
    return { jsonrpc: "2.0", id, error: { code: -32601, message: "method_not_found" } };
  }

  const httpServer = http.createServer(async (req, res) => {
    const requestPath = new URL(req.url ?? "/", "http://localhost").pathname;
    if (req.method === "GET" && requestPath === `${basePath}/healthz`) { try { const db = await ensureStore(); if (db) await db.health(); return json(res, 200, { ok: true, service: "voice-sip-central-mcp", connected_gateways: db ? (await db.listGateways()).filter(g => g.connected && g.last_seen && Date.now() - new Date(g.last_seen).getTime() <= 60_000).length : gateways.size, state_backend: db ? "mongodb_atlas" : "instance_memory", multi_instance_safe: Boolean(db) }); } catch { return json(res, 503, { ok: false, service: "voice-sip-central-mcp", state_backend: "unavailable", multi_instance_safe: false }); } }
    if (requestPath !== `${basePath}/mcp`) return json(res, 404, { error: "not_found" });
    if (req.method !== "POST") return json(res, 405, { error: "method_not_allowed" });
    try { await ensureStore(); } catch { return json(res, 503, { error: "persistent_storage_unavailable" }); }
    if (!authorizedAdmin(req)) return json(res, 401, { error: "unauthorized" });
    let body = "";
    req.setEncoding("utf8");
    for await (const chunk of req) {
      body += chunk;
      if (Buffer.byteLength(body) > MAX_BODY) return json(res, 413, { error: "payload_too_large" });
    }
    let message;
    try { message = JSON.parse(body); } catch { return json(res, 400, { error: "invalid_json" }); }
    if (!message || typeof message !== "object" || Array.isArray(message) || message.jsonrpc !== "2.0" || typeof message.method !== "string") {
      return json(res, 400, { error: "invalid_json_rpc" });
    }
    try {
      const response = await handleMcp(message);
      if (response === null) return res.writeHead(202).end();
      return json(res, 200, response);
    } catch {
      return json(res, 500, { error: "internal_error" });
    }
  });

  const agentServer = new WebSocketServer({ noServer: true, maxPayload: MAX_AGENT_MESSAGE, perMessageDeflate: false });
  httpServer.on("upgrade", (req, socket, head) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    if (url.pathname !== `${basePath}/v1/agent`) {
      socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
      socket.destroy();
      return;
    }
    const gatewayId = registeredGateway(req);
    if (!gatewayId) {
      socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
      socket.destroy();
      return;
    }
    agentServer.handleUpgrade(req, socket, head, ws => agentServer.emit("connection", ws, gatewayId));
  });
  agentServer.on("connection", (ws, gatewayId) => {
    const previous = gateways.get(gatewayId);
    if (previous?.ws.readyState === WebSocket.OPEN) previous.ws.close(4001, "replaced_by_new_connection");
    const entry = { ws, connectedAt: new Date().toISOString(), lastSeen: new Date().toISOString(), metadata: {} };
    gateways.set(gatewayId, entry);
    const connectionToken = randomUUID();
    entry.connectionToken = connectionToken;
    ensureStore().then(db => db?.registerGateway({ gateway_id: gatewayId, instance_id: instanceId, connection_token: connectionToken, connected_at: entry.connectedAt, last_seen: entry.lastSeen, metadata: entry.metadata })).catch(() => ws.close(1011, "persistent_storage_unavailable"));
    ws.send(JSON.stringify({ type: "server.ready", gateway_id: gatewayId }));
    ws.on("message", raw => {
      if (gateways.get(gatewayId)?.ws !== ws) return;
      let message;
      try { message = JSON.parse(raw.toString()); } catch { return ws.close(1007, "invalid_json"); }
      if (!message || typeof message !== "object") return;
      entry.lastSeen = new Date().toISOString();
      if (message.type === "heartbeat") {
        if (message.metadata && typeof message.metadata === "object" && !Array.isArray(message.metadata)) entry.metadata = message.metadata;
        ensureStore().then(db => db?.heartbeat(gatewayId, instanceId, entry.metadata, entry.lastSeen)).catch(() => {});
        return;
      }
      if (message.type === "command_result" && typeof message.id === "string") {
        if (store) store.completeCommand(message.id, gatewayId, message.result, message.error).catch(() => {});
        else settlePending(message.id, message.result, message.error, gatewayId);
      }
    });
    ws.on("close", () => {
      if (gateways.get(gatewayId)?.ws !== ws) return;
      gateways.delete(gatewayId);
      if (store) store.disconnectGateway(gatewayId, instanceId, new Date()).catch(() => {});
      for (const [id, item] of pending) if (item.gatewayId === gatewayId) settlePending(id, null, "gateway_disconnected", gatewayId);
    });
    ws.on("error", () => {});
  });

  return { httpServer, gateways, tools, callTool, instanceId, close: async () => { stopCommandWatch?.(); await store?.close?.(); } };
}

if (process.argv[1] && new URL(import.meta.url).pathname === process.argv[1]) {
  const server = createCentralMcpServer();
  const port = Number(process.env.PORT ?? 8788);
  server.httpServer.listen(port, "0.0.0.0", () => {
    console.log(`voice-sip central MCP listening on :${port}; gateway credentials configured=${Object.keys(parseJsonEnv(process.env.GATEWAY_CREDENTIALS_JSON, {})).length}`);
  });
}
