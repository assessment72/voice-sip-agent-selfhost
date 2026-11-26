import { authorize, requireHttps } from "../../lib/auth.mjs";
import { executeDbOperation } from "../../lib/db-operations.mjs";

const MAX_BODY_BYTES = 64 * 1024;
function send(res, status, body) {
  res.statusCode = status;
  res.setHeader("content-type", "application/json; charset=utf-8");
  res.setHeader("cache-control", "no-store");
  res.setHeader("x-content-type-options", "nosniff");
  res.end(JSON.stringify(body));
}
async function readJson(req) {
  if (req.body && typeof req.body === "object") return req.body;
  let raw = "";
  for await (const chunk of req) {
    raw += chunk;
    if (Buffer.byteLength(raw) > MAX_BODY_BYTES) throw Object.assign(new Error("request_too_large"), { status: 413 });
  }
  if (!raw) return {};
  try { return JSON.parse(raw); }
  catch { throw Object.assign(new Error("invalid_json"), { status: 400 }); }
}

export default async function handler(req, res) {
  if (req.method !== "POST") return send(res, 405, { error: "method_not_allowed" });
  if (!requireHttps(req)) return send(res, 400, { error: "https_required" });
  const identity = authorize(req);
  if (!identity.ok) return send(res, identity.status, { error: identity.error });
  try {
    const body = await readJson(req);
    if (!body || Array.isArray(body) || typeof body !== "object") return send(res, 400, { error: "invalid_request" });
    const operation = body.operation;
    const args = body.args && typeof body.args === "object" && !Array.isArray(body.args) ? body.args : {};
    const result = await executeDbOperation(operation, args);
    return send(res, 200, result);
  } catch (error) {
    const status = Number.isInteger(error?.status) ? error.status : error?.message === "database_not_configured" ? 503 : 500;
    if (status >= 500) console.error("Database API operation failed", { operation: req.body?.operation || "unknown", code: error?.code || error?.name || "error" });
    return send(res, status, { error: status >= 500 ? "service_unavailable" : String(error.message || "request_failed") });
  }
}
