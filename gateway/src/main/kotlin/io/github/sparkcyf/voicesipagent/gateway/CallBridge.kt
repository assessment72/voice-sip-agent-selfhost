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
    /** Monotonically increases whenever an employee call attempt is replaced. */
    @Volatile var attemptId: Long = 0
        internal set
    @Volatile var selectedEmployeeId: String? = null
        internal set
    @Volatile var employeeAnswered: Boolean = false
        internal set
    internal val attemptedEmployeeIds = linkedSetOf<String>()
    /** Read-only failure history exposed for app diagnostics and local call history. */
    val attemptFailures = CopyOnWriteArrayList<EmployeeAttemptFailure>()
    internal var outboundWatcher: CallLeg.Listener? = null
    val startedAtMs: Long = System.currentTimeMillis()
    @Volatile var connectedAtMs: Long? = null
        internal set
    var media: MediaBridge? = null
        internal set
    @Volatile var endedAtMs: Long? = null
        internal set
    internal var answerTimer: ScheduledFuture<*>? = null
    internal var inboundAnswerRequested = false
    internal var mediaTimer: ScheduledFuture<*>? = null
    internal val recordings = mutableListOf<PcmWavRecorder>()
    val ivr = ClinicIvrSession()
    internal var localIvr = false
    internal var ivrPromptSamples: ShortArray? = null
    /** Employees selected for this call's local IVR fallback, for diagnostics and history. */
    var ivrEmployees: List<CallCenterEmployee> = emptyList()
    internal var ivrPromptAttempts = 0
    internal var ivrInputTimer: ScheduledFuture<*>? = null
    internal var ivrPlayback: MediaBridge? = null
    internal var ivrPort: IvrMediaPort? = null
    internal var recordedInboundPort: MediaPort? = null
    @Volatile var endCause: HangupCause? = null
        internal set

    override fun toString() =
        "session#$id[$origin ${inbound.kind}:${inbound.state} -> ${outbound?.kind}:${outbound?.state}]"
}

