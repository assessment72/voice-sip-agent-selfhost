package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomerSmsSequencePolicyTest {
    @Test fun secondStageChecksRepliesReceivedSinceTheFirstMessage() {
        assertEquals(1_000L, CustomerSmsSequencePolicy.replyWindowStart(2, 1_000L, 0L))
    }

    @Test fun thirdStageChecksRepliesReceivedSinceTheSecondMessage() {
        assertEquals(2_000L, CustomerSmsSequencePolicy.replyWindowStart(3, 1_000L, 2_000L))
    }

    @Test fun eachFollowUpIsAtLeastSevenDaysAfterThePreviousSend() {
        val sentAt = 2_000_000_000_000L
        val earliest = sentAt + CustomerSmsSequencePolicy.FOLLOW_UP_DELAY_MS
        assertEquals(earliest, CustomerSmsSequencePolicy.nextStageAt(2, sentAt, sentAt))
        assertEquals(earliest + 60_000L, CustomerSmsSequencePolicy.nextStageAt(3, earliest + 60_000L, sentAt))
    }

    @Test fun configuredFollowUpDateCanBeLaterThanTheMinimumInterval() {
        val sentAt = 2_000_000_000_000L
        val configuredAt = sentAt + CustomerSmsSequencePolicy.FOLLOW_UP_DELAY_MS + 86_400_000L
        assertEquals(configuredAt, CustomerSmsSequencePolicy.nextStageAt(2, configuredAt, sentAt))
    }
}
