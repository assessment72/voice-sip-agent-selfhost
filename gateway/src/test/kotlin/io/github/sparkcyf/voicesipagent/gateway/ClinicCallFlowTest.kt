package io.github.sparkcyf.voicesipagent.gateway

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClinicCallFlowTest {
    @Test fun menuRoutesOnlyDoctorAndReceptionExtensions() {
        val ivr = ClinicIvrSession()
        ivr.announcementFinished()
        assertEquals(ClinicIvrSession.State.MENU, ivr.state)
        assertEquals(ClinicIvrSession.Action.ConnectEmployee("1"), ivr.digitReceived('1'))
        assertEquals(ClinicIvrSession.State.CONNECTING, ivr.state)

        val retry = ClinicIvrSession()
        retry.announcementFinished()
        assertEquals(ClinicIvrSession.Action.ConnectEmployee("2"), retry.digitReceived('2'))
    }

    @Test fun invalidDigitsAreIgnoredAndRetryReturnsToAnnouncement() {
        val ivr = ClinicIvrSession()
        ivr.announcementFinished()
        assertEquals(ClinicIvrSession.Action.InvalidDigit('9'), ivr.digitReceived('9'))
        ivr.retry()
        assertEquals(ClinicIvrSession.State.ANNOUNCEMENT, ivr.state)
        ivr.announcementFinished()
        ivr.digitReceived('1')
        ivr.retry()
        assertEquals(ClinicIvrSession.State.ANNOUNCEMENT, ivr.state)
    }

    @Test fun wavRecorderWritesPcm16MonoHeader() {
        val file = Files.createTempFile("clinic-call-", ".wav").toFile()
        PcmWavRecorder(file).use { it.writeFrame(shortArrayOf(0, 1000, -1000, 0)) }
        val bytes = file.readBytes()
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("data", bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertEquals(52, bytes.size)
        file.delete()
    }
}
