package io.github.sparkcyf.voicesipagent.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSpeechProcessorTest {
    @Test
    fun quietSpeechIsLiftedGraduallyWithoutMutatingInput() {
        val processor = PcmSpeechProcessor()
        val input = ShortArray(160) { 1_200 }
        var output = input
        repeat(40) { output = processor.process(input) }

        assertNotSame(input, output)
        assertTrue(output.average().toInt() > 1_700)
        assertEquals(1_200, input[0].toInt())
    }

    @Test
    fun loudFramesAreLimitedAfterConfiguredAmplification() {
        val processor = PcmSpeechProcessor()
        val input = ShortArray(160) { 20_000 }
        var output = input
        repeat(40) { output = processor.process(input, configuredGain = 2.0f) }

        assertTrue(output.maxOf { kotlin.math.abs(it.toInt()) } <= 30_000)
    }

    @Test
    fun silenceAndVeryLowLevelNoiseAreNotAutomaticallyAmplified() {
        val processor = PcmSpeechProcessor()
        val silence = ShortArray(160)
        val noise = ShortArray(160) { 100 }

        assertTrue(processor.process(silence).all { it == 0.toShort() })
        assertEquals(100, processor.process(noise)[0].toInt())
    }

    @Test
    fun zeroConfiguredGainProducesSilence() {
        val processor = PcmSpeechProcessor()
        val input = ShortArray(160) { 2_000 }

        assertTrue(processor.process(input, configuredGain = 0f).all { it == 0.toShort() })
    }
}
