import { authorize, requireHttps } from "../../../lib/auth.mjs";
import { getDb } from "../../../lib/mongodb.mjs";

export default async function handler(req, res) {
  res.setHeader("content-type", "application/json; charset=utf-8");
  res.setHeader("cache-control", "no-store");
  res.setHeader("x-content-type-options", "nosniff");
  if (req.method !== "GET") { res.statusCode = 405; return res.end(JSON.stringify({ error: "method_not_allowed" })); }
  if (!requireHttps(req)) { res.statusCode = 400; return res.end(JSON.stringify({ error: "https_required" })); }
  const identity = authorize(req);
  if (!identity.ok) { res.statusCode = identity.status; return res.end(JSON.stringify({ error: identity.error })); }
  try {
    const db = await getDb();
    await db.command({ ping: 1 });
    res.statusCode = 200;
    return res.end(JSON.stringify({ ok: true, api: "available", database: "available", databaseName: db.databaseName, generatedAt: new Date().toISOString() }));
  } catch (error) {
    const status = error?.message === "database_not_configured" ? 503 : 503;
    console.error("Database status check failed", { code: error?.code || error?.name || "error" });
    res.statusCode = status;
    return res.end(JSON.stringify({ error: "database_unavailable" }));
  }
}
