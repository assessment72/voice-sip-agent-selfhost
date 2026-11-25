package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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
        val secondBytes = byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x03, 0x00)
        val splitResult = converter.append(secondBytes).single()[0]
        val wholeResult = GeminiPcmOutputBuffer(frameSamples = 1)
            .append(byteArrayOf(0x00) + secondBytes).single()[0]
        assertEquals(wholeResult, splitResult)
    }

    @Test
    fun `fir preserves speech band and attenuates frequencies that alias into it`() {
        val passBand = filteredSineRms(1_000)
        val stopBand = filteredSineRms(7_000)
        assertTrue("speech-band RMS was too low: $passBand", passBand > 5_000.0)
        assertTrue("alias attenuation was too weak: pass=$passBand stop=$stopBand", stopBand < passBand * 0.08)
    }

    private fun filteredSineRms(frequencyHz: Int): Double {
        val input = ByteArray(24_000 * 2)
        for (i in 0 until 24_000) {
            val sample = (12_000 * sin(2.0 * PI * frequencyHz * i / 24_000.0)).toInt()
            input[2 * i] = sample.toByte()
            input[2 * i + 1] = (sample shr 8).toByte()
        }
        val samples = GeminiPcmOutputBuffer(frameSamples = 160).append(input).flatMap { it.asList() }
        val steadyState = samples.drop(20)
        return sqrt(steadyState.map { it.toDouble().pow(2) }.average())
    }
}
