package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import io.github.sparkcyf.voicesipagent.gateway.OfflineDialogueModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Offline GGUF dialogue adapter backed by Arm's llama.cpp Android library.
 *
 * Construct and invoke this adapter only on the local AI worker, never from an RTP callback.
 * The selected GGUF must already be installed in app-private storage and SHA-256 verified.
 */
class ArmLlamaDialogueModel(
    context: Context,
    modelFile: File,
    private val systemPrompt: String = DEFAULT_ARABIC_SYSTEM_PROMPT,
    private val maxTokens: Int = 96,
    private val maxReplyChars: Int = 900,
    private val initializationTimeoutMs: Long = 90_000,
) : OfflineDialogueModel {

    private val engine = AiChat.getInferenceEngine(context.applicationContext)
    private var closed = false

    init {
        require(modelFile.isFile && modelFile.canRead()) { "GGUF model is missing or unreadable" }
        require(modelFile.extension.equals("gguf", ignoreCase = true)) { "Expected a GGUF model" }
        require(modelFile.length() in 1..MAX_MODEL_BYTES) { "GGUF model exceeds the configured size limit" }
        require(systemPrompt.isNotBlank())
        require(maxTokens in 16..128) { "Voice replies are limited to 16..128 generated tokens" }
        require(maxReplyChars in 100..2_000)
        require(initializationTimeoutMs in 10_000..180_000)

        runBlocking(Dispatchers.IO) {
            val initialState = withTimeout(initializationTimeoutMs) {
                engine.state.first { state ->
                    state is InferenceEngine.State.Initialized || state is InferenceEngine.State.Error
                }
            }
            if (initialState is InferenceEngine.State.Error) {
                throw IllegalStateException("Local LLM runtime initialization failed", initialState.exception)
            }
            withTimeout(initializationTimeoutMs) {
                engine.loadModel(modelFile.absolutePath)
                engine.setSystemPrompt(systemPrompt)
            }
        }
    }

    override fun reply(userText: String): String? {
        check(!closed) { "Local LLM is closed" }
        val prompt = userText.trim().take(MAX_PROMPT_CHARS)
        if (prompt.isEmpty()) return null

        return runBlocking(Dispatchers.IO) {
            val response = StringBuilder()
            withTimeout(REPLY_TIMEOUT_MS) {
                engine.sendUserPrompt(prompt, maxTokens).collect { token ->
                    if (response.length < maxReplyChars) {
                        response.append(token.take(maxReplyChars - response.length))
                    }
                }
            }
            response.toString().trim().takeIf { it.isNotEmpty() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { engine.cleanUp() }
        engine.destroy()
    }

    companion object {
        private const val MAX_MODEL_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MAX_PROMPT_CHARS = 1_200
        private const val REPLY_TIMEOUT_MS = 45_000L

        const val DEFAULT_ARABIC_SYSTEM_PROMPT =
            "أنت مساعد صوتي عربي موجز. أجب باللغة العربية الواضحة وبجمل قصيرة مناسبة للمكالمة الهاتفية. " +
                "لا تدّع تنفيذ أي إجراء خارجي، ولا تطلب كلمات المرور أو رموز التحقق."
    }
}
