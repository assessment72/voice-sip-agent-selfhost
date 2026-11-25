package io.github.sparkcyf.voicesipagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiLiveClientTest {
    @Test
    fun finalAudioChunkIsDeliveredBeforeTurnComplete() {
        val events = mutableListOf<String>()
        val client = GeminiLiveClient(
            apiKey = "test-key",
            model = "test-model",
            instructions = "test",
            onAudio24k = { events += "audio:${it.size}" },
            onInputTranscript = {},
            onOutputTranscript = {},
            onReady = {},
            onClosed = {},
            onTurnComplete = { events += "turnComplete" },
        )
        val message = JSONObject().put("serverContent",
            JSONObject()
                .put("modelTurn", JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("inlineData", JSONObject()
                        .put("mimeType", "audio/pcm;rate=24000")
                        .put("data", "AA=="))
                )))
                .put("turnComplete", true))

        client.handle(message)
        client.close()

        assertEquals(listOf("audio:1", "turnComplete"), events)
    }

    @Test
    fun turnCompleteWithoutAudioStillNotifiesTheTurnListener() {
        val events = mutableListOf<String>()
        val client = GeminiLiveClient(
            apiKey = "test-key",
            model = "test-model",
            instructions = "test",
            onAudio24k = { events += "audio" },
            onInputTranscript = {},
            onOutputTranscript = {},
            onReady = {},
            onClosed = {},
            onTurnComplete = { events += "turnComplete" },
        )

        client.handle(JSONObject().put("serverContent", JSONObject().put("turnComplete", true)))
        client.close()

        assertEquals(listOf("turnComplete"), events)
    }
}
