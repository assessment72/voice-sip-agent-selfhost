import { authenticatePeer, json, redis, requireMethod } from "../lib/server.js";

export const config = { runtime: "nodejs" };

export default async function handler(req, res) {
  if (!requireMethod(req, res, "GET")) return;
  try {
    const peer = await authenticatePeer(req);
    if (!peer) return json(res, 401, { error: "invalid_or_expired_peer_token" });
    const queue = `vsa:queue:${peer.roomId}:${peer.role}`;
    const messages = [];
    // One bounded batch per HTTP request; clients poll again after a short delay.
    for (let i = 0; i < 20; i += 1) {
      const item = await redis(["LPOP", queue]);
      if (item === null || item === undefined) break;
      try { messages.push(JSON.parse(item)); } catch { /* discard malformed queue entry */ }
    }
    return json(res, 200, { messages });
  } catch {
    return json(res, 503, { error: "signaling_unavailable" });
  }
}
