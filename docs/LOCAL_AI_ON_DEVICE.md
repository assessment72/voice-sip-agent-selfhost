# Local AI Voice Agent — Android / SIP only

## Product boundary

The AI agent runs on the Android handset and talks only to the built-in SIP endpoint. It does not
use CallAgent.pro, OpenAI, cloud inference, a remote gateway, cellular-call takeover, or Telegram.
The existing SIP signalling, RTP, jitter buffer, G.711 codecs and call lifecycle remain authoritative.

## Audio path

- SIP RTP receives G.711 PCMU/PCMA and decodes it to mono PCM16, 8 kHz, 20 ms frames.
- MediaBridge writes caller frames to LocalAiMediaPort.
- The local engine performs VAD -> streaming ASR -> local dialogue model -> streaming TTS.
- LocalAiMediaPort.readFrame() supplies synthesized PCM16 frames back to MediaBridge.
- MediaBridge and RtpSession encode and transmit the frames using the negotiated G.711 codec.
- Missing TTS frames become silence; queue pressure is counted and must never block the RTP clock.

## Runtime components to embed

1. VAD + Arabic TTS: sherpa-onnx is the leading candidate because it supports Android ARM64, offline VAD/TTS and Kotlin/Java APIs. Evaluate the Nabra-82M Arabic TTS model (including INT8/INT4 artifacts) for intelligibility and real-time factor on this handset.
2. ASR: whisper.cpp with a multilingual model, starting with tiny/base quantized variants; evaluate Arabic recognition on 8 kHz telephony audio.
3. Dialogue: llama.cpp with a compact instruct model quantized for ARM64. Keep a strict context and token budget; the dialogue engine must be streamable and interruptible.
4. Audio adapter: 8 kHz PCM16 narrowband boundary. Any model requiring 16 kHz/24 kHz must use an explicit streaming resampler in the engine worker, never inside the RTP callback.

Candidate references: sherpa-onnx Android/offline runtime (https://github.com/k2-fsa/sherpa-onnx), Nabra Arabic TTS model announcement (https://github.com/k2-fsa/sherpa-onnx/releases), whisper.cpp Android example (https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android), and llama.cpp Android build guide (https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md). These are candidates, not yet vendored or compiled into this app.

## Device constraints

- Target ABI: arm64-v8a (the app currently filters to this ABI).
- Android minSdk 30 / targetSdk 36; device is HONOR MTN-NX1, Android 16.
- Keep inference in a foreground service while a SIP AI call is active; use bounded worker queues,
  a partial wake lock only during an active call, and release all native contexts on hangup.
- Do not assume Shizuku grants access to arbitrary audio devices. This feature consumes SIP RTP PCM,
  not Android cellular downlink audio, so Shizuku is not a prerequisite for SIP-only AI.
- Benchmark memory, sustained CPU, thermal throttling and battery on the actual handset before
  choosing quantization or claiming concurrent-call support. Initial target is one AI SIP call.

## Safety and privacy

- All model files are downloaded/imported by the user before going offline and verified by SHA-256.
- No network calls are made by the inference layer. SIP networking remains separately configurable.
- Do not store raw audio or transcripts by default. Keep only opt-in, redacted diagnostics.
- Clearly disclose that the caller is speaking with an AI assistant.
- AI mode is opt-in and defaults off. Existing SIP-to-SIP bridge behavior remains unchanged.
- Never execute arbitrary commands or external actions based only on spoken instructions.

## Implementation status

- Added LocalAiMediaPort as a non-blocking PCM boundary and unit tests for frame copying,
  silence-on-underrun, engine rejection accounting and lifecycle.
- A concrete JNI/native engine, model packaging/downloader, VAD/ASR/LLM/TTS runtimes, UI controls,
  and live SIP handset validation are not implemented yet.
- Do not mark the feature production-ready until native runtime tests and an on-device SIP loop test
  pass on the HONOR Android 16 handset.
