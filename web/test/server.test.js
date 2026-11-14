import test from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { parseSignal, verifyTelegramInitData } from "../lib/server.js";

const BOT_TOKEN = "123456:abcdefghijklmnopqrstuvwxyzABCDE";
const OWNER_ID = "777001";
const NOW = 1_800_000_000;

function signedInitData({ userId = OWNER_ID, authDate = NOW, botToken = BOT_TOKEN } = {}) {
  const params = new URLSearchParams({
    auth_date: String(authDate),
    query_id: "AAEAAExample",
    user: JSON.stringify({ id: Number(userId), first_name: "Owner" }),
  });
  const dataCheck = [...params.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, value]) => `${key}=${value}`)
    .join("\n");
  const secret = createHmac("sha256", "WebAppData").update(botToken).digest();
  const hash = createHmac("sha256", secret).update(dataCheck).digest("hex");
  params.set("hash", hash);
  return params.toString();
}

test("accepts valid Telegram initData for the configured owner", () => {
  assert.deepEqual(
    verifyTelegramInitData(signedInitData(), BOT_TOKEN, OWNER_ID, NOW),
    { id: OWNER_ID, firstName: "Owner" },
  );
});

test("rejects a different Telegram account even with a valid signature", () => {
  assert.equal(
    verifyTelegramInitData(signedInitData({ userId: "777002" }), BOT_TOKEN, OWNER_ID, NOW),
    null,
  );
});

test("rejects expired and future-dated Telegram initData", () => {
  assert.equal(
    verifyTelegramInitData(signedInitData({ authDate: NOW - 301 }), BOT_TOKEN, OWNER_ID, NOW),
    null,
  );
  assert.equal(
    verifyTelegramInitData(signedInitData({ authDate: NOW + 31 }), BOT_TOKEN, OWNER_ID, NOW),
    null,
  );
});

test("rejects tampered Telegram initData and malformed hashes", () => {
  const tampered = signedInitData().replace("AAEAAExample", "AAEAAOther");
  assert.equal(verifyTelegramInitData(tampered, BOT_TOKEN, OWNER_ID, NOW), null);
  assert.equal(
    verifyTelegramInitData("auth_date=1800000000&hash=not-a-hash", BOT_TOKEN, OWNER_ID, NOW),
    null,
  );
});

test("allows only the documented signaling message types", () => {
  assert.deepEqual(parseSignal({ type: "offer", data: { sdp: "v=0" } }), {
    type: "offer", data: { sdp: "v=0" },
  });
  assert.equal(parseSignal({ type: "exec", data: {} }), null);
  assert.equal(parseSignal({ type: "ice-candidate", data: "not-an-object" }), null);
});

test("rejects signaling payloads above the size limit", () => {
  assert.equal(parseSignal({ type: "offer", data: { sdp: "x".repeat(50 * 1024) } }), null);
});
