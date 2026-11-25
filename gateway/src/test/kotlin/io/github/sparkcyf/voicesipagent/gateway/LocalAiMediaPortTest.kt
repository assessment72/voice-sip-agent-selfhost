package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiMediaPortTest {
    @Test fun callerFramesAreCopiedAndSubmittedWithoutBlockingForInference() {
        val engine = FakeLocalSpeechEngine()
        val port = LocalAiMediaPort(engine)
        val frame = ShortArray(PcmFormat.NARROWBAND.frameSamples) { 123 }
        port.writeFrame(frame)
        frame.fill(0)
        assertEquals(1, port.stats.callerFrames)
        assertEquals(123, engine.lastCallerFrame!!.first().toInt())
    }

    @Test fun missingAssistantAudioProducesSilenceAndIncrementsCounter() {
        val port = LocalAiMediaPort(FakeLocalSpeechEngine())
        assertTrue(port.readFrame().all { it.toInt() == 0 })
        assertEquals(1, port.stats.missingAssistantFrames)
    }

    @Test fun assistantFramesAreCopiedAndCounted() {
        val engine = FakeLocalSpeechEngine()
        val port = LocalAiMediaPort(engine)
        val expected = ShortArray(PcmFormat.NARROWBAND.frameSamples) { 321 }
        engine.nextAssistantFrame = expected
        val actual = port.readFrame()
        expected.fill(0)
        assertEquals(321, actual.first().toInt())
        assertEquals(1, port.stats.assistantFrames)
    }

    @Test fun rejectedCallerFrameIsCountedAsDropped() {
        val engine = FakeLocalSpeechEngine().apply { acceptFrames = false }
        val port = LocalAiMediaPort(engine)
        port.writeFrame(PcmFormat.NARROWBAND.silence())
        assertEquals(1, port.stats.droppedCallerFrames)
        assertEquals(0, port.stats.callerFrames)
    }

    @Test fun closeIsIdempotentAndSubsequentFramesAreSilent() {
        val engine = FakeLocalSpeechEngine()
        val port = LocalAiMediaPort(engine)
        port.close()
        port.close()
        port.writeFrame(PcmFormat.NARROWBAND.silence())
        assertTrue(engine.closed)
        assertFalse(port.stats.ready)
        assertTrue(port.readFrame().all { it.toInt() == 0 })
        assertEquals(0, port.stats.callerFrames)
    }

    private class FakeLocalSpeechEngine : LocalSpeechEngine {
        override var isReady: Boolean = true
        var acceptFrames = true
        var closed = false
        var lastCallerFrame: ShortArray? = null
        var nextAssistantFrame: ShortArray? = null
        override fun offerCallerFrame(frame: ShortArray): Boolean {
            if (!acceptFrames || closed) return false
            lastCallerFrame = frame
            return true
        }
        override fun pollAssistantFrame(): ShortArray? = nextAssistantFrame
        override fun close() { closed = true; isReady = false }
    }
}
