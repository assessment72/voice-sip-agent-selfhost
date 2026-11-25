package io.github.sparkcyf.voicesipagent.gateway

import kotlin.math.sqrt

/**
 * Conservative per-direction speech level control for 16-bit PCM.
 *
 * A slow, bounded AGC lifts quiet speech without changing the call clock or ports.
 * A frame peak limiter prevents clipping after the configured user gain is applied.
 * This is deliberately not an acoustic echo canceller: echo cancellation belongs to
 * the telephony/audio endpoint and must be validated against its actual reference path.
 */
internal class PcmSpeechProcessor(
    private val targetRms: Double = 6_500.0,
    private val maximumAutoGain: Float = 2.0f,
    private val minimumAutoGain: Float = 0.85f,
    private val limiterPeak: Double = 30_000.0,
) {
    private var autoGain = 1.0f

    init {
        require(targetRms > 0.0)
        require(maximumAutoGain >= 1.0f)
        require(minimumAutoGain in 0.0f..1.0f)
        require(limiterPeak in 1.0..Short.MAX_VALUE.toDouble())
    }

    /** Returns a processed copy; the input frame is never modified. */
    fun process(frame: ShortArray, configuredGain: Float = 1.0f): ShortArray {
        if (frame.isEmpty()) return frame
        val rms = sqrt(frame.sumOf { sample -> sample.toDouble() * sample.toDouble() } / frame.size)
        val desiredAutoGain = if (rms >= MIN_SPEECH_RMS) {
            (targetRms / rms).toFloat().coerceIn(minimumAutoGain, maximumAutoGain)
        } else {
            1.0f
        }

        // Fast enough to control loudness, slow enough to avoid audible pumping.
        val smoothing = if (desiredAutoGain < autoGain) ATTACK else RELEASE
        autoGain += (desiredAutoGain - autoGain) * smoothing

        val userGain = if (configuredGain.isFinite()) configuredGain.coerceIn(0.0f, 2.0f) else 1.0f
        val totalGain = userGain * autoGain
        if (totalGain == 1.0f) return frame

        var peakAfterGain = 0.0
        for (sample in frame) {
            peakAfterGain = maxOf(peakAfterGain, kotlin.math.abs(sample.toDouble() * totalGain))
        }
        val limiter = if (peakAfterGain > limiterPeak) limiterPeak / peakAfterGain else 1.0
        val effectiveGain = totalGain * limiter

        return ShortArray(frame.size) { index ->
            (frame[index].toDouble() * effectiveGain)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    companion object {
        private const val MIN_SPEECH_RMS = 350.0
        private const val ATTACK = 0.35f
        private const val RELEASE = 0.08f
    }
}
