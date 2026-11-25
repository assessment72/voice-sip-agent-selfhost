# Gemini Live + SIP-SIM Android: Technical Investigation & Bug-Fix Report

**Repository:** `assessment72/voice-sip-agent-selfhost`  
**Branch:** `feature/telegram-simcall-balance-ussd`  
**Scope:** Android app, Gemini Live WebSocket, SIP-SIM audio bridge, Telegram control, Shizuku and Telecom lifecycle.  
**Production status:** No merge or deployment performed.

## Executive summary

The investigation identified two confirmed Gemini media/reporting defects and one redundant turn-finalization path. Fixes were committed on the feature branch. Telegram shutdown now interrupts the blocking long-poll before coroutine cancellation. The audio path retains transient zero-byte read tolerance and snapshots route information before releasing AudioTrack. Audio diagnostics now report dBFS and directional frame-count delta.

The following items still require runtime evidence from the target HONOR device and a live SIM call: the exact Telegram API error status, persistent audio HAL zero reads, actual routed device when `routedDevice` is null, and the cause of the observed 139-frame directional gap. These cannot be proved by JVM tests alone.

## Findings and changes

| # | Observation | Root cause / evidence | File and function | Change / status |
|---|---|---|---|---|
| 1 | `record read 0 at 8000 Hz` | Android/OEM AudioRecord can transiently return zero during telephony route handover. Zero is not an error code; negative values are errors. The current tap retries zero reads with a short delay and only tears down on a negative read or another fatal condition. A device trace is needed to distinguish a brief handover from a permanently stalled HAL. | `privileged/.../CallAudioTap.kt` — `downLoop()` | Retry behavior retained. Device-level soak/regression test still required; no call teardown is triggered for a transient zero read. |
| 2 | Telegram polling `IllegalStateException` | In `TelegramBotClient.request()`, this exception is deliberately thrown for non-2xx HTTP responses and Telegram responses with `ok=false`. A common Telegram cause is HTTP 409 (another getUpdates poller or an active webhook), but the bare class name does not identify which status occurred. | `app/.../TelegramBotClient.kt` — `request()`, `poll()`; `GatewayService.kt` — `syncTelegramControl()`, `stopGateway()` | Poll cancellation now disconnects the active HttpURLConnection before cancelling its coroutine, including service shutdown. Poll failures include the safe HTTP/API detail in logs; intentional shutdown disconnects are not logged as outages. Exact server-side cause remains conditional on the newly detailed log. |
| 3 | Gemini audio stream end queued twice | Gemini setup enables server `automaticActivityDetection`; the app also sent a manual `audioStreamEnd` after its local silence detector. These are two independent end-of-activity mechanisms and can race to finalize the same utterance. The client had only one explicit call site; the duplicate-finalization risk was the overlapping local/server mechanisms. | `GeminiAiEndpoint.kt` — `AiPort.writeFrame()`; `GeminiLiveClient.kt` — setup config | Removed the redundant client `audioStreamEnd` send. Local VAD still timestamps caller speech end for metrics; Gemini server VAD is authoritative for turn finalization. |
| 4 | First Gemini audio latency missing / incorrect | `GeminiLiveClient.handle()` invoked `onTurnComplete` before iterating the audio parts in the same `serverContent` message. The endpoint closed/reset turn metrics before the final audio callback could record the response. | `app/.../GeminiLiveClient.kt` — `handle()`; `GeminiAiEndpoint.kt` — `AiPort.addOutput()`, `completeTurn()` | Audio parts are now delivered before `onTurnComplete`. A regression test asserts audio callback ordering. First successful input timestamp is recorded separately from the last input timestamp; per-turn response latency is measured from local speech-end detection. |
| 5 | First audio queue timestamp missing | The 24 kHz-to-8 kHz converter buffers a partial final telephony frame. If a Gemini turn ends before enough samples form a complete frame, no queue insertion occurs and the queue timestamp remains null. | `app/.../GeminiPcmAudio.kt` — output buffer; `GeminiAiEndpoint.kt` — `AiPort.completeTurn()` | Added `flushPadded()` to pad and emit the final partial frame at turn completion. Regression test covers the flush and ensures it is idempotent. |
| 6 | First playback timestamp missing | Playback time is recorded when `AiPort.readFrame()` actually dequeues a frame. A partial frame that was never queued cannot be played, so its timestamp was absent. A call that ends before a queued frame is consumed should still report playback as unavailable. | `GeminiAiEndpoint.kt` — `AiPort.readFrame()`, `completeTurn()` | The final partial frame is now queued before turn metrics are finalized. Playback timestamp remains tied to actual dequeue, rather than being fabricated at receipt time. |
| 7 | Conversation turn count missing | The report previously had response-latency samples but did not consistently expose a count of completed Gemini turns. | `GeminiAiEndpoint.kt` — `AiLeg.start()`, `AiCallReport.telegramMessages()` | Completed turns are counted atomically on Gemini turn-complete callbacks and included in the report. |
| 8 | Full customer/AI transcript missing | Previous report fields retained only rolling text tails and the summary was limited to Telegram's single-message length. That was not a complete transcript. | `GeminiAiEndpoint.kt` — transcript callbacks, `appendTranscript()`, `AiCallReport.telegramMessages()`; `GatewayRuntime.kt`; `GatewayService.kt` | Conversation transcript is accumulated with a 24,000-character bound and sent as separate Telegram messages (3,500-character transcript chunks). This is bounded retention, not unlimited archival. |
| 9 | Audio counts down=4986, up=4847 | Difference = 139 frames, about 2.79% of downlink. At 20 ms/frame this is about 2.78 seconds of directional frame-count difference. Counters cover different pipeline stages and start/stop boundaries; these two totals alone cannot prove clock drift or locate dropped frames. | `privileged/.../CallAudioTap.kt` — `downLoop()`, `upLoop()`, `stats()` | Added `frameDelta` and percentage to tap diagnostics. No resampling, pacing, or gain change was made without evidence. A live test should capture per-second counters and queue/drop metrics during one uninterrupted call. |
| 10 | downPeak=15763, upPeak=28450 | Approximate peak levels are -6.36 dBFS downlink and -1.23 dBFS uplink. Neither supplied peak equals full scale, but uplink has limited headroom. Peak alone does not prove clipping or excessive RMS/loudness. | `privileged/.../CallAudioTap.kt` — `peak()`, `stats()` | dBFS values are now included in diagnostics. No automatic gain change was made because that could damage call audio. RMS and clipped-sample counts remain useful next measurements. |
| 11 | preferred=18, routed=null | 18 is Android `AudioDeviceInfo.TYPE_TELEPHONY`: the requested preferred output is the telephony device. A null `routedDevice` can mean routing is not exposed by the device/vendor track implementation; previously querying after release could also lose the route snapshot. Null alone does not prove the track was not routed. | `privileged/.../CallAudioTap.kt` — `openLegacy()`, `close()`, `stats()` | Preferred/routed device types are snapshotted before AudioTrack release, preserving the last observable route. If it remains null while active, a device-side route trace is still required. |
| 12 | Shizuku lifecycle / InCallService cleanup | Shizuku stop removes listeners, closes privileged call audio and unbinds its UserService. InCallService destruction detaches the service; CellularEndpoint clears owned calls and unregisters Call.Callback instances; CellularLeg invalidates audio and restores the mute lease. A genuine InCallService loss during an active owned SIM call intentionally tears down that call because the audio/control owner is gone. | `privileged/.../ShizukuConnector.kt` — `start()`, `stop()`, binder callbacks; `telephony/.../GatewayInCallService.kt` — `onDestroy()`; `CellularEndpoint.kt` — `detachService()`, `clearCalls()`; `CellularLeg.kt` — `stopMedia()`, `finish()` | Existing lifecycle cleanup and regression tests were reviewed. Runtime policy defers takeover/role changes while calls are active. No lifecycle rewrite was made that could leave the SIM microphone muted or leak a privileged audio handle. |

