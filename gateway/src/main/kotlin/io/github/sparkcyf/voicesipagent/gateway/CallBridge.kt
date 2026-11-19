package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import io.github.sparkcyf.voicesipagent.voice.redactNumber
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One inbound leg tied to the outbound leg we created for it. */
class BridgeSession internal constructor(
    val id: Int,
    /** Name of the endpoint the call came from (e.g. "cellular" -> SIP). */
    val origin: String,
    val inbound: CallLeg,
) {
    var outbound: CallLeg? = null
        internal set
    val startedAtMs: Long = System.currentTimeMillis()
    var connectedAtMs: Long? = null
        internal set
    var media: MediaBridge? = null
        internal set
    var endedAtMs: Long? = null
        internal set
    internal var answerTimer: ScheduledFuture<*>? = null
    internal var inboundAnswerRequested = false
    internal var mediaTimer: ScheduledFuture<*>? = null
    internal val recordings = mutableListOf<PcmWavRecorder>()
    val ivr = ClinicIvrSession()
    internal var localIvr = false
    internal var ivrPromptSamples: ShortArray? = null
    internal var ivrEmployees: List<CallCenterEmployee> = emptyList()
    internal var ivrPromptAttempts = 0
    internal var ivrInputTimer: ScheduledFuture<*>? = null
    internal var ivrPlayback: MediaBridge? = null
    internal var ivrPort: IvrMediaPort? = null
    internal var recordedInboundPort: MediaPort? = null

    override fun toString() =
        "session#$id[$origin ${inbound.kind}:${inbound.state} -> ${outbound?.kind}:${outbound?.state}]"
}

fun interface BridgeListener {
    fun onSessionChanged(session: BridgeSession)
}

/**
 * Bridges calls between two [CallEndpoint]s (e.g. cellular and SIP). The bridge only ever sees
 * the abstract leg interface, so either side can be swapped for another voice backend.
 *
 * Signalling rules (inbound leg A from endpoint X, outbound leg B on endpoint Y):
 *  - A arrives -> dial B; B proceeding/ringing -> A.ring(); B connected -> A.answer().
 *  - Media is cross-connected once both legs expose a port.
 *  - Either side ending ends the other; failures before answer propagate their cause.
 *  - DTMF from one leg is replayed on the other.
 */
