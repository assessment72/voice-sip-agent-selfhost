import test from "node:test";
import assert from "node:assert/strict";
import { selectGateway } from "./routing.mjs";

const now = Date.now();
const entry = (metadata = {}, lastSeen = new Date(now).toISOString(), lastAssignedAt = 0) => ({
  ws: { readyState: 1 }, lastSeen, lastAssignedAt,
  metadata: { sip_ready: true, takeover: true, active_call: false, ...metadata }
});

test("selects only a fresh, connected, ready and idle gateway", () => {
  const gateways = new Map([
    ["offline", { ...entry(), ws: { readyState: 3 } }],
    ["busy", entry({ active_call: true })],
    ["not-ready", entry({ sip_ready: false })],
    ["stale", entry({}, new Date(now - 120_000).toISOString())],
    ["eligible", entry()]
  ]);
  assert.equal(selectGateway(gateways, {}, now), "eligible");
});

test("prefers higher configured priority and honors disabled gateways", () => {
  const gateways = new Map([["gw1", entry()], ["gw2", entry()]]);
  assert.equal(selectGateway(gateways, { gw1: { priority: 1 }, gw2: { priority: 9 } }, now), "gw2");
  assert.equal(selectGateway(gateways, { gw1: { priority: 1 }, gw2: { enabled: false } }, now), "gw1");
});

test("balances equal-priority gateways by oldest assignment", () => {
  const gateways = new Map([["gw1", entry({}, undefined, 200)], ["gw2", entry({}, undefined, 100)]]);
  assert.equal(selectGateway(gateways, {}, now), "gw2");
});

test("fails closed when there is no eligible gateway", () => {
  assert.equal(selectGateway(new Map([["gw1", entry({ takeover: false })]]), {}, now), null);
});