data class EmployeeAttemptFailure(
    val attemptId: Long,
    val employeeId: String,
    val extension: String,
    val cause: HangupCause,
    val sipResponseCode: Int? = null,
)

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
    private val fallbackPolicy: CallFallbackPolicy = CallFallbackPolicy(),
    private val audioDiagnostics: () -> String = { "unavailable" },
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
            val watcher = LegWatcher(s, inbound = false, attemptId = s.attemptId)
            s.outboundWatcher = watcher
            outbound.addListener(watcher)
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

    private inner class LegWatcher(
        val s: BridgeSession,
        val inbound: Boolean,
        private val attemptId: Long? = null,
    ) : CallLeg.Listener {
        override fun onStateChanged(leg: CallLeg, state: LegState) = post {
            if (!isCurrentLeg(s, leg, inbound, attemptId)) return@post
            if (!inbound && state == LegState.CONNECTED) s.employeeAnswered = true
            reevaluate(s); notifyChanged(s)
        }
        override fun onMediaChanged(leg: CallLeg, media: MediaPort?) = post {
            if (isCurrentLeg(s, leg, inbound, attemptId)) reevaluate(s)
        }
        override fun onDtmf(leg: CallLeg, digit: Char, durationMs: Int) = post {
            if (!isCurrentLeg(s, leg, inbound, attemptId)) return@post
            if (s.localIvr && s.outbound == null) {
                handleIvrDigit(s, digit)
                return@post
            }
            val other = if (inbound) s.outbound else s.inbound
            if (other?.state == LegState.CONNECTED) other.sendDtmf(digit, durationMs)
        }
    }

    private fun isCurrentLeg(s: BridgeSession, leg: CallLeg, inbound: Boolean, attemptId: Long?): Boolean =
        session === s && if (inbound) leg === s.inbound else
            leg === s.outbound && (attemptId == null || attemptId == s.attemptId)

    /** Drives the session from the current leg states; idempotent. */
    private fun reevaluate(s: BridgeSession) {
        if (session !== s) return
        val a = s.inbound
        val b = s.outbound
        if (a.state == LegState.ENDED) {
            endSession(s, if (s.connectedAtMs == null) HangupCause.CANCELLED else HangupCause.NORMAL)
            return
        }
        if (b?.state == LegState.ENDED) {
            if (s.localIvr && s.selectedEmployeeId != null && !s.employeeAnswered && s.connectedAtMs == null) {
                handleEmployeeAttemptFailure(s, s.attemptId, b.endCause ?: HangupCause.UNAVAILABLE)
                return
            }
            endSession(s, if (s.connectedAtMs == null) b.endCause ?: HangupCause.UNAVAILABLE else HangupCause.NORMAL)
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
            s.employeeAnswered = true
            if (s.localIvr) s.ivr.complete()
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
        val ivrPort = s.ivrPort
        val audioRoute = runCatching { audioDiagnostics() }.getOrDefault("unavailable")
        log("$s: IVR result=software framesProduced=${ivrPort?.framesProduced ?: 0} " +
            "callerAudioChannelWrites=${playback?.writesToA ?: 0} " +
            "callerAudioWriteErrors=${playback?.writeErrorsToA ?: 0} " +
            "lastWriteError=${playback?.lastWriteErrorToA ?: "none"} route=[$audioRoute]; " +
            "frames were sent in software; remote audibility was not verified")
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
                val policyAttempts = s.ivrEmployees.size.coerceIn(1, 4)
                val decision = runCatching {
                    ClinicCallRouter(CallCenterPolicy(s.ivrEmployees, maxAttempts = policyAttempts))
                        .route(digit, s.attemptedEmployeeIds)
                }
                    .getOrNull()
                val employeeId = (decision as? RouteDecision.Connect)?.employeeId
                val employee = employeeId?.let { id -> s.ivrEmployees.firstOrNull { it.id == id } }
                if (employee == null) {
                    log("$s: no enabled employee for extension ${action.extension}")
                    retryIvr(s, "extension unavailable")
                    return
                }
                startEmployeeAttempt(s, employee)
            }
        }
    }

    private fun startEmployeeAttempt(s: BridgeSession, employee: CallCenterEmployee) {
        if (session !== s || s.inbound.state == LegState.ENDED || s.connectedAtMs != null) return
        if (s.attemptedEmployeeIds.contains(employee.id) || s.attemptedEmployeeIds.size >= fallbackPolicy.maxAttempts) {
            finishFallback(s, s.attemptFailures.lastOrNull()?.cause ?: HangupCause.UNAVAILABLE)
            return
        }
        s.ivrInputTimer?.cancel(false)
        s.ivrInputTimer = null
        stopIvrPlayback(s)
        val target = when (val destination = employee.destination) {
            is EmployeeDestination.Sip -> destination.uri
            is EmployeeDestination.Mobile -> destination.e164
        }
        val thisAttempt = ++s.attemptId
        s.selectedEmployeeId = employee.id
        s.attemptedEmployeeIds += employee.id
        s.employeeAnswered = false
        s.answerTimer?.cancel(false)
        val outbound = try {
            sideB.dial(target, s.inbound.remote)
        } catch (_: Throwable) {
            log("$s: employee extension ${employee.extension} dial setup failed")
            handleEmployeeAttemptFailure(s, thisAttempt, HangupCause.UNAVAILABLE)
            return
        }
        if (session !== s || s.attemptId != thisAttempt || s.inbound.state == LegState.ENDED) {
            outbound.hangup(HangupCause.CANCELLED)
            return
        }
        s.outbound = outbound
        val watcher = LegWatcher(s, inbound = false, attemptId = thisAttempt)
        s.outboundWatcher = watcher
        outbound.addListener(watcher)
        s.answerTimer = executor.schedule({
            if (session === s && s.attemptId == thisAttempt && s.connectedAtMs == null && !s.employeeAnswered) {
                log("$s: employee extension ${employee.extension} ring timeout")
                handleEmployeeAttemptFailure(s, thisAttempt, HangupCause.NO_ANSWER)
            }
        }, employee.ringSeconds.toLong(), TimeUnit.SECONDS)
        log("$s: extension ${employee.extension} attempt=$thisAttempt dialing ${sideB.name}")
        reevaluate(s)
        notifyChanged(s)
    }

    private fun handleEmployeeAttemptFailure(s: BridgeSession, attemptId: Long, cause: HangupCause) {
        if (session !== s || s.attemptId != attemptId || s.connectedAtMs != null || s.employeeAnswered) return
        if (s.inbound.state == LegState.ENDED) {
            endSession(s, HangupCause.CANCELLED)
            return
        }
        val employee = s.ivrEmployees.firstOrNull { it.id == s.selectedEmployeeId }
        val previous = s.outbound
        val sipCode = previous?.endCode
        if (employee != null) s.attemptFailures += EmployeeAttemptFailure(
            attemptId, employee.id, employee.extension, cause, sipResponseCode = sipCode,
        )
        s.outboundWatcher?.let { previous?.removeListener(it) }
        s.outboundWatcher = null
        s.answerTimer?.cancel(false)
        s.answerTimer = null
        // Invalidate this attempt before hanging it up: any reentrant or queued callback is stale.
        s.attemptId++
        s.outbound = null
        if (previous != null && previous.state != LegState.ENDED) previous.hangup(HangupCause.CANCELLED)
        log("$s: employee extension ${employee?.extension ?: "?"} attempt failed: " +
            (sipCode?.let { "SIP $it " } ?: "") + cause)

        val retryable = cause == HangupCause.BUSY || cause == HangupCause.NO_ANSWER || cause == HangupCause.UNAVAILABLE
        if (!retryable) {
            endSession(s, cause)
            return
        }
        if (s.attemptedEmployeeIds.size < fallbackPolicy.maxAttempts) {
            val fallback = fallbackPolicy.nextFallback(s.ivrEmployees, s.attemptedEmployeeIds)
            if (fallback != null) {
                startEmployeeAttempt(s, fallback)
                return
            }
        }
        finishFallback(s, cause)
    }

    private fun finishFallback(s: BridgeSession, cause: HangupCause) {
        if (session !== s || s.inbound.state == LegState.ENDED) {
            if (session === s) endSession(s, HangupCause.CANCELLED)
            return
        }
        if (fallbackPolicy.finalAction == FinalCallAction.RETURN_TO_IVR_ONCE &&
            s.ivrPromptAttempts < MAX_IVR_PROMPTS && s.outbound == null) {
            log("$s: fallback list exhausted after $cause; replaying IVR once")
            s.ivr.retry()
            startIvrPlayback(s)
            notifyChanged(s)
        } else {
            log("$s: fallback list exhausted; final action ends call ($cause)")
            endSession(s, cause)
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
        s.outbound?.let { leg ->
            s.outboundWatcher?.let(leg::removeListener)
            if (leg.state != LegState.ENDED) leg.hangup(if (cause == HangupCause.CANCELLED) HangupCause.CANCELLED else cause)
        }
        s.endCause = cause
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
