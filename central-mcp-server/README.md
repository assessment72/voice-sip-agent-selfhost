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
- `gateway_pending_call_approvals`
- `gateway_request_call_action`
- `gateway_execute_approved_action`
- `gateway_call_audit`

Every command other than listing gateways requires an explicit `gateway_id`; the server never broadcasts call actions to every phone. Call actions must first be requested and approved on the selected phone. The execute operation is a separate one-time step using the locally approved ID.

## Current implementation boundary

This commit provides the central MCP coordinator and the authenticated device WebSocket protocol. It does not yet add the Android-side WSS agent, persistent enrollment UI, durable server-side registry, reverse-proxy deployment, or real-call/device validation. Until the Android agent is implemented, phones will not appear online. The existing local ADB MCP remains available and is not replaced by this service.

No phone is contacted, no call is initiated, no production setting is changed, and no deployment is performed by tests or startup.

## Android gateway enrollment

Each Android installation now has a **Central MCP management** section. Enter the HTTPS base URL, its own gateway ID, and its own device token. The app opens an outbound WSS connection and stores the token encrypted with Android Keystore.

Provision the exact ID/token pair in `GATEWAY_CREDENTIALS_JSON` on the server before connecting that phone. Generate a different random token for every gateway; do not share tokens. A newly installed app creates a gateway ID automatically, but the server operator must provision that ID and token.

The foreground gateway service keeps the WSS agent alive even when call takeover is off, as long as central MCP is configured. The agent sends operational heartbeat metadata only and does not send SIP passwords, SIM PINs, call audio, transcripts, or caller numbers.

Call actions are approval-gated: MCP creates a pending request, the user approves it in the phone UI, then the MCP client may execute the exact one-time approval. No action is executed merely because a command arrived.
