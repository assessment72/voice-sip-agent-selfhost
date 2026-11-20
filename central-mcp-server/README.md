# Voice SIP Central MCP

One MCP endpoint manages multiple independent Android gateway installations. Each phone keeps its own SIP accounts, SIM cards, routes and call state. The central service never merges credentials or assumes that a SIM/account exists on another device.

## Services and ports

- `POST /mcp`: authenticated MCP JSON-RPC endpoint for the operator/admin.
- `GET /healthz`: minimal health response.
- `wss://HOST/v1/agent`: persistent outbound connection from each Android gateway to the central server.

Run behind a TLS reverse proxy. Do not expose the plain HTTP listener to the public internet.

## Configuration

- `PORT`: listener port (default 8788).
- `MCP_ADMIN_TOKEN`: high-entropy admin bearer token for MCP clients.
- `GATEWAY_CREDENTIALS_JSON`: server-side JSON object mapping gateway IDs to unique high-entropy device tokens, for example `{"gw01":"...","gw02":"..."}`. Keep this in a secret manager; never commit it or put it in an APK.

Start with Node.js 22+:

```sh
cd central-mcp-server
npm install
npm run check
npm test
MCP_ADMIN_TOKEN='...' GATEWAY_CREDENTIALS_JSON='{"gw01":"..."}' npm start
```

## Agent protocol

Each app instance connects outbound using `Authorization: Bearer <device-token>` and `X-Gateway-Id: <gateway-id>`. The server responds with `server.ready`. The app sends periodic `heartbeat` messages with non-sensitive metadata and handles `command` messages, replying with `command_result`.

Command envelope:

```json
{"type":"command","id":"cmd_1","name":"sip_gateway_status","arguments":{}}
```

Result envelope:

```json
{"type":"command_result","id":"cmd_1","result":{"ok":true,"status":"..."}}
```

Do not send SIP passwords, SIM PINs, API keys, audio, or full caller numbers in heartbeat metadata.

## MCP tools

- `gateways_list`
- `gateway_status`
- `gateway_calls_list`
- `gateway_set_takeover`
- `gateway_restart_sip`
- `gateway_set_earpiece_mute`
- `gateway_pending_call_approvals`
- `gateway_request_call_action`
- `gateway_execute_approved_action`
- `gateway_call_audit`

Every command other than listing gateways requires an explicit `gateway_id`; the server never broadcasts call actions to every phone. Call actions must first be requested and approved on the selected phone. The execute operation is a separate one-time step using the locally approved ID.

## Current implementation boundary

The branch includes the authenticated central MCP coordinator, Android outbound WSS agent, per-device credential configuration, heartbeat/status reporting, approval-gated device commands, and an Arabic read-only web dashboard. Live device state, command queue, leases, and audit events are persisted in MongoDB Atlas. Legacy environment-provisioned device credentials remain server secrets; managed enrollment stores only token hashes in MongoDB Atlas. Physical-device validation remains future work.

## Automatic gateway routing

- `gateway_route_preview` is read-only and selects an eligible online phone.
- `gateway_request_routed_call` selects one eligible phone and asks it to create a local DIAL approval. It does **not** execute the call.
- Eligibility requires a live WSS connection, heartbeat no older than 60 seconds, SIP ready, takeover enabled, and no active call.
- Selection orders by configured priority, then oldest assignment, then most recent heartbeat and gateway ID for deterministic tie-breaking.
- A gateway is reserved for up to 90 seconds while the local approval is pending, preventing duplicate routing to the same phone.
- Capacity is deliberately limited to one active call per phone until the Android agent reports a verified concurrent-call count. The current boolean `active_call` heartbeat is not sufficient to safely claim multi-channel capacity.
- Optional `GATEWAY_ROUTING_POLICY_JSON` maps gateway IDs to `priority` and `enabled`, for example `{"gw01":{"priority":10},"gw02":{"priority":2,"enabled":false}}`.

No automatic retry to another phone is performed after a local approval request fails; the operator can preview and request again. No phone call is placed without local approval and the separate one-time execution step.

## Android gateway enrollment

Each Android installation now has a **Central MCP management** section. Enter the HTTPS base URL, its own gateway ID, and its own device token. The app opens an outbound WSS connection and stores the token encrypted with Android Keystore.

Provision the exact ID/token pair in `GATEWAY_CREDENTIALS_JSON` on the server before connecting that phone. Generate a different random token for every gateway; do not share tokens. A newly installed app creates a gateway ID automatically, but the server operator must provision that ID and token.

