import { json, peerKeyFromRequest, redis, requireMethod } from "../lib/server.js";

export const config = { maxDuration: 10 };

// Verify the role token and drain its queue in one atomic Redis script invocation.
// The script returns a RESP array so an empty queue is represented as [] rather than {}.
const POLL_SCRIPT = `
local raw = redis.call("GET", KEYS[1])
if not raw then return {0} end
local peer = cjson.decode(raw)
if not peer.roomId or (peer.role ~= "browser" and peer.role ~= "android") then
  return {0}
end
local queue = "vsa:queue:" .. peer.roomId .. ":" .. peer.role
local messages = {}
for i = 1, 20 do
  local item = redis.call("LPOP", queue)
  if not item then break end
  table.insert(messages, item)
end
return {1, messages}
`;

export default async function handler(req, res) {
  if (!requireMethod(req, res, "GET")) return;
  try {
    const peerKey = peerKeyFromRequest(req);
    if (!peerKey) return json(res, 401, { error: "invalid_or_expired_peer_token" });
    const result = await redis(["EVAL", POLL_SCRIPT, 1, peerKey]);
    if (!Array.isArray(result) || Number(result[0]) !== 1) {
      return json(res, 401, { error: "invalid_or_expired_peer_token" });
    }
    const messages = (Array.isArray(result[1]) ? result[1] : []).flatMap(raw => {
      try { return [JSON.parse(raw)]; } catch { return []; }
    });
    return json(res, 200, { messages });
  } catch {
    return json(res, 503, { error: "signaling_unavailable" });
  }
}
