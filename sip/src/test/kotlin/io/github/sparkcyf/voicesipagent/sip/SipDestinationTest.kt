package io.github.sparkcyf.voicesipagent.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SipDestinationTest {
    @Test
    fun acceptsInternationalAndExtensionDestinations() {
        assertEquals("+967712345678", SipDestination.normalize("+967712345678"))
        assertEquals("1001", SipDestination.normalize("1001"))
    }

    @Test
    fun rejectsSipUriInjectionAndHeaderCharacters() {
        assertNull(SipDestination.normalize("967712345678@evil.example"))
        assertNull(SipDestination.normalize("9677;transport=tcp"))
        assertNull(SipDestination.normalize("9677?x=y"))
        assertNull(SipDestination.normalize("+9677+123"))
        assertNull(SipDestination.normalize("+"))
        assertNull(SipDestination.normalize("+967ABC"))
    }

    @Test
    fun trimsOuterWhitespaceButRejectsEmbeddedWhitespace() {
        assertEquals("967712345678", SipDestination.normalize(" 967712345678 "))
        assertNull(SipDestination.normalize("9677 123456"))
    }
}