The foreground gateway service keeps the WSS agent alive even when call takeover is off, as long as central MCP is configured. The agent sends operational heartbeat metadata only and does not send SIP passwords, SIM PINs, call audio, transcripts, or caller numbers.

Call actions are approval-gated: MCP creates a pending request, the user approves it in the phone UI, then the MCP client may execute the exact one-time approval. No action is executed merely because a command arrived.


## Vercel deployment

The Vercel project is named `voice-sip-central-mcp`, with root directory `central-mcp-server`, Node.js runtime, Fluid Compute, region `sfo1`, and a 300-second function duration. The project is intentionally not connected to GitHub and has no deployment yet.

The adapter preserves the Android app's endpoint paths through rewrites:

- `POST /mcp`
- `GET /healthz`
- `wss://HOST/v1/agent`

MongoDB Atlas is the shared gateway registry, command queue, lease store, and audit log. Each Vercel function instance watches its own MongoDB change stream. Admin requests are persisted and delivered to the function instance holding the target device WebSocket; delivery uses an atomic queued-to-delivered claim and recovers queued commands after a watcher reconnect. Command results are persisted and awaited by the requesting function. The Atlas cluster is replica-set based and supports change streams.

The Vercel project has sensitive/encrypted environment variables configured for `MONGODB_URI`, `MONGODB_DATABASE`, `MCP_ADMIN_TOKEN`, `GATEWAY_CREDENTIALS_JSON`, and `GATEWAY_ROUTING_POLICY_JSON`. The Atlas database user is scoped to `readWrite` on `voice_sip_central` only. Device credentials are currently an empty object; add a unique ID/token pair for each phone before enrollment.

**Not yet production-validated:** the project is not linked to the GitHub branch, no deployment has been made, and cross-instance WebSocket/change-stream delivery has not been smoke-tested. Vercel WebSocket connections are pinned to one function instance and close when the function reaches its maximum duration; Android reconnect/backoff and state resynchronization remain required. The health endpoint reports `multi_instance_safe: true` only when Atlas is reachable, but this is not a substitute for the multi-instance smoke test.

Never put database credentials or device tokens in Git, an APK, or chat. Keep all secrets in Vercel's sensitive environment-variable store. Do not widen Atlas network access without reviewing the impact on other databases in the same cluster.

## Container deployment (not performed by this PR)

Build and run on a server with a reverse proxy that terminates TLS:

```sh
cp .env.example .env
# Replace every placeholder with independently generated random secrets.
docker build -t voice-sip-central-mcp:dev .
docker run -d --name voice-sip-central-mcp --restart unless-stopped \
  --env-file .env -p 127.0.0.1:8788:8788 voice-sip-central-mcp:dev
```

Configure the reverse proxy to forward HTTPS `/mcp` to the HTTP listener and upgrade WSS `/v1/agent` to the same listener. Preserve the `Authorization` and `X-Gateway-Id` headers and enable WebSocket upgrades. Expose only the TLS proxy publicly; the container listener is bound to localhost on the host in the example.

When `MONGODB_URI` is configured, the service persists gateway state, commands, leases and audit events in MongoDB Atlas; otherwise local development uses an in-memory registry. After a server restart, Android agents reconnect automatically. Keep the `.env` file private and restrict access to it. Rotate credentials by changing the device token in the server secret and then updating that gateway's app configuration; a managed enrollment/rotation API remains future work.


## Central web dashboard

Open `https://YOUR-HOST/dashboard` to use the Arabic, mobile-friendly dashboard. Sign in with the `MCP_ADMIN_TOKEN` (not a gateway device token). The browser keeps this admin token in session storage only; logging out or closing the browser session clears it. Dashboard data APIs require the admin bearer token and return an allowlist of operational metadata only; device tokens, SIP passwords, SIM PINs and internal instance identifiers are never returned. The dashboard refreshes device status every 15 seconds and can show the persisted audit events for a selected gateway. This initial dashboard is read-only: it does not issue call commands or change gateway settings.


## Gateway administration API

- `POST /admin/gateways/enroll` with `{ "gateway_id": "gw01" }` creates a random per-device token and returns it once. The raw token is never persisted.
- `PATCH /admin/gateways/{gateway_id}/status` with `{ "enabled": false }` disables a device; use `true` to re-enable it.
- `POST /admin/gateways/{gateway_id}/rotate-token` invalidates the old device token and returns a new token once. Update the phone configuration before reconnecting.
- All routes require the admin bearer token and MongoDB Atlas. The dashboard does not display stored token hashes or secrets.
