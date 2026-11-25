package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiPcmAudioTest {
    @Test
    fun `preserves samples across arbitrary websocket chunk boundaries`() {
        val source = ShortArray(1384) { (it * 13 - 9000).toShort() }
        val stream = source.flatMap { sample ->
            listOf(sample.toInt() and 0xff, (sample.toInt() ushr 8) and 0xff)
        }.map(Int::toByte).toByteArray()
        val converter = GeminiPcmOutputBuffer(frameSamples = 160)
        val emitted = mutableListOf<ShortArray>()
        var offset = 0
        val chunkSizes = listOf(2768, 17, 149, 1001, 25, 333)
        var chunk = 0
        while (offset < stream.size) {
            val end = minOf(stream.size, offset + chunkSizes[chunk++ % chunkSizes.size])
            emitted += converter.append(stream.copyOfRange(offset, end))
            offset = end
        }

        assertEquals((source.size / 3) / 160, emitted.size)
        assertTrue(emitted.all { it.size == 160 })
        // At most two source samples and fewer than one 8 kHz frame remain buffered.
        val expectedOutputSamples = source.size / 3
        assertEquals(expectedOutputSamples / 160, emitted.size)
    }

    @Test
    fun `joins a PCM sample split between websocket messages`() {
        val converter = GeminiPcmOutputBuffer(frameSamples = 1)
        val first = converter.append(byteArrayOf(0x00))
        assertTrue(first.isEmpty())
        val second = converter.append(byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x03, 0x00))
        assertEquals(1, second.size)
        assertEquals(512, second.single()[0].toInt())
    }
}
