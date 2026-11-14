# Telegram Mini App → WebRTC → SIP/SIM bridge

Status: architecture plan only. This document does not claim that live calls are implemented.

## Goal

Allow the authenticated owner to start a live audio session from a Telegram Mini App. The browser peer connects to the Android gateway over WebRTC; the Android gateway routes the remote audio to the existing cellular/SIP call bridge. The existing Shizuku capture/playback path remains the cellular audio boundary.

The Telegram Bot API is used only for private call invitations and recorded-message fallback. It does not carry live call media.

## Components

1. **Telegram Mini App (HTTPS)** — obtains `Telegram.WebApp.initData`; sends the raw value to the backend for verification; requests a short-lived call room; opens the WebSocket signaling channel; establishes the WebRTC peer connection.
2. **Vercel signaling/API** — validates Telegram init data on the server, enforces the owner allowlist, creates short-lived room invitations, relays SDP/ICE signaling, and returns ICE server configuration. WebRTC media must never pass through Vercel.
3. **Shared signaling state** — use Redis Streams with a Vercel WebSocket function. A socket is pinned to one function instance, so Redis fan-out is required for peers that land on different instances. A free Upstash Redis database is suitable for an initial low-volume prototype, subject to its published quotas.
4. **Metered TURN** — keep the account secret on the backend. Use a credential-scoped API key or a pre-created credential; return only the ICE server array to the authenticated peers. Create/rotate credentials ahead of calls because new credentials may take up to two minutes to propagate. Prefer the Middle East region when the Metered account exposes it.
5. **Android WebRTC endpoint** — implement the existing `CallEndpoint` abstraction and expose a `MediaPort` compatible with the current bridge. The WebRTC endpoint is an additional endpoint; it must not replace SIP or the Shizuku cellular audio implementation.

## Audio bridge decision

The current bridge exchanges fixed-size 8 kHz, mono, signed 16-bit PCM frames on a 20 ms cadence (160 samples / 320 bytes per frame). Android's standard WebRTC Java audio device module is backed by Android `AudioRecord` and `AudioTrack`; its sample callback is diagnostic and is not a supported PCM injection API. Therefore, the first interoperability prototype should evaluate a WebRTC DataChannel carrying sequenced PCM frames, rather than pretending that a normal microphone AudioTrack is already connected to the cellular `MediaPort`.

Proposed frame format:

- 32-bit monotonically increasing sequence number (network byte order).
- 160 signed 16-bit mono PCM samples (little-endian).
- One frame every 20 ms.
- Unordered DataChannel with no retransmissions; stale voice frames should be dropped, not retransmitted.
- A bounded jitter buffer and silence insertion on underrun.
- Browser AudioWorklet performs resampling between the browser audio device rate and 8 kHz.

This is a prototype transport, not a final audio-quality claim. It requires measurements for latency, packet loss, echo, clipping, and CPU/battery usage. If the DataChannel PCM path is inadequate, evaluate a native custom WebRTC AudioDeviceModule rather than changing the Shizuku audio path.

## Invitation and authorization

- Verify Telegram `initData` on the server using the bot token; reject expired or invalid data and enforce the configured owner Telegram user ID.
- Generate separate, cryptographically random, short-lived web-peer and Android-peer invitation tokens.
- Send the Android token only to the configured private Telegram chat as a one-time bot message; the Android worker consumes that invitation and connects to the matching room.
- Store only token hashes in Redis; apply a short TTL and one active connection per role.
- Do not put bot tokens, Metered secret keys, or Redis write credentials in browser JavaScript, Android source, or Git.
- Limit signaling message types and sizes; authorize every message against the room and peer role.

## Signaling protocol

The initial protocol is limited to `offer`, `answer`, `ice-candidate`, `ringing`, `accepted`, `hangup`, and `error`. Each message carries a room ID, sender role, and bounded payload. Redis Streams provide cross-instance relay; the local WebSocket instance delivers to its own connected peer immediately and relays to other instances through the stream.

## Rollout order

1. Keep the recorded Telegram voice fallback working and CI green.
2. Add the Vercel Mini App/API: Telegram init-data validation, room invitation, Redis-backed WSS signaling, and Metered ICE configuration.
3. Add Android invitation consumption and the WebRTC `CallEndpoint` / PCM `MediaPort`.
4. Route browser-originated calls to the existing cellular endpoint without changing the SIP path. Restore the SIP bridge after the WebRTC session ends.
5. Test with two peers on different networks, then test both audio directions on the target HONOR Android 16 device.
6. Only mark live calling ready after real two-way audio, TURN relay, reconnect, hangup, and failure-path tests pass.

## Acceptance criteria

- Invalid/expired Telegram init data and wrong Telegram users are rejected.
- Room tokens are role-scoped, single-use, short-lived, and never logged.
- Signaling works when the peers connect to different Vercel function instances.
- ICE succeeds peer-to-peer and through the configured TURN relay.
- Browser → Android → cellular and cellular → Android → browser audio are both audible and intelligible.
- SIP calls and the existing Shizuku audio mode remain intact outside an active WebRTC session.
- No call media is sent through the signaling server.
