# Telegram voice-message fallback

## Current implementation

This feature is a **recorded voice-message fallback**, not a live call bridge.

- Configure a Telegram bot token and the numeric ID of one private chat in the app settings.
- The token is encrypted at rest using an Android Keystore AES-GCM key.
- While the gateway foreground service is running, the app long-polls Telegram updates.
- Only `message.voice` updates from the configured private chat are accepted.
- Received OGG voice files are downloaded with a 20 MiB limit to the app-private directory `files/telegram/inbox`.
- The Settings screen can refresh the inbox list and play downloaded voice files.
- Update offsets are persisted only after accepted voice files have been downloaded.
- The existing SIP, cellular audio, and Shizuku paths are not modified by this fallback.

## Setup

1. Create a bot with Telegram's official BotFather and keep its token private.
2. Open a private chat with the bot and send `/start`.
3. Obtain the numeric private chat ID using a trusted bot-update inspection method. Do not use a group chat ID.
4. In Voice SIP Agent settings, enter the bot token and private chat ID.
5. Enable **Telegram voice-message fallback**, then save settings.
6. Keep the gateway foreground service running. Send a voice message from that private chat.
7. Open Settings, refresh received voice messages, and tap Play.

The bot token is never required in source code, build configuration, or a public issue. Do not paste it into logs or commit it.

## Current limitations

- This does not place or answer Telegram calls.
- The Bot API voice-message path is asynchronous and recorded; it is not real-time bidirectional audio.
- Sending a locally recorded message from the app UI is not yet wired.
- Inbox files are private to the app and are removed if the app is uninstalled or its data is cleared.
- Delivery and playback still require an end-to-end test on the target device.

## Live-call work still required

A Telegram Mini App live-call path needs a separate WebRTC media endpoint and a signaling service over HTTPS/WSS. The Android SIP registrar currently speaks SIP/UDP; it cannot be used directly as a WebRTC signaling endpoint. ICE/STUN connectivity must be tested, and TURN relay is required when direct connectivity fails. The Mini App must be served from a trusted HTTPS origin, and bot credentials must remain on a trusted backend—not in Mini App JavaScript.

The WebRTC work must be added as a separate endpoint alongside the existing cellular and SIP endpoints. It must not replace the current Shizuku audio capture/playback pipeline. No live-call capability should be claimed until two-way audio is verified on the target HONOR device and a second client.
