package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import io.github.sparkcyf.voicesipagent.gateway.BusyPolicy
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GatewaySettings(
    /** Master switch; call cleanup and default-dialer restoration may finish later. */
    val takeover: Boolean = true,
    /** Retain the role on switch-off instead of returning to the saved dialer. */
    val keepDefaultDialer: Boolean = false,
    /** Mute local call output while bridged. Telecom manages microphone mute independently. */
    val muteEarpiece: Boolean = true,
    val server: String = "",
    val port: Int = 5060,
    /** udp | tcp | tls; the built-in stack only supports udp. */
    val sipTransport: String = "udp",
    /** off | optional | mandatory SDES-SRTP (pjsua2 over TLS only). */
    val srtp: String = "optional",
    /** pjsip info-level logging (development); errors/warnings are always logged. */
    val sipDebugLog: Boolean = false,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val localSipPort: Int = 5062,
    /** Server mode: lease (seconds) granted to the softphone; longer survives background Doze. */
    val registrarLeaseSec: Int = 600,
    /**
     * Server mode: password the softphone must present to REGISTER (SIP digest auth). Empty = no
     * authentication (anyone who reaches the port can register and dial out) — set one before
     * exposing the gateway to the internet. Sealed on disk like the upstream SIP password.
     */
    val registrarPassword: String = "",
    /** SIP extension / DID that receives cellular inbound calls; blank = the SIP username (trunk DID). */
    val inboundTarget: String = "",
    /** SIP extension / URI dialed when the caller explicitly asks for a human employee. */
    val humanHandoffTarget: String = "",
    /** Comma-separated; empty = any number may be dialed out. */
    val allowedPrefixes: String = "",
    val busyPolicy: BusyPolicy = BusyPolicy.REJECT,
    val audioMethod: Int = AudioMethod.AUTO,
    val callAccess: Int = CallAccess.AUTO,
    /** Which SIP implementation backs the PBX side. */
    val sipStack: SipStack = SipStack.BUILTIN,
    /**
     * Device-in-itself mode: this phone hosts the SIP registrar. A softphone (e.g. baresip
     * in the field) registers directly to it; no external PBX/server is involved. When on,
     * [server]/[username]/[password] are not required.
     */
    val serverMode: Boolean = true,
    /** Optional Voicx control bridge. Empty URL disables remote control. */
    val controlBridgeUrl: String = "",
    val controlBridgeDeviceId: String = "",
    /** Stored encrypted with the Android Keystore key; never logged. */
    val controlBridgeToken: String = "",
    /** Optional direct Telegram Bot API control; encrypted with Android Keystore. */
    val telegramBotToken: String = "",
    /** Telegram numeric user ID allowed to issue private-chat commands; zero disables control. */
    val telegramAllowedUserId: Long = 0L,
    /** When true Telegram delivery is owned by Vercel; Android consumes only its durable command queue. */
    val telegramWebhookMode: Boolean = false,
    /** Gemini Developer API key, encrypted with Android Keystore. */
    val geminiApiKey: String = "",
    /** Gemini model used by the direct Live WebSocket client. */
    val geminiModel: String = "gemini-2.5-flash-native-audio-preview-12-2025",
    /** Answer incoming cellular calls with Gemini instead of routing them to SIP. */
    val aiAutoAnswer: Boolean = false,
    /** Vercel API endpoint; Atlas credentials remain server-side. */
    val mongoApiUrl: String = "",
    val mongoDeviceId: String = "",
    /** Per-device API token, encrypted with Android Keystore. */
    val mongoDeviceToken: String = "",
    /** Expected database name configured in Vercel (not sent in database operations). */
    val mongoDatabaseName: String = "voicx_sip_prod",
    val mongoEnabled: Boolean = false,
    val mongoLastSuccessAt: Long = 0L,
    /** Adasms API key, encrypted with Android Keystore. */
    val smsApiKey: String = "",
    /** UI language: "ru" or "en". Pure UI preference, no SIP restart. */
    val uiLanguage: String = "ru",
) {
    val sipConfigured: Boolean get() =
        serverMode || (server.isNotBlank() && username.isNotBlank() && password.isNotEmpty())

    override fun toString() =
        "GatewaySettings(takeover=$takeover muteEarpiece=$muteEarpiece stack=$sipStack serverMode=$serverMode server=$server:$port/$sipTransport srtp=$srtp user=$username target=$inboundTarget)"
}

