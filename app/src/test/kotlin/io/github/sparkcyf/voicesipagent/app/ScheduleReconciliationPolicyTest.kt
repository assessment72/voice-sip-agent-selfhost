package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleReconciliationPolicyTest {
    @Test fun onlyQuarantinedSchedulesRequireReview() {
        assertTrue(ScheduleReconciliationPolicy.requiresReview("needs_reconciliation"))
        assertFalse(ScheduleReconciliationPolicy.requiresReview("pending"))
        assertFalse(ScheduleReconciliationPolicy.requiresReview("completed"))
    }

    @Test fun completedAndFailedRequireManagerAndEvidence() {
        val completed = ScheduleReconciliationPolicy.resolve(
            "needs_reconciliation",
            ScheduleReconciliationPolicy.Decision(
                ScheduleReconciliationPolicy.Action.MARK_COMPLETED, "manager-1", "Matched provider receipt"
            )
        )
        assertEquals("completed", completed.status)
        assertEquals("schedule.reconciliation.completed", completed.auditAction)

        val failed = ScheduleReconciliationPolicy.resolve(
            "needs_reconciliation",
            ScheduleReconciliationPolicy.Decision(
                ScheduleReconciliationPolicy.Action.MARK_FAILED, "manager-1", "Carrier log confirms rejection"
            )
        )
        assertEquals("failed", failed.status)
    }

    @Test fun retryRequiresExplicitConfirmationAndEvidence() {
        val unconfirmed = runCatching {
            ScheduleReconciliationPolicy.resolve(
                "needs_reconciliation",
                ScheduleReconciliationPolicy.Decision(
                    ScheduleReconciliationPolicy.Action.RETRY, "manager-1", "Reviewed call history"
                )
            )
        }
        assertTrue(unconfirmed.isFailure)

        val confirmed = ScheduleReconciliationPolicy.resolve(
            "needs_reconciliation",
            ScheduleReconciliationPolicy.Decision(
                ScheduleReconciliationPolicy.Action.RETRY, "manager-1", "No call found in handset history",
                retryConfirmed = true
            )
        )
        assertEquals("pending", confirmed.status)
        assertEquals("schedule.reconciliation.retry_authorized", confirmed.auditAction)
    }

    @Test fun cannotResolveOtherStatesOrWithoutAuditContext() {
        assertTrue(runCatching {
            ScheduleReconciliationPolicy.resolve(
                "running",
                ScheduleReconciliationPolicy.Decision(
                    ScheduleReconciliationPolicy.Action.MARK_FAILED, "manager-1", "checked"
                )
            )
        }.isFailure)
        assertTrue(runCatching {
            ScheduleReconciliationPolicy.resolve(
                "needs_reconciliation",
                ScheduleReconciliationPolicy.Decision(
                    ScheduleReconciliationPolicy.Action.MARK_FAILED, "", "checked"
                )
            )
        }.isFailure)
        assertTrue(runCatching {
            ScheduleReconciliationPolicy.resolve(
                "needs_reconciliation",
                ScheduleReconciliationPolicy.Decision(
                    ScheduleReconciliationPolicy.Action.MARK_FAILED, "manager-1", ""
                )
            )
        }.isFailure)
    }
}
