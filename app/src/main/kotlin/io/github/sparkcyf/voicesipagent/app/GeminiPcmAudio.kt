package io.github.sparkcyf.voicesipagent.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Converts Gemini's mono PCM16LE 24 kHz stream to fixed-size 8 kHz telephony frames.
 * WebSocket audio chunks are not frame-aligned, so byte, decimation, filter-history, and
 * output-frame state are retained across calls to [append]. A short linear-phase FIR filter
 * limits aliasing before downsampling; its group delay is about 1.3 ms.
 */
internal class GeminiPcmOutputBuffer(private val frameSamples: Int) {
    init { require(frameSamples > 0) }

    private var outputRemainder = ShortArray(0)
    private var oddByte: Int? = null
    private val history = ShortArray(FILTER_TAPS.size)
    private var historyIndex = 0
    private var decimationPhase = 0

    @Synchronized
    fun reset() {
        outputRemainder = ShortArray(0)
        oddByte = null
        history.fill(0)
        historyIndex = 0
        decimationPhase = 0
    }

    @Synchronized
    fun append(pcm24kLe: ByteArray): List<ShortArray> {
        if (pcm24kLe.isEmpty()) return emptyList()

        val samples = ShortArray((pcm24kLe.size + (if (oddByte == null) 0 else 1)) / 2)
        var sampleIndex = 0
        var byteIndex = 0
        oddByte?.let { first ->
            samples[sampleIndex++] = ((pcm24kLe[0].toInt() shl 8) or (first and 0xff)).toShort()
            byteIndex = 1
            oddByte = null
        }
        while (byteIndex + 1 < pcm24kLe.size) {
            val lo = pcm24kLe[byteIndex].toInt() and 0xff
            val hi = pcm24kLe[byteIndex + 1].toInt()
            samples[sampleIndex++] = ((hi shl 8) or lo).toShort()
            byteIndex += 2
        }
        if (byteIndex < pcm24kLe.size) oddByte = pcm24kLe[byteIndex].toInt() and 0xff

        val converted = ArrayList<Short>((sampleIndex + 2) / 3)
        for (i in 0 until sampleIndex) {
            history[historyIndex] = samples[i]
            if (decimationPhase == 2) {
                var filtered = 0.0
                for (tap in FILTER_TAPS.indices) {
                    val sampleAt = (historyIndex - tap + history.size) % history.size
                    filtered += FILTER_TAPS[tap] * history[sampleAt].toInt()
                }
                converted += filtered.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            historyIndex = (historyIndex + 1) % history.size
            decimationPhase = (decimationPhase + 1) % 3
        }
        if (converted.isEmpty()) return emptyList()

        val convertedArray = converted.toShortArray()
        val pending = if (outputRemainder.isEmpty()) convertedArray else outputRemainder + convertedArray
        val frameCount = pending.size / frameSamples
        val frames = ArrayList<ShortArray>(frameCount)
        repeat(frameCount) { frame ->
            val start = frame * frameSamples
            frames += pending.copyOfRange(start, start + frameSamples)
        }
        outputRemainder = pending.copyOfRange(frameCount * frameSamples, pending.size)
        return frames
    }

    companion object {
        // 63-tap Blackman-windowed low-pass, 3.6 kHz cutoff at 24 kHz sample rate.
        // About 1.3 ms group delay; suppresses frequencies that would alias into the 8 kHz call band.
        private val FILTER_TAPS = lowPassTaps()
        private const val TAP_COUNT = 63
        private const val INPUT_RATE_HZ = 24_000.0
        private const val CUTOFF_HZ = 3_600.0

        private fun lowPassTaps(): DoubleArray {
            val middle = (TAP_COUNT - 1) / 2
            val normalizedCutoff = CUTOFF_HZ / INPUT_RATE_HZ
            val taps = DoubleArray(TAP_COUNT) { i ->
                val offset = i - middle
                val ideal = if (offset == 0) 2.0 * normalizedCutoff
                    else sin(2.0 * PI * normalizedCutoff * offset) / (PI * offset)
                val phase = 2.0 * PI * i / (TAP_COUNT - 1)
                val blackman = 0.42 - 0.5 * cos(phase) + 0.08 * cos(2.0 * phase)
                ideal * blackman
            }
            val sum = taps.sum()
            return DoubleArray(TAP_COUNT) { taps[it] / sum }
        }
    }
}
