# Voicx Control Bridge (API v1)

A small local control-plane service for up to 20 Android gateway devices. Device telemetry and command state use a local JSON file; the optional customer CRM uses MongoDB. It does not replace or modify the SIP engine.

## Requirements

- Node.js 20+
- MongoDB 6-compatible deployment for persistent CRM (optional)
- A dedicated always-on local host
- Private network access from Android devices and API clients

## Start

Generate long random secrets (at least 32 characters each), then configure:

```bash
export VOICX_ADMIN_TOKEN='replace-with-a-long-random-admin-secret'
export VOICX_DEVICES='[{"id":"phone-01","name":"HONOR MTN-NX1","token":"replace-with-a-different-long-device-secret"}]'
# Bind to loopback for local-only use; for handset access, bind to the host's private LAN/VPN IP.
export HOST='127.0.0.1'
export PORT='8787'
export VOICX_STORE='./data/voicx-store.json'
export TELEGRAM_BOT_TOKEN='token-from-botfather'
export TELEGRAM_ADMIN_IDS='123456789'
# Optional separate allowlist for CRM-only Telegram commands.
export VOICX_CRM_TELEGRAM_ADMIN_IDS='123456789'
export MONGODB_URI='mongodb+srv://...'
export MONGODB_DATABASE='voice_sip_agent_control'
export VOICX_CRM_TENANT_ID='your-organization-id'
node server.mjs
```

For a second device, add another object with a unique id and unique token. Never commit real tokens. Android devices cannot reach a loopback-bound listener on the bridge host: for handset access, set `HOST` to the host's private LAN or VPN interface address and restrict the port with the host firewall. Do not expose it directly to the public internet. Use a private VPN/overlay for remote administration.

## Admin API

Send `Authorization: Bearer $VOICX_ADMIN_TOKEN`.

- `GET /health` — liveness (no secrets)
- `GET /api/v1/devices` — configured devices and last-seen telemetry
- `POST /api/v1/devices/command` — queue an allow-listed command
- `GET /api/v1/commands/{commandId}` — inspect command state
- `GET /api/v1/audit` — recent audit events

Example:

```bash
curl -H "Authorization: Bearer $VOICX_ADMIN_TOKEN" http://127.0.0.1:8787/api/v1/devices
curl -X POST http://127.0.0.1:8787/api/v1/devices/command \
  -H "Authorization: Bearer $VOICX_ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: service-status-phone-01-001' \
  -d '{"deviceId":"phone-01","type":"service.status"}'
```

Allowed command types in this first slice: `service.status`, `service.start`, and
`service.stop`. Audio controls remain disabled until a separately reviewed adapter is implemented. Commands expire after 60 seconds. Delivery is
at-least-once; clients should use the command UUID to deduplicate. Idempotency keys
prevent duplicate admin submissions for the same device.

## Android device protocol

Device requests use that device's own bearer token.

- `POST /api/v1/devices/{deviceId}/calls/report` — report a completed SIM/Gemini call using the device token; stored in MongoDB CRM when configured.\n- `POST /api/v1/devices/{deviceId}/calls/report` — report a completed SIM/Gemini call using the device token; stored in MongoDB CRM when configured.
- `POST /api/v1/devices/{deviceId}/heartbeat` with JSON such as
  `{"battery":87,"charging":true,"serviceState":"running","appVersion":"0.2.0"}`
- `GET /api/v1/devices/{deviceId}/commands/next` to receive queued commands
- `POST /api/v1/devices/{deviceId}/commands/{commandId}/result` with
  `{"status":"succeeded","result":{"serviceState":"running"}}` or
  `{"status":"failed","error":"permission_denied"}`

The Android source includes an opt-in `ControlBridgeClient` with authenticated heartbeat, command polling, and result reporting. The device token is encrypted using Android Keystore. `GatewayService` reports heartbeat every 30 seconds and polls the local command queue every 8 seconds while its persistent foreground host is running. The approved command adapter supports only `service.status`, `service.start`, and `service.stop`; start/stop change the phone's SIP/call-takeover switch, while the foreground control host remains alive so it can be started again. It does not initiate or terminate real calls, and it does not change audio settings remotely.

