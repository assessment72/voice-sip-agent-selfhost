# Voice SIP Agent — Telegram Mini App signaling backend

This folder is a Vercel project root. It contains the server-side signaling API and a browser WebRTC Mini App. It is not yet a complete Android-to-cellular gateway integration.

## Vercel setup

Set the Vercel project **Root Directory** to `web`, then configure these server-side environment variables:

- `TELEGRAM_BOT_TOKEN`: bot token used by the app and backend. Keep it server-side.
- `TELEGRAM_OWNER_ID`: numeric Telegram user ID allowed to create calls.
- `TELEGRAM_PRIVATE_CHAT_ID`: numeric private chat where the Android gateway receives invitations; it must match `TELEGRAM_OWNER_ID` for this single-owner build.
- `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN`: shared Upstash Redis REST database.
- `METERED_TURN_URLS`: comma-separated TURN URLs copied from the Metered dashboard, preferably the approved Saudi region.
- `METERED_TURN_USERNAME` and `METERED_TURN_PASSWORD`: a newly rotated, short-lived or periodically rotated TURN credential.

Do not add secrets to Git, browser code, Android source, or public `NEXT_PUBLIC_*` variables. The previously exposed TURN credential must be revoked and replaced before testing. The API returns TURN credentials only to a peer with a valid room token.

## Endpoints

- `POST /api/room`: validates Telegram Mini App `initData`, checks the configured owner ID, creates a 10-minute room, issues role-specific random peer tokens, and sends the Android token to the configured private Telegram chat.
- `POST /api/signal`: authenticated, role-derived signaling relay. Message types are allowlisted and payloads are limited to 48 KiB.
- `GET /api/poll`: authenticated bounded queue drain for the caller's role. Clients should poll at a modest interval; do not run multiple pollers for the same peer token.
- `GET /api/ice`: returns the server-configured ICE server list only to a valid room peer.

Redis is shared state; no Vercel function-instance memory is used for room or signaling state. WebRTC media flows through the data channel peer-to-peer or through TURN, never through Vercel.

## Telegram bot configuration

Configure the bot's Mini App URL in BotFather to point to the deployed HTTPS URL. The bot must be able to send a private message to the configured chat. The backend sends an inline one-time callback button. The Android worker listens for Telegram `callback_query` updates, validates both the private chat and sender ID, acknowledges the callback, and starts the authenticated Android peer without exposing the token in chat text.

## Browser audio transport

The Mini App captures microphone audio with echo cancellation and noise suppression, resamples it to mono 8 kHz, and sends sequenced 20 ms PCM16LE frames over an unordered, no-retransmit DataChannel. It uses an AudioWorklet for capture/playback and a small jitter buffer for incoming frames. This is a prototype PCM transport and still needs real network/latency/audio-quality testing.

## Current limitations

- Android still needs to consume the one-time callback invitation and create its WebRTC `PeerConnection`.
- The current Android `CallBridge` still connects cellular to SIP; WebRTC has not been inserted into that bridge.
- TURN credentials are supplied as Vercel environment variables and need an operational rotation procedure; they are not generated at call time.
- Deploying this folder is not authorized or performed by this commit.


## Android gateway configuration

In the app's Telegram section:
1. Enable Telegram integration (the existing bot polling service).
2. Enter the same bot token and numeric private chat ID configured on Vercel.
3. Enter the deployed HTTPS Vercel project URL in **Vercel WebRTC server URL**.
4. Save settings. The foreground service polls for the one-time callback button and starts the WebRTC peer after it is pressed.

The existing SIP registration and cellular audio implementation remain in place. When a WebRTC peer is ready, the gateway temporarily selects it as the other side of the existing cellular `CallBridge`. It switches back to SIP after the WebRTC session closes. Switching is deferred while a call is active.

## Important pre-release checks

- Revoke the previously exposed Metered TURN credential and set a newly issued credential in Vercel environment variables.
- Configure Upstash and all Telegram/Metered environment variables before deploying the `web` directory.
- Set the bot's Telegram Mini App URL to the deployed Vercel HTTPS URL.
- The same Telegram bot must not have another active `getUpdates` poller; Telegram allows only one consumer for this bot token.
- The 250 ms signaling poll interval is a prototype setting. Monitor Vercel invocations and Upstash usage before prolonged use.
- A successful CI run validates compilation and unit tests only. A real call still needs testing on the target HONOR Android 16 device and a second client, on different networks, including TURN relay and both audio directions.
