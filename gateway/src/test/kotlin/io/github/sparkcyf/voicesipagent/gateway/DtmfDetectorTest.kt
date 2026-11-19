package io.github.sparkcyf.voicesipagent.gateway

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtmfDetectorTest {
    @Test fun detectsOneAndTwoAfterStableToneAndRearmsAfterRelease() {
        val detector = DtmfDetector()
        val one = toneFrame(697.0, 1209.0)
        assertNull(detector.process(one))
        assertNull(detector.process(one))
        assertEquals('1', detector.process(one))
        repeat(4) { assertNull(detector.process(one)) }
        repeat(2) { assertNull(detector.process(ShortArray(160))) }
        val two = toneFrame(697.0, 1336.0)
        assertNull(detector.process(two))
        assertNull(detector.process(two))
        assertEquals('2', detector.process(two))
    }

    @Test fun silenceDoesNotCreateDigits() {
        val detector = DtmfDetector()
        repeat(10) { assertNull(detector.process(ShortArray(160))) }
    }

    @Test fun ivrMediaPortPlaysPromptAndCollectsCallerDigit() {
        val received = mutableListOf<Char>()
        var finished = false
        val port = IvrMediaPort(ShortArray(160) { 321 }, received::add, { finished = true })
        assertEquals(321.toShort(), port.readFrame().first())
        assertEquals(true, finished)
        val one = toneFrame(697.0, 1209.0)
        repeat(3) { port.writeFrame(one) }
        assertEquals(listOf('1'), received)
    }

    private fun toneFrame(low: Double, high: Double): ShortArray = ShortArray(160) { i ->
        val sample = 4000.0 * sin(2.0 * PI * low * i / 8000.0) +
            4000.0 * sin(2.0 * PI * high * i / 8000.0)
        sample.roundToInt().toShort()
    }
}
