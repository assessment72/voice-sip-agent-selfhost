# Central multi-gateway MCP — implementation contract

## Topology

One central MCP service maintains independent WebSocket sessions for many Android installations. A gateway is identified by an immutable operator-assigned ID and a unique revocable device credential. A reconnect replaces only the old connection for the same ID.

## Isolation rules

- SIP registrar users, passwords, SIM inventory, routes, call IDs and history stay local to each gateway.
- Every command includes exactly one gateway ID; no implicit fan-out.
- The central service forwards commands and reports online/offline state; it does not dial SIP or SIM itself.
- Each device credential is distinct. Admin MCP authentication is separate from device authentication.
- TLS/WSS is mandatory outside localhost. Legacy device credentials can be provisioned through server secrets; managed enrollment stores only SHA-256 hashes of random device tokens in MongoDB Atlas.
- Heartbeats contain operational status only; never secrets, call audio, transcripts or full phone numbers.

## Call-control safety

A call action is a two-step operation: request approval, then execute the returned approval ID after the user approves on that exact Android device. The Android app remains the authority for local approval, route selection, Telecom/SIM access and one-time execution. The central MCP must not manufacture approval or bypass the phone's approval UI.

## Phased completion

1. Central coordinator and MCP tools (this branch).
2. Android WSS agent with Keystore-backed credential storage, reconnect/backoff, heartbeat and command-result handling.
3. Device enrollment/revocation UX and server-side durable registry/credential rotation (initial admin controls implemented).
4. Per-device status, audit and credential lifecycle UI in the central admin dashboard (initial version implemented; call-list and approval UI remain future work).
5. CI, multi-device simulation tests, then controlled device tests. No production rollout until separately approved.

## Protocol version

Initial JSON envelope is intentionally small and versioned by the application release. Add an explicit compatibility field before the first Android agent release. Commands have bounded payloads and a timeout; offline or disconnected gateways fail closed.


## Android gateway enrollment (implemented in this branch)

1. Deploy the central service behind HTTPS/WSS. Do not expose its plain HTTP port directly.
2. Generate a different high-entropy random token for each phone. Keep the gateway ID/token pairs in the server-side secret `GATEWAY_CREDENTIALS_JSON`.
3. Open the gateway app, then the **Central MCP management** section.
4. Copy the phone's generated gateway ID into the server credential map and assign that phone its own random token.
5. Enter the HTTPS base URL (for example `https://mcp.example.net`), the exact gateway ID, and that device's token in the app. Save and connect.
6. Confirm that the gateway appears online in the MCP `gateways_list` tool.

The app stores the token encrypted with a non-exportable Android Keystore AES-GCM key. It opens an outbound WSS connection, sends a heartbeat every 20 seconds, and reconnects with bounded exponential backoff. The connection is maintained by the existing foreground gateway service, including when call takeover is switched off but central management remains configured.

## Call-control approval sequence

- MCP `gateway_request_call_action` sends a request to exactly one selected gateway.
- The app adds the request to its existing on-device Call Control Approvals UI.
- A person must explicitly approve it on that phone.
- MCP `gateway_execute_approved_action` can then consume that one-time approval. The controller rejects pending, expired, mismatched, and replayed approvals.
- The local SIP/SIM endpoints and route remain owned by the selected phone.

## Current limitations

This branch includes the Android WSS agent, MongoDB-backed gateway enrollment registry, an Arabic admin dashboard, one-time enrollment-token creation, gateway disable/re-enable, and token rotation. Managed device tokens are stored as SHA-256 hashes; raw tokens are returned once. The dashboard uses the admin bearer token and does not expose device credentials. Environment-provisioned credentials remain supported for existing gateways. The dashboard is available at `/dashboard`; management APIs require MongoDB Atlas. Cross-instance disable takes effect on the next heartbeat for sockets attached to other function instances. CI must pass and physical-device validation is still required before field use. No production rollout, merge, or real call is performed by this branch.


## Central admin dashboard and gateway lifecycle

- Open `https://HOST/dashboard` and authenticate with the admin token (`MCP_ADMIN_TOKEN`), never a device token.
- The dashboard shows per-device online state, last heartbeat, safe operational metadata and audit events.
- Enroll: provide a unique gateway ID. The server creates a 256-bit random token, stores only its SHA-256 hash in `gateway_enrollments`, and returns the raw token once. Enter that token in the Android app's Central MCP settings.
- Disable/re-enable: the server persists the enabled state. Disabled devices cannot reconnect or receive routed commands; a connection held by another Vercel instance closes on its next heartbeat.
- Rotate token: the old token becomes invalid immediately and the new random token is returned once. Update the Android app configuration before expecting reconnection.
- Every administrative action is recorded in `audit_events`. Device tokens and token hashes are never returned by the dashboard listing or audit API.
- Vercel rewrites include `/dashboard`, `/admin/gateways`, and nested gateway management routes. Deployment remains separate and requires explicit approval.
