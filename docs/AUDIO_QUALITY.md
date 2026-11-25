# Voicx SIP audio quality and release validation

## Implemented in this change

- Independent, stateful speech-level control for cellular-to-SIP and SIP-to-cellular media.
- Bounded automatic gain control: quiet speech is lifted gradually; silence and very-low-level noise are not boosted.
- A per-frame peak limiter after the user's gain setting prevents PCM clipping.
- The two directions keep independent gain state. Muting SIP-to-cellular is exposed as Mute SIM microphone; it only silences the SIP-to-cellular media direction and does not change SIP registration, Telecom call state, or the reverse audio direction.
- Existing cellular-first clocking, frame sizes, socket protocol, and call lifecycle remain unchanged.

## Echo and noise handling boundary

The bridge receives the Android telephony downlink and injects PCM into the cellular uplink. It does not own a microphone capture session with a guaranteed far-end render reference, so attaching Android's AcousticEchoCanceler or NoiseSuppressor to this downlink AudioRecord would not be a valid or reliable AEC/NS implementation.

The gateway already mutes the handset microphone while injecting SIP audio and can suppress local handset playback. Acoustic/network echo cancellation must be provided by the modem/Android telephony path or by a separately validated AEC engine with a correctly synchronized reverse-path reference. This change does not claim software AEC or broadband noise suppression.

## Required pre-production validation

1. Run JVM, Android unit, APK assembly, hosted API, and Preview checks.
2. On the target Android device, verify inbound and outbound calls independently.
3. Verify SIM-microphone mute on/off during both call directions: no hangup, no SIP re-registration, no loss of reverse audio.
4. Compare quiet speech, normal speech, loud speech, and silence at both ends; check for pumping, clipping, and background-noise lift.
5. Test with speaker muted and unmuted, handset/headset routes, and real network conditions. Record one-way latency and packet/frame loss.
6. If echo remains, capture device-specific diagnostics and integrate/test an AEC implementation that has access to the actual synchronized echo reference before enabling it by default.

Passing unit tests is not proof of better perceived call quality or production readiness; target-device and carrier tests are mandatory.
