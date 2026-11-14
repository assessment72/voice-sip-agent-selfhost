package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.webrtc.PcmDataChannel
import livekit.org.webrtc.DataChannel
import java.nio.ByteBuffer

/**
 * Bridges the native WebRTC DataChannel API to the JVM-testable PCM media port.
 * The PeerConnection owner remains responsible for closing the underlying DataChannel.
 */
internal class AndroidWebRtcDataChannel(
    private val delegate: DataChannel,
) : PcmDataChannel {
    @Volatile
    private var messageHandler: ((ByteArray) -> Unit)? = null

    private val observer = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() { stateListener?.invoke(isOpen) }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (!buffer.binary) return
            val source = buffer.data
            val payload = ByteArray(source.remaining())
            source.get(payload)
            messageHandler?.invoke(payload)
        }
    }

    init {
        delegate.registerObserver(observer)
    }

    override val isOpen: Boolean
        get() = delegate.state() == DataChannel.State.OPEN

    override fun send(message: ByteArray): Boolean {
        if (!isOpen) return false
        return delegate.send(DataChannel.Buffer(ByteBuffer.wrap(message), true))
    }

    override fun setMessageHandler(handler: ((ByteArray) -> Unit)?) {
        messageHandler = handler
    }

    /** Detaches the observer without taking ownership of closing the PeerConnection's channel. */
    fun dispose() {
        messageHandler = null
        delegate.unregisterObserver()
    }
}
