package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.sparkcyf.voicesipagent.gateway.BusyPolicy
import io.github.sparkcyf.voicesipagent.gateway.FinalCallAction
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.voice.CallerIdMode
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
    /** Comma-separated; empty = any number may be dialed out. */
    val allowedPrefixes: String = "",
    val busyPolicy: BusyPolicy = BusyPolicy.REJECT,
    /** Caller-facing fallback extensions in order, e.g. "2,3". */
    val fallbackExtensionsCsv: String = "2",
    val maxCallAttempts: Int = 3,
    val finalCallAction: FinalCallAction = FinalCallAction.RETURN_TO_IVR_ONCE,
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
    /** UI language: "ru", "en", or "ar". Pure UI preference, no SIP restart. */
    val uiLanguage: String = "ru",
    /** Caller-facing local IVR text. These preferences never contain caller/employee audio. */
    val ivrWelcome: String = "مرحباً بك. اضغط 1 للتحدث مع الطبيب، أو اضغط 2 للتحدث مع الاستقبال.",
    val ivrDoctor: String = "يتم تحويلك الآن إلى الطبيب.",
    val ivrReception: String = "يتم تحويلك الآن إلى الاستقبال.",
    val ivrInvalid: String = "الاختيار غير صحيح. حاول مرة أخرى.",
    val ivrTimeout: String = "لم يتم اختيار أي خيار.",
    val ivrSource: String = "default",
    val ivrSpeechRate: Float = 1.0f,
    val ivrPitch: Float = 1.0f,
    val ivrAudioFileName: String = "",
    val ivrTtsVoice: String = "",
    /** Default caller identity policy for outgoing SIP and cellular legs. */
    val callerIdMode: CallerIdMode = CallerIdMode.NORMAL,
    /** Optional provider-authorized E.164 caller ID for outbound calls through an external SIP trunk. */
    val sipCallerId: String = "",
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

/** First-run UI language from the system locale. */
private fun systemDefaultUiLang(): String =
    when (java.util.Locale.getDefault().language) {
        "ru" -> "ru"
        "ar" -> "ar"
        else -> "en"
    }

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
        allowedPrefixes = prefs.getString("allowed_prefixes", "")!!,
        callerIdMode = CallerIdMode.fromId(prefs.getString("caller_id_mode", CallerIdMode.NORMAL.id)),
        sipCallerId = prefs.getString("sip_caller_id", "")!!.take(16),
        busyPolicy = runCatching { BusyPolicy.valueOf(prefs.getString("busy_policy", "REJECT")!!) }.getOrDefault(BusyPolicy.REJECT),
        fallbackExtensionsCsv = prefs.getString("fallback_extensions", "2")!!,
        maxCallAttempts = prefs.getInt("max_call_attempts", 3).takeIf { it in 1..4 } ?: 3,
        finalCallAction = runCatching {
            FinalCallAction.valueOf(prefs.getString("final_call_action", FinalCallAction.RETURN_TO_IVR_ONCE.name)!!)
        }.getOrDefault(FinalCallAction.RETURN_TO_IVR_ONCE),
        audioMethod = prefs.getInt("audio_method", AudioMethod.AUTO).takeIf { it in 0..2 } ?: AudioMethod.AUTO,
        callAccess = prefs.getInt("call_access", CallAccess.AUTO).takeIf { it in 0..2 } ?: CallAccess.AUTO,
        sipStack = SipStack.of(prefs.getString("sip_stack", null)) ?: SipStack.BUILTIN,
        serverMode = prefs.getBoolean("server_mode", true),
        // First run (key absent): default to Russian, Arabic, or English system language.
        // Once the user picks a language it is saved and always wins.
        uiLanguage = prefs.getString("ui_lang", null) ?: systemDefaultUiLang(),
        ivrWelcome = prefs.getString("ivr_welcome", "مرحباً بك. اضغط 1 للتحدث مع الطبيب، أو اضغط 2 للتحدث مع الاستقبال.")!!,
        ivrDoctor = prefs.getString("ivr_doctor", "يتم تحويلك الآن إلى الطبيب.")!!,
        ivrReception = prefs.getString("ivr_reception", "يتم تحويلك الآن إلى الاستقبال.")!!,
        ivrInvalid = prefs.getString("ivr_invalid", "الاختيار غير صحيح. حاول مرة أخرى.")!!,
        ivrTimeout = prefs.getString("ivr_timeout", "لم يتم اختيار أي خيار.")!!,
        ivrSource = prefs.getString("ivr_source", "default")!!.takeIf { it in setOf("tts", "file", "default") } ?: "default",
        ivrSpeechRate = prefs.getFloat("ivr_speech_rate", 1f).takeIf { it in 0.5f..2f } ?: 1f,
        ivrPitch = prefs.getFloat("ivr_pitch", 1f).takeIf { it in 0.5f..2f } ?: 1f,
        ivrAudioFileName = prefs.getString("ivr_audio_name", "")!!,
        ivrTtsVoice = prefs.getString("ivr_tts_voice", "")!!,
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
            .putString("allowed_prefixes", s.allowedPrefixes.trim())
            .putString("caller_id_mode", s.callerIdMode.id)
            .putString("sip_caller_id", s.sipCallerId.trim().take(16))
            .putString("busy_policy", s.busyPolicy.name)
            .putString("fallback_extensions", s.fallbackExtensionsCsv.trim())
            .putInt("max_call_attempts", s.maxCallAttempts.coerceIn(1, 4))
            .putString("final_call_action", s.finalCallAction.name)
            .putInt("audio_method", s.audioMethod)
            .putInt("call_access", s.callAccess)
            .putString("sip_stack", s.sipStack.id)
            .putBoolean("server_mode", s.serverMode)
            .putString("ui_lang", s.uiLanguage)
            .putString("ivr_welcome", s.ivrWelcome.take(500))
            .putString("ivr_doctor", s.ivrDoctor.take(500))
            .putString("ivr_reception", s.ivrReception.take(500))
            .putString("ivr_invalid", s.ivrInvalid.take(500))
            .putString("ivr_timeout", s.ivrTimeout.take(500))
            .putString("ivr_source", s.ivrSource.takeIf { it in setOf("tts", "file", "default") } ?: "tts")
            .putFloat("ivr_speech_rate", s.ivrSpeechRate.coerceIn(0.5f, 2f))
            .putFloat("ivr_pitch", s.ivrPitch.coerceIn(0.5f, 2f))
            .putString("ivr_audio_name", s.ivrAudioFileName.take(120))
            .putString("ivr_tts_voice", s.ivrTtsVoice.take(160))
            .apply()
    }

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
