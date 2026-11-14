package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class TelegramBotClientTest {
    @Test
    fun rejectsMalformedBotToken() {
        assertThrows(IllegalArgumentException::class.java) {
            TelegramBotClient("not-a-token", "123456789")
        }
    }

    @Test
    fun rejectsNonNumericPrivateChatId() {
        assertThrows(IllegalArgumentException::class.java) {
            TelegramBotClient("123456:abcdefghijklmnopqrstuvwxyz_123456", "@username")
        }
    }

    @Test
    fun rejectsEmptyBotToken() {
        assertThrows(IllegalArgumentException::class.java) {
            TelegramBotClient("", "123456789")
        }
    }

    @Test
    fun rejectsUnsupportedOutgoingAudioFormatBeforeNetworkAccess() {
        val audio = Files.createTempFile("telegram-voice", ".wav").toFile()
        try {
            audio.writeBytes(byteArrayOf(1, 2, 3))
            val client = TelegramBotClient("123456:abcdefghijklmnopqrstuvwxyz_123456", "123456789")
            assertThrows(IllegalArgumentException::class.java) { client.sendVoice(audio) }
        } finally {
            audio.delete()
        }
    }
}
