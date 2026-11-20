package io.github.sparkcyf.voicesipagent.sip

import org.junit.Assert.assertEquals
import org.junit.Test

class SipPeerRegistrationStatusTest {
    @Test
    fun matchingLocalPeerIsRegisteredOnlyBeforeGrantedExpiry() {
        assertEquals(true, localPeerRegistrationStatus(true, "doctor", "pbx.local", "pbx.local", "doctor", 20_000, true, 19_999))
        assertEquals(false, localPeerRegistrationStatus(true, "doctor", "pbx.local", "pbx.local", "doctor", 20_000, true, 20_000))
    }

    @Test
    fun unknownDomainsAndExternalRegistrarStayUnknown() {
        assertEquals(null, localPeerRegistrationStatus(false, "doctor", "pbx.local", "pbx.local", "doctor", 20_000, true, 1))
        assertEquals(null, localPeerRegistrationStatus(true, "doctor", "elsewhere.invalid", "pbx.local", "doctor", 20_000, true, 1))
    }

    @Test
    fun absentOrDifferentLocalPeerIsOffline() {
        assertEquals(false, localPeerRegistrationStatus(true, "doctor", "pbx.local", "pbx.local", null, 20_000, false, 1))
        assertEquals(false, localPeerRegistrationStatus(true, "doctor", "pbx.local", "pbx.local", "reception", 20_000, true, 1))
        assertEquals(false, localPeerRegistrationStatus(true, "doctor", "pbx.local", "pbx.local", "doctor", 20_000, false, 1))
    }
}
