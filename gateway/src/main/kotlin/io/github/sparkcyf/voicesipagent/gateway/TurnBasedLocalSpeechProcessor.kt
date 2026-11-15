package io.github.sparkcyf.voicesipagent.gateway

import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * A small energy-based endpoint detector for 20 ms mono PCM16 frames.
 * It is a bootstrap segmenter, not a replacement for a neural VAD.
 */
class EnergyVadSegmenter(
    private val sampleRate: Int = 8000,
    private val speechRmsThreshold: Int = 500,
    private val preRollFrames: Int = 8,
    private val hangoverFrames: Int = 18,
    private val maxUtteranceFrames: Int = 750,
    private val minUtteranceFrames: Int = 8,
) {
    private val preRoll = ArrayDeque<ShortArray>()
    private val utterance = ArrayList<ShortArray>()
    private var inSpeech = false
    private var trailingSilence = 0

    init {
        require(sampleRate == 8000 || sampleRate == 16000)
        require(speechRmsThreshold > 0)
        require(preRollFrames >= 0 && hangoverFrames >= 1)
        require(maxUtteranceFrames >= minUtteranceFrames && minUtteranceFrames > 0)
    }

    /** Returns a completed utterance as contiguous PCM16 samples, or null. */
    fun accept(frame: ShortArray): ShortArray? {
        require(frame.size == sampleRate / 50) { "Expected one 20 ms PCM frame" }
        val copy = frame.copyOf()
        val voiced = rms(copy) >= speechRmsThreshold

        if (!inSpeech) {
            if (voiced) {
                inSpeech = true
                while (preRoll.isNotEmpty()) utterance.add(preRoll.removeFirst())
                utterance.add(copy)
            } else {
                if (preRollFrames > 0) {
                    if (preRoll.size == preRollFrames) preRoll.removeFirst()
                    preRoll.addLast(copy)
                }
                return null
            }
        } else {
            utterance.add(copy)
            if (voiced) trailingSilence = 0 else trailingSilence++
        }

        if (utterance.size >= maxUtteranceFrames ||
            (inSpeech && trailingSilence >= hangoverFrames)
        ) {
            val completed = if (utterance.size >= minUtteranceFrames) {
                ShortArray(utterance.size * (sampleRate / 50)).also { result ->
                    utterance.forEachIndexed { index, samples ->
                        samples.copyInto(result, index * samples.size)
                    }
                }
            } else null
            reset()
            return completed
        }
        return null
    }

    fun reset() {
        preRoll.clear()
        utterance.clear()
        inSpeech = false
        trailingSilence = 0
    }

    private fun rms(frame: ShortArray): Int {
        var sum = 0.0
        for (sample in frame) {
            val value = sample.toDouble()
            sum += value * value
        }
        return sqrt(sum / frame.size).toInt()
    }
}

/** Native VAD adapters can replace EnergyVadSegmenter without changing the SIP pipeline. */
fun interface OfflineSpeechRecognizer : AutoCloseable {
    fun transcribe(pcm16: ShortArray): String?
    override fun close() {}
}

fun interface OfflineDialogueModel : AutoCloseable {
    fun reply(userText: String): String?
    override fun close() {}
}

fun interface OfflineSpeechSynthesizer : AutoCloseable {
    /** Returns mono PCM16 20 ms frames at the SIP media rate. */
    fun synthesize(text: String): List<ShortArray>
    override fun close() {}
}

/**
 * Turn-based local pipeline. All methods run on QueuedLocalSpeechEngine's worker thread,
 * never on the SIP/RTP media callback. Native adapters must perform no network access.
 */
class TurnBasedLocalSpeechProcessor(
    private val segmenter: EnergyVadSegmenter,
    private val recognizer: OfflineSpeechRecognizer,
    private val dialogue: OfflineDialogueModel,
    private val synthesizer: OfflineSpeechSynthesizer,
    private val frameSamples: Int = 160,
    private val maxTranscriptChars: Int = 1200,
    private val maxReplyChars: Int = 1200,
    private val onTranscript: (String) -> Unit = {},
) : LocalSpeechProcessor {
    private var closed = false

    init {
        require(frameSamples == 160 || frameSamples == 320)
        require(maxTranscriptChars > 0 && maxReplyChars > 0)
    }

    override fun processCallerFrame(frame: ShortArray): List<ShortArray> {
        check(!closed) { "Local speech processor is closed" }
        require(frame.size == frameSamples) { "Unexpected SIP PCM frame size" }
        val utterance = segmenter.accept(frame) ?: return emptyList()
        val transcript = recognizer.transcribe(utterance)
            ?.trim()
            ?.take(maxTranscriptChars)
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        runCatching { onTranscript(transcript) }
        val reply = dialogue.reply(transcript)
            ?.trim()
            ?.take(maxReplyChars)
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        return synthesizer.synthesize(reply).map { pcm ->
            require(pcm.size == frameSamples) {
                "TTS must return $frameSamples samples per 20 ms frame"
            }
            pcm.copyOf()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        segmenter.reset()
        listOf(recognizer, dialogue, synthesizer).forEach { stage ->
            runCatching { (stage as? AutoCloseable)?.close() }
        }
    }
}
