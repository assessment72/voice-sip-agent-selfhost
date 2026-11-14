package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import io.github.sparkcyf.voicesipagent.webrtc.WebRtcPcmMediaPort
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.DataChannel
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Authenticated HTTP-signaling/WebRTC peer for one single-use Telegram room invitation.
 * WebRTC media stays on the peer connection; Vercel is used only for signaling and ICE config.
 */
internal class WebRtcGatewayWorker(
    context: Context,
    private val serverUrl: String,
    private val peerToken: String,
    private val onEndpointChanged: (WebRtcCallEndpoint?) -> Unit,
) {
    private val app = context.applicationContext
    private val outgoing = LinkedBlockingQueue<JSONObject>()
    private val stopping = AtomicBoolean(false)
    @Volatile private var endpoint: WebRtcCallEndpoint? = null
    @Volatile private var channelAdapter: AndroidWebRtcDataChannel? = null
    @Volatile private var peer: PeerConnection? = null
    @Volatile private var remoteDescriptionSet = false
    private val pendingCandidates = mutableListOf<IceCandidate>()

    suspend fun run() {
        try {
            val iceServers = fetchIceServers()
            val factory = createFactory()
            val observer = createPeerObserver()
            val configuration = PeerConnection.RTCConfiguration(iceServers)
            peer = factory.createPeerConnection(configuration, observer)
                ?: error("Could not create WebRTC peer connection")

            while (currentCoroutineContext().isActive && !stopping.get()) {
                try {
                    flushOutgoing()
                    val messages = pollMessages()
                    for (message in messages) handleSignal(message)
                    delay(POLL_INTERVAL_MS)
                } catch (e: SecurityException) {
                    throw e
                } catch (e: Exception) {
                    delay(RETRY_DELAY_MS)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("WebRTC session stopped: " + e.javaClass.simpleName)
        } finally {
            stopping.set(true)
            endpoint?.close()
            endpoint = null
            onEndpointChanged(null)
            channelAdapter?.dispose()
            peer?.close()
            peer?.dispose()
            peer = null
        }
    }

    private fun createPeerObserver(): PeerConnection.Observer =
        Proxy.newProxyInstance(
            PeerConnection.Observer::class.java.classLoader,
            arrayOf(PeerConnection.Observer::class.java),
        ) { _, method, args ->
            when (method.name) {
                "onIceCandidate" -> {
                    val candidate = args?.getOrNull(0) as? IceCandidate
                    if (candidate != null) {
                        enqueueJsonSignal("ice-candidate", JSONObject()
                            .put("candidate", JSONObject()
                                .put("sdpMid", candidate.sdpMid)
                                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                                .put("candidate", candidate.sdp)))
                    }
                }
                "onDataChannel" -> {
                    val channel = args?.getOrNull(0) as? DataChannel
                    if (channel != null) attachDataChannel(channel)
                }
                "onIceConnectionChange" -> {
                    val state = args?.getOrNull(0)?.toString()
                    if (state == "FAILED" || state == "CLOSED") stopping.set(true)
                }
            }
            null
        } as PeerConnection.Observer

    private fun attachDataChannel(channel: DataChannel) {
        channelAdapter?.dispose()
        val adapter = AndroidWebRtcDataChannel(channel)
        channelAdapter = adapter
        adapter.setStateListener { open ->
            if (open && endpoint == null && !stopping.get()) {
                val created = WebRtcCallEndpoint(WebRtcPcmMediaPort(adapter), ::enqueueSignal)
                endpoint = created
                onEndpointChanged(created)
            } else if (!open) {
                endpoint?.close()
                endpoint = null
                onEndpointChanged(null)
            }
        }
    }

    private fun enqueueSignal(type: String, target: String?) {
        val data = JSONObject()
        if (target != null) data.put("target", target)
        outgoing.offer(JSONObject().put("type", type).put("data", data))
    }

    private fun enqueueJsonSignal(type: String, data: JSONObject) {
        outgoing.offer(JSONObject().put("type", type).put("data", data))
    }

    private fun flushOutgoing() {
        while (true) {
            val message = outgoing.poll() ?: return
            try {
                postSignal(message)
            } catch (e: Exception) {
                outgoing.offer(message)
                return
            }
        }
    }

    private fun handleSignal(message: JSONObject) {
        val type = message.optString("type")
        val data = message.optJSONObject("data") ?: JSONObject()
        when (type) {
            "offer" -> acceptOffer(data)
            "ice-candidate" -> {
                val item = data.optJSONObject("candidate") ?: return
                val candidate = IceCandidate(
                    item.optString("sdpMid"),
                    item.optInt("sdpMLineIndex"),
                    item.optString("candidate"),
                )
                if (remoteDescriptionSet) peer?.addIceCandidate(candidate) else synchronized(pendingCandidates) { pendingCandidates += candidate }
            }
            "dial", "accepted", "hangup", "error" -> endpoint?.onRemoteSignal(type, data.optString("target").takeIf { it.isNotBlank() })
        }
    }

    private fun acceptOffer(data: JSONObject) {
        val description = data.optJSONObject("description") ?: return
        val sdp = description.optString("sdp")
        if (sdp.isBlank() || peer == null) return
        val remote = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peer?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) = Unit
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                synchronized(pendingCandidates) {
                    pendingCandidates.forEach { peer?.addIceCandidate(it) }
                    pendingCandidates.clear()
                }
                createAnswer()
            }
            override fun onCreateFailure(error: String?) { AppLog.w("WebRTC remote offer rejected") }
            override fun onSetFailure(error: String?) { AppLog.w("WebRTC remote offer setup failed") }
        }, remote)
    }

    private fun createAnswer() {
        peer?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null) return
                peer?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) = Unit
                    override fun onSetSuccess() {
                        enqueueSignal("answer", JSONObject().put("description",
                            JSONObject().put("type", "answer").put("sdp", description.description)))
                    }
                    override fun onCreateFailure(error: String?) { AppLog.w("WebRTC local answer failed") }
                    override fun onSetFailure(error: String?) { AppLog.w("WebRTC local description rejected") }
                }, description)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) { AppLog.w("WebRTC answer creation failed") }
            override fun onSetFailure(error: String?) = Unit
        }, MediaConstraints())
    }

    private fun fetchIceServers(): List<PeerConnection.IceServer> {
        val response = request("GET", "/api/ice")
        val array = response.getJSONArray("iceServers")
        val result = mutableListOf<PeerConnection.IceServer>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val urlsValue = item.opt("urls")
            val urls = when (urlsValue) {
                is JSONArray -> (0 until urlsValue.length()).map { urlsValue.getString(it) }
                is String -> listOf(urlsValue)
                else -> emptyList()
            }
            for (url in urls) {
                val builder = PeerConnection.IceServer.builder(url)
                if (item.has("username")) builder.setUsername(item.getString("username"))
                if (item.has("credential")) builder.setPassword(item.getString("credential"))
                result += builder.createIceServer()
            }
        }
        require(result.isNotEmpty()) { "No ICE servers configured" }
        return result
    }

    private fun pollMessages(): List<JSONObject> {
        val response = request("GET", "/api/poll")
        val array = response.optJSONArray("messages") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun postSignal(message: JSONObject) {
        request("POST", "/api/signal", message)
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val base = serverUrl.trimEnd('/')
        require(base.startsWith("https://")) { "WebRTC server must use HTTPS" }
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Authorization", "Bearer " + peerToken)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val json = stream?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) } ?: JSONObject()
            if (connection.responseCode == 401) throw SecurityException("WebRTC invitation expired or invalid")
            if (connection.responseCode !in 200..299) throw IOException("WebRTC signaling HTTP " + connection.responseCode)
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun createFactory(): PeerConnectionFactory {
        synchronized(FACTORY_LOCK) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions(),
                )
                factoryInitialized = true
            }
        }
        return PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 250L
        private const val RETRY_DELAY_MS = 1_000L
        private val FACTORY_LOCK = Any()
        @Volatile private var factoryInitialized = false
    }
}
