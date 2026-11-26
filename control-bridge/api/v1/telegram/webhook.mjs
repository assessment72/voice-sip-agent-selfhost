import { telegramWebhook } from "../../../lib/telegram.mjs";

export default async function handler(req, res) {
  return telegramWebhook(req, res);
}
