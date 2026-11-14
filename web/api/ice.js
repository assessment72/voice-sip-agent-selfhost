import { authenticatePeer, configuredIceServers, json, requireMethod } from "../lib/server.js";

export const config = { runtime: "nodejs" };

export default async function handler(req, res) {
  if (!requireMethod(req, res, "GET")) return;
  try {
    const peer = await authenticatePeer(req);
    if (!peer) return json(res, 401, { error: "invalid_or_expired_peer_token" });
    return json(res, 200, { iceServers: await configuredIceServers() });
  } catch {
    return json(res, 503, { error: "ice_configuration_unavailable" });
  }
}
