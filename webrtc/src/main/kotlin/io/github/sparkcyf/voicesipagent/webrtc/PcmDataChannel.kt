package io.github.sparkcyf.voicesipagent.webrtc

/**
 * Minimal adapter over a WebRTC DataChannel. Implementations must deliver complete binary messages
 * and must not block while sending a real-time frame.
 */
interface PcmDataChannel {
    val isOpen: Boolean
    fun send(message: ByteArray): Boolean
    fun setMessageHandler(handler: ((ByteArray) -> Unit)?)
}
