package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection

class TelegramBotClientTest {
    @Test
    fun pollFailureIncludesTelegramConflictDescriptionWithoutExposingToken() {
        val connection = mock(HttpURLConnection::class.java)
        `when`(connection.responseCode).thenReturn(409)
        `when`(connection.errorStream).thenReturn(
            ByteArrayInputStream(
                """{"ok":false,"error_code":409,"description":"Conflict: terminated by other getUpdates request"}"""
                    .toByteArray(Charsets.UTF_8),
            ),
        )
        val token = "123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghi"
        val client = TelegramBotClient(token = token, connectionFactory = { connection })

        try {
            client.poll(0)
            fail("Expected Telegram HTTP conflict to be surfaced")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("409"))
            assertTrue(error.message.orEmpty().contains("other getUpdates request"))
            assertTrue(!error.message.orEmpty().contains(token))
        }

        verify(connection).disconnect()
    }
}
