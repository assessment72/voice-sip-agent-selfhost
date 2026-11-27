package io.github.sparkcyf.voicesipagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicxDataServicesPolicyTest {
    @Test fun partialPatchPreservesUnchangedCustomerAndScheduleFields() {
        val original = JSONObject()
            .put("phone", "+967730501035")
            .put("runAt", 1_800_000_000_000L)
            .put("status", "pending")
            .put("attempts", 0)

        val patched = LocalDocumentPatch.merge(original, mapOf("status" to "running", "attempts" to 1))

        assertEquals("+967730501035", patched.getString("phone"))
        assertEquals(1_800_000_000_000L, patched.getLong("runAt"))
        assertEquals("running", patched.getString("status"))
        assertEquals(1, patched.getInt("attempts"))
    }

    @Test fun nullPatchRemovesOnlyTheRequestedField() {
        val original = JSONObject().put("status", "running").put("claimedAt", 123L).put("phone", "+967730501035")

        val patched = LocalDocumentPatch.merge(original, mapOf("claimedAt" to null))

        assertFalse(patched.has("claimedAt"))
        assertEquals("running", patched.getString("status"))
        assertEquals("+967730501035", patched.getString("phone"))
    }

    @Test fun scheduleLeaseExpiresOnlyForOldRunningClaims() {
        val now = 2_000_000_000_000L
        assertTrue(ScheduleLeasePolicy.isExpired("running", now - ScheduleLeasePolicy.CLAIM_LEASE_MS - 1L, now))
        assertFalse(ScheduleLeasePolicy.isExpired("running", now - ScheduleLeasePolicy.CLAIM_LEASE_MS + 1L, now))
        assertFalse(ScheduleLeasePolicy.isExpired("completed", now - ScheduleLeasePolicy.CLAIM_LEASE_MS - 1L, now))
        assertFalse(ScheduleLeasePolicy.isExpired("running", now - 60_000L, now))
        assertFalse(ScheduleLeasePolicy.isExpired("running", 0L, now))
    }

    @Test fun expiredExternalSideEffectClaimsRequireReconciliationInsteadOfAutomaticRetry() {
        assertEquals("needs_reconciliation", ScheduleLeasePolicy.recoveryStatus())
        assertTrue(ScheduleLeasePolicy.EXPIRED_CLAIM_ERROR.contains("outcome is unknown"))
        assertTrue(ScheduleLeasePolicy.EXPIRED_CLAIM_ERROR.contains("before retrying"))
    }

    @Test fun globalOptOutBlocksEveryCampaignButInterestedAndPausedAreScoped() {
        assertTrue(CustomerSuppressionPolicy.isBlocked("queued", true))
        assertTrue(CustomerSuppressionPolicy.isBlocked("opted_out", false))
        assertTrue(CustomerSuppressionPolicy.isBlocked("admin", false))
        assertTrue(CustomerSuppressionPolicy.isBlocked("paused", false))
        assertFalse(CustomerSuppressionPolicy.isBlocked("queued", false))
        assertFalse(CustomerSuppressionPolicy.isBlocked("follow_up", false))
    }

    @Test fun yemeniArabicAndInternationalMobileNumbersNormalizeToSameE164() {
        assertEquals("+967730501035", PhoneNumberNormalizer.normalizeYemen("730501035"))
        assertEquals("+967730501035", PhoneNumberNormalizer.normalizeYemen("٠٧٣٠٥٠١٠٣٥"))
        assertEquals("+967730501035", PhoneNumberNormalizer.normalizeYemen("00967730501035"))
        assertEquals("+967730501035", PhoneNumberNormalizer.normalizeYemen("+967 730 501 035"))
    }
    @Test fun quoteRequestIsRoutedToManagementOnlyFromCustomerSpeech() {
        assertEquals(SalesCallDisposition.QUOTE_REQUEST,
            SalesCallDispositionPolicy.classify("نعم أريد عرض سعر لتوريد الديزل", io.github.sparkcyf.voicesipagent.voice.HangupCause.NORMAL))
        assertEquals(SalesCallDisposition.REMINDER,
            SalesCallDispositionPolicy.classify("نعم تفضل، أرسل التفاصيل", io.github.sparkcyf.voicesipagent.voice.HangupCause.NORMAL))
    }

    @Test fun busyOrClosedCustomerGetsThreeHourRetryDisposition() {
        assertEquals(SalesCallDisposition.BUSY_OR_UNAVAILABLE,
            SalesCallDispositionPolicy.classify("أنا مشغول اتصل بعد ثلاث ساعات", io.github.sparkcyf.voicesipagent.voice.HangupCause.NORMAL))
        assertEquals(SalesCallDisposition.BUSY_OR_UNAVAILABLE,
            SalesCallDispositionPolicy.classify("", io.github.sparkcyf.voicesipagent.voice.HangupCause.NO_ANSWER))
        assertEquals(SalesCallDisposition.BUSY_OR_UNAVAILABLE,
            SalesCallDispositionPolicy.classify("الهاتف مغلق", io.github.sparkcyf.voicesipagent.voice.HangupCause.NORMAL))
    }

    @Test fun optOutAlwaysTakesPriorityOverFollowUp() {
        assertEquals(SalesCallDisposition.OPTED_OUT,
            SalesCallDispositionPolicy.classify("لا تتصل بي مرة أخرى", io.github.sparkcyf.voicesipagent.voice.HangupCause.NORMAL))
    }

    @Test fun normalCompletedCallGetsOneWeekReminderDisposition() {
        assertEquals(SalesCallDisposition.REMINDER,
            SalesCallDispositionPolicy.classify("شكراً لك، سأراجع العرض", io.github.sparkcyf.voicesipagent.voice.HangupCause.NORMAL))
        assertEquals(SalesCallDisposition.NO_FOLLOW_UP,
            SalesCallDispositionPolicy.classify("", io.github.sparkcyf.voicesipagent.voice.HangupCause.NETWORK_ERROR))
    }

}
