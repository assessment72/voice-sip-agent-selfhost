# Voice SIP Agent — Telegram Mini App signaling backend

This folder is a Vercel project root. It contains the first server-side signaling foundation and a Telegram Mini App launch page. It is not yet a completed live-call client: the browser WebRTC peer and Android PeerConnection lifecycle still need to be connected to these endpoints.

## Vercel setup

Set the Vercel project **Root Directory** to `web`, then configure these server-side environment variables:

- `TELEGRAM_BOT_TOKEN`: bot token used by the app and backend. Keep it server-side.
- `TELEGRAM_OWNER_ID`: numeric Telegram user ID allowed to create calls.
- `TELEGRAM_PRIVATE_CHAT_ID`: the private chat where the Android gateway receives invitations.
- `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN`: a shared Upstash Redis REST database.
- `METERED_TURN_URLS`: comma-separated TURN URLs copied from the Metered dashboard, preferably the approved Saudi region.
- `METERED_TURN_USERNAME` and `METERED_TURN_PASSWORD`: a newly rotated, short-lived or periodically rotated TURN credential.

Do not add secrets to Git, browser code, Android source, or public `NEXT_PUBLIC_*` variables. The previously exposed TURN credential must be revoked and replaced before testing. The backend only returns TURN URLs and the current scoped username/password to a peer that already has a valid room token.

## Endpoints

- `POST /api/room`: validates Telegram Mini App `initData`, checks the configured owner ID, creates a 10-minute room, issues role-specific random peer tokens, and sends the Android token to the configured private Telegram chat.
- `POST /api/signal`: authenticated, role-derived signaling relay. Message types are allowlisted and payloads are limited to 48 KiB.
- `GET /api/poll`: authenticated bounded queue drain for the caller's role. Clients should poll at a modest interval; do not run multiple pollers for the same peer token.
- `GET /api/ice`: returns the server-configured ICE server list only to a valid room peer.

Redis is shared state; no Vercel function-instance memory is used for room or signaling state. WebRTC media must flow peer-to-peer or through TURN, never through Vercel.

## Telegram bot configuration

Configure the bot's Mini App URL in BotFather to point to the deployed HTTPS URL. The bot must be able to send a private message to the configured chat. The Android worker must be updated to recognize the one-time `/vsa_join <token>` message before it can consume the Android invitation.

## Current limitations

- This folder does not yet implement the browser SDP/ICE state machine or audio DataChannel worklet.
- Android does not yet consume `/vsa_join` or create a WebRTC `PeerConnection`.
- The current Android `CallBridge` still connects cellular to SIP; WebRTC has not been inserted into that bridge.
- TURN credentials are supplied as Vercel environment variables and need an operational rotation procedure; they are not generated at call time.
- Deploying this folder is not authorized or performed by this commit.
