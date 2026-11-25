package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.CallEndpoint

/**
 * Routes incoming SIP calls to a per-call local AI engine.
 *
 * This is deliberately a separate bridge instance: it must not replace the existing
 * cellular<->SIP bridge. The app may activate it only in an explicit SIP-AI mode and must ensure
 * the SIP endpoint is not simultaneously registered with another incoming-call handler.
 */
class LocalAiSipBridge(
    sipEndpoint: CallEndpoint,
    localAiEndpoint: LocalAiEndpoint,
    assistantTarget: String = "assistant",
    config: GatewayConfig = GatewayConfig(),
    log: (String) -> Unit = {},
) : AutoCloseable {
    private val bridge = CallBridge(
        sideA = sipEndpoint,
        sideB = localAiEndpoint,
        routeAtoB = { assistantTarget },
        routeBtoA = { null },
        config = config,
        log = log,
    )

    fun start() = bridge.start()

    fun stop() = bridge.stop()

    val activeSession: BridgeSession?
        get() = bridge.session

    override fun close() = stop()
}
