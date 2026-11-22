package io.github.sparkcyf.voicesipagent.sip

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SipTrustedUpstreamTest {
    @Test fun acceptsAnyCurrentAddressOfTheConfiguredHaProxy() {
        val first = InetAddress.getByName("203.0.113.10")
        val second = InetAddress.getByName("203.0.113.11")
        val unrelated = InetAddress.getByName("198.51.100.20")
        val allowed = setOf(first, second)

        assertTrue(isTrustedUpstream(first, allowed))
        assertTrue(isTrustedUpstream(second, allowed))
        assertFalse(isTrustedUpstream(unrelated, allowed))
    }
}
