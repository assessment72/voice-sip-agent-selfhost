# Incoming-call AI answering — implementation plan

## Goal

Add an opt-in AI receptionist for inbound SIP calls and, later, inbound SIM calls routed through the existing SIP↔cellular bridge. Preserve normal SIP and SIM gateway behavior when AI answering is disabled.

The selected direction is a cloud Realtime voice model because the project decision prioritizes natural, low-latency speech quality over minimizing API cost. The Android app must not contain the provider API key. The new `voice-ai-server` relay keeps that key server-side.

## Existing verified integration points

- `:sip` receives authenticated SIP INVITEs and creates inbound `CallLeg` instances.
- `CallLeg` exposes state, answer/hangup, DTMF and a PCM `MediaPort`.
- `:gateway` uses `CallBridge` and `MediaBridge` to connect call legs.
- SIP RTP is decoded to PCM16 mono narrowband frames (8 kHz, 20 ms) before reaching the media bridge.
- `CallBridge` currently auto-answers the inbound leg when its outbound leg connects. This is the seam where an AI destination can be introduced without replacing SIP signalling or RTP.
- The existing SIM path remains owned by the Android Telecom/Shizuku code. It must not be conflated with the SIP RTP path.

## Target call flow

1. SIP INVITE passes existing Digest authentication and inbound-call validation.
2. A routing policy checks whether AI auto-answer is enabled for this account/route.
3. If enabled, the bridge connects the SIP leg to one isolated AI call leg; otherwise the current route is used unchanged.
4. The media adapter converts 8 kHz PCM frames to the Realtime service's negotiated audio format off the RTP clock thread, using bounded queues and back-pressure.
5. The AI session streams caller audio upstream and assistant audio downstream. Output is converted back to 8 kHz PCM for G.711/RTP.
6. On caller hangup, timeout, network loss, or explicit stop, both legs and the Realtime session close; no session survives the call.
7. The caller is told clearly that an automated assistant is answering.

## Safety and reliability requirements

- AI mode is opt-in and SIP-only until SIM call-audio routing is separately proven on the target HONOR device.
- Existing SIP Digest authentication remains mandatory in server mode; no open SIP relay.
- Server API key is stored only in server secrets. Android stores only a revocable gateway credential, encrypted with Android Keystore.
- WSS/TLS only; authenticate every connection; bound sessions, frame sizes, queues and concurrent calls.
- Never log raw audio, transcripts, API keys, SIP authorization headers, or full caller numbers.
- Do not execute external tools, payments, account changes, call transfers, or other side effects from spoken instructions without a separate explicit authorization policy.
- Provide a deterministic fallback: ring/forward to the configured human destination or end with a safe unavailable response if the AI service is unavailable. Never leave a call connected silently.
- Barge-in must stop queued assistant audio immediately when caller speech is detected.
- Do not record calls by default.

## Work staged in this branch

- Added an authenticated WebSocket relay scaffold under `voice-ai-server/`.
- The relay keeps the OpenAI API key server-side, requires a bearer gateway token, applies connection/session/payload limits, and does not log audio or transcript content.
- Added this design document to keep the SIP and SIM call paths separate.

## Remaining before a real AI answer is possible

1. Implement the Android Realtime WebSocket client with Keystore-backed token storage and reconnect/timeout handling.
2. Implement a tested 8 kHz ↔ provider audio-rate streaming adapter with bounded lock-free or non-blocking frame queues.
3. Implement a dedicated AI `CallEndpoint` / `CallLeg` and wire an explicit inbound routing policy into `GatewayRuntime`.
4. Add call-specific instructions, Arabic language/voice configuration, opening disclosure, barge-in and safe fallback.
5. Add tests for state transitions, format conversion, underruns, disconnects, replayed sessions, concurrent calls and cleanup.
6. Run CI and build the APK; then validate on the HONOR MTN-NX1 with a controlled SIP test call.
7. Only after SIP is stable, separately investigate SIM inbound audio routing. Existing Shizuku audio capture must be tested on the actual device; no support claim is made yet.

## Not done

The relay scaffold is not a working AI receptionist by itself. The Android client, audio adapter, call routing, server deployment, API key provisioning, APK install and real-call validation have not been performed. No call has been placed or answered by this change.
