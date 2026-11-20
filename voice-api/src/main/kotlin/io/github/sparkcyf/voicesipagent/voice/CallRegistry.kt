package io.github.sparkcyf.voicesipagent.voice

import java.time.Clock
import java.time.Instant
import java.util.LinkedHashMap

data class ManagedCall(
    val id: String,
    val kind: String,
    val direction: LegDirection,
    val remoteNumber: String?,
    val displayName: String?,
    val target: String?,
    val state: LegState,
    val endCause: HangupCause?,
    val observedAt: Instant,
)

/**
 * Thread-safe index of CallLeg instances for a local control surface.
 * Ended calls remain in a bounded history so clients can inspect the final state.
 */
class CallRegistry(
    private val clock: Clock = Clock.systemUTC(),
    private val maxHistory: Int = 100,
) {
    private val calls = LinkedHashMap<String, ManagedCall>()
    private val legs = LinkedHashMap<String, CallLeg>()

    init { require(maxHistory > 0) }

    private val listener = object : CallLeg.Listener {
        override fun onStateChanged(leg: CallLeg, state: LegState) {
            synchronized(this@CallRegistry) { update(leg) }
        }

        override fun onMediaChanged(leg: CallLeg, media: MediaPort?) {
            synchronized(this@CallRegistry) { update(leg) }
        }
    }

    @Synchronized
    fun track(leg: CallLeg): ManagedCall {
        val existing = legs[leg.id]
        require(existing == null || existing === leg) { "A different call already uses id ${leg.id}" }
        if (existing == null) {
            legs[leg.id] = leg
            leg.addListener(listener)
        }
        return update(leg)
    }

    @Synchronized
    fun get(callId: String): ManagedCall? = calls[callId]

    @Synchronized
    fun active(): List<ManagedCall> = calls.values.filter { it.state != LegState.ENDED }

    @Synchronized
    fun history(limit: Int = maxHistory): List<ManagedCall> {
        require(limit > 0)
        return calls.values.toList().takeLast(limit)
    }

    @Synchronized
    fun forgetEnded(callId: String): Boolean {
        val call = calls[callId] ?: return false
        if (call.state != LegState.ENDED) return false
        legs.remove(callId)?.removeListener(listener)
        calls.remove(callId)
        return true
    }

    @Synchronized
    fun clear() {
        legs.values.forEach { it.removeListener(listener) }
        legs.clear()
        calls.clear()
    }

    private fun update(leg: CallLeg): ManagedCall {
        val snapshot = ManagedCall(
            id = leg.id,
            kind = leg.kind,
            direction = leg.direction,
            remoteNumber = leg.remote.number,
            displayName = leg.remote.displayName,
            target = leg.target,
            state = leg.state,
            endCause = leg.endCause,
            observedAt = clock.instant(),
        )
        calls[leg.id] = snapshot
        while (calls.size > maxHistory) {
            val oldestEnded = calls.entries.firstOrNull { it.value.state == LegState.ENDED } ?: break
            legs.remove(oldestEnded.key)?.removeListener(listener)
            calls.remove(oldestEnded.key)
        }
        return snapshot
    }
}
