package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.sip.media.RtpSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException

class RtpSessionTest {
    @Test
    fun bindSocketUsesEvenPortInsideRequestedRange() {
        RtpSession.bindSocket(null, 12000..12006).use { socket ->
            assertTrue(socket.localPort in 12000..12006)
            assertEquals(0, socket.localPort % 2)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun bindSocketRejectsInvalidRange() {
        RtpSession.bindSocket(null, 20000..10000)
    }

    @Test(expected = SocketException::class)
    fun bindSocketReportsExhaustedRange() {
        RtpSession.bindSocket(null, 1..1)
    }
}
***
