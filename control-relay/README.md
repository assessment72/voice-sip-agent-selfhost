# Voicx SIP Control — Local Telegram Relay

This service is the **single Telegram Bot API consumer** for the installation. It runs on an
always-on computer on the same LAN or private VPN as the phones. Each Android device initiates an
outbound authenticated WebSocket connection to the relay. Telegram does not connect to phones.

## Current scope

- One bot, one or more explicitly allowlisted Telegram administrator user IDs.
- Up to 20 device IDs are supported by configuration; each device has its own random bearer token.
- Device online/offline notifications, device listing, status request, and gateway service
  start/stop commands.
- No hosted API, VPS, public database, or enrollment bootstrap token is required.
- The relay does not place, answer, intercept, or terminate PSTN/SIP calls. It only transports
  management commands; the Android agent must implement and authorize each action.

## Run locally

1. Install Python 3.11+.
2. Copy `.env.example` to `.env`, then set the Telegram bot token, your numeric Telegram user ID,
   and per-device tokens. Generate a token with `python -c "import secrets; print(secrets.token_urlsafe(32))"`.
3. Install dependencies: `python -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt`.
4. Start: `python server.py`.
5. Check `http://127.0.0.1:8765/health`.

For an always-on Linux mini PC or home server, Docker is the recommended deployment:

```bash
docker compose up -d --build
docker compose logs -f voicx-relay
```

Enable Docker at host startup (`sudo systemctl enable --now docker`) and keep the host powered
and connected. `restart: unless-stopped` brings the relay back after host/container restarts.

Keep `.env` private. Do not place the Telegram bot token in an APK, browser, or Git repository.
The relay must be the only process polling this bot's updates.

## Network model

- On one LAN, expose TCP 8765 only to the local subnet.
- For phones outside that LAN, connect the relay and phones through a private overlay VPN
  (recommended: Tailscale or WireGuard). No VPS is needed if the relay host remains online.
- Do not expose the plain HTTP/WebSocket listener directly to the public internet. Put it behind
  TLS on a trusted local reverse proxy or use the private VPN.
- Reserve a DHCP address for the relay host and configure each phone with its relay URL.

## Device protocol

Connect to `ws(s)://RELAY_HOST:8765/ws/device/{device_id}` with
`Authorization: Bearer {device_token}`.

Send JSON heartbeat packets periodically:
`{"type":"heartbeat","status":{"gateway":"running","sip":"registered"}}`

Receive command packets:
`{"type":"command","id":"UUID","action":"status|gateway_start|gateway_stop","issued_at":1730000000}`

Return:
`{"type":"result","id":"UUID","result":{"ok":true,"message":"..."}}`

The Android client must validate the action allowlist, reject stale command timestamps, and only
start/stop its own foreground gateway service. Add confirmation for disruptive actions before
enabling them in production.

## Security and limitations

- Telegram administrator IDs are checked on every command; group chats are ignored.
- Device tokens are unique, random, and compared in constant time.
- Never log tokens. Rotate a device token by changing the local secret config and reconnecting it.
- Run the relay as a dedicated non-root user; firewall the listener; back up the private config.
- This first relay implementation is a control-plane foundation. Android WebSocket client
  integration, durable audit storage, TLS deployment, and end-to-end tests are still required.
- Android may stop background work due to OEM battery management. A foreground service,
  battery exemption where appropriate, and reboot recovery are separate device-side concerns;
  no software can promise uninterrupted operation on every Android build.
