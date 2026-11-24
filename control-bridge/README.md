# Voicx Control Bridge (API v1)

A small local control-plane service for up to 20 Android gateway devices. It uses Node.js built-ins only and stores state in a local JSON file. It does not replace or modify the SIP engine.

## Requirements

- Node.js 20+
- A dedicated always-on local host
- Private network access from Android devices and API clients

## Start

Generate long random secrets (at least 32 characters each), then configure:

```bash
export VOICX_ADMIN_TOKEN='replace-with-a-long-random-admin-secret'
export VOICX_DEVICES='[{"id":"phone-01","name":"HONOR MTN-NX1","token":"replace-with-a-different-long-device-secret"}]'
export HOST='127.0.0.1'
export PORT='8787'
export VOICX_STORE='./data/voicx-store.json'
node server.mjs
```

For a second device, add another object with a unique id and unique token. Never commit real tokens. Keep the service bound to loopback or a private VPN interface; do not expose it directly to the public internet. Use a private VPN/overlay for remote administration.

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

Allowed command types in this first slice: `service.status`, `service.start`,
`service.stop`, and `audio.set`. Commands expire after 60 seconds. Delivery is
at-least-once; clients should use the command UUID to deduplicate. Idempotency keys
prevent duplicate admin submissions for the same device.

## Android device protocol

Device requests use that device's own bearer token.

- `POST /api/v1/devices/{deviceId}/heartbeat` with JSON such as
  `{"battery":87,"charging":true,"serviceState":"running","appVersion":"0.2.0"}`
- `GET /api/v1/devices/{deviceId}/commands/next` to receive queued commands
- `POST /api/v1/devices/{deviceId}/commands/{commandId}/result` with
  `{"status":"succeeded","result":{"serviceState":"running"}}` or
  `{"status":"failed","error":"permission_denied"}`

Heartbeat freshness window is 90 seconds. Device credentials are independent and can
be revoked by removing/replacing that device's configured token and restarting the
bridge. Android-side enrollment/agent integration is the next implementation step.

## Deliberate safety limits

- No endpoint starts a real SIP call. The call route returns HTTP 501 until the
  outbound SIP path is implemented and validated on the target handset.
- No SIP listener, call audio, SMS content, or SIP credentials are exposed by this API.
- Service commands are only queued; the Android agent must validate and execute them
  under Android's foreground-service and permission rules.
- The bridge has no internet-facing authentication proxy or TLS termination. Put it
  behind a private VPN/overlay or a separately configured HTTPS reverse proxy.

## Smoke test

Run the end-to-end local API smoke test with Node.js 20+: `npm test` (or `node smoke-test.mjs`). It checks admin/device authentication, heartbeat telemetry, command queue delivery/result reporting, and confirms that outbound SIP call requests remain disabled (HTTP 501).

## Persistence

State is atomically written to a local JSON file with restrictive file permissions.
Back up this file securely. It contains device telemetry, command metadata, and audit
records; it does not contain bearer tokens.