class CallBridge(
    private val sideA: CallEndpoint,
    private val sideB: CallEndpoint,
    private val routeAtoB: RouteTarget,
    private val routeBtoA: RouteTarget,
    private val config: GatewayConfig,
    private val dialPolicyForA: DialPolicy = DialPolicy { null },
    private val recordingDirectory: File? = null,
    private val log: (String) -> Unit = {},
    private val localCellularIvr: Boolean = false,
    private val ivrEmployees: () -> List<CallCenterEmployee> = { emptyList() },
    private val ivrPromptSamples: () -> ShortArray? = { null },
    private val ivrInputTimeoutMs: Long = 10_000,
) {
    private val executor: ScheduledExecutorService =
        ScheduledThreadPoolExecutor(1) { r -> Thread(r, "call-bridge").apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
            continueExistingPeriodicTasksAfterShutdownPolicy = false
        }
    private val listeners = CopyOnWriteArrayList<BridgeListener>()
    private var nextId = 1

    @Volatile var session: BridgeSession? = null
        private set

    fun start() {
        sideA.setIncomingCallHandler { leg -> post { onInbound(leg, sideA, sideB, routeAtoB) } }
        sideB.setIncomingCallHandler { leg -> post { onInbound(leg, sideB, sideA, routeBtoA) } }
    }

    fun stop() {
        sideA.setIncomingCallHandler(null)
        sideB.setIncomingCallHandler(null)
        post { session?.let { endSession(it, HangupCause.NORMAL) } }
        executor.shutdown()
    }

    fun addListener(l: BridgeListener) = listeners.addIfAbsent(l)
    fun removeListener(l: BridgeListener) = listeners.remove(l)

    /** Starts a call from one bridge side to the other (used by authenticated SIP commands). */
    fun startOutgoing(from: CallEndpoint, fromTarget: String, to: CallEndpoint, toTarget: String, caller: Party): Boolean {
        if (from !== sideA && from !== sideB) return false
        if (to !== sideA && to !== sideB) return false
        post {
            if (session != null) {
                log("cannot start outgoing call: bridge busy")
                return@post
            }
            if (to === sideA) {
                val veto = dialPolicyForA.check(toTarget)
                    ?: if (config.allowedDialPrefixes.isNotEmpty() && config.allowedDialPrefixes.none(toTarget::startsWith)) HangupCause.FORBIDDEN else null
                if (veto != null) {
                    log("refusing outgoing call to ${redactNumber(toTarget)}: $veto")
                    return@post
                }
            }
            val inbound = from.dial(fromTarget, caller)
            val s = BridgeSession(nextId++, from.name, inbound)
            session = s
            inbound.addListener(LegWatcher(s, inbound = true))
            val outbound = to.dial(toTarget, caller)
            s.outbound = outbound
            outbound.addListener(LegWatcher(s, inbound = false))
            s.answerTimer = executor.schedule({
                if (session === s && s.connectedAtMs == null) endSession(s, HangupCause.NO_ANSWER)
            }, config.answerTimeoutMs, TimeUnit.MILLISECONDS)
            reevaluate(s)
            notifyChanged(s)
        }
        return true
    }

    /** Blocks until every task posted so far has run (tests). */
    internal fun drain() {
        if (!executor.isShutdown) executor.submit {}.get()
    }

    private fun post(block: () -> Unit) {
        if (executor.isShutdown) return
        executor.execute {
            try {
                block()
            } catch (t: Throwable) {
                log("bridge task failed: $t")
            }
        }
    }

    private fun onInbound(leg: CallLeg, from: CallEndpoint, to: CallEndpoint, route: RouteTarget) {
        val busy = session
        if (busy != null) {
            log("busy with $busy; ${config.busyPolicy} new ${from.name} call")
            if (config.busyPolicy == BusyPolicy.REJECT || from === sideB) leg.hangup(HangupCause.BUSY)
            return
        }
        val useLocalIvr = localCellularIvr && from === sideA && from.name == "cellular"
        val target = if (useLocalIvr) null else route.targetFor(leg)
        if (!useLocalIvr && target.isNullOrEmpty()) {
            log("no route for ${from.name} call to ${redactNumber(leg.target)}")
            leg.hangup(HangupCause.NOT_FOUND)
            return
        }
        if (!useLocalIvr && to === sideA) {
            val dialTarget = target!!
            val veto = dialPolicyForA.check(dialTarget)
                ?: if (config.allowedDialPrefixes.isNotEmpty() && config.allowedDialPrefixes.none(dialTarget::startsWith)) HangupCause.FORBIDDEN else null
            if (veto != null) {
                log("refusing to dial ${redactNumber(dialTarget)} on ${to.name}: $veto")
                leg.hangup(veto)
                return
            }
        }
        val prompt = if (useLocalIvr) runCatching { ivrPromptSamples() }.getOrNull() else null
        val employees = if (useLocalIvr) runCatching { ivrEmployees() }.getOrDefault(emptyList()).filter { it.enabled } else emptyList()
        if (useLocalIvr && (prompt == null || prompt.isEmpty() || employees.none { it.extension == "1" } || employees.none { it.extension == "2" } ||
                runCatching { CallCenterPolicy(employees) }.isFailure)) {
            log("local IVR not ready: configure enabled employees on extensions 1 and 2 and verify prompt audio")
            leg.hangup(HangupCause.UNAVAILABLE)
            return
        }
        val s = BridgeSession(nextId++, from.name, leg)
        session = s
        s.localIvr = useLocalIvr
        if (useLocalIvr) {
            s.ivrPromptSamples = prompt
            s.ivrEmployees = employees
        }
        leg.addListener(LegWatcher(s, inbound = true))
        if (useLocalIvr) {
            log("$s: answering cellular caller for local IVR")
            s.inboundAnswerRequested = true
            s.answerTimer = executor.schedule({
                if (session === s && s.outbound == null) endSession(s, HangupCause.NO_ANSWER)
            }, config.answerTimeoutMs, TimeUnit.MILLISECONDS)
            leg.answer()
            reevaluate(s)
            notifyChanged(s)
            return
        }
        log("$s: ${from.name} ${redactNumber(leg.remote.number)} -> ${to.name} ${redactNumber(target)}")
        val out = to.dial(target!!, leg.remote)
        s.outbound = out
        out.addListener(LegWatcher(s, inbound = false))
        s.answerTimer = executor.schedule({
            if (session === s && s.connectedAtMs == null) {
                log("$s: answer timeout")
                endSession(s, HangupCause.NO_ANSWER)
            }
        }, config.answerTimeoutMs, TimeUnit.MILLISECONDS)
        // The outbound leg may have finished synchronously (e.g. placement refused).
        reevaluate(s)
        notifyChanged(s)
    }

    private inner class LegWatcher(val s: BridgeSession, val inbound: Boolean) : CallLeg.Listener {
        override fun onStateChanged(leg: CallLeg, state: LegState) = post { reevaluate(s); notifyChanged(s) }
        override fun onMediaChanged(leg: CallLeg, media: MediaPort?) = post { reevaluate(s) }
        override fun onDtmf(leg: CallLeg, digit: Char, durationMs: Int) = post {
            if (s.localIvr && s.outbound == null) {
                handleIvrDigit(s, digit)
                return@post
            }
            val other = if (inbound) s.outbound else s.inbound
            if (other?.state == LegState.CONNECTED) other.sendDtmf(digit, durationMs)
        }
    }

    /** Drives the session from the current leg states; idempotent. */
    private fun reevaluate(s: BridgeSession) {
        if (session !== s) return
        val a = s.inbound
        val b = s.outbound
        if (a.state == LegState.ENDED || b?.state == LegState.ENDED) {
            val cause = if (a.state == LegState.ENDED) {
                if (s.connectedAtMs == null) HangupCause.CANCELLED else HangupCause.NORMAL
            } else {
                if (s.connectedAtMs == null) b?.endCause ?: HangupCause.UNAVAILABLE else HangupCause.NORMAL
            }
            endSession(s, cause)
            return
        }
        if (s.localIvr && b == null) {
            if (a.state == LegState.CONNECTED && a.media != null && s.ivrPlayback == null) startIvrPlayback(s)
            if (a.media == null && s.ivrPlayback != null) {
                s.ivrPlayback?.stop()
                s.ivrPlayback = null
                s.ivrPort = null
            }
            return
        }
        if (b == null) return
        if ((b.state == LegState.PROCEEDING || b.state == LegState.RINGING) && a.state == LegState.PROCEEDING) a.ring()
        if (b.state == LegState.CONNECTED && a.state < LegState.CONNECTED && !s.inboundAnswerRequested) {
            // Answer is asynchronous: media callbacks can arrive before acceptance is confirmed.
            s.inboundAnswerRequested = true
            a.answer()
        }
        if (a.state == LegState.CONNECTED && b.state == LegState.CONNECTED) {
            if (s.connectedAtMs == null) {
                s.connectedAtMs = System.currentTimeMillis()
                s.answerTimer?.cancel(false)
                log("$s: both legs connected")
                s.mediaTimer = executor.schedule({
                    if (session === s && s.media == null) {
                        log("$s: media did not come up")
                        endSession(s, HangupCause.INTERNAL_ERROR)
                    }
                }, config.mediaSetupTimeoutMs, TimeUnit.MILLISECONDS)
            }
            val ma = a.media
            val mb = b.media
            if (s.media == null && ma != null && mb != null) {
                if (ma.format != mb.format) {
                    log("$s: incompatible media formats ${ma.format} / ${mb.format}")
                    endSession(s, HangupCause.INTERNAL_ERROR)
                    return
                }
                val recordedA = s.recordedInboundPort ?: recordingPort(s, ma, "inbound").also { s.recordedInboundPort = it }
                val recordedB = recordingPort(s, mb, "outbound")
                s.media = MediaBridge(recordedA, recordedB).also { it.start() }
                s.mediaTimer?.cancel(false)
                log("$s: media bridged")
            } else if (s.media != null && (ma == null || mb == null)) {
                s.media?.stop()
                s.media = null
            }
        }
    }

    private fun startIvrPlayback(s: BridgeSession) {
        if (session !== s || !s.localIvr || s.outbound != null) return
        val callerPort = s.inbound.media ?: return
        val prompt = s.ivrPromptSamples ?: return endSession(s, HangupCause.UNAVAILABLE)
        if (callerPort.format != promptFormat) {
            log("$s: cellular media format ${callerPort.format} is incompatible with IVR prompt $promptFormat")
            endSession(s, HangupCause.INTERNAL_ERROR)
            return
        }
        s.answerTimer?.cancel(false)
        s.answerTimer = null
        s.ivrInputTimer?.cancel(false)
        s.ivrInputTimer = null
        s.ivrPromptAttempts++
        s.ivr.retry()
        val playbackPort = IvrMediaPort(
            prompt = prompt,
            onDigit = { digit -> post { handleIvrDigit(s, digit) } },
            onPromptFinished = { post { onIvrPromptFinished(s) } },
        )
        s.ivrPort = playbackPort
        val recorded = s.recordedInboundPort ?: recordingPort(s, callerPort, "inbound").also { s.recordedInboundPort = it }
        s.ivrPlayback = try {
            MediaBridge(recorded, playbackPort).also { it.start() }
        } catch (e: Exception) {
            log("$s: IVR media failed: ${e.message}")
            endSession(s, HangupCause.INTERNAL_ERROR)
            return
        }
        log("$s: IVR prompt attempt ${s.ivrPromptAttempts} started")
    }

    private fun onIvrPromptFinished(s: BridgeSession) {
        if (session !== s || !s.localIvr || s.outbound != null) return
        s.ivr.announcementFinished()
        val playback = s.ivrPlayback
        log("$s: IVR prompt ended; frames=${playback?.frames ?: 0}, promptPeak=${playback?.peakB ?: 0} (remote audibility not verified)")
        s.ivrInputTimer?.cancel(false)
        s.ivrInputTimer = executor.schedule({
            if (session === s && s.outbound == null) retryIvr(s, "no extension entered")
        }, ivrInputTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun handleIvrDigit(s: BridgeSession, digit: Char) {
        if (session !== s || !s.localIvr || s.outbound != null) return
        when (val action = s.ivr.digitReceived(digit)) {
            is ClinicIvrSession.Action.InvalidDigit -> {
                log("$s: invalid IVR digit ${action.digit}")
                retryIvr(s, "invalid extension")
            }
            is ClinicIvrSession.Action.ConnectEmployee -> {
                s.ivrInputTimer?.cancel(false)
                s.ivrInputTimer = null
                val decision = runCatching { ClinicCallRouter(CallCenterPolicy(s.ivrEmployees)).route(digit) }
                    .getOrNull()
                val employeeId = (decision as? RouteDecision.Connect)?.employeeId
                val employee = employeeId?.let { id -> s.ivrEmployees.firstOrNull { it.id == id } }
                if (employee == null) {
                    log("$s: no enabled employee for extension ${action.extension}")
                    retryIvr(s, "extension unavailable")
                    return
                }
                s.ivr.complete()
                stopIvrPlayback(s)
                val target = when (val destination = employee.destination) {
                    is EmployeeDestination.Sip -> destination.uri
                    is EmployeeDestination.Mobile -> destination.e164
                }
                val outbound = runCatching { sideB.dial(target, s.inbound.remote) }.getOrElse {
                    log("$s: employee dial failed: ${it.javaClass.simpleName}")
                    endSession(s, HangupCause.UNAVAILABLE)
                    return
                }
                s.outbound = outbound
                outbound.addListener(LegWatcher(s, inbound = false))
                s.answerTimer?.cancel(false)
                s.answerTimer = executor.schedule({
                    if (session === s && s.connectedAtMs == null) {
                        log("$s: employee ${employee.id} did not answer")
                        endSession(s, HangupCause.NO_ANSWER)
                    }
                }, employee.ringSeconds.toLong(), TimeUnit.SECONDS)
                log("$s: extension ${action.extension} -> employee ${employee.id}, dialing ${sideB.name}")
                reevaluate(s)
                notifyChanged(s)
            }
        }
    }

    private fun retryIvr(s: BridgeSession, reason: String) {
        if (session !== s || s.outbound != null) return
        s.ivrInputTimer?.cancel(false)
        s.ivrInputTimer = null
        if (s.ivrPromptAttempts >= MAX_IVR_PROMPTS) {
            log("$s: IVR ended after $reason")
            endSession(s, HangupCause.NO_ANSWER)
            return
        }
        stopIvrPlayback(s)
        startIvrPlayback(s)
    }

    private fun stopIvrPlayback(s: BridgeSession) {
        s.ivrPlayback?.stop()
        s.ivrPlayback = null
        s.ivrPort = null
    }

    private fun endSession(s: BridgeSession, cause: HangupCause) {
        if (session !== s) return
        session = null
        s.endedAtMs = System.currentTimeMillis()
        s.answerTimer?.cancel(false)
        s.mediaTimer?.cancel(false)
        s.ivrInputTimer?.cancel(false)
        s.ivrInputTimer = null
        stopIvrPlayback(s)
        s.media?.stop()
        s.media = null
        s.recordings.toList().forEach { runCatching { it.close() } }
        s.recordings.clear()
        if (s.inbound.state != LegState.ENDED) s.inbound.hangup(cause)
        s.outbound?.let { if (it.state != LegState.ENDED) it.hangup(if (cause == HangupCause.CANCELLED) HangupCause.CANCELLED else cause) }
        log("$s ended: $cause")
        notifyChanged(s)
    }

    private fun notifyChanged(s: BridgeSession) = listeners.forEach { it.onSessionChanged(s) }

    private fun recordingPort(s: BridgeSession, port: MediaPort, direction: String): MediaPort {
        val dir = recordingDirectory ?: return port
        dir.mkdirs()
        val file = File(dir, "call-${s.id}-$direction-${s.startedAtMs}.wav")
        val recorder = PcmWavRecorder(file, port.format.sampleRate)
        s.recordings += recorder
        return RecordingMediaPort(port, recorder)
    }

    private companion object {
        val promptFormat = PcmFormat.NARROWBAND
        const val MAX_IVR_PROMPTS = 2
    }
}
