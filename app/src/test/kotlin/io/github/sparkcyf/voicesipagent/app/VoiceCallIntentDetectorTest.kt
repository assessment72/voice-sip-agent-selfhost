package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCallIntentDetectorTest {
    @Test fun explicitHumanHandoffPhrasesAreDetected() {
        listOf("حولني لموظف", "أريد أكلم موظف", "أبغى أتحدث مع شخص", "وصلني بمسؤول المبيعات").forEach {
            assertEquals(it, VoiceCallIntent.HANDOFF_TO_EMPLOYEE, VoiceCallIntentDetector.detect(it))
        }
    }

    @Test fun explicitHangupPhrasesAreDetected() {
        listOf("أنهي المكالمة", "اقفل الاتصال", "خلاص شكرا انتهينا", "مع السلامة").forEach {
            assertEquals(it, VoiceCallIntent.HANG_UP, VoiceCallIntentDetector.detect(it))
        }
    }

    @Test fun ordinaryOrNegatedSpeechDoesNotTriggerAnAction() {
        listOf("السلام عليكم", "لا تحولني لموظف", "لا تنهي المكالمة", "أريد معرفة سعر الديزل").forEach {
            assertEquals(it, VoiceCallIntent.NONE, VoiceCallIntentDetector.detect(it))
        }
    }
}
