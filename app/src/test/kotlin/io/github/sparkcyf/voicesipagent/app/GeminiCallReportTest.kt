package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.voice.HangupCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiCallReportTest {
    @Test
    fun `completed turns are reported independently from latency samples`() {
        val report = AiCallReport(
            number = "+966500000000",
            name = "Customer",
            role = "Manager",
            company = "Example",
            startedAt = 1_000L,
            endedAt = 9_000L,
            cause = HangupCause.NORMAL,
            inputFrames = 100,
            outputFrames = 120,
            inputText = "hello",
            outputText = "welcome",
            turnCount = 4,
        )

        assertTrue(report.telegramMessages().first().contains("عدد جولات الحوار المكتملة: 4"))
    }

    @Test
    fun `full transcript is emitted in ordered Telegram sized chunks`() {
        val transcript = (1..8_000).joinToString(" ") { "word$it" }
        val report = AiCallReport(
            number = "+966500000000",
            name = null,
            role = null,
            company = null,
            startedAt = 1_000L,
            endedAt = 9_000L,
            cause = HangupCause.NORMAL,
            inputFrames = 100,
            outputFrames = 120,
            inputText = "",
            outputText = "",
            conversationTranscript = transcript,
        )

        val messages = report.telegramMessages()
        assertTrue(messages.size > 2)
        assertTrue(messages.all { it.length <= 3_900 })
        val recovered = messages.drop(1).mapIndexed { index, message ->
            message.removePrefix("تفريغ المحادثة ${index + 1}:\n")
        }.joinToString("")
        assertEquals(transcript, recovered)
    }
}
