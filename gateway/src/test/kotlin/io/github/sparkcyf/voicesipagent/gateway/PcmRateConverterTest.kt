package io.github.sparkcyf.voicesipagent.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRateConverterTest {
    @Test fun upsampleConvertsTwentyMillisecondNarrowbandFrameToWideband() {
        val converter = PcmRateConverter(8000, 16000)
        val input = ShortArray(160) { (it * 10).toShort() }
        val output = converter.convert(input)
        assertEquals(320, output.size)
        assertEquals(input.last(), output.last())
    }

    @Test fun downsampleConvertsWidebandFrameToNarrowband() {
        val converter = PcmRateConverter(16000, 8000)
        val input = ShortArray(320) { 200 }
        val output = converter.convert(input)
        assertEquals(160, output.size)
        assertTrue(output.all { it.toInt() == 200 })
    }

    @Test fun sameRateReturnsAnIndependentCopy() {
        val converter = PcmRateConverter(8000, 8000)
        val input = ShortArray(160) { 55 }
        val output = converter.convert(input)
        input.fill(0)
        assertEquals(55, output[0].toInt())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedFrameLength() {
        PcmRateConverter(8000, 16000).convert(ShortArray(80))
    }
}
