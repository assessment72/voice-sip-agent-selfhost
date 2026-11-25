package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.Party
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiEndpointTest {
    @Test fun disabledEndpointDoesNotCreateEngineOrAnswerCall() {
        var created = false
        val endpoint = LocalAiEndpoint(LocalSpeechEngineFactory { created = true; FakeEngine() }, { false })
        val leg = endpoint.dial("assistant", Party("sip:caller@example.test"))

        assertFalse(created)
        assertEquals(LegState.ENDED, leg.state)
        assertNull(leg.media)
    }

    @Test fun readyEngineCreatesConnectedIsolatedAiLeg() {
        val engine = FakeEngine()
        val endpoint = LocalAiEndpoint(LocalSpeechEngineFactory { engine }, { true })
        val leg = endpoint.dial("assistant", Party("sip:caller@example.test"))

        assertEquals(LegState.CONNECTED, leg.state)
        assertNotNull(leg.media)
        assertEquals("local-ai", leg.kind)
        leg.hangup()
        assertTrue(engine.closed)
        assertEquals(LegState.ENDED, leg.state)
    }

    @Test fun unavailableEngineFailsWithoutExposingMedia() {
        val engine = FakeEngine().apply { isReady = false }
        val endpoint = LocalAiEndpoint(LocalSpeechEngineFactory { engine }, { true })
        val leg = endpoint.dial("assistant", Party("sip:caller@example.test"))

        assertEquals(LegState.ENDED, leg.state)
        assertNull(leg.media)
        assertTrue(engine.closed)
    }

    private class FakeEngine : LocalSpeechEngine {
        override var isReady = true
        var closed = false
        override fun offerCallerFrame(frame: ShortArray) = !closed
        override fun pollAssistantFrame(): ShortArray? = null
        override fun close() { closed = true; isReady = false }
    }
}
