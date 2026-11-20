package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.voice.CallerIdMode
import io.github.sparkcyf.voicesipagent.voice.Party
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SipCallerIdPolicyTest {
    private val caller = Party("+967712345678", "Caller")

    @Test fun normalModePreservesCallerIdentity() {
        val from = SipCallerIdPolicy.from(caller, "gateway", CallerIdMode.NORMAL)
        assertEquals("Caller", from.displayName)
        assertEquals("sip:+967712345678@anonymous.invalid", from.uri)
        assertEquals("<sip:+967712345678@example.test>",
            SipCallerIdPolicy.assertedIdentity(caller, "example.test", CallerIdMode.NORMAL))
        assertNull(SipCallerIdPolicy.privacyHeader(CallerIdMode.NORMAL))
    }

    @Test fun privacyModesUseAnonymousFromAndSuppressAssertedIdentity() {
        listOf(CallerIdMode.PRIVATE, CallerIdMode.UNKNOWN, CallerIdMode.HIDDEN).forEach { mode ->
            val from = SipCallerIdPolicy.from(caller, "gateway", mode)
            assertEquals(mode.sipDisplayName, from.displayName)
            assertEquals("sip:anonymous@anonymous.invalid", from.uri)
            assertNull(SipCallerIdPolicy.assertedIdentity(caller, "example.test", mode))
            assertEquals("id;user", SipCallerIdPolicy.privacyHeader(mode))
        }
    }
}