## Regression coverage

New/updated tests on the feature branch:

- `GeminiLiveClientTest`: audio parts are delivered before the turn-complete callback; turn completion without audio is still delivered.
- `GeminiPcmAudioTest`: final partial PCM frame is padded and flushed exactly once.
- `AiCallReportTest`: completed turn count is included and the transcript is split into Telegram-safe messages.
- Existing `ShizukuConnectorLifecycleTest`: binder death/reconnect, stale callback handling, and stop-before-rebind.
- Existing `GatewayRuntimeLifecycleTest`: switch-off during an active call retains resources until the call ends.
- Existing `GatewayInCallServiceTest`: mute state snapshots and restoration behavior.

Hardware-only checks are not represented as passed unit tests. They require a live SIM call on the target device, Shizuku permission, and inspection of the resulting audio diagnostics.

## Active-call safety

The Gemini fixes change callback ordering, transcript/metrics bookkeeping, and the final partial output-frame flush. The Telegram change only interrupts Telegram's blocking HTTP poll during configuration/service shutdown. No SIP endpoint, PJSIP transport, SIM call placement, active call bridge, API credential, application ID, or signing-key secret was intentionally changed.

A real device call is still required to validate the no-interruption requirement under Android route changes, Shizuku binder death, and InCallService rebinding. InCallService loss is an exceptional ownership loss and currently triggers safe teardown rather than leaving the cellular microphone muted.

## Build and signing verification

GitHub Actions is configured to run JVM tests, Android unit tests, control-bridge smoke tests, assemble the APK, sign it using the repository's existing `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` secrets, verify the APK signature, print the certificate fingerprint, inspect the package identity, and upload the signed APK artifact.

**Final pass/fail and artifact availability must be filled from the completed Actions run for the final branch commit.** A queued or in-progress run is not a successful build. No production merge or deployment is authorized by this report.