### Telegram bot control

Set `TELEGRAM_BOT_TOKEN` to the token created with BotFather and `TELEGRAM_ADMIN_IDS` to the numeric Telegram user IDs allowed to control the system. The bot accepts private chats from allowlisted IDs only; groups and all other users are ignored. The Node bridge is the only process that polls `getUpdates` for this bot token—do not run a second copy of the bridge with the same token.

Commands:

- `/devices` — list all configured phones and their last reported online state.
- `/status phone-01` — queue a status request for one phone.
- `/start_device phone-01` — ask for a one-time confirmation, then enable that phone's gateway takeover.
- `/stop_device phone-01` — ask for a one-time confirmation, then disable that phone's gateway takeover.

SMS-only Telegram users are configured separately with `VOICX_SMS_TELEGRAM_ADMIN_IDS`. Configure `VOICX_HOSTED_API_URL`, `VOICX_SMS_ORGANIZATION_ID`, and `VOICX_SMS_ORGANIZATION_TOKEN` on this backend; never place the Adasms provider key here or in the Android app. If the Hosted API preview is protected by Vercel SSO, configure its deployment-protection bypass secret in `VOICX_HOSTED_API_BYPASS_TOKEN` or use an approved custom domain. SMS commands (private chat only): `/sms_help`, `/sms_send +9677xxxxxxx | message | موافق`, and `/sms_history [count]`. The operator must confirm recipient consent in the command before an SMS request is accepted.

CRM-only users are configured separately with `VOICX_CRM_TELEGRAM_ADMIN_IDS`; they cannot control devices unless also listed in `TELEGRAM_ADMIN_IDS`. CRM commands (private chat only): `/crm_help`, `/crm_add name | phone | company | city`, `/crm_search query`, `/crm_never`, `/crm_due`, and `/crm_log phone | outcome | summary | follow-up-ISO`.

Start/stop confirmations expire after 60 seconds and can be used once. Results are sent back to the same private Telegram chat after the phone reports completion. Bot credentials stay on the local bridge host; they are never placed in the APK.

The bridge's built-in listener is plain HTTP. Android requires HTTPS for its bridge URL, so put it behind a private HTTPS endpoint. A local always-on computer can use Tailscale Serve or another private VPN/reverse proxy for TLS termination; no VPS is required. The host must remain powered and connected, and each phone must be able to reach that private HTTPS address. Do not expose the bridge directly to the public internet.

Heartbeat freshness window is 90 seconds. Device credentials are independent and can
be revoked by removing/replacing that device's configured token and restarting the
bridge. Each handset is configured independently in the Android app.

## Deliberate safety limits

- No endpoint starts a real SIP call. The call route returns HTTP 501 until the
  outbound SIP path is implemented and validated on the target handset.
- No SIP listener, call audio, SMS content, or SIP credentials are exposed by this API.
- The Android agent executes only the three allow-listed service status/start/stop commands. It does not accept arbitrary shell commands, initiate SIP calls, or alter audio settings.
- The bridge has no internet-facing authentication proxy or TLS termination. Put it
  behind a private VPN/overlay or a separately configured HTTPS reverse proxy.

## Smoke test

Run the end-to-end local API smoke test with Node.js 20+: `npm test` (or `node smoke-test.mjs`). It checks admin/device authentication, heartbeat telemetry, command queue delivery/result reporting, and confirms that outbound SIP call requests remain disabled (HTTP 501). The Telegram poller stays disabled in tests unless a bot token and admin allowlist are supplied.

## Persistence

State is atomically written to a local JSON file with restrictive file permissions.
Back up this file securely. It contains device telemetry, command metadata, and audit
records; it does not contain bearer tokens.

## CRM and marketing API (admin authenticated)

All routes below use `Authorization: Bearer $VOICX_ADMIN_TOKEN`. Data is stored in the bridge's existing persistent JSON store under `crm`.

