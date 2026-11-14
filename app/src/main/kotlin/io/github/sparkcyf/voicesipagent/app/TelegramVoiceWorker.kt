package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Receives Telegram voice messages while GatewayService is alive.
 *
 * The bot is deliberately restricted to one configured private chat. Updates are acknowledged
 * only after all accepted voice files have been downloaded into app-private storage.
 * This is a recorded-message fallback, not a live Telegram call or a WebRTC media bridge.
 */
class TelegramVoiceWorker(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("telegram_voice", Context.MODE_PRIVATE)
    private val settings = SettingsStore(app)

    suspend fun run(onWebRtcInvite: suspend (String) -> Unit = {}) {
        while (currentCoroutineContext().isActive) {
            val config = settings.load()
            if (!config.telegramVoiceEnabled ||
                config.telegramBotToken.isBlank() ||
                config.telegramPrivateChatId.isBlank()
            ) {
                delay(IDLE_DELAY_MS)
                continue
            }

            try {
                val client = TelegramBotClient(config.telegramBotToken, config.telegramPrivateChatId)
                val offset = prefs.getLong(KEY_OFFSET, 0L)
                val result = withContext(Dispatchers.IO) { client.pollVoice(offset) }
                if (result.joinInvites.isNotEmpty() && config.webRtcServerUrl.isBlank()) {
                    AppLog.w("WebRTC invite received; configure the Vercel server URL in Settings")
                    delay(RETRY_DELAY_MS)
                    continue
                }
                for (voice in result.voices) {
                    val inbox = File(app.filesDir, "telegram/inbox")
                    val target = File(inbox, "voice_${voice.updateId}.ogg")
                    val saved = if (target.isFile) target else withContext(Dispatchers.IO) {
                        client.downloadVoice(voice, inbox)
                    }
                    AppLog.i("Telegram voice saved privately: ${saved.name}, ${voice.durationSeconds}s")
                }
                for (invite in result.joinInvites) {
                    withContext(Dispatchers.IO) { client.answerCallbackQuery(invite.callbackQueryId) }
                    onWebRtcInvite(invite.token)
                }
                prefs.edit().putLong(KEY_OFFSET, result.nextOffset).apply()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Do not log tokens, chat IDs, message text, or Telegram URLs.
                AppLog.w("Telegram voice polling failed; will retry")
                delay(RETRY_DELAY_MS)
            }
        }
    }

    companion object {
        private const val KEY_OFFSET = "update_offset"
        private const val IDLE_DELAY_MS = 3_000L
        private const val RETRY_DELAY_MS = 5_000L
    }
}
