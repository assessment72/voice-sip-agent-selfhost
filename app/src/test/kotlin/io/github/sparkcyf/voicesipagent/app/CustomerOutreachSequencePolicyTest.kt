package io.github.sparkcyf.voicesipagent.app

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerOutreachSequencePolicyTest {
    @Test
    fun `start requires future time valid interval and at least one consented channel`() {
        val now = 1_000_000L
        assertTrue(CustomerOutreachSequencePolicy.isValidStart(now + 1, now, 3, 7, true, false))
        assertTrue(CustomerOutreachSequencePolicy.isValidStart(now + 1, now, 4, 4, false, true))
        assertFalse(CustomerOutreachSequencePolicy.isValidStart(now, now, 3, 7, true, true))
        assertFalse(CustomerOutreachSequencePolicy.isValidStart(now + 1, now, 2, 7, true, false))
        assertFalse(CustomerOutreachSequencePolicy.isValidStart(now + 1, now, 3, 8, true, false))
        assertFalse(CustomerOutreachSequencePolicy.isValidStart(now + 1, now, 6, 5, true, false))
        assertFalse(CustomerOutreachSequencePolicy.isValidStart(now + 1, now, 3, 7, false, false))
    }

    @Test
    fun `random interval is inclusive and next run time is measured from completion`() {
        val random = Random(19)
        val selected = (0 until 100).map {
            CustomerOutreachSequencePolicy.randomDelayDays(3, 7, random)
        }
        assertTrue(selected.all { it in 3..7 })
        assertEquals(4, CustomerOutreachSequencePolicy.randomDelayDays(4, 4, Random(1)))
        assertEquals(20_000L + 5 * CustomerOutreachSequencePolicy.DAY_MS,
            CustomerOutreachSequencePolicy.nextRunAt(20_000L, 5))
    }

    @Test
    fun `running claim is stale only after thirty minutes`() {
        val now = 5_000_000L
        assertFalse(CustomerOutreachSequencePolicy.isStaleRunning(now, now))
        assertFalse(CustomerOutreachSequencePolicy.isStaleRunning(now - CustomerOutreachSequencePolicy.CLAIM_TIMEOUT_MS + 1, now))
        assertTrue(CustomerOutreachSequencePolicy.isStaleRunning(now - CustomerOutreachSequencePolicy.CLAIM_TIMEOUT_MS, now))
        assertFalse(CustomerOutreachSequencePolicy.isStaleRunning(0L, now))
    }
}
