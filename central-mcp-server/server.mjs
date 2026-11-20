import http from "node:http";
import { timingSafeEqual } from "node:crypto";
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

export function createCentralMcpServer(options = {}) {
  const adminToken = options.adminToken ?? process.env.MCP_ADMIN_TOKEN ?? "";
  const credentials = options.credentials ?? parseJsonEnv(process.env.GATEWAY_CREDENTIALS_JSON, {});
  const routingPolicy = options.routingPolicy ?? parseJsonEnv(process.env.GATEWAY_ROUTING_POLICY_JSON, {});
  const requestTimeoutMs = options.requestTimeoutMs ?? DEFAULT_TIMEOUT_MS;
  const gateways = new Map();
  const pending = new Map();
  let nextId = 1;
  function chooseGateway() { return selectGateway(gateways, routingPolicy); }

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
  function sendToGateway(gatewayId, name, args) {
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
  function listGateways() {
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
    if (name === "gateways_list") return { gateways: listGateways() };
    if (name === "gateway_route_preview") {
      const selected = chooseGateway();
      return { selected_gateway_id: selected, eligible: selected !== null, policy: "priority_then_oldest_assignment", execution: "none" };
    }
    if (name === "gateway_request_routed_call") {
      if (typeof args.target !== "string" || !args.target.trim() || args.target.length > 64) throw new Error("valid_dial_target_required");
      const route = args.route ?? "auto";
      if (typeof route !== "string" || !/^[A-Za-z0-9._-]{1,64}$/.test(route)) throw new Error("invalid_route");
      const gatewayId = chooseGateway();
      if (!gatewayId) throw new Error("no_eligible_gateway");
      const entry = gateways.get(gatewayId);
      if (!entry || entry.routingReservedUntil > Date.now()) throw new Error("gateway_already_reserved");
      entry.routingReservedUntil = Date.now() + 90_000;
      try {
        const approval = await sendToGateway(gatewayId, "sip_request_call_action", { action: "DIAL", target: args.target.trim(), route });
        entry.lastAssignedAt = Date.now();
        return { selected_gateway_id: gatewayId, approval, requires_local_approval: true, call_executed: false };
      } catch (error) {
        entry.routingReservedUntil = 0;
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
    if (name === "gateway_call_audit") return await sendToGateway(gatewayId, "sip_call_audit", { limit: args.limit ?? 100 });
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
    if (req.method === "GET" && req.url === "/healthz") return json(res, 200, { ok: true, service: "voice-sip-central-mcp", connected_gateways: gateways.size });
    if (req.url !== "/mcp") return json(res, 404, { error: "not_found" });
    if (req.method !== "POST") return json(res, 405, { error: "method_not_allowed" });
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
    if (url.pathname !== "/v1/agent") {
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
    ws.send(JSON.stringify({ type: "server.ready", gateway_id: gatewayId }));
    ws.on("message", raw => {
      if (gateways.get(gatewayId)?.ws !== ws) return;
      let message;
      try { message = JSON.parse(raw.toString()); } catch { return ws.close(1007, "invalid_json"); }
      if (!message || typeof message !== "object") return;
      entry.lastSeen = new Date().toISOString();
      if (message.type === "heartbeat") {
        if (message.metadata && typeof message.metadata === "object" && !Array.isArray(message.metadata)) entry.metadata = message.metadata;
        return;
      }
      if (message.type === "command_result" && typeof message.id === "string") {
        settlePending(message.id, message.result, message.error, gatewayId);
      }
    });
    ws.on("close", () => {
      if (gateways.get(gatewayId)?.ws !== ws) return;
      gateways.delete(gatewayId);
      for (const [id, item] of pending) if (item.gatewayId === gatewayId) settlePending(id, null, "gateway_disconnected", gatewayId);
    });
    ws.on("error", () => {});
  });

  return { httpServer, gateways, tools, callTool };
}

if (process.argv[1] && new URL(import.meta.url).pathname === process.argv[1]) {
  const server = createCentralMcpServer();
  const port = Number(process.env.PORT ?? 8788);
  server.httpServer.listen(port, "0.0.0.0", () => {
    console.log(`voice-sip central MCP listening on :${port}; gateway credentials configured=${Object.keys(parseJsonEnv(process.env.GATEWAY_CREDENTIALS_JSON, {})).length}`);
  });
}
