package io.github.sparkcyf.voicesipagent.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Snapshot of the network conditions relevant to a long-running SIP gateway. */
data class NetworkMetrics(
    val sim2Active: Boolean = false,
    val sim2SignalLevel: Int = -1,
    val sim2Dbm: Int? = null,
    val sim2Network: String = "unknown",
    val internetConnected: Boolean = false,
    val internetTransport: String = "none",
    /** Android's link bandwidth estimate, in kbps. */
    val downstreamKbps: Int = 0,
    val upstreamKbps: Int = 0,
    /** Lightweight HTTPS download measurement, in Mbps. */
    val measuredDownloadMbps: Double? = null,
    val speedTestAtMs: Long? = null,
    val detail: String = "network monitor stopped",
)

/**
 * Watches SIM 2 and the default network without keeping an Activity alive.
 * The speed probe is intentionally small and infrequent so 24-hour operation does not consume
 * significant data; the continuous bandwidth values come from NetworkCapabilities.
 */
class NetworkMonitor(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val subscriptions = app.getSystemService(SubscriptionManager::class.java)
    private val telephonyExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "network-monitor").apply { isDaemon = true }
    }
    private val probeExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "network-speed-probe").apply { isDaemon = true }
    }
    private val _metrics = MutableStateFlow(NetworkMetrics())
    val metrics: StateFlow<NetworkMetrics> = _metrics
    private var sim2: TelephonyManager? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var legacyListener: PhoneStateListener? = null
    private var speedJob: ScheduledFuture<*>? = null
    private var running = false

    fun start() {
        if (running) return
        running = true
        cm.activeNetwork?.let { network -> cm.getNetworkCapabilities(network)?.let(::onCapabilitiesChanged) }
        registerSim2()
        speedJob = probeExecutor.scheduleWithFixedDelay(::measureSpeed, 5, 30, TimeUnit.MINUTES)
        measureSpeed()
    }

    fun stop() {
        if (!running) return
        running = false
        speedJob?.cancel(false)
        speedJob = null
        sim2?.let { manager ->
            if (Build.VERSION.SDK_INT >= 31) {
                telephonyCallback?.let { callback -> runCatching { manager.unregisterTelephonyCallback(callback) } }
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let { manager.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        }
        telephonyCallback = null
        legacyListener = null
        sim2 = null
        _metrics.value = NetworkMetrics(detail = "network monitor stopped")
    }

    fun close() {
        stop()
        probeExecutor.shutdownNow()
        telephonyExecutor.shutdownNow()
    }

    /** Called by GatewayRuntime's single default-network callback. */
    fun onNetworkAvailable(network: Network) {
        cm.getNetworkCapabilities(network)?.let(::onCapabilitiesChanged)
    }

    /** Called by GatewayRuntime when the default network capabilities change. */
    fun onCapabilitiesChanged(caps: NetworkCapabilities) {
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "other"
        }
        val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        _metrics.update {
            it.copy(
                internetConnected = connected,
                internetTransport = transport,
                downstreamKbps = caps.linkDownstreamBandwidthKbps,
                upstreamKbps = caps.linkUpstreamBandwidthKbps,
                detail = if (connected) "internet connected" else "network has no validated internet",
            )
        }
    }

    /** Called by GatewayRuntime when the default network is lost. */
    fun onNetworkLost() = _metrics.update {
        it.copy(internetConnected = false, internetTransport = "none", downstreamKbps = 0, upstreamKbps = 0,
            detail = "internet unavailable")
    }

    private fun registerSim2() {
        if (app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            _metrics.update { it.copy(detail = "READ_PHONE_STATE required for SIM 2") }
            return
        }
        val info = runCatching { subscriptions.getActiveSubscriptionInfoForSimSlotIndex(1) }.getOrNull()
        if (info == null) {
            _metrics.update { it.copy(sim2Active = false, detail = "SIM 2 is not active") }
            return
        }
        val manager = runCatching { app.getSystemService(TelephonyManager::class.java).createForSubscriptionId(info.subscriptionId) }
            .getOrNull() ?: return
        sim2 = manager
        _metrics.update { it.copy(sim2Active = true, sim2Network = networkName(manager)) }
        if (Build.VERSION.SDK_INT >= 31) {
            val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.DisplayInfoListener, TelephonyCallback.ServiceStateListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = updateSignal(signalStrength)
                override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                    _metrics.update { it.copy(sim2Network = networkTypeName(info.networkType)) }
                }
                override fun onServiceStateChanged(serviceState: ServiceState) {
                    _metrics.update { it.copy(sim2Active = serviceState.state == ServiceState.STATE_IN_SERVICE) }
                }
            }
            telephonyCallback = callback
            runCatching { manager.registerTelephonyCallback(app.mainExecutor, callback) }
                .onFailure { _metrics.update { it.copy(detail = "SIM 2 signal callback unavailable") } }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = updateSignal(signalStrength)
                override fun onServiceStateChanged(serviceState: ServiceState) {
                    _metrics.update { it.copy(sim2Active = serviceState.state == ServiceState.STATE_IN_SERVICE) }
                }
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            manager.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_SERVICE_STATE)
        }
    }

    private fun updateSignal(signal: SignalStrength) {
        val dbm = signal.cellSignalStrengths.maxByOrNull { it.dbm }?.dbm?.takeIf { it < 0 }
        _metrics.update { it.copy(sim2SignalLevel = signal.level, sim2Dbm = dbm, sim2Active = true) }
    }

    private fun networkName(manager: TelephonyManager): String = networkTypeName(runCatching { manager.dataNetworkType }.getOrDefault(0))

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        else -> if (type == 0) "unknown" else "mobile-$type"
    }

    private fun measureSpeed() {
        if (!running || !_metrics.value.internetConnected) return
        telephonyExecutor.execute {
            val started = System.nanoTime()
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://speed.cloudflare.com/__down?bytes=262144").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    useCaches = false
                }
                var bytes = 0L
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (bytes < 262_144) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        bytes += n
                    }
                }
                val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                if (bytes > 0 && seconds > 0) _metrics.update {
                    it.copy(measuredDownloadMbps = bytes * 8.0 / seconds / 1_000_000.0, speedTestAtMs = System.currentTimeMillis())
                }
            } catch (_: Exception) {
                _metrics.update { it.copy(measuredDownloadMbps = null, speedTestAtMs = System.currentTimeMillis()) }
            } finally {
                connection?.disconnect()
            }
        }
    }
}
