package io.github.sparkcyf.voicesipagent.privileged.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioReadRecoveryTest {
    @Test
    fun `transient zero reads are retried and do not stop the active call`() {
        val policy = AudioReadRecovery()
        assertEquals(AudioReadRecovery.Decision.RETRY_EMPTY, policy.classify(0, true))
        assertEquals(1, policy.consecutiveEmptyReads)
        assertEquals(AudioReadRecovery.Decision.RETRY_EMPTY, policy.classify(0, true))
        assertEquals(2, policy.consecutiveEmptyReads)
        assertEquals(AudioReadRecovery.Decision.DATA, policy.classify(320, true))
        assertEquals(0, policy.consecutiveEmptyReads)
    }

    @Test
    fun `negative reads stop the audio worker and stopped owners do not retry`() {
        val policy = AudioReadRecovery()
        assertEquals(AudioReadRecovery.Decision.STOP, policy.classify(-6, true))
        assertEquals(AudioReadRecovery.Decision.STOPPED, policy.classify(0, false))
    }
}
