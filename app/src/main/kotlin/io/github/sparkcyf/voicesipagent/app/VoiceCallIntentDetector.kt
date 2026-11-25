package io.github.sparkcyf.voicesipagent.app

import java.text.Normalizer
import java.util.Locale

internal enum class VoiceCallIntent { HANDOFF_TO_EMPLOYEE, HANG_UP, NONE }

/** Conservative phrase matcher for explicit caller commands; it never reacts to model output. */
internal object VoiceCallIntentDetector {
    fun detect(transcript: String): VoiceCallIntent {
        val text = normalize(transcript)
        if (text.isBlank()) return VoiceCallIntent.NONE

        val handoff = listOf(
            Regex("""(?:حولني|حوّلني|وصلني|اربطني).{0,32}(?:موظف|موظفه|شخص|انسان|مسؤول|الدعم|المبيعات)"""),
            Regex("""(?:اريد|ابغى|ودي|احتاج).{0,28}(?:اكلم|اتكلم|اتحدث).{0,24}(?:موظف|موظفه|شخص|انسان|مسؤول)"""),
            Regex("""(?:موظف|موظفه|شخص حقيقي|انسان).{0,24}(?:معي|اكلمه|يتكلم|اتحدث)"""),
        )
        if (handoff.any { it.containsMatchIn(text) } && !Regex("""(?:لا|مش|ما).{0,10}(?:تحولني|حولني|موظف)""").containsMatchIn(text)) {
            return VoiceCallIntent.HANDOFF_TO_EMPLOYEE
        }

        val explicitHangup = listOf(
            Regex("""(?:انهي|انه|اقفل|اغلق|سكر|اقطع).{0,18}(?:المكالمه|الاتصال|الخط)"""),
            Regex("""(?:خلاص|شكرا|شكرًا).{0,20}(?:انتهينا|بس|مع السلامه)"""),
            Regex("""(?:ما عاد|مش محتاج|لا اريد).{0,24}(?:اكمل|استمر|المكالمه|الاتصال)"""),
            Regex("""مع السلامه"""),
        )
        if (explicitHangup.any { it.containsMatchIn(text) } &&
            !Regex("""(?:لا|مش|ما).{0,12}(?:تنهي|انهي|تقفل|تغلق)""").containsMatchIn(text)) {
            return VoiceCallIntent.HANG_UP
        }
        return VoiceCallIntent.NONE
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي')
        .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
