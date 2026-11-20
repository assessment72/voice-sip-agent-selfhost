package io.github.sparkcyf.voicesipagent.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallCenterPolicyTest {
    @Test
    fun ordersEnabledEmployeesAndLimitsInitialAttempts() {
        val policy = CallCenterPolicy(listOf(
            CallCenterEmployee("z", "Backup", EmployeeDestination.Sip("sip:backup@example.invalid"), priority = 2),
            CallCenterEmployee("disabled", "Disabled", EmployeeDestination.Sip("sip:disabled@example.invalid"), priority = 0, enabled = false),
            CallCenterEmployee("a", "Primary", EmployeeDestination.Mobile("+12025550123"), priority = 1),
            CallCenterEmployee("b", "Second", EmployeeDestination.Sip("sip:second@example.invalid"), priority = 1),
        ))

        assertEquals(listOf("a", "b"), policy.nextEmployees().map { it.id })
    }

    @Test
    fun recordingRequiresAnnouncementBeforeItCanStart() {
        val recording = RecordingPolicy(enabled = true, announcementRequired = true)
        assertFalse(recording.mayStartRecording(announcementPlayed = false))
        assertTrue(recording.mayStartRecording(announcementPlayed = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun mobileDestinationRejectsNonE164Value() {
        CallCenterEmployee("x", "Bad", EmployeeDestination.Mobile("0555123456"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateEmployeeIdsAreRejected() {
        CallCenterPolicy(listOf(
            CallCenterEmployee("same", "One", EmployeeDestination.Sip("sip:one@example.invalid")),
            CallCenterEmployee("same", "Two", EmployeeDestination.Sip("sip:two@example.invalid")),
        ))
    }

    @Test
    fun fallbackOrderSkipsAttemptedAndDisabledEmployees() {
        val employees = listOf(
            CallCenterEmployee("reception", "Reception", EmployeeDestination.Sip("sip:rec@example.invalid"), extension = "2"),
            CallCenterEmployee("backup", "Backup", EmployeeDestination.Sip("sip:backup@example.invalid"), extension = "3"),
            CallCenterEmployee("disabled", "Disabled", EmployeeDestination.Sip("sip:off@example.invalid"), enabled = false, extension = "4"),
        )
        val policy = CallFallbackPolicy(listOf("4", "3", "2"), maxAttempts = 3)
        assertEquals("backup", policy.nextFallback(employees, emptySet())?.id)
        assertEquals("reception", policy.nextFallback(employees, setOf("backup"))?.id)
        assertEquals(null, policy.nextFallback(employees, setOf("backup", "reception"))?.id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fallbackPolicyRejectsUnboundedAttemptCounts() {
        CallFallbackPolicy(maxAttempts = 5)
    }
}
