package io.github.sparkcyf.voicesipagent.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnBasedLocalSpeechProcessorTest {
    @Test fun energySegmenterKeepsPreRollAndEndsAfterHangover() {
        val vad = EnergyVadSegmenter(
            speechRmsThreshold = 400,
            preRollFrames = 2,
            hangoverFrames = 3,
            maxUtteranceFrames = 20,
            minUtteranceFrames = 2,
        )
        val silence = ShortArray(160)
        val speech = ShortArray(160) { 2000 }
        assertNull(vad.accept(silence))
        assertNull(vad.accept(silence))
        assertNull(vad.accept(speech))
        assertNull(vad.accept(speech))
        assertNull(vad.accept(silence))
        assertNull(vad.accept(silence))
        val segment = vad.accept(silence)
        assertNotNull(segment)
        // two pre-roll frames, two speech frames and three hangover frames
        assertEquals(7 * 160, segment!!.size)
    }

    @Test fun processorRunsAsrDialogueAndTtsOnlyAfterUtterance() {
        var recognized: String? = null
        var replied: String? = null
        val processor = TurnBasedLocalSpeechProcessor(
            segmenter = EnergyVadSegmenter(
                speechRmsThreshold = 400,
                preRollFrames = 0,
                hangoverFrames = 1,
                maxUtteranceFrames = 10,
                minUtteranceFrames = 1,
            ),
            recognizer = OfflineSpeechRecognizer { pcm ->
                assertTrue(pcm.isNotEmpty())
                "مرحبا".also { recognized = it }
            },
            dialogue = OfflineDialogueModel { text ->
                "أهلا بك".also { replied = text }
            },
            synthesizer = OfflineSpeechSynthesizer { text ->
                assertEquals("أهلا بك", text)
                listOf(ShortArray(160) { 123 })
            },
        )
        try {
            val speech = ShortArray(160) { 2000 }
            assertTrue(processor.processCallerFrame(speech).isEmpty())
            val output = processor.processCallerFrame(ShortArray(160))
            assertEquals("مرحبا", recognized)
            assertEquals("مرحبا", replied)
            assertEquals(1, output.size)
            assertEquals(123, output.single()[0].toInt())
        } finally {
            processor.close()
        }
    }

    @Test fun emptyRecognitionProducesNoAssistantAudio() {
        val processor = TurnBasedLocalSpeechProcessor(
            segmenter = EnergyVadSegmenter(
                speechRmsThreshold = 100,
                preRollFrames = 0,
                hangoverFrames = 1,
                maxUtteranceFrames = 10,
                minUtteranceFrames = 1,
            ),
            recognizer = OfflineSpeechRecognizer { null },
            dialogue = OfflineDialogueModel { error("must not run") },
            synthesizer = OfflineSpeechSynthesizer { error("must not run") },
        )
        try {
            processor.processCallerFrame(ShortArray(160) { 1000 })
            assertTrue(processor.processCallerFrame(ShortArray(160)).isEmpty())
        } finally {
            processor.close()
        }
    }
}