enum class SipStack(val id: String) {
    /** The hand-written Kotlin stack in :sip. */
    BUILTIN("builtin"),

    /** pjsip's pjsua2 via :sip-pjsua2. */
    PJSUA2("pjsua2"),
    ;

    companion object {
        fun of(id: String?): SipStack? = entries.firstOrNull { it.id == id }
    }
}

/** First-run UI language from the system locale: a Russian system → "ru", otherwise "en". */
private fun systemDefaultUiLang(): String =
    if (java.util.Locale.getDefault().language == "ru") "ru" else "en"

/** SharedPreferences, with the SIP password sealed by an Android Keystore AES-GCM key. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    fun load(): GatewaySettings = GatewaySettings(
        takeover = prefs.getBoolean("takeover", true),
        keepDefaultDialer = prefs.getBoolean("keep_default_dialer", false),
        muteEarpiece = prefs.getBoolean("mute_earpiece", true),
        server = prefs.getString("server", "")!!,
        port = prefs.getInt("port", 5060),
        sipTransport = prefs.getString("sip_transport", "udp")!!,
        srtp = prefs.getString("srtp", "optional")!!,
        sipDebugLog = prefs.getBoolean("sip_debug_log", false),
        username = prefs.getString("username", "")!!,
        password = prefs.getString("password_sealed", null)?.let(::unseal).orEmpty(),
        domain = prefs.getString("domain", "")!!,
        localSipPort = prefs.getInt("local_sip_port", 5062),
        registrarLeaseSec = prefs.getInt("registrar_lease_sec", 600).takeIf { it in 30..86400 } ?: 600,
        registrarPassword = prefs.getString("registrar_password_sealed", null)?.let(::unseal).orEmpty(),
        inboundTarget = prefs.getString("inbound_target", "")!!,
        humanHandoffTarget = prefs.getString("human_handoff_target", "")!!,
        allowedPrefixes = prefs.getString("allowed_prefixes", "")!!,
        busyPolicy = runCatching { BusyPolicy.valueOf(prefs.getString("busy_policy", "REJECT")!!) }.getOrDefault(BusyPolicy.REJECT),
        audioMethod = prefs.getInt("audio_method", AudioMethod.AUTO).takeIf { it in 0..2 } ?: AudioMethod.AUTO,
        callAccess = prefs.getInt("call_access", CallAccess.AUTO).takeIf { it in 0..2 } ?: CallAccess.AUTO,
        sipStack = SipStack.of(prefs.getString("sip_stack", null)) ?: SipStack.BUILTIN,
        serverMode = prefs.getBoolean("server_mode", true),
        controlBridgeUrl = prefs.getString("control_bridge_url", "")!!,
        controlBridgeDeviceId = prefs.getString("control_bridge_device_id", "")!!,
        controlBridgeToken = prefs.getString("control_bridge_token_sealed", null)?.let(::unseal).orEmpty(),
        telegramBotToken = prefs.getString("telegram_bot_token_sealed", null)?.let(::unseal).orEmpty(),
        telegramAllowedUserId = prefs.getLong("telegram_allowed_user_id", 0L).takeIf { it > 0 } ?: 0L,
        telegramWebhookMode = prefs.getBoolean("telegram_webhook_mode", false),
        geminiApiKey = prefs.getString("gemini_api_key_sealed", null)?.let(::unseal).orEmpty(),
        geminiModel = prefs.getString("gemini_model", "gemini-2.5-flash-native-audio-preview-12-2025")!!,
        aiAutoAnswer = prefs.getBoolean("ai_auto_answer", false),
        mongoApiUrl = prefs.getString("mongo_api_url", "")!!,
        mongoDeviceId = prefs.getString("mongo_device_id", "")!!,
        mongoDeviceToken = prefs.getString("mongo_device_token_sealed", null)?.let(::unseal).orEmpty(),
        mongoDatabaseName = prefs.getString("mongo_database_name", "voicx_sip_prod")!!,
        mongoEnabled = prefs.getBoolean("mongo_enabled", false),
        mongoLastSuccessAt = prefs.getLong("mongo_last_success_at", 0L),
        smsApiKey = prefs.getString("sms_api_key_sealed", null)?.let(::unseal).orEmpty(),
        // First run (key absent): default to the system language — Russian locale → "ru",
        // anything else → "en". Once the user picks a language it is saved and always wins.
        uiLanguage = prefs.getString("ui_lang", null) ?: systemDefaultUiLang(),
    )

    fun save(s: GatewaySettings) {
        prefs.edit()
            .putBoolean("takeover", s.takeover)
            .putBoolean("keep_default_dialer", s.keepDefaultDialer)
            .putBoolean("mute_earpiece", s.muteEarpiece)
            .putString("server", s.server.trim())
            .putInt("port", s.port)
            .putString("sip_transport", s.sipTransport.trim().lowercase())
            .putString("srtp", s.srtp.trim().lowercase())
            .putBoolean("sip_debug_log", s.sipDebugLog)
            .putString("username", s.username.trim())
            .putString("password_sealed", seal(s.password))
            .putString("domain", s.domain.trim())
            .putInt("local_sip_port", s.localSipPort)
            .putInt("registrar_lease_sec", s.registrarLeaseSec)
            .putString("registrar_password_sealed", seal(s.registrarPassword))
            .putString("inbound_target", s.inboundTarget.trim())
            .putString("human_handoff_target", s.humanHandoffTarget.trim())
            .putString("allowed_prefixes", s.allowedPrefixes.trim())
            .putString("busy_policy", s.busyPolicy.name)
            .putInt("audio_method", s.audioMethod)
            .putInt("call_access", s.callAccess)
            .putString("sip_stack", s.sipStack.id)
            .putBoolean("server_mode", s.serverMode)
            .putString("control_bridge_url", s.controlBridgeUrl.trim())
            .putString("control_bridge_device_id", s.controlBridgeDeviceId.trim())
            .putString("control_bridge_token_sealed", seal(s.controlBridgeToken))
            .putString("telegram_bot_token_sealed", seal(s.telegramBotToken))
            .putLong("telegram_allowed_user_id", s.telegramAllowedUserId)
            .putBoolean("telegram_webhook_mode", s.telegramWebhookMode)
            .putString("gemini_api_key_sealed", seal(s.geminiApiKey))
            .putString("gemini_model", s.geminiModel.trim())
            .putBoolean("ai_auto_answer", s.aiAutoAnswer)
            .putString("mongo_api_url", s.mongoApiUrl.trim())
            .putString("mongo_device_id", s.mongoDeviceId.trim())
            .putString("mongo_device_token_sealed", seal(s.mongoDeviceToken))
            .remove("mongo_uri_sealed")
            .putString("mongo_database_name", s.mongoDatabaseName.trim())
            .putBoolean("mongo_enabled", s.mongoEnabled)
            .putLong("mongo_last_success_at", s.mongoLastSuccessAt)
            .putString("sms_api_key_sealed", seal(s.smsApiKey))
            .putString("ui_lang", s.uiLanguage)
            .apply()
    }

    fun loadTelegramUpdateOffset(): Long =
        prefs.getLong("telegram_update_offset", 0L).coerceAtLeast(0L)

    fun saveTelegramUpdateOffset(offset: Long) {
        if (offset >= 0L) prefs.edit().putLong("telegram_update_offset", offset).apply()
    }

    /**
     * Atomically records that a queued Telegram command is about to run. The receipt is encrypted
     * with the Android Keystore key and deliberately survives process death. A command marked
     * started is never run a second time: delivery is at-least-once, while phone side effects are
     * guarded at-most-once by commandId.
     */
    @Synchronized
    fun beginTelegramCommand(commandId: String): JSONObject? {
        if (commandId.isBlank()) return null
        val receipts = readSealedJsonObject("telegram_command_receipts_sealed")
        val existing = receipts.optJSONObject(commandId)
        if (existing != null) return existing
        receipts.put(commandId, JSONObject()
            .put("state", "started")
            .put("startedAt", System.currentTimeMillis()))
        trimReceipts(receipts)
        prefs.edit().putString("telegram_command_receipts_sealed", seal(receipts.toString())).commit()
        return null
    }

    @Synchronized
    fun completeTelegramCommand(commandId: String, result: JSONObject) {
        if (commandId.isBlank()) return
        val receipts = readSealedJsonObject("telegram_command_receipts_sealed")
        receipts.put(commandId, JSONObject()
            .put("state", "completed")
            .put("completedAt", System.currentTimeMillis())
            .put("result", result))
        trimReceipts(receipts)
        prefs.edit().putString("telegram_command_receipts_sealed", seal(receipts.toString())).commit()
    }

    private fun trimReceipts(receipts: JSONObject) {
        if (receipts.length() <= 500) return
        val oldest = receipts.keys().asSequence().mapNotNull { key ->
            key to receipts.optJSONObject(key)?.optLong("completedAt", Long.MAX_VALUE)
        }.sortedBy { it.second }.take(receipts.length() - 500).map { it.first }.toList()
        oldest.forEach(receipts::remove)
    }

    /** Keeps a small, encrypted offline snapshot so a call can start without Vercel. */
    @Synchronized
    fun cacheCustomerContext(phone: String, context: JSONObject) {
        val normalized = phone.trim()
        if (normalized.isBlank()) return
        val cache = readSealedJsonObject("customer_context_cache_sealed")
        context.put("cachedAt", System.currentTimeMillis())
        cache.put(normalized, context)
        if (cache.length() > 250) {
            val oldest = cache.keys().asSequence().mapNotNull { key ->
                val savedAt = cache.optJSONObject(key)?.optLong("cachedAt") ?: 0L
                key to savedAt
            }.sortedBy { it.second }.take(cache.length() - 250).map { it.first }.toList()
            oldest.forEach(cache::remove)
        }
        prefs.edit().putString("customer_context_cache_sealed", seal(cache.toString())).commit()
    }

    @Synchronized
    fun cachedCustomerContext(phone: String): JSONObject? =
        readSealedJsonObject("customer_context_cache_sealed").optJSONObject(phone.trim())

    /** Encrypted durable outbox: failed report uploads survive process death and are retried later. */
    @Synchronized
    fun enqueueCallReport(reportId: String, payload: String) {
        val queue = readSealedJsonArray("call_report_outbox_sealed")
        if ((0 until queue.length()).any { queue.optJSONObject(it)?.optString("id") == reportId }) return
        queue.put(JSONObject().put("id", reportId).put("payload", payload).put("queuedAt", System.currentTimeMillis()))
        prefs.edit().putString("call_report_outbox_sealed", seal(queue.toString())).commit()
    }

    @Synchronized
    fun pendingCallReports(): List<Pair<String, String>> {
        val queue = readSealedJsonArray("call_report_outbox_sealed")
        return (0 until queue.length()).mapNotNull { index ->
            queue.optJSONObject(index)?.let { item ->
                val id = item.optString("id")
                val payload = item.optString("payload")
                if (id.isNotBlank() && payload.isNotBlank()) id to payload else null
            }
        }
    }

    @Synchronized
    fun removePendingCallReport(reportId: String) {
        val old = readSealedJsonArray("call_report_outbox_sealed")
        val updated = JSONArray()
        for (index in 0 until old.length()) {
            val item = old.optJSONObject(index) ?: continue
            if (item.optString("id") != reportId) updated.put(item)
        }
        prefs.edit().putString("call_report_outbox_sealed", seal(updated.toString())).commit()
    }

    private fun readSealedJsonObject(key: String): JSONObject =
        prefs.getString(key, null)?.let(::unseal)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    private fun readSealedJsonArray(key: String): JSONArray =
        prefs.getString(key, null)?.let(::unseal)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun unseal(sealed: String): String? = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) {
        AppLog.w("stored SIP password unreadable; re-enter it", e)
        null
    }

    companion object {
        private const val KEY_ALIAS = "sip-password"
    }
}
