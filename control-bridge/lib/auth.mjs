import { createHash, timingSafeEqual } from "node:crypto";

const DEVICE_ID = /^[A-Za-z0-9_-]{1,64}$/;
const hash = value => createHash("sha256").update(value).digest();
function equalSecret(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || !a || !b) return false;
  return timingSafeEqual(hash(a), hash(b));
}
function deviceMap() {
  let raw;
  try { raw = JSON.parse(process.env.VOICX_DEVICE_TOKENS || "{}"); }
  catch { throw new Error("VOICX_DEVICE_TOKENS must be valid JSON"); }
  const entries = Array.isArray(raw) ? raw.map(item => [item?.id, item?.token]) : Object.entries(raw || {});
  const result = new Map();
  for (const [id, token] of entries) {
    if (!DEVICE_ID.test(String(id || "")) || typeof token !== "string" || token.length < 32 || result.has(id)) {
      throw new Error("Invalid device token configuration");
    }
    result.set(id, token);
  }
  return result;
}

export function isConfiguredDeviceId(deviceId) {
  try { return deviceMap().has(String(deviceId || "")); }
  catch { return false; }
}

export function authorize(req, { adminOnly = false } = {}) {
  const supplied = (req.headers.authorization || "").startsWith("Bearer ")
    ? req.headers.authorization.slice(7) : "";
  if (!supplied) return { ok: false, status: 401, error: "unauthorized" };
  const admin = process.env.VOICX_ADMIN_TOKEN || "";
  if (admin.length >= 32 && equalSecret(supplied, admin)) return { ok: true, role: "admin", deviceId: null };
  const apiToken = process.env.VOICX_API_TOKEN || "";
  if (!adminOnly && apiToken.length >= 32 && equalSecret(supplied, apiToken)) return { ok: true, role: "api", deviceId: null };
  if (adminOnly) return { ok: false, status: 403, error: "forbidden" };
  const deviceId = String(req.headers["x-voicx-device-id"] || "");
  if (!DEVICE_ID.test(deviceId)) return { ok: false, status: 401, error: "unauthorized" };
  let devices;
  try { devices = deviceMap(); }
  catch { return { ok: false, status: 503, error: "service_not_configured" }; }
  const expected = devices.get(deviceId);
  if (!expected || !equalSecret(supplied, expected)) return { ok: false, status: 401, error: "unauthorized" };
  return { ok: true, role: "device", deviceId };
}

export function requireHttps(req) {
  const proto = String(req.headers["x-forwarded-proto"] || "").split(",")[0].trim().toLowerCase();
  return proto === "https" || process.env.NODE_ENV !== "production";
}
