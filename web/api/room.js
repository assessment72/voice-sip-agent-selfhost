import {
  createRoom, json, requireMethod, verifyTelegramInitData,
} from "../lib/server.js";

export default async function handler(req, res) {
  if (!requireMethod(req, res, "POST")) return;
  try {
    const origin = req.headers.origin;
    const host = req.headers.host;
    if (origin && new URL(origin).host !== host) {
      return json(res, 403, { error: "origin_not_allowed" });
    }
    const initData = req.body?.initData;
    const user = verifyTelegramInitData(
      initData,
      process.env.TELEGRAM_BOT_TOKEN || "",
      process.env.TELEGRAM_OWNER_ID || "",
    );
    if (!user) return json(res, 401, { error: "telegram_auth_failed" });
    if (String(process.env.TELEGRAM_PRIVATE_CHAT_ID || "") !== user.id) {
      return json(res, 503, { error: "telegram_private_chat_must_match_owner" });
    }

    const room = await createRoom();
    return json(res, 201, room);
  } catch {
    return json(res, 503, { error: "room_creation_unavailable" });
  }
}