| Route | Purpose |
|---|---|
| `GET/POST /api/v1/crm/contacts` | List/create contacts |
| `GET /api/v1/crm/contacts/:id/messages` | Read message history for a contact |
| `PATCH /api/v1/crm/contacts/:id/call-summary` | Save the previous-call summary and timestamp |
| `GET/PATCH/DELETE /api/v1/crm/contacts/:id` | Read/update/delete a contact |
| `POST /api/v1/crm/contacts/search` | Search contacts; optional `filterId` |
| `GET/POST /api/v1/crm/filters` | Manage reusable audience filters |
| `GET/POST /api/v1/crm/campaigns` | Save and list marketing campaigns |
| `GET/POST /api/v1/crm/templates` | Save and list reusable SMS/WhatsApp templates |
| `GET /api/v1/crm/dashboard` | Today's tasks, upcoming sends, recent calls and messages |
| `GET/POST /api/v1/crm/tasks`, `GET /api/v1/crm/tasks/today` | Daily follow-up tasks |
| `POST /api/v1/crm/sms` | Queue an SMS |
| `POST /api/v1/crm/whatsapp` | Queue a WhatsApp message |
| `POST /api/v1/crm/calls` | Queue a call request |
| `GET /api/v1/crm/outbox`, `PATCH /api/v1/crm/outbox/:id/status` | Inspect/update delivery jobs |
| `GET/POST /api/v1/crm/automations` | Store persistent automation and reply policies |
| `POST /api/v1/crm/webhooks/sms` and `/api/v1/crm/webhooks/whatsapp` | Receive provider delivery/inbound events |
| `POST /api/v1/crm/publications/haraj` | Save a Haraj listing draft for manual publishing |
| `POST /api/v1/crm/publications/website` | Save a website publication draft |
| `GET /api/v1/crm/marketing/status` | Marketing activity summary |
| `GET/POST /api/v1/crm/reports`, `POST /api/v1/crm/reports/generate` | Store and generate period marketing reports |

Contact fields include name, phone, personal/professional email, job title, company, previous messages, previous-call summary, tags, notes, and next-send time. Contact opt-out flags (`optedOut` / `doNotContact`) block new outbound SMS/WhatsApp queue requests. The dashboard exposes upcoming queued sends, today's open tasks, recent calls and message activity. Reports aggregate queued, delivered, failed and reply counts for a requested period.

**Delivery and publishing safety:** SMS, WhatsApp, and call endpoints currently create persistent queued jobs only. They do not contact a carrier, WhatsApp Business provider, or SIP endpoint until a provider adapter and credentials are configured. Inbound webhooks require `VOICX_MESSAGING_WEBHOOK_TOKEN` (at least 32 characters). The webhook stores inbound messages; automatic replies require a configured provider adapter and an explicitly enabled automation policy. Haraj and website routes create drafts only; no automated login or publishing is performed. This avoids claiming a message was sent or an advert was published when it was not.


## Direct Adasms SMS adapter

The separate Adasms adapter calls `https://adasms-standalone.vercel.app/api/sms/send`
from the backend only. Configure `ADASMS_API_KEY` as a **Sensitive** Vercel
environment variable for the backend deployment; never add it to GitHub, Telegram,
logs, or the Android app.

- `POST /api/v1/sms/adasms/send` — admin bearer-authenticated backend route.
  JSON: `{"phone":"+9677xxxxxxxx","message":"...","consentConfirmed":true}`.
  Pass an `Idempotency-Key` header to prevent duplicate sends.
- Telegram private-chat commands for IDs in `VOICX_SMS_TELEGRAM_ADMIN_IDS`:
  `/adasms_help`, `/adasms_send +9677xxxxxxx | message | موافق`,
  and `/adasms_history [count]`.
- SMS request records are stored in MongoDB collection `sms_messages` with
  tenant scoping, masked phone display, idempotency, and accepted/failed state.
- Existing `/sms_send` Hosted SMS integration is retained unchanged.
- The backend requires explicit consent confirmation and blocks contacts marked
  `optedOut`, `doNotContact`, `smsOptOut`, or `marketingConsent=false`.
- A provider HTTP success is recorded as `accepted`, not as carrier delivery.
  No live SMS is sent by CI tests.
