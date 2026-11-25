package io.github.sparkcyf.voicesipagent.app

/**
 * Converts Gemini's mono PCM16LE 24 kHz stream to fixed-size 8 kHz telephony frames.
 * WebSocket audio chunks are not frame-aligned, so both source and output remainders
 * must survive across calls to [append].
 */
internal class GeminiPcmOutputBuffer(private val frameSamples: Int) {
    init { require(frameSamples > 0) }

    private var sourceRemainder = ShortArray(0)
    private var outputRemainder = ShortArray(0)
    private var oddByte: Int? = null

    @Synchronized
    fun append(pcm24kLe: ByteArray): List<ShortArray> {
        if (pcm24kLe.isEmpty()) return emptyList()

        val samples = ShortArray((pcm24kLe.size + (if (oddByte == null) 0 else 1)) / 2)
        var sampleIndex = 0
        var byteIndex = 0
        oddByte?.let { first ->
            if (pcm24kLe.isNotEmpty()) {
                samples[sampleIndex++] = ((pcm24kLe[0].toInt() shl 8) or (first and 0xff)).toShort()
                byteIndex = 1
            }
            oddByte = null
        }
        while (byteIndex + 1 < pcm24kLe.size) {
            val lo = pcm24kLe[byteIndex].toInt() and 0xff
            val hi = pcm24kLe[byteIndex + 1].toInt()
            samples[sampleIndex++] = ((hi shl 8) or lo).toShort()
            byteIndex += 2
        }
        if (byteIndex < pcm24kLe.size) oddByte = pcm24kLe[byteIndex].toInt() and 0xff

        val source = if (sourceRemainder.isEmpty()) samples.copyOf(sampleIndex)
            else sourceRemainder + samples.copyOf(sampleIndex)
        val groups = source.size / 3
        sourceRemainder = source.copyOfRange(groups * 3, source.size)
        if (groups == 0) return emptyList()

        // A short 1-2-1 low-pass before decimation reduces aliasing in the 8 kHz voice band.
        val converted = ShortArray(groups) { i ->
            val p = i * 3
            ((source[p].toInt() + 2 * source[p + 1].toInt() + source[p + 2].toInt()) / 4).toShort()
        }
        val pending = if (outputRemainder.isEmpty()) converted else outputRemainder + converted
        val frameCount = pending.size / frameSamples
        val frames = ArrayList<ShortArray>(frameCount)
        repeat(frameCount) { frame ->
            val start = frame * frameSamples
            frames += pending.copyOfRange(start, start + frameSamples)
        }
        outputRemainder = pending.copyOfRange(frameCount * frameSamples, pending.size)
        return frames
    }
}
