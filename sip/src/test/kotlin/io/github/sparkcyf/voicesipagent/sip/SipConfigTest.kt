package io.github.sparkcyf.voicesipagent.sip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SipConfigTest {
    private fun config(maxCalls: Int = 1) = SipConfig(
        account = SipAccount(server = "127.0.0.1", username = "test", password = "test"),
        maxConcurrentCalls = maxCalls,
    )

    @Test
    fun defaultsToOneSimultaneousCall() {
        val cfg = config()
        assertTrue(cfg.hasCallCapacity(0))
        assertFalse(cfg.hasCallCapacity(1))
    }

    @Test
    fun configuredCapacityIsApplied() {
        val cfg = config(maxCalls = 2)
        assertTrue(cfg.hasCallCapacity(1))
        assertFalse(cfg.hasCallCapacity(2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidCapacity() {
        config(maxCalls = 0)
    }
}
