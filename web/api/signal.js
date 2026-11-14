import {
  authenticatePeer, json, parseSignal, redis, requireMethod, SIGNAL_TTL,
} from "../lib/server.js";

export default async function handler(req, res) {
  if (!requireMethod(req, res, "POST")) return;
  try {
    const peer = await authenticatePeer(req);
    if (!peer) return json(res, 401, { error: "invalid_or_expired_peer_token" });
    const message = parseSignal(req.body);
    if (!message) return json(res, 400, { error: "invalid_signal" });
    if ((message.type === "offer" || message.type === "dial") && peer.role !== "browser") {
      return json(res, 403, { error: "signal_not_allowed_for_peer_role" });
    }
    if ((message.type === "answer" || message.type === "ringing") && peer.role !== "android") {
      return json(res, 403, { error: "signal_not_allowed_for_peer_role" });
    }

    const target = peer.role === "browser" ? "android" : "browser";
    const envelope = {
      id: crypto.randomUUID(),
      from: peer.role,
      type: message.type,
      data: message.data,
      createdAt: Date.now(),
    };
    const queue = `vsa:queue:${peer.roomId}:${target}`;
    await redis(["RPUSH", queue, JSON.stringify(envelope)]);
    await redis(["EXPIRE", queue, SIGNAL_TTL]);
    return json(res, 202, { accepted: true });
  } catch {
    return json(res, 503, { error: "signaling_unavailable" });
  }
}
