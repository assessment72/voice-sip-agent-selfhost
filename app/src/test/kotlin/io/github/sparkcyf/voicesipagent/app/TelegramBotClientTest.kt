package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertThrows
import org.junit.Test

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
}
