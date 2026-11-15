# Local AI Voice Agent — Android / SIP only

## Product boundary

The AI agent runs on the Android handset and talks only to the built-in SIP endpoint. It does not
use CallAgent.pro, OpenAI, cloud inference, a remote gateway, cellular-call takeover, or Telegram.
The existing SIP signalling, RTP, jitter buffer, G.711 codecs and call lifecycle remain authoritative.

## Audio path

- SIP RTP receives G.711 PCMU/PCMA and decodes it to mono PCM16, 8 kHz, 20 ms frames.
- MediaBridge writes caller frames to LocalAiMediaPort.
- The local engine currently performs endpointed VAD -> offline ASR -> local dialogue model -> offline TTS (turn-based; not streaming).
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
- Added a turn-based pipeline contract (energy VAD endpointing -> offline ASR -> offline dialogue ->
  offline TTS) and tests. The energy VAD is only a bootstrap endpoint detector; production should
  replace it with a neural VAD adapter. Stage implementations are interfaces, not bundled models.
- The pipeline is turn-based, not yet barge-in/streaming dialogue. It currently waits for endpointing,
  then performs ASR, dialogue and TTS on the dedicated worker.
- A concrete JNI/native engine, model packaging/downloader, neural VAD/ASR/LLM/TTS runtimes,
  UI controls, and live SIP handset validation are not implemented yet.
- Do not mark the feature production-ready until native runtime tests and an on-device SIP loop test
  pass on the HONOR Android 16 handset.


## sherpa-onnx Android runtime integration

The app now pins the official sherpa-onnx Android AAR through JitPack:
`com.github.k2-fsa:sherpa-onnx:2.0.1`. The release provides a 47.2 MB AAR;
the app's existing ABI filter packages only `arm64-v8a`. This is a build-time dependency
fetch only. The inference adapters do not make network requests at runtime.

Concrete adapters added:
- `SherpaSileroVadSegmenter`: Silero neural VAD, receiving SIP 8 kHz PCM16 and
  converting frames to 16 kHz float for sherpa-onnx.
- `SherpaWhisperRecognizer`: local Whisper encoder/decoder/tokens files, configured for
  Arabic by default, running CPU inference on the queued worker.
- `SherpaOfflineTtsSynthesizer`: model-configurable sherpa offline TTS, resampling generated
  audio to 8 kHz PCM16 and 20 ms frames for the SIP media port.

The model files are not bundled. Import each file through `LocalModelStore`, verify its
SHA-256, then pass the private installed file handles into the adapter constructors.
Whisper requires encoder, decoder and tokens files. TTS requires a model-specific
`OfflineTtsConfig` whose referenced files have all been imported.

Still not implemented:
- A local LLM/dialogue adapter. `OfflineDialogueModel` remains an injection point; no cloud
  or network dialogue fallback is present.
- A model catalogue/manifest for Arabic ASR/TTS, model license checks, and automatic
  validation of multi-file model bundles.
- UI/service wiring to instantiate these adapters and select SIP-AI mode.
- Instrumented device tests and performance/thermal/RAM profiling on HONOR MTN-NX1.
- Streaming partial transcripts, interruption/barge-in, and full-duplex conversational TTS.

Do not start a SIP-AI call until the user imports a compatible model bundle, the app validates
all model files, and on-device tests pass. The existing SIP/cellular bridge remains unchanged.
