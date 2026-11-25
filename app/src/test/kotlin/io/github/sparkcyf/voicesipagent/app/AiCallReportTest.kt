package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCallReportTest {
    @Test
    fun includesTurnCountAndSplitsTheFullTranscriptIntoTelegramSafeMessages() {
        val transcript = (1..900).joinToString(System.lineSeparator()) { "العميل: جملة محادثة رقم $it" }
        val report = AiCallReport(
            number = "+967700000000",
            name = "عميل تجريبي",
            role = "مدير",
            company = "شركة",
            startedAt = 1_000,
            endedAt = 61_000,
            cause = io.github.sparkcyf.voicesipagent.voice.HangupCause.NORMAL,
            inputFrames = 100,
            outputFrames = 100,
            inputText = "آخر كلام",
            outputText = "آخر رد",
            turnCount = 7,
            conversationTranscript = transcript,
        )

        val messages = report.telegramMessages()

        assertTrue(messages.first().contains("عدد جولات الحوار المكتملة: 7"))
        assertTrue(messages.drop(1).first().contains("جملة محادثة رقم 1"))
        assertTrue(messages.drop(1).last().contains("جملة محادثة رقم 900"))
        assertTrue(messages.all { it.length <= 3_900 })
        assertTrue(messages.size > 1)
    }
}
