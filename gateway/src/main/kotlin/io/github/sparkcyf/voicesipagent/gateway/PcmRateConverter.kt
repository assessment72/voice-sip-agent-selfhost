package io.github.sparkcyf.voicesipagent.gateway

/**
 * Stateful narrowband/wideband PCM16 converter for the inference worker.
 *
 * Supports 8 kHz <-> 16 kHz only. It is intentionally not used from MediaPort callbacks;
 * callers must invoke it on the local inference/TTS worker thread.
 */
class PcmRateConverter(
    private val inputRate: Int,
    private val outputRate: Int,
) {
    init {
        require(inputRate in setOf(8000, 16000) && outputRate in setOf(8000, 16000))
    }

    private var previousSample: Short? = null

    fun convert(frame: ShortArray): ShortArray {
        val expectedInput = inputRate / 50
        require(frame.size == expectedInput) { "Expected $expectedInput samples per 20 ms frame" }
        if (inputRate == outputRate) return frame.copyOf()

        return if (inputRate == 8000) {
            val output = ShortArray(frame.size * 2)
            var previous = previousSample ?: frame.first()
            for (i in frame.indices) {
                val current = frame[i].toInt()
                output[2 * i] = ((previous.toInt() + current) / 2).toShort()
                output[2 * i + 1] = frame[i]
                previous = frame[i]
            }
            previousSample = previous
            output
        } else {
            val output = ShortArray(frame.size / 2)
            for (i in output.indices) {
                val a = frame[2 * i].toInt()
                val b = frame[2 * i + 1].toInt()
                output[i] = ((a + b) / 2).toShort()
            }
            output
        }
    }

    fun reset() {
        previousSample = null
    }
}
