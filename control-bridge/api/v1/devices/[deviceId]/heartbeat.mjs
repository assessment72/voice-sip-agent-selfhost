import { authorize, requireHttps } from "../../../../lib/auth.mjs";
import { getDb } from "../../../../lib/mongodb.mjs";

const send = (res, status, value) => {
  res.statusCode = status;
  res.setHeader("content-type", "application/json; charset=utf-8");
  res.setHeader("cache-control", "no-store");
  res.setHeader("x-content-type-options", "nosniff");
  res.end(JSON.stringify(value));
};
async function body(req) {
  if (req.body && typeof req.body === "object") return req.body;
  let raw = "";
  for await (const chunk of req) {
    raw += chunk;
    if (Buffer.byteLength(raw) > 8 * 1024) throw Object.assign(new Error("request_too_large"), { status: 413 });
  }
  if (!raw) return {};
  try { return JSON.parse(raw); } catch { throw Object.assign(new Error("invalid_json"), { status: 400 }); }
}
export default async function handler(req, res) {
  if (req.method !== "POST") return send(res, 405, { error: "method_not_allowed" });
  if (!requireHttps(req)) return send(res, 400, { error: "https_required" });
  const pathId = String(req.query?.deviceId || new URL(req.url, "https://vercel.invalid").pathname.match(/^\/api\/v1\/devices\/([^/]+)\/heartbeat$/)?.[1] || "");
  const identity = authorize(req);
  if (!identity.ok) return send(res, identity.status, { error: identity.error });
  if (identity.role !== "device" || identity.deviceId !== pathId) return send(res, 403, { error: "forbidden" });
  try {
    const value = await body(req);
    if (value.battery != null && (!Number.isInteger(value.battery) || value.battery < 0 || value.battery > 100)) return send(res, 400, { error: "invalid_battery" });
    for (const key of ["charging", "sipReady", "activeCall", "takeover", "takeoverPending"]) {
      if (value[key] != null && typeof value[key] !== "boolean") return send(res, 400, { error: `invalid_${key}` });
    }
    if (value.serviceState != null && !["running", "stopped", "starting", "unknown"].includes(value.serviceState)) return send(res, 400, { error: "invalid_service_state" });
    const document = {
      deviceId: pathId,
      lastSeen: new Date(),
      battery: value.battery ?? null,
      charging: value.charging ?? null,
      serviceState: value.serviceState || "unknown",
      appVersion: typeof value.appVersion === "string" ? value.appVersion.slice(0, 40) : "",
      sipReady: value.sipReady ?? false,
      sipDetail: typeof value.sipDetail === "string" ? value.sipDetail.slice(0, 200) : "",
      activeCall: value.activeCall ?? false,
      takeover: value.takeover ?? false,
      takeoverPending: value.takeoverPending ?? false,
    };
    await (await getDb()).collection("device_status").updateOne({ deviceId: pathId }, { $set: document }, { upsert: true });
    return send(res, 200, { ok: true, lastSeen: document.lastSeen.getTime() });
  } catch (error) {
    const status = Number.isInteger(error?.status) ? error.status : error?.message === "database_not_configured" ? 503 : 500;
    if (status >= 500) console.error("Device heartbeat failed", { deviceId: pathId, code: error?.code || error?.name || "error" });
    return send(res, status, { error: status >= 500 ? "service_unavailable" : String(error.message || "request_failed") });
  }
}
