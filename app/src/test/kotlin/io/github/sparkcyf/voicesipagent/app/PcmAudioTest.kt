package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmAudioTest {
    @Test
    fun splitsExactFramesWithoutChangingSamples() {
        val input = shortArrayOf(1, 2, 3, 4, 5, 6)
        val frames = PcmAudio.toFixedFrames(input, 3)

        assertEquals(2, frames.size)
        assertArrayEquals(shortArrayOf(1, 2, 3), frames[0])
        assertArrayEquals(shortArrayOf(4, 5, 6), frames[1])
    }

    @Test
    fun padsOnlyFinalPartialFrameWithSilence() {
        val input = shortArrayOf(7, 8, 9, 10, 11)
        val frames = PcmAudio.toFixedFrames(input, 4)

        assertEquals(2, frames.size)
        assertArrayEquals(shortArrayOf(7, 8, 9, 10), frames[0])
        assertArrayEquals(shortArrayOf(11, 0, 0, 0), frames[1])
    }

    @Test
    fun emptyPcmProducesNoFrames() {
        assertEquals(0, PcmAudio.toFixedFrames(shortArrayOf(), 160).size)
    }

    @Test
    fun rejectsNonPositiveFrameSize() {
        assertThrows(IllegalArgumentException::class.java) {
            PcmAudio.toFixedFrames(shortArrayOf(1), 0)
        }
    }
}
