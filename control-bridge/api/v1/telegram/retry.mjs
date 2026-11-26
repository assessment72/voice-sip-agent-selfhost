import { telegramRetryEndpoint } from "../../../lib/telegram.mjs";

export default async function handler(req, res) {
  return telegramRetryEndpoint(req, res);
}
