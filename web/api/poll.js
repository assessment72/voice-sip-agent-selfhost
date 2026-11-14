import { json, peerKeyFromRequest, redis, requireMethod } from "../lib/server.js";

export const config = { maxDuration: 10 };

// Verify the role token and drain its queue in one atomic Redis script invocation.
// This avoids a separate GET plus one LPOP request for every idle poll.
const POLL_SCRIPT = `
local raw = redis.call("GET", KEYS[1])
if not raw then return cjson.encode({error = "unauthorized"}) end
local peer = cjson.decode(raw)
if not peer.roomId or (peer.role ~= "browser" and peer.role ~= "android") then
  return cjson.encode({error = "unauthorized"})
end
local queue = "vsa:queue:" .. peer.roomId .. ":" .. peer.role
local messages = {}
for i = 1, 20 do
  local item = redis.call("LPOP", queue)
  if not item then break end
  local ok, decoded = pcall(cjson.decode, item)
  if ok then table.insert(messages, decoded) end
end
return cjson.encode({messages = messages})
`;

export default async function handler(req, res) {
  if (!requireMethod(req, res, "GET")) return;
  try {
    const peerKey = peerKeyFromRequest(req);
    if (!peerKey) return json(res, 401, { error: "invalid_or_expired_peer_token" });
    const raw = await redis(["EVAL", POLL_SCRIPT, 1, peerKey]);
    const result = JSON.parse(raw || "{}");
    if (result.error === "unauthorized") return json(res, 401, { error: "invalid_or_expired_peer_token" });
    return json(res, 200, { messages: Array.isArray(result.messages) ? result.messages : [] });
  } catch {
    return json(res, 503, { error: "signaling_unavailable" });
  }
}
