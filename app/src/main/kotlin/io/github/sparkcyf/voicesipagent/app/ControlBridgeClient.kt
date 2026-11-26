package io.github.sparkcyf.voicesipagent.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

/**
 * Minimal device-side transport for the Voicx control plane.
 *
 * Calls are synchronous by design: invoke only from Dispatchers.IO/a worker thread.
 * This class does not start/stop GatewayService or issue SIP calls. Command execution
 * remains the responsibility of a separately reviewed runtime adapter.
 */
internal class ControlBridgeClient(
    baseUrl: String,
    private val deviceId: String,
    private val deviceToken: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 8_000,
) {
    private val base: String

    init {
        val uri = URI(baseUrl.trim().trimEnd('/'))
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Control bridge must use HTTPS (use a private VPN with HTTPS termination)"
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Invalid control bridge URL"
        }
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid device id" }
        require(deviceToken.length >= 32) { "Device token must be at least 32 characters" }
        require(connectTimeoutMs in 1..30_000 && readTimeoutMs in 1..30_000) { "Invalid timeout" }
        base = uri.toString().trimEnd('/')
    }

    fun heartbeat(
        battery: Int?, charging: Boolean?, serviceState: String, appVersion: String,
        sipReady: Boolean = false, sipDetail: String = "", activeCall: Boolean = false,
        takeover: Boolean = false, takeoverPending: Boolean = false,
    ): JSONObject {
        require(battery == null || battery in 0..100)
        require(serviceState in setOf("running", "stopped", "starting", "unknown"))
        val body = JSONObject()
            .putIfNotNull("battery", battery)
            .putIfNotNull("charging", charging)
            .put("serviceState", serviceState)
            .put("appVersion", appVersion.take(40))
            .put("sipReady", sipReady)
            .put("sipDetail", sipDetail.take(200))
            .put("activeCall", activeCall)
            .put("takeover", takeover)
            .put("takeoverPending", takeoverPending)
        return request("POST", "/api/v1/devices/$deviceId/heartbeat", body)
    }

    fun pollCommands(): List<JSONObject> {
        val response = request("GET", "/api/v1/devices/$deviceId/commands/next")
        val commands = response.optJSONArray("commands") ?: JSONArray()
        return (0 until commands.length()).mapNotNull { commands.optJSONObject(it) }
    }

    fun reportResult(commandId: String, status: String, result: JSONObject? = null, error: String? = null, leaseToken: String? = null): JSONObject {
        require(runCatching { UUID.fromString(commandId) }.isSuccess) { "Invalid command id" }
        require(status in setOf("running", "succeeded", "failed")) { "Invalid command status" }
        val body = JSONObject().put("status", status)
        if (result != null) body.put("result", result)
        if (error != null) body.put("error", error.take(500))
        if (leaseToken != null) body.put("leaseToken", leaseToken)
        return request("POST", "/api/v1/devices/$deviceId/commands/$commandId/result", body)
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $deviceToken")
            setRequestProperty("X-Voicx-Device-Id", deviceId)
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ControlBridgeException(code, text.take(500))
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.putIfNotNull(key: String, value: Any?): JSONObject =
        if (value == null) this else put(key, value)
}

internal class ControlBridgeException(val httpStatus: Int, detail: String) :
    IllegalStateException("Control bridge request failed (HTTP $httpStatus): $detail")
