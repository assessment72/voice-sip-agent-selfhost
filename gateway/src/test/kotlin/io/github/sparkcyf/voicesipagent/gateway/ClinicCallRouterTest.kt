package io.github.sparkcyf.voicesipagent.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class ClinicCallRouterTest {
    private val primary = CallCenterEmployee(
        id = "primary",
        displayName = "Primary",
        destination = EmployeeDestination.Sip("sip:primary@example.invalid"),
        priority = 1,
    )
    private val mobile = CallCenterEmployee(
        id = "mobile",
        displayName = "Mobile",
        destination = EmployeeDestination.Mobile("+12025550123"),
        priority = 2,
    )
    private val router = ClinicCallRouter(CallCenterPolicy(listOf(mobile, primary)))

    @Test
    fun zeroSelectsNextEligibleEmployeeAndSkipsAttemptedEmployee() {
        assertEquals(
            RouteDecision.Connect("primary", primary.destination),
            router.route('0'),
        )
        assertEquals(
            RouteDecision.Connect("mobile", mobile.destination),
            router.route('0', attemptedEmployeeIds = setOf("primary")),
        )
    }

    @Test
    fun oneAndTwoSelectEmployeesByPriorityOrder() {
        assertEquals(
            RouteDecision.Connect("primary", primary.destination),
            router.route('1'),
        )
        assertEquals(
            RouteDecision.Connect("mobile", mobile.destination),
            router.route('2'),
        )
    }

    @Test
    fun unavailableSlotReturnsNoAvailableEmployee() {
        assertEquals(RouteDecision.NoAvailableEmployee, router.route('0', setOf("primary", "mobile")))
        assertEquals(RouteDecision.NoAvailableEmployee, router.route('2'))
    }

    @Test
    fun unsupportedDigitDoesNotProduceDialingDestination() {
        assertEquals(RouteDecision.UnsupportedDigit('9'), router.route('9'))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankAttemptedEmployeeIdIsRejected() {
        router.route('0', attemptedEmployeeIds = setOf(" "))
    }
}
