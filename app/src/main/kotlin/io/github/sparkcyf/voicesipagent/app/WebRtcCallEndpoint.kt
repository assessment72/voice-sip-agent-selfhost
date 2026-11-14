package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.EndpointStatusListener
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.IncomingCallHandler
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.Party
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One active Telegram WebRTC peer exposed to the existing CallBridge as a normal call endpoint.
 * It does not own the PeerConnection; WebRtcGatewayWorker owns and closes the transport.
 */
internal class WebRtcCallEndpoint(
    private val mediaPort: MediaPort,
    private val sendSignal: (String, JSONObject) -> Unit,
) : CallEndpoint, AutoCloseable {
    override val name: String = "telegram-webrtc"
    @Volatile private var currentStatus = EndpointStatus(true, "WebRTC data channel open")
    override val status: EndpointStatus get() = currentStatus

    private val lock = Any()
    private val statusListeners = CopyOnWriteArrayList<EndpointStatusListener>()
    @Volatile private var incomingHandler: IncomingCallHandler? = null
    @Volatile private var activeLeg: WebRtcCallLeg? = null
    @Volatile private var closed = false

    override fun setIncomingCallHandler(handler: IncomingCallHandler?) {
        incomingHandler = handler
    }

    override fun addStatusListener(listener: EndpointStatusListener) {
        statusListeners.addIfAbsent(listener)
    }

    override fun removeStatusListener(listener: EndpointStatusListener) {
        statusListeners.remove(listener)
    }

    override fun dial(target: String, caller: Party): CallLeg = synchronized(lock) {
        if (closed || !status.ready) return@synchronized failedLeg(target, caller, HangupCause.UNAVAILABLE)
        if (activeLeg != null) return@synchronized failedLeg(target, caller, HangupCause.BUSY)
        val leg = WebRtcCallLeg(
            direction = LegDirection.OUTBOUND,
            remote = Party("telegram", "Telegram WebRTC"),
            target = null,
            mediaPort = mediaPort,
            signal = sendSignal,
            onEnded = ::clearActive,
        )
        activeLeg = leg
        sendSignal("ringing", JSONObject().put("target", target))
        leg
    }

    /** Called by the signaling worker for messages received from the authenticated browser peer. */
    fun onRemoteSignal(type: String, data: JSONObject) {
        when (type) {
            "dial" -> {
                val target = data.optString("target")
                if (!target.matches(Regex("^\\+?[0-9*#]{3,32}$"))) {
                    sendSignal("error", JSONObject().put("reason", "invalid_target"))
                    return
                }
                val leg = synchronized(lock) {
                    if (closed || activeLeg != null) null else WebRtcCallLeg(
                        direction = LegDirection.INBOUND,
                        remote = Party("telegram", "Telegram WebRTC"),
                        target = target,
                        mediaPort = mediaPort,
                        signal = sendSignal,
                        onEnded = ::clearActive,
                    ).also { activeLeg = it }
                }
                if (leg == null) {
                    sendSignal("error", JSONObject().put("reason", "busy"))
                } else {
                    val handler = incomingHandler
                    if (handler == null) leg.hangup(HangupCause.UNAVAILABLE) else handler.onIncomingCall(leg)
                }
            }
            "accepted" -> activeLeg?.connect()
            "hangup" -> activeLeg?.finish(HangupCause.NORMAL)
            "error" -> activeLeg?.finish(HangupCause.UNAVAILABLE)
        }
    }

    private fun failedLeg(target: String, caller: Party, cause: HangupCause): CallLeg =
        WebRtcCallLeg(
            direction = LegDirection.OUTBOUND,
            remote = Party("telegram", "Telegram WebRTC"),
            target = null,
            mediaPort = mediaPort,
            signal = sendSignal,
            onEnded = {},
        ).also { it.finish(cause) }

    private fun clearActive(leg: WebRtcCallLeg) {
        synchronized(lock) { if (activeLeg === leg) activeLeg = null }
    }

    override fun close() {
        if (closed) return
        closed = true
        activeLeg?.finish(HangupCause.UNAVAILABLE)
        mediaPort.let { (it as? AutoCloseable)?.close() }
        currentStatus = EndpointStatus(false, "WebRTC data channel closed")
        statusListeners.forEach { it.onStatusChanged(this, currentStatus) }
        incomingHandler = null
    }

    private inner class WebRtcCallLeg(
        override val direction: LegDirection,
        override val remote: Party,
        override val target: String?,
        override val media: MediaPort?,
        private val signal: (String, JSONObject) -> Unit,
        private val onEnded: (WebRtcCallLeg) -> Unit,
    ) : CallLeg {
        override val id: String = "webrtc-" + java.util.UUID.randomUUID().toString()
        override val kind: String = name
        @Volatile private var currentState = LegState.PROCEEDING
        @Volatile private var cause: HangupCause? = null
        override val state: LegState get() = currentState
        override val endCause: HangupCause? get() = cause
        private val listeners = CopyOnWriteArrayList<CallLeg.Listener>()

        override fun addListener(listener: CallLeg.Listener) { listeners.addIfAbsent(listener) }
        override fun removeListener(listener: CallLeg.Listener) { listeners.remove(listener) }
        override fun ring() { signal("ringing", JSONObject()) }

        override fun answer() {
            if (connect()) signal("accepted", JSONObject())
        }

        fun connect(): Boolean {
            if (currentState == LegState.ENDED || currentState == LegState.CONNECTED) return false
            currentState = LegState.CONNECTED
            listeners.forEach { it.onStateChanged(this, currentState) }
            listeners.forEach { it.onMediaChanged(this, media) }
            return true
        }

        override fun hangup(cause: HangupCause) {
            if (currentState == LegState.ENDED) return
            signal("hangup", JSONObject())
            finish(cause)
        }

        fun finish(cause: HangupCause) {
            if (currentState == LegState.ENDED) return
            this.cause = cause
            currentState = LegState.ENDED
            listeners.forEach { it.onStateChanged(this, currentState) }
            listeners.forEach { it.onMediaChanged(this, null) }
            onEnded(this)
        }

        override fun sendDtmf(digit: Char, durationMs: Int) = Unit
    }

    companion object {
        private val UNUSED = Unit
    }
}
