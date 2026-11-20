/**
 * Deterministic, fail-closed gateway selection.
 * Only live gateways with a fresh heartbeat, SIP ready, takeover enabled,
 * and no active call are eligible. This first version intentionally enforces
 * one call per phone because the Android heartbeat currently reports a boolean
 * active_call rather than a verified concurrent-call capacity.
 */
export function selectGateway(gateways, policy = {}, now = Date.now(), maxHeartbeatAgeMs = 60_000) {
  const candidates = [];
  for (const [gatewayId, entry] of gateways.entries()) {
    const cfg = policy[gatewayId] ?? {};
    if (cfg.enabled === false) continue;
    if (entry.ws?.readyState !== 1) continue;
    const lastSeen = Date.parse(entry.lastSeen ?? "");
    if (!Number.isFinite(lastSeen) || now - lastSeen > maxHeartbeatAgeMs || lastSeen > now + 5_000) continue;
    const meta = entry.metadata ?? {};
    if (meta.sip_ready !== true || meta.takeover !== true || meta.active_call !== false) continue;
    candidates.push({
      gateway_id: gatewayId,
      priority: Number.isFinite(cfg.priority) ? cfg.priority : 0,
      lastAssignedAt: Number.isFinite(entry.lastAssignedAt) ? entry.lastAssignedAt : 0,
      lastSeen
    });
  }
  candidates.sort((a, b) =>
    b.priority - a.priority ||
    a.lastAssignedAt - b.lastAssignedAt ||
    b.lastSeen - a.lastSeen ||
    a.gateway_id.localeCompare(b.gateway_id)
  );
  return candidates[0]?.gateway_id ?? null;
}
