package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.FrameClockListener
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import kotlin.math.cos
import kotlin.math.sqrt

/** Clockless prompt source that also decodes in-band caller DTMF from the cellular downlink. */
class IvrMediaPort(
    private val prompt: ShortArray,
    private val onDigit: (Char) -> Unit,
    private val onPromptFinished: () -> Unit,
    override val format: PcmFormat = PcmFormat.NARROWBAND,
) : MediaPort {
    init {
        require(prompt.isNotEmpty()) { "IVR prompt must not be empty" }
        require(format == PcmFormat.NARROWBAND) { "IVR prompt currently supports 8 kHz PCM only" }
    }

    override val isClockSource: Boolean = false
    private val detector = DtmfDetector(format.sampleRate)
    private var cursor = 0
    private var completionSent = false
    @Volatile var framesProduced: Long = 0
        private set

    override fun setClockListener(listener: FrameClockListener?) = Unit

    override fun readFrame(): ShortArray {
        val frame = format.silence()
        if (cursor >= prompt.size) {
            notifyFinished()
            return frame
        }
        val count = minOf(frame.size, prompt.size - cursor)
        prompt.copyInto(frame, 0, cursor, cursor + count)
        cursor += count
        framesProduced++
        if (cursor >= prompt.size) notifyFinished()
        return frame
    }

    override fun writeFrame(frame: ShortArray) {
        detector.process(frame)?.let(onDigit)
    }

    private fun notifyFinished() {
        if (completionSent) return
        completionSent = true
        onPromptFinished()
    }
}

/**
 * Goertzel DTMF detector for 20 ms PCM frames. A digit is reported once after three stable frames,
 * then re-armed only after a short tone-free interval to avoid duplicate routing.
 */
class DtmfDetector(private val sampleRate: Int = 8000) {
    init { require(sampleRate in setOf(8000, 16000)) }

    private val rows = doubleArrayOf(697.0, 770.0, 852.0, 941.0)
    private val columns = doubleArrayOf(1209.0, 1336.0, 1477.0, 1633.0)
    private val digits = arrayOf(
        charArrayOf('1', '2', '3', 'A'),
        charArrayOf('4', '5', '6', 'B'),
        charArrayOf('7', '8', '9', 'C'),
        charArrayOf('*', '0', '#', 'D'),
    )
    private var candidate: Char? = null
    private var candidateFrames = 0
    private var latched = false
    private var quietFrames = 0

    @Synchronized fun process(frame: ShortArray): Char? {
        require(frame.isNotEmpty())
        val tone = detect(frame)
        if (tone == null) {
            candidate = null
            candidateFrames = 0
            quietFrames++
            if (quietFrames >= 2) latched = false
            return null
        }
        quietFrames = 0
        if (latched) return null
        if (candidate == tone) candidateFrames++ else {
            candidate = tone
            candidateFrames = 1
        }
        if (candidateFrames < 3) return null
        latched = true
        candidate = null
        candidateFrames = 0
        return tone
    }

    private fun detect(frame: ShortArray): Char? {
        var energy = 0.0
        for (sample in frame) {
            val x = sample.toDouble()
            energy += x * x
        }
        if (sqrt(energy / frame.size) < 250.0) return null
        val rowPowers = rows.map { goertzel(frame, it) }
        val colPowers = columns.map { goertzel(frame, it) }
        val rowIndex = rowPowers.indices.maxByOrNull { rowPowers[it] } ?: return null
        val colIndex = colPowers.indices.maxByOrNull { colPowers[it] } ?: return null
        val row = rowPowers[rowIndex]
        val col = colPowers[colIndex]
        val rowSecond = rowPowers.indices.filter { it != rowIndex }.maxOf { rowPowers[it] }
        val colSecond = colPowers.indices.filter { it != colIndex }.maxOf { colPowers[it] }
        if (row < energy * 0.008 || col < energy * 0.008) return null
        if (row < rowSecond * 2.0 || col < colSecond * 2.0) return null
        val twist = row / col
        if (twist !in 0.1..10.0) return null
        return digits[rowIndex][colIndex]
    }

    private fun goertzel(frame: ShortArray, frequency: Double): Double {
        val coefficient = 2.0 * cos(2.0 * Math.PI * frequency / sampleRate)
        var q1 = 0.0
        var q2 = 0.0
        for (sample in frame) {
            val q0 = coefficient * q1 - q2 + sample
            q2 = q1
            q1 = q0
        }
        return q1 * q1 + q2 * q2 - coefficient * q1 * q2
    }
}
