# Central multi-gateway MCP — implementation contract

## Topology

One central MCP service maintains independent WebSocket sessions for many Android installations. A gateway is identified by an immutable operator-assigned ID and a unique revocable device credential. A reconnect replaces only the old connection for the same ID.

## Isolation rules

- SIP registrar users, passwords, SIM inventory, routes, call IDs and history stay local to each gateway.
- Every command includes exactly one gateway ID; no implicit fan-out.
- The central service forwards commands and reports online/offline state; it does not dial SIP or SIM itself.
- Each device credential is distinct. Admin MCP authentication is separate from device authentication.
- TLS/WSS is mandatory outside localhost. Credentials are stored only in server secret configuration.
- Heartbeats contain operational status only; never secrets, call audio, transcripts or full phone numbers.

## Call-control safety

A call action is a two-step operation: request approval, then execute the returned approval ID after the user approves on that exact Android device. The Android app remains the authority for local approval, route selection, Telecom/SIM access and one-time execution. The central MCP must not manufacture approval or bypass the phone's approval UI.

## Phased completion

1. Central coordinator and MCP tools (this branch).
2. Android WSS agent with Keystore-backed credential storage, reconnect/backoff, heartbeat and command-result handling.
3. Device enrollment/revocation UX and server-side durable registry/credential rotation.
4. Per-device status, call-list and approval UI in the central admin dashboard.
5. CI, multi-device simulation tests, then controlled device tests. No production rollout until separately approved.

## Protocol version

Initial JSON envelope is intentionally small and versioned by the application release. Add an explicit compatibility field before the first Android agent release. Commands have bounded payloads and a timeout; offline or disconnected gateways fail closed.
