package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramSimCallTest {
    @Test
    fun masksPhoneNumberForTelegramProgressMessages() {
        assertEquals("…7890", TelegramSimCall.maskNumber("+15551237890"))
        assertEquals("1234", TelegramSimCall.maskNumber("1234"))
    }

    @Test
    fun formatsDurationAsHoursMinutesAndSeconds() {
        assertEquals("00:00:00", TelegramSimCall.formatDuration(0))
        assertEquals("00:01:05", TelegramSimCall.formatDuration(65))
        assertEquals("27:00:01", TelegramSimCall.formatDuration(97_201))
    }

    @Test
    fun reportsCarrierConnectedDurationRatherThanUnansweredRinging() {
        val answered = TelegramCallRecord("ab12cd34", "+12345678", 1_000, 5_000, 15_500, "ended")
        val unanswered = TelegramCallRecord("cd34ef56", "+12345678", 1_000, null, 15_500, "no answer")
        assertEquals(10L, answered.durationSeconds)
        assertEquals(0L, unanswered.durationSeconds)
    }

    @Test
    fun audioStatusDistinguishesUnstartedSilentAndActivePaths() {
        assertEquals("مسار الصوت لم يبدأ", TelegramSimCall.formatAudioStatus(false, 0, 0, 0))
        assertEquals("تم إعداد الجسر لكن لم تُسجل إطارات صوت", TelegramSimCall.formatAudioStatus(true, 0, 0, 0))
        assertEquals(
            "إطارات=50؛ نشاط صوت مرصود بالاتجاهين (مؤشران=120/95)",
            TelegramSimCall.formatAudioStatus(true, 50, 120, 95),
        )
    }
}
