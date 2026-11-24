# Voicx SIP Control — Telegram + local host setup

This is the no-VPS deployment for one Telegram bot and up to 20 Android phones.

## 1. Prepare the always-on host

Use a mini PC, desktop, or small Linux server that stays powered on and has internet access. Install Docker Engine and Docker Compose, then copy `control-bridge/.env.example` to `control-bridge/.env`.

Generate secrets locally on that host:

```bash
node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"
```

Generate one secret for `VOICX_ADMIN_TOKEN` and a different secret for every phone. Never reuse a phone's token on another device.

## 2. Configure the bot and the 20 phones

Create one bot with Telegram's BotFather. Put its token in `TELEGRAM_BOT_TOKEN`. Put the numeric Telegram user ID(s) allowed to administer the system in `TELEGRAM_ADMIN_IDS`, separated by commas.

Set `VOICX_DEVICES` to a JSON array. Example for two phones:

```json
[
  {"id":"phone-01","name":"HONOR MTN-NX1","token":"FIRST_UNIQUE_RANDOM_SECRET"},
  {"id":"phone-02","name":"Android Phone 2","token":"SECOND_UNIQUE_RANDOM_SECRET"}
]
```

Add up to 20 entries. Each ID must be unique and match the ID entered on that phone in the Android app. Keep the actual `.env` file private; it is ignored by Git.

## 3. Start the local relay

From the `control-bridge` directory:

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f voicx-control-bridge
```

The bridge listens on host loopback port 8787. Docker restarts it after host/container restarts. Keep the persistent Docker volume and back it up.

## 4. Give phones a private HTTPS route

The Android client deliberately requires HTTPS. For phones outside the host's LAN, put the host and phones on the same private VPN/overlay network. Tailscale Serve or another private HTTPS reverse proxy can terminate TLS and forward to `http://127.0.0.1:8787`. Use the private HTTPS URL provided by that setup in the app. Do not expose port 8787 directly to the public internet.

No VPS is used: the local host is the relay. It must stay online, and it must be able to reach Telegram's Bot API. Phones must be able to reach the host over the private HTTPS network.

## 5. Configure each Android phone

In the app's **Voicx Control API** settings, enter:

- Bridge URL: the private HTTPS URL from step 4
- Device ID: the exact ID from `VOICX_DEVICES`
- Device token: that phone's unique secret from `VOICX_DEVICES`

Save settings. The token is encrypted with Android Keystore. The app sends a heartbeat every 30 seconds and checks its command queue every 8 seconds while the persistent foreground host is active.

Repeat with a different ID and token for each phone.

## 6. Telegram commands

Open a private chat with the bot from an allowlisted Telegram account:

- `/devices` — show all configured phones and online/offline state
- `/status phone-01` — request status from one online phone
- `/start_device phone-01` — confirm, then enable that phone's SIP/call takeover
- `/stop_device phone-01` — confirm, then disable that phone's SIP/call takeover

Start/stop confirmation is one-time and expires after 60 seconds. Results return to the same Telegram private chat. Only the local bridge polls the bot token; do not run multiple bridge instances with the same token.

## Scope and limits

- The bot manages up to 20 independent Android devices through one local bridge.
- Start/stop changes the app's SIP/call-takeover switch. The Android foreground control host remains alive so a later Telegram command can start takeover again.
- Status/start/stop are the only remote device commands enabled in this release slice. No shell command, audio change, SIM PIN operation, SIP call initiation, or call answering/hangup is exposed through Telegram.
- Android can still terminate an app after user force-stop, OEM task killing, battery restrictions, or system-enforced process termination. A foreground service and battery settings improve resilience but cannot guarantee that a phone never stops.
- Telegram Bot API and the private VPN may use external coordination infrastructure, but the Voicx control server and device state remain on the local host. No VPS is required.
