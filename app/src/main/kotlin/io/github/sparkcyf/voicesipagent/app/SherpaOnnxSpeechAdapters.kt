package io.github.sparkcyf.voicesipagent.app

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import io.github.sparkcyf.voicesipagent.gateway.OfflineSpeechRecognizer
import io.github.sparkcyf.voicesipagent.gateway.OfflineSpeechSynthesizer
import java.io.File
import kotlin.math.roundToInt

/**
 * Concrete sherpa-onnx Whisper adapter.
 *
 * The caller supplies verified model files from LocalModelStore. No assets are downloaded,
 * no network client is used, and inference runs on QueuedLocalSpeechEngine's worker thread.
 */
class SherpaWhisperRecognizer(
    encoder: File,
    decoder: File,
    tokens: File,
    language: String = "ar",
    numThreads: Int = 2,
) : OfflineSpeechRecognizer {
    private val recognizer: OfflineRecognizer

    init {
        requireModel(encoder, "Whisper encoder")
        requireModel(decoder, "Whisper decoder")
        requireModel(tokens, "Whisper tokens")
        require(language.isNotBlank())
        require(numThreads in 1..4)
        val model = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = encoder.absolutePath,
                decoder = decoder.absolutePath,
                language = language,
                task = "transcribe",
            ),
            tokens = tokens.absolutePath,
            modelType = "whisper",
            numThreads = numThreads,
            provider = "cpu",
        )
        recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = MODEL_SAMPLE_RATE, featureDim = 80),
                modelConfig = model,
                decodingMethod = "greedy_search",
            )
        )
    }

    override fun transcribe(pcm16: ShortArray): String? {
        if (pcm16.isEmpty()) return null
        val input = PcmAudio.toFloat16k(pcm16)
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(input, MODEL_SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim().takeIf { it.isNotEmpty() }
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}

/**
 * Concrete sherpa-onnx TTS adapter. Supply a model-specific OfflineTtsConfig whose paths
 * point to verified local files. Output is normalized to 8 kHz PCM16/20 ms frames for SIP.
 */
class SherpaOfflineTtsSynthesizer(
    config: OfflineTtsConfig,
    private val outputSampleRate: Int = SIP_SAMPLE_RATE,
    private val maxOutputSeconds: Int = 45,
) : OfflineSpeechSynthesizer {
    private val tts = OfflineTts(config = config)

    init {
        require(outputSampleRate == SIP_SAMPLE_RATE || outputSampleRate == 16000)
        require(maxOutputSeconds in 1..120)
        require(tts.sampleRate() > 0)
    }

    override fun synthesize(text: String): List<ShortArray> {
        val normalized = text.trim()
        if (normalized.isEmpty()) return emptyList()
        val generated = tts.generate(normalized)
        require(generated.sampleRate > 0) { "TTS returned an invalid sample rate" }
        val maxSamples = generated.sampleRate.toLong() * maxOutputSeconds
        require(generated.samples.size.toLong() <= maxSamples) {
            "TTS output exceeded configured duration limit"
        }
        val pcm = PcmAudio.floatToPcm16(
            PcmAudio.resample(generated.samples, generated.sampleRate, outputSampleRate)
        )
        if (pcm.isEmpty()) return emptyList()
        val frameSamples = outputSampleRate / 50
        return pcm.asList().chunked(frameSamples).map { chunk ->
            ShortArray(frameSamples).also { frame ->
                chunk.forEachIndexed { index, sample -> frame[index] = sample }
            }
        }
    }

    override fun close() = tts.release()
}

private object PcmAudio {
    fun toFloat16k(input: ShortArray): FloatArray {
        val mono = FloatArray(input.size) { input[it] / 32768.0f }
        return resample(mono, 8000, MODEL_SAMPLE_RATE)
    }

    fun resample(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        require(sourceRate > 0 && targetRate > 0)
        if (input.isEmpty()) return FloatArray(0)
        if (sourceRate == targetRate) return input.copyOf()
        val outputSize = ((input.size.toLong() * targetRate) / sourceRate)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return FloatArray(outputSize) { index ->
            val sourcePosition = index.toDouble() * sourceRate / targetRate
            val left = sourcePosition.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = (sourcePosition - left).toFloat().coerceIn(0f, 1f)
            input[left] * (1f - fraction) + input[right] * fraction
        }
    }

    fun floatToPcm16(input: FloatArray): ShortArray =
        ShortArray(input.size) { index ->
            (input[index].coerceIn(-1f, 1f) * 32767f).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
}

private fun requireModel(file: File, label: String) {
    require(file.isFile && file.canRead()) { "$label is missing or unreadable: ${file.name}" }
}

private const val SIP_SAMPLE_RATE = 8000
private const val MODEL_SAMPLE_RATE = 16000
