package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.FrameClockListener
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBridgeDiagnosticsTest {
    @Test
    fun `pump failures are counted and retained instead of silently disappearing`() {
        val clock = FakePort(isClockSource = true, fill = 0)
        val broken = object : MediaPort {
            override val format = PcmFormat.NARROWBAND
            override val isClockSource = false
            override fun setClockListener(listener: FrameClockListener?) {}
            override fun readFrame(): ShortArray = error("test read failure")
            override fun writeFrame(frame: ShortArray) {}
        }
        val bridge = MediaBridge(clock, broken)
        bridge.start()
        clock.clock!!.onFrame()

        assertEquals(1L, bridge.pumpFailures)
        assertTrue(bridge.diagnostics().contains("IllegalStateException"))
        bridge.stop()
    }
}
