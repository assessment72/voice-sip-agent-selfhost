package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.AbstractCallLeg
import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.EndpointStatusListener
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Creates one isolated local inference session per SIP call. */
fun interface LocalSpeechEngineFactory {
    fun create(): LocalSpeechEngine?
}

/**
 * CallEndpoint adapter that lets the existing CallBridge connect a SIP leg to an on-device AI
 * session. It is intentionally outbound-only: the bridge dials this endpoint after receiving a
 * SIP call. It never opens a cellular call or a network connection.
 */
class LocalAiEndpoint(
    private val engineFactory: LocalSpeechEngineFactory,
    private val enabled: () -> Boolean,
    private val pcmFormat: PcmFormat = PcmFormat.NARROWBAND,
) : CallEndpoint {
    override val name: String = "local-ai"
    private val listeners = CopyOnWriteArrayList<EndpointStatusListener>()
    override val status: EndpointStatus
        get() = if (enabled()) EndpointStatus(true, "local AI enabled")
        else EndpointStatus(false, "local AI disabled")

    override fun setIncomingCallHandler(handler: io.github.sparkcyf.voicesipagent.voice.IncomingCallHandler?) {
        // Calls are initiated by the SIP endpoint and routed to this endpoint by CallBridge.
    }

    override fun addStatusListener(listener: EndpointStatusListener) { listeners.addIfAbsent(listener) }
    override fun removeStatusListener(listener: EndpointStatusListener) { listeners.remove(listener) }

    override fun dial(target: String, caller: Party): CallLeg {
        val leg = LocalAiLeg(target, caller)
        if (!enabled()) {
            leg.hangup(HangupCause.UNAVAILABLE)
            return leg
        }
        val engine = runCatching { engineFactory.create() }.getOrNull()
        if (engine == null || !engine.isReady) {
            runCatching { engine?.close() }
            leg.hangup(HangupCause.UNAVAILABLE)
            return leg
        }
        val port = LocalAiMediaPort(engine, pcmFormat)
        leg.attach(port)
        return leg
    }

    private class LocalAiLeg(target: String, caller: Party) : AbstractCallLeg(
        id = UUID.randomUUID().toString(),
        kind = "local-ai",
        direction = LegDirection.OUTBOUND,
        remote = caller,
        target = target,
    ) {
        fun attach(port: LocalAiMediaPort) {
            publishMedia(port)
            transition(LegState.PROCEEDING)
            transition(LegState.CONNECTED)
        }

        override fun ring() {}
        override fun answer() {}
        override fun sendDtmf(digit: Char, durationMs: Int) {}
        override fun hangup(cause: HangupCause) {
            if (state == LegState.ENDED) return
            (media as? LocalAiMediaPort)?.close()
            transition(LegState.ENDED, cause)
        }
    }
}
