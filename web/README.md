# Voice SIP Agent — Telegram Mini App signaling backend

This folder is a Vercel project root. It contains the server-side signaling API and a browser WebRTC Mini App. It is not yet a complete Android-to-cellular gateway integration.

## Vercel setup

Set the Vercel project **Root Directory** to `web`, then configure these server-side environment variables:

- `TELEGRAM_BOT_TOKEN`: bot token used by the app and backend. Keep it server-side.
- `TELEGRAM_OWNER_ID`: numeric Telegram user ID allowed to create calls.
- `TELEGRAM_PRIVATE_CHAT_ID`: private chat where the Android gateway receives invitations.
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

Configure the bot's Mini App URL in BotFather to point to the deployed HTTPS URL. The bot must be able to send a private message to the configured chat. The Android worker still needs to recognize the one-time `/vsa_join <token>` message and create its PeerConnection before the Android invitation can be accepted.

## Browser audio transport

The Mini App captures microphone audio with echo cancellation and noise suppression, resamples it to mono 8 kHz, and sends sequenced 20 ms PCM16LE frames over an unordered, no-retransmit DataChannel. It uses an AudioWorklet for capture/playback and a small jitter buffer for incoming frames. This is a prototype PCM transport and still needs real network/latency/audio-quality testing.

## Current limitations

- Android does not yet consume `/vsa_join` or create a WebRTC `PeerConnection`.
- The current Android `CallBridge` still connects cellular to SIP; WebRTC has not been inserted into that bridge.
- TURN credentials are supplied as Vercel environment variables and need an operational rotation procedure; they are not generated at call time.
- Deploying this folder is not authorized or performed by this commit.
