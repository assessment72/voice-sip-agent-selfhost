package io.github.sparkcyf.voicesipagent.app

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.sparkcyf.voicesipagent.gateway.LocalSpeechSegmenter
import java.io.File
import java.util.ArrayDeque
import kotlin.math.roundToInt

/**
 * Neural Silero VAD adapter. SIP input is 8 kHz PCM16; sherpa VAD receives 16 kHz float audio.
 * Speech segments are returned as 8 kHz PCM16 so the existing Whisper adapter can resample
 * them to its configured 16 kHz feature rate.
 */
class SherpaSileroVadSegmenter(
    model: File,
    private val threshold: Float = 0.5f,
    private val maxSpeechSeconds: Float = 5.0f,
) : LocalSpeechSegmenter {
    override val frameSamples: Int = 160
    private val completed = ArrayDeque<ShortArray>()
    private val vad: Vad

    init {
        require(model.isFile && model.canRead()) { "Silero VAD model is missing or unreadable" }
        require(threshold in 0.1f..0.95f)
        require(maxSpeechSeconds in 1f..30f)
        vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = model.absolutePath,
                    threshold = threshold,
                    minSilenceDuration = 0.30f,
                    minSpeechDuration = 0.15f,
                    windowSize = 512,
                    maxSpeechDuration = maxSpeechSeconds,
                ),
                sampleRate = VAD_SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            )
        )
    }

    override fun accept(frame: ShortArray): ShortArray? {
        require(frame.size == frameSamples) { "Expected one 20 ms 8 kHz SIP frame" }
        val float8k = FloatArray(frame.size) { frame[it] / 32768.0f }
        vad.acceptWaveform(resample(float8k, SIP_SAMPLE_RATE, VAD_SAMPLE_RATE))
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val at8k = resample(segment.samples, VAD_SAMPLE_RATE, SIP_SAMPLE_RATE)
            if (at8k.isNotEmpty()) {
                completed.addLast(ShortArray(at8k.size) { index ->
                    (at8k[index].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                })
            }
        }
        return completed.pollFirst()
    }

    override fun reset() {
        completed.clear()
        vad.reset()
    }

    fun flush() {
        vad.flush()
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val at8k = resample(segment.samples, VAD_SAMPLE_RATE, SIP_SAMPLE_RATE)
            if (at8k.isNotEmpty()) {
                completed.addLast(ShortArray(at8k.size) { index ->
                    (at8k[index].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                })
            }
        }
    }

    fun release() {
        reset()
        vad.release()
    }

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        val size = (input.size.toLong() * to / from).coerceAtLeast(1L).toInt()
        return FloatArray(size) { index ->
            val pos = index.toDouble() * from / to
            val left = pos.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = (pos - left).toFloat().coerceIn(0f, 1f)
            input[left] * (1f - fraction) + input[right] * fraction
        }
    }

    private companion object {
        const val SIP_SAMPLE_RATE = 8000
        const val VAD_SAMPLE_RATE = 16000
    }
}
