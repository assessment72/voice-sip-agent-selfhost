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
        assertFalse(ScheduleLeasePolicy.isExpired("running", 0L, now))
    }
}
