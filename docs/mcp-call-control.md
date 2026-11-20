# Local MCP call-control foundation

This document describes the first, approval-gated call-control layer. It is not yet an MCP transport server.

## Current architecture

`GatewayRuntime.callControl` exposes a small control facade over the existing `CallEndpoint` and `CallLeg` interfaces:

- `CallRegistry` tracks active legs and bounded call history.
- `CallApprovalManager` creates short-lived, single-use approvals.
- `ApprovedCallController` executes dial, answer, reject, hangup and DTMF only after an explicit approval.
- The existing `CallBridge` continues to own SIP↔cellular bridging and RTP/media. The control layer does not intercept or forward audio.

## Approval flow

1. A local client requests an operation using a complete `CallActionSpec`.
2. The request appears in the gateway app's **Call-control approvals** section with the exact number/call ID, route and DTMF digit.
3. The user taps Approve or Reject on the Android device. The ADB/MCP interface intentionally has no approval operation.
4. Immediately before execution, the controller checks that the approval is still valid and matches the exact operation.
5. The approval is atomically consumed. Replays, mismatches, rejected approvals and expired approvals are blocked.

The default approval lifetime is 90 seconds. Audit history is bounded in memory. The Android host must persist audit records securely before this is treated as a durable compliance log.

## Routes

- `auto`: first ready endpoint in the configured endpoint order (currently cellular before SIP in the runtime).
- `cellular` / `sip`: explicit endpoint name when that endpoint is ready.

This is not multi-SIM load balancing. The current Android cellular endpoint and Telecom integration do not establish support for selecting among multiple SIM accounts or handling multiple independent cellular call channels.

## Current scope and remaining work

Implemented:
- JVM call registry, approval manager and approval-gated call controller.
- Runtime wiring to observe both legs of existing bridge sessions.
- Unit tests and CI coverage for the `voice-api` module.

Not implemented yet:
- MCP JSON-RPC transport and tool/resource declarations.
- A hardened local transport with authenticated clients, localhost-only binding, origin checks and request-size/time limits.
- Durable approval/audit storage (the on-device approval UI is now present).
- A verified Android device test matrix for SIP and cellular answer/reject/hangup/DTMF.
- Explicit Android `PhoneAccountHandle` discovery and multi-SIM route selection.

No network listener is started by this foundation. Do not expose an unauthenticated local control endpoint or forward this API to the public internet.
