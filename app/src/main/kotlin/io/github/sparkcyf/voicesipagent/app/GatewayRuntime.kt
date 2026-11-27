package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Bundle
import android.os.Build
import android.os.BatteryManager
import android.telecom.TelecomManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.app.role.RoleManager
import android.Manifest
import android.net.Uri
import io.github.sparkcyf.voicesipagent.gateway.BridgeSession
import io.github.sparkcyf.voicesipagent.gateway.CallBridge
import io.github.sparkcyf.voicesipagent.gateway.EchoEndpoint
import io.github.sparkcyf.voicesipagent.gateway.GatewayConfig
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult
import io.github.sparkcyf.voicesipagent.privileged.CallAccessRequirements
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.sip.SipAccount
import io.github.sparkcyf.voicesipagent.sip.SipConfig
import io.github.sparkcyf.voicesipagent.sip.SipLog
import io.github.sparkcyf.voicesipagent.sip.SipUserAgent
import io.github.sparkcyf.voicesipagent.telephony.CellularEndpoint
import io.github.sparkcyf.voicesipagent.telephony.CallSnapshot
import io.github.sparkcyf.voicesipagent.telephony.GatewayInCallService
import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.redactNumber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor

data class HealthState(
    val shizuku: ShizukuConnector.State = ShizukuConnector.State.SHIZUKU_NOT_RUNNING,
    val shizukuDetail: String = "",
    /** Null until the privileged service has reported. */
    val privileged: Map<String, Boolean>? = null,
    val privilegedDetail: String = "",
    val sip: EndpointStatus = EndpointStatus(false, "not configured"),
    val takeover: Boolean = true,
    /** Takeover was switched off during a call; it takes effect when that call ends. */
    val takeoverOffPending: Boolean = false,
    val takeoverOnPending: Boolean = false,
    val inCallServiceBound: Boolean = false,
    val loopbackTest: Boolean = false,
    val session: String? = null,
    val lastSetupLog: String = "",
    val holdsDialer: Boolean = false,
    val dialerRoleAvailable: Boolean = false,
    val effectiveCallAccess: Int = CallAccess.AUTO,
    val callAccessReady: Boolean = false,
    val audioReady: Boolean = false,
    val cellularReady: Boolean = false,
    val readinessDetail: String = "Checking gateway setup",
    val calls: CallSnapshot = CallSnapshot(),
    val settingsPending: Boolean = false,
    val dialerChangePending: Boolean = false,
    val dialerChangeRunning: Boolean = false,
    val dialerChangeFailed: Boolean = false,
    val dialerChangeAcquire: Boolean = false,
) {
    val privilegedReady: Boolean
        get() = callAccessReady && audioReady && shizuku == ShizukuConnector.State.CONNECTED

    /**
     * The gateway host is intentionally persistent even when call takeover is disabled.
     * Keeping the foreground service alive allows health monitoring and recovery without
     * changing SIP/call behavior; actual SIP and call bridging remain gated by [takeover].
     *
     * Android may still stop a process (force-stop, OEM policy, resource pressure), so this
     * is a best-effort persistence policy rather than an OS-level guarantee.
     */
    val needsForegroundService: Boolean
        get() = true
}

/**
 * Owns every long-lived component and wires them together:
 * Shizuku -> privileged setup -> cellular endpoint readiness; settings -> SIP UA; both -> CallBridge.
 * The bridge's B side is the SIP UA normally, or [EchoEndpoint] in loopback-test mode.
 * Switching takeover off withdraws companion access and SIP, then restores the previous dialer
 * unless the user chose to retain the role. Role-qualified recovery components stay enabled.
 */
class GatewayRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val worker = newWorker()
    val settingsStore = SettingsStore(app)
    private val salesAdminStore = SalesAdminStore(app)
    private val _settings = MutableStateFlow(settingsStore.load())
    val settingsFlow: StateFlow<GatewaySettings> = _settings
    val settings: GatewaySettings get() = _settings.value
    private val firebaseStore = VoicxFirebaseStore(app)
    private val customers = VoicxCustomerRepository(firebaseStore)

    // Service entry points can run before the worker initializes. Use the actual saved switch and
    // role immediately so opening the app or receiving BOOT_COMPLETED cannot flash an idle notification.
    private val _health = MutableStateFlow(run {
        val holds = holdsDialer()
        HealthState(takeover = settings.takeover, holdsDialer = holds,
            effectiveCallAccess = CallAccess.effective(settings.callAccess, Build.VERSION.SDK_INT, holds))
    })
    val health: StateFlow<HealthState> = _health
    private val _dialerMessage = MutableStateFlow("")
    val dialerMessage: StateFlow<String> = _dialerMessage

    val shizuku = ShizukuConnector(app) { AppLog.i(it) }
    // Built-in Kotlin SIP stack is G.711 (narrowband) only.
    private fun audioFormat() = PcmFormat.NARROWBAND

    val cellular = CellularEndpoint(app,
        PrivilegedAudioOpener(shizuku, { settings.audioMethod }, { settings.muteEarpiece }, ::audioFormat),
        logger = { AppLog.i(it) },
    )
    private val gemini = GeminiAiEndpoint(
        apiKey = { settings.geminiApiKey },
        model = { settings.geminiModel },
        salesContext = { salesAdminStore.assistantContext() },
        report = { report ->
            onAiReport?.invoke(report.telegramMessages())
            // Snapshot and detach this report from mutable current-call state before
            // handing persistence to the worker. A later call must not steal this report's IDs.
            val (reportScheduleId, reportCampaignCustomerId) = synchronized(scheduledCallLock) {
                val ids = scheduledCallId to scheduledCampaignCustomerId
                scheduledCallId = null
                scheduledCampaignCustomerId = null
                ids
            }
            worker.execute {
                // SQLite is the active offline-first store. Cloud-sync availability must not
                // gate local call reports or prevent queued work from running.
                runCatching { customers.saveCallReport(report, reportScheduleId, reportCampaignCustomerId) }
                    .onFailure { AppLog.w("could not save Gemini call report", it) }
                reportCampaignCustomerId?.let { ccId ->
                    val text = (report.conversationTranscript + "\n" + report.outputText + "\n" + report.failureDetail).lowercase()
                    val outcome = when {
                        listOf("stop", "unsubscribe", "لا تتصل", "لا أرغب", "إلغاء").any(text::contains) -> "opted_out"
                        listOf("مهتم", "interested", "الإدارة", "manager", "sales").any(text::contains) -> "interested"
                        else -> "follow_up"
                    }
                    runCatching {
                        customers.setCampaignDecision(ccId, outcome, "نتيجة المكالمة: ${report.cause?.name.orEmpty()}")
                        if (outcome == "follow_up" && report.cause == HangupCause.NORMAL && report.failureDetail.isBlank()) {
                            customers.scheduleCampaignSmsAfterCall(ccId)
                        }
                        customers.advanceCampaign(ccId)
                    }.onFailure { AppLog.w("could not advance campaign after call", it) }
                }
                reportScheduleId?.let { id ->
                    val status = if (report.cause == HangupCause.NORMAL && report.failureDetail.isBlank()) "completed" else "failed"
                    runCatching { customers.updateScheduleStatus(id, status, report.failureDetail.ifBlank { report.cause?.name.orEmpty() }) }
                        .onFailure { AppLog.w("could not complete scheduled call $id", it) }
                }
            }
        },
    )
    @Volatile var onAiReport: ((List<String>) -> Unit)? = null
    /** Built-in SIP user agent. */
    @Volatile private var sip: CallEndpoint? = null
    @Volatile private var bridge: CallBridge? = null
    private var echo: EchoEndpoint? = null
    @Volatile
    private var telegramSimCall: TelegramSimCall? = null
    private val scheduledCallLock = Any()
    @Volatile
    private var scheduledCallId: String? = null
    @Volatile
    private var scheduledCampaignCustomerId: String? = null
    private var started = false
    private var pendingSettings: GatewaySettings? = null
    private var restartPending = false
    private var loopbackPending: Boolean? = null
    private var aiAutoAnswerPending: Boolean? = null
    private var setupPending = false
    private var foregroundRequested: Boolean? = null
    private var serviceToken: Any? = null
    private var foregroundRunning = false
    private val gatewayEnabled get() = foregroundRunning && _health.value.takeover
    private var pendingDialerChange: DialerChange? = null
    private var dialerRetry: ScheduledFuture<*>? = null
    private var healthCheck: ScheduledFuture<*>? = null
    private var scheduleCheck: ScheduledFuture<*>? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val dialerHandoffStore = DialerHandoffStore(app)
    private val dialerHandoff = DialerHandoff(app.packageName, dialerHandoffStore, object : DialerHandoff.Backend {
        private fun service() = shizuku.service ?: error("Shizuku not connected. Use system dialer selection.")
        override fun state() = DialerRoleResult.fromBundle(service().dialerState()).let {
            it.state ?: error(it.message.ifEmpty { "Could not read the default dialer." })
        }
        override fun acquire(expectedHolder: String) = DialerRoleResult.fromBundle(service().becomeDefaultDialer(expectedHolder))
        override fun restore(previousHolder: String) = DialerRoleResult.fromBundle(service().restoreDefaultDialer(previousHolder))
    })
    private val takeoverControl = TakeoverControl(object : TakeoverControl.Host {
        override val enabled get() = _health.value.takeover
        override val active get() = hasActiveWork()
        override val needsDialer get() = _health.value.effectiveCallAccess == CallAccess.DIALER
        override val holdsDialer get() = _health.value.holdsDialer
        override val keepDialer get() = settings.keepDefaultDialer
        override fun saveEnabled(on: Boolean) {
            if (settings.takeover != on) {
                settingsStore.save(settings.copy(takeover = on).also { _settings.value = it })
            }
        }
        override fun cancelDialerChange() = clearDialerChange()
        override fun prepareDialerChange(acquire: Boolean) = prepareDialerChangeRequest(acquire)
        override fun showPending(on: Boolean?) {
            _health.update { it.copy(takeoverOffPending = on == false, takeoverOnPending = on == true) }
        }
        override fun applyEnabled() = applyTakeover()
    })

    private val sipLog = object : SipLog {
        override fun info(msg: String) = AppLog.i("sip: $msg")
        override fun warn(msg: String, t: Throwable?) = AppLog.w("sip: $msg", t)
    }

    init {
        gemini.onHandoffRequested = {
            val employeeEndpoint: CallEndpoint? = sip
            val destination = settings.humanHandoffTarget.trim().ifBlank {
                if (settings.serverMode) "employee" else ""
            }
            val activeBridge: CallBridge? = bridge
            if (destination.isBlank() || employeeEndpoint == null || !employeeEndpoint.status.ready) {
                false
            } else {
                activeBridge?.handoffTo(employeeEndpoint, destination) == true ||
                    gemini.handoffDirectTo(employeeEndpoint, destination)
            }
        }
        gemini.onHangupRequested = {
            val activeBridge: CallBridge? = bridge
            if (activeBridge?.hangupActive() != true) gemini.stop()
        }
    }
    fun start() = worker.execute {
        if (started) return@execute
        started = true
        cellular.onCallsChanged = { snapshot ->
            _health.update { it.copy(calls = snapshot, inCallServiceBound = CellularEndpoint.isInCallServiceBound) }
            worker.execute {
                syncForegroundService()
                if (!cellular.hasCalls && _health.value.session == null) applyDeferredChanges()
            }
        }
        AppLog.i("runtime starting ($settings)")
        shizuku.addListener { state, svc ->
            val detail = shizuku.detail
            worker.execute {
                if (shizuku.state != state || shizuku.service !== svc) return@execute
                _health.update { it.copy(shizuku = state, shizukuDetail = detail) }
                if (state == ShizukuConnector.State.CONNECTED && svc != null) {
                    if (pendingDialerChange?.acquire == true) attemptDialerChange()
                    runSetup()
                } else {
                    updatePrivileged(null, detail)
                }
            }
        }
        configureCallAccess()
        if (settings.firebaseEnabled && settings.firebaseProjectId.isNotBlank()) firebaseStore.connect(settings.firebaseProjectId, settings.firebaseApiKey, settings.firebaseApplicationId)
        _health.update { it.copy(takeover = settings.takeover) }
        updateReadiness()
        if (!_health.value.takeover) shizuku.stop()
    }

    /** Track ownership before promotion so destruction also cancels a failed start. */
    internal fun onServiceCreated(service: Any) = worker.execute {
        serviceToken = service
        foregroundRunning = false
    }

    /** Called only after startForeground succeeds. All gateway work stays off the main thread. */
    internal fun onForegroundStarted(service: Any) = worker.execute {
        if (serviceToken !== service) return@execute
        foregroundRunning = true
        foregroundRequested = true
        startGateway()
        updateReadiness()
    }

    internal fun onForegroundStopped(service: Any) = worker.execute {
        if (serviceToken !== service) return@execute
        serviceToken = null
        foregroundRunning = false
        // Destruction releases resources, never changes roles or immediately restarts the service.
        // An explicit launch or a later switch-on can request it again.
        foregroundRequested = _health.value.needsForegroundService
        if (pendingDialerChange != null) clearDialerChange()
        stopGateway()
        configureCallAccess()
        updateReadiness()
    }

    private fun startGateway() {
        if (!gatewayEnabled || healthCheck != null) return
        configureCallAccess()
        startBackgroundTasks()
        startSip()
        rebuildBridge()
    }

    /** Stop producers before their dependencies; retain the helper for an explicit role handoff. */
    private fun stopGateway() {
        stopBackgroundTasks()
        runCatching { stopBridge() }.onFailure { AppLog.w("bridge cleanup failed", it) }
        runCatching { stopSip(releaseLibrary = true) }.onFailure { AppLog.w("SIP cleanup failed", it) }
        shizuku.service?.let { svc ->
            runCatching { svc.setupCallAccess(app.packageName, false, _health.value.effectiveCallAccess) }
                .onFailure { AppLog.w("companion cleanup failed", it) }
        }
        if (pendingDialerChange == null) runCatching { shizuku.stop() }
            .onFailure { AppLog.w("Shizuku cleanup failed", it) }
        _health.update { it.copy(privileged = null, sip = EndpointStatus(false,
            if (it.takeover) "gateway stopped" else "takeover off")) }
    }

    private fun startBackgroundTasks() {
        if (healthCheck != null) return
        worker.allowCoreThreadTimeOut(false)
        shizuku.start()
        watchNetwork()
        // Telecom or Shizuku restarts can disrupt call access. Recheck health and repair companion
        // setup while idle if access is unavailable; a missing override alone does not trigger setup.
        healthCheck = worker.scheduleWithFixedDelay(::periodicCheck, 60, 60, TimeUnit.SECONDS)
        scheduleCheck = worker.scheduleWithFixedDelay(::runDueSchedules, 5, 30, TimeUnit.SECONDS)
    }

    private fun stopBackgroundTasks() {
        healthCheck?.cancel(false)
        healthCheck = null
        scheduleCheck?.cancel(false)
        scheduleCheck = null
        dialerRetry?.cancel(false)
        dialerRetry = null
        val callback = networkCallback
        networkCallback = null
        if (callback != null) runCatching {
            app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        }.onFailure { AppLog.w("network callback cleanup failed", it) }
        setupPending = false
        worker.allowCoreThreadTimeOut(true)
    }

    // ------------------------------------------------------------ privileged setup / health

    fun runSetup() = worker.execute {
        if (!gatewayEnabled) { setupPending = false; return@execute }
        if (hasActiveWork()) {
            setupPending = true
            _health.update { it.copy(settingsPending = true) }
            return@execute
        }
        setupPending = false
        val svc = shizuku.service ?: return@execute updatePrivileged(null, "Shizuku not connected")
        try {
            refreshRoleState()
            val b = svc.setupCallAccess(app.packageName, _health.value.takeover, _health.value.effectiveCallAccess)
            _health.update { it.copy(lastSetupLog = b.getString("setup_log").orEmpty()) }
            applyHealthBundle(b)
        } catch (e: Exception) {
            AppLog.w("privileged setup failed", e)
            updatePrivileged(null, "setup failed: ${e.message}")
        }
    }

    private fun periodicCheck() {
        if (healthCheck == null) return
        // Keep a long-running scheduled customer conversation leased so another recovery
        // worker cannot mistake it for an abandoned call and dial the same customer again.
        val activeScheduledCallId = synchronized(scheduledCallLock) { scheduledCallId }
        activeScheduledCallId?.let { id ->
            runCatching { customers.renewCallScheduleLease(id) }
                .onFailure { AppLog.w("could not renew scheduled call lease $id", it) }
        }
        refreshRoleState()
        val svc = shizuku.service ?: return updateReadiness()
        try {
            val b = svc.health(app.packageName)
            applyHealthBundle(b)
            if (_health.value.takeover && _health.value.effectiveCallAccess == CallAccess.COMPANION &&
                !_health.value.callAccessReady && !hasActiveWork()) {
                AppLog.i("health degraded; re-running setup")
                runSetup()
            }
        } catch (e: Exception) {
            AppLog.w("health check failed", e)
        }
        _health.update { it.copy(inCallServiceBound = CellularEndpoint.isInCallServiceBound) }
    }

    private fun applyHealthBundle(b: Bundle) {
        val map = HealthKeys.BOOLEAN_KEYS.associateWith { b.getBoolean(it) }
        updatePrivileged(map, b.getString(HealthKeys.AUDIO_DETAIL).orEmpty())
    }

    private fun updatePrivileged(map: Map<String, Boolean>?, detail: String) {
        _health.update { it.copy(privileged = map, privilegedDetail = detail) }
        updateReadiness()
        if (map != null) AppLog.i("privileged health: ${map.entries.joinToString { "${it.key}=${it.value}" }} $detail")
    }

    private fun holdsDialer() = app.getSystemService(TelecomManager::class.java).defaultDialerPackage == app.packageName

    private fun refreshRoleState() {
        val holds = holdsDialer()
        val available = app.getSystemService(RoleManager::class.java).isRoleAvailable(RoleManager.ROLE_DIALER)
        _health.update { it.copy(holdsDialer = holds,
            dialerRoleAvailable = available,
            effectiveCallAccess = CallAccess.effective(settings.callAccess, Build.VERSION.SDK_INT, holds)) }
    }

    private fun configureCallAccess() {
        refreshRoleState()
        cellular.setPreferredDialerService(_health.value.holdsDialer)
        if (!cellular.hasCalls) CellularEndpoint.setCompanionComponentEnabled(app,
            gatewayEnabled && _health.value.effectiveCallAccess == CallAccess.COMPANION && Build.VERSION.SDK_INT >= 31)
        // The dialer activity/UI service remain enabled, including when takeover is off.
    }

    fun refreshCallAccess() = worker.execute {
        configureCallAccess()
        updateReadiness()
        // Changing companion registration can change binding. Defer app-controlled setup while
        // Telecom has any call, including calls outside the bridge.
        if (gatewayEnabled) {
            if (!hasActiveWork()) runSetup() else setupPending = true
        }
    }

    @Suppress("DEPRECATION")
    private fun componentEnabled(type: Class<*>, service: Boolean): Boolean = runCatching {
        val component = ComponentName(app, type)
        val pm = app.packageManager
        val info = if (service) pm.getServiceInfo(component, PackageManager.GET_META_DATA)
            else pm.getActivityInfo(component, 0)
        val setting = pm.getComponentEnabledSetting(component)
        val enabled = setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled)
        enabled && info.applicationInfo.enabled && (!service ||
            (info as android.content.pm.ServiceInfo).permission == Manifest.permission.BIND_INCALL_SERVICE)
    }.getOrDefault(false)

    private fun updateReadiness() {
        refreshRoleState()
        val h = _health.value
        val p = h.privileged
        val requirements = CallAccessRequirements(h.effectiveCallAccess, Build.VERSION.SDK_INT,
            h.holdsDialer, h.dialerRoleAvailable,
            componentEnabled(GatewayDialerActivity::class.java, false) && componentEnabled(GatewayDialerService::class.java, true),
            componentEnabled(GatewayInCallService::class.java, true),
            p?.get(HealthKeys.MANAGE_ONGOING_CALLS) == true, p?.get(HealthKeys.COMPANION_OVERRIDE) == true,
            listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE).all {
                app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            })
        val access = requirements.ready
        val audio = when (settings.audioMethod) {
            AudioMethod.OFFICIAL -> p?.get(HealthKeys.REDIRECT_AUDIO) == true
            AudioMethod.LEGACY -> p?.get(HealthKeys.DIRECT_AUDIO) == true
            else -> p?.get(HealthKeys.DIRECT_AUDIO) == true || p?.get(HealthKeys.REDIRECT_AUDIO) == true
        }
        val detail = when {
            h.dialerChangeRunning -> "Changing the default dialer"
            h.takeoverOnPending -> "Enabling gateway after current calls end"
            h.takeoverOffPending -> "Turning gateway off after current calls end"
            h.dialerChangePending -> if (h.dialerChangeAcquire)
                "Waiting to become the default dialer" else "Waiting to restore the previous dialer"
            !h.takeover -> if (h.holdsDialer) "Gateway off; this app still holds the dialer role" else "Gateway off"
            !gatewayEnabled -> "Gateway service not running"
            !access -> requirements.problem!!
            h.shizuku != ShizukuConnector.State.CONNECTED -> "Shizuku not connected"
            !audio -> "Selected call audio unavailable: ${h.privilegedDetail}"
            !h.loopbackTest && !settings.aiAutoAnswer && !h.sip.ready -> "SIP unavailable: ${h.sip.detail}"
            else -> "Gateway ready"
        }
        val ready = !h.dialerChangeRunning && gatewayEnabled && access && audio && h.shizuku == ShizukuConnector.State.CONNECTED &&
            (h.loopbackTest || settings.aiAutoAnswer || h.sip.ready)
        _health.update { it.copy(callAccessReady = access, audioReady = audio, cellularReady = ready, readinessDetail = detail) }
        cellular.setReadiness(ready, detail)
        syncForegroundService()
    }

    private fun syncForegroundService() {
        val h = _health.value
        if (foregroundRequested == h.needsForegroundService) return
        if (GatewayService.sync(app, h)) foregroundRequested = h.needsForegroundService
    }

    private fun hasActiveWork() = telegramSimCall != null || cellular.hasCalls || bridge?.session != null || _health.value.session != null || gemini.hasActiveCall()

    fun showDialerMessage(message: String) { _dialerMessage.value = message }

    /** Called only from an explicit, unlocked UI action. */
    fun changeDefaultDialer(acquire: Boolean) = worker.execute {
        clearDialerChange()
        prepareDialerChangeRequest(acquire)
        attemptDialerChange()
    }

    private fun clearDialerChange() {
        dialerRetry?.cancel(false)
        dialerRetry = null
        pendingDialerChange = null
        _health.update { it.copy(dialerChangePending = false, dialerChangeFailed = false, dialerChangeAcquire = false) }
        _dialerMessage.value = ""
        if (!_health.value.takeover) shizuku.stop()
    }

    private fun prepareDialerChangeRequest(acquire: Boolean) {
        _health.update { it.copy(dialerChangeAcquire = acquire) }
        try {
            val prepared = if (acquire) {
                // Null can mean an empty role; the helper rechecks the holder before any change.
                val holder = app.getSystemService(TelecomManager::class.java).defaultDialerPackage.orEmpty()
                dialerHandoff.prepareAcquisition(PreviousDialer(android.os.Process.myUid() / 100_000, holder))
            } else dialerHandoff.prepare(false)
            pendingDialerChange = prepared.change
            prepared.result?.let(::finishDialerChange)
        } catch (e: Exception) {
            finishDialerChange(DialerRoleResult(DialerRoleResult.FAILED,
                "Could not prepare the dialer change: ${e.message}. Use system default-app settings."))
        }
    }

    private fun finishDialerChange(result: DialerRoleResult) {
        clearDialerChange()
        _health.update { it.copy(dialerChangeFailed = result.status == DialerRoleResult.FAILED) }
        _dialerMessage.value = result.message
        AppLog.i("dialer change: ${result.status} ${result.message}")
        refreshCallAccess()
    }

    private fun attemptDialerChange() {
        val change = pendingDialerChange ?: return
        dialerRetry?.cancel(false)
        dialerRetry = null
        if (change.acquire && (!_health.value.takeover || shizuku.service == null)) {
            _health.update { it.copy(dialerChangePending = true) }
            _dialerMessage.value = "Waiting for Shizuku. You can cancel the dialer change."
            updateReadiness()
            return
        }
        if (hasActiveWork()) { deferDialerChange(); return }
        takeoverControl.applyIfIdle()
        _health.update { it.copy(dialerChangeRunning = true, dialerChangePending = false) }
        val result = try {
            updateReadiness()
            dialerHandoff.execute(change)
        } catch (e: Exception) {
            DialerRoleResult(DialerRoleResult.FAILED, "Dialer change failed: ${e.message}. Use system default-app settings.")
        } finally {
            _health.update { it.copy(dialerChangeRunning = false) }
        }
        if (result.deferred) deferDialerChange() else finishDialerChange(result)
        updateReadiness()
    }

    private fun deferDialerChange() {
        if (!_health.value.takeover) {
            finishDialerChange(DialerRoleResult(DialerRoleResult.FAILED,
                "Gateway off. Choose your phone app in system Settings after current calls end."))
            return
        }
        _health.update { it.copy(dialerChangePending = true) }
        _dialerMessage.value = "Waiting for current calls to end. You can cancel the dialer change."
        // Before acquisition our InCallService may not see the other dialer's calls. The helper
        // queries Telecom, including self-managed calls, on each retry and before each command.
        dialerRetry = worker.schedule({ dialerRetry = null; attemptDialerChange() }, 1, TimeUnit.SECONDS)
    }

    fun cancelDialerChange() = worker.execute {
        val wasPending = pendingDialerChange != null
        clearDialerChange()
        if (wasPending) _dialerMessage.value = "Dialer change cancelled."
    }

    /** Public role-dialog fallback still saves a return destination before Android can kill us. */
    fun rememberDialerForSystemRequest(): Boolean {
        val previous = app.getSystemService(TelecomManager::class.java).defaultDialerPackage
            ?: return false.also { showDialerMessage("Could not read the previous dialer. Use system default-app settings.") }
        if (previous == app.packageName) return true
        val saved = dialerHandoffStore.save(PreviousDialer(android.os.Process.myUid() / 100_000, previous))
        if (!saved) showDialerMessage("Could not save the previous dialer; no role change was attempted.")
        return saved
    }

    private fun sipStatus(endpoint: CallEndpoint, status: EndpointStatus) = worker.execute {
        if (sip !== endpoint) return@execute
        _health.update { it.copy(sip = status) }
        updateReadiness()
    }

    // ------------------------------------------------------------ SIP

    private fun startSip() {
        if (!gatewayEnabled) return
        stopSip()
        val s = settings
        if (!s.takeover) {
            _health.update { it.copy(sip = EndpointStatus(false, "takeover off")) }
            return
        }
        if (!s.sipConfigured) {
            _health.update { it.copy(sip = EndpointStatus(false, "not configured")) }
            return
        }
        val transport = SipTransport.of(s.sipTransport)
        val srtp = SrtpPolicy.of(s.srtp)
        if (srtp == null) {
            AppLog.w("unknown SRTP policy '${s.srtp}'")
            _health.update { it.copy(sip = EndpointStatus(false, "unknown srtp ${s.srtp}")) }
            return
        }
        if (transport == null || transport != SipTransport.UDP) {
            AppLog.w("SIP transport '${s.sipTransport}' not supported by the built-in stack (UDP only)")
            _health.update { it.copy(sip = EndpointStatus(false, "transport ${s.sipTransport} unsupported (UDP only)")) }
            return
        }
        try {
            val ep: CallEndpoint = SipUserAgent(
                SipConfig(
                    account = SipAccount(
                        server = s.server,
                        port = s.port,
                        username = s.username,
                        password = s.password,
                        domain = s.domain.ifBlank { s.server },
                        displayName = "gateway",
                    ),
                    localPort = s.localSipPort,
                    serverMode = s.serverMode,
                    registrarLeaseSec = s.registrarLeaseSec,
                    registrarPassword = s.registrarPassword,
                ),
                sipLog,
            ).also { ua ->
                ua.addStatusListener { ep, st -> sipStatus(ep, st) }
                ua.start()
            }
            sip = ep
            _health.update { it.copy(sip = ep.status) }
            AppLog.i("SIP stack: built-in${if (s.serverMode) " (server mode)" else ""}")
        } catch (e: Throwable) {
            AppLog.w("SIP start failed", e)
            _health.update { it.copy(sip = EndpointStatus(false, "start failed: ${e.message}")) }
        }
    }

    private fun stopSip(releaseLibrary: Boolean = false) {
        val previous = sip
        sip = null
        (previous as? SipUserAgent)?.stop()
    }

    private fun refreshSipRegistration() {
        (sip as? SipUserAgent)?.refreshRegistration()
    }

    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var lastAddrs: String? = null
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (networkCallback !== this) return
                val addrs = lp.linkAddresses.joinToString { it.address.hostAddress.orEmpty() }
                if (addrs != lastAddrs) {
                    if (lastAddrs != null) {
                        AppLog.i("network addresses changed; re-registering")
                        worker.execute {
                            if (networkCallback === this && _health.value.takeover) refreshSipRegistration()
                        }
                    }
                    lastAddrs = addrs
                }
            }
        }
        networkCallback = callback
        cm.registerDefaultNetworkCallback(callback)
    }

    // ------------------------------------------------------------ bridge

    private fun stopBridge() {
        val previous = bridge
        bridge = null
        val previousEcho = echo
        echo = null
        _health.update { it.copy(session = null) }
        try { previous?.stop() } finally { previousEcho?.close() }
    }

    private fun rebuildBridge() {
        stopBridge()
        if (!gatewayEnabled) return
        val s = settings
        if (!s.takeover) {
            bridge = null
            AppLog.i("bridge idle: takeover off")
            return
        }
        val loopback = _health.value.loopbackTest
        val sideB: CallEndpoint? = when {
            loopback -> EchoEndpoint(format = audioFormat()).also { echo = it }
            settings.aiAutoAnswer -> gemini
            else -> sip
        }
        if (sideB == null) {
            bridge = null
            AppLog.i("bridge idle: SIP not configured")
            return
        }
        // In server mode the SIP side dials whichever phone is registered, so the target only has
        // to be non-empty; "phone" is a readable placeholder when no DID/username is configured.
        val inboundTarget = s.inboundTarget.ifBlank {
            s.username.ifBlank { if (s.aiAutoAnswer) "gemini" else if (s.serverMode) "phone" else "" }
        }
        val b = CallBridge(
            sideA = cellular,
            sideB = sideB,
            routeAtoB = { inboundTarget },
            routeBtoA = { leg -> leg.target?.takeIf { it != s.username } },
            config = GatewayConfig(
                cellularInboundTarget = inboundTarget,
                busyPolicy = s.busyPolicy,
                allowedDialPrefixes = s.allowedPrefixes.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            ),
            dialPolicyForA = { n -> if (cellular.isEmergencyNumber(n)) HangupCause.FORBIDDEN else null },
            log = { AppLog.i("bridge: $it") },
        )
        b.addListener { session ->
            val description = describe(session)
            worker.execute {
                if (bridge !== b) return@execute
                _health.update { it.copy(session = description) }
                syncForegroundService()
                if (session.endedAtMs != null && !cellular.hasCalls) applyDeferredChanges()
            }
        }
        bridge = b
        b.start()
        AppLog.i("bridge ready: cellular <-> ${sideB.name}")
    }

    private fun describe(s: BridgeSession): String? {
        if (s.endedAtMs != null) return null
        val out = s.outbound
        return "${s.origin}: ${redactNumber(s.inbound.remote.number)} ${s.inbound.state} -> ${out?.kind} ${out?.state ?: LegState.NEW}"
    }

    // ------------------------------------------------------------ external controls

    /**
     * Saves only remote-control configuration. This intentionally does not restart SIP,
     * alter takeover, or start a network worker.
     */
    fun updateControlBridgeSettings(url: String, deviceId: String, token: String) = worker.execute {
        val cleanUrl = url.trim().trimEnd('/')
        val cleanId = deviceId.trim()
        if (cleanUrl.isNotEmpty()) {
            val parsed = runCatching { java.net.URI(cleanUrl) }.getOrNull()
            require(parsed?.scheme.equals("https", ignoreCase = true) &&
                !parsed?.host.isNullOrBlank() && parsed?.userInfo == null &&
                parsed?.query == null && parsed?.fragment == null) {
                "Control bridge URL must be a valid HTTPS URL"
            }
            require(cleanId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid device id" }
            require(token.length >= 32) { "Device token must be at least 32 characters" }
        }
        val updated = settings.copy(
            controlBridgeUrl = cleanUrl,
            controlBridgeDeviceId = if (cleanUrl.isEmpty()) "" else cleanId,
            controlBridgeToken = if (cleanUrl.isEmpty()) "" else token,
        )
        settingsStore.save(updated)
        _settings.value = updated
        AppLog.i(if (cleanUrl.isEmpty()) "Voicx remote control disabled" else "Voicx bridge settings saved")
    }

    /** Saves the direct Telegram bot credential without changing SIP or takeover state. */
    fun updateTelegramBotSettings(token: String, allowedUserId: String) = worker.execute {
        val clean = token.trim()
        require(clean.isEmpty() || (clean.length >= 30 && Regex("^[0-9]+:[A-Za-z0-9_-]+$").matches(clean))) {
            "Invalid Telegram bot token format"
        }
        val allowedId = allowedUserId.trim().toLongOrNull()?.takeIf { it > 0 } ?: 0L
        require(clean.isEmpty() || allowedId > 0) { "Enter the numeric Telegram user ID allowed to control this phone" }
        if (settings.telegramBotToken != clean) settingsStore.saveTelegramUpdateOffset(0L)
        val updated = settings.copy(telegramBotToken = clean, telegramAllowedUserId = if (clean.isEmpty()) 0L else allowedId)
        settingsStore.save(updated)
        _settings.value = updated
        AppLog.i(if (clean.isEmpty()) "Telegram control disabled" else "Telegram bot settings saved")
    }

    fun updateGeminiSettings(apiKey: String, model: String = settings.geminiModel) = worker.execute {
        val cleanKey = apiKey.trim()
        require(cleanKey.isEmpty() || cleanKey.length >= 20) { "Invalid Gemini API key" }
        val cleanModel = model.trim().ifEmpty { "gemini-2.5-flash-native-audio-preview-12-2025" }
        val updated = settings.copy(geminiApiKey = cleanKey, geminiModel = cleanModel)
        settingsStore.save(updated); _settings.value = updated
    }

    fun setAiAutoAnswer(on: Boolean, callback: (String) -> Unit = {}) = worker.execute {
        if (on && settings.geminiApiKey.isBlank()) {
            callback("تعذر تشغيل الرد الآلي: احفظ مفتاح Gemini API أولاً.")
            return@execute
        }
        if (settings.aiAutoAnswer == on && aiAutoAnswerPending == null) {
            callback(if (on) "الرد الآلي بواسطة Gemini مفعّل بالفعل." else "الرد الآلي بواسطة Gemini متوقف بالفعل.")
            return@execute
        }
        if (hasActiveWork()) {
            aiAutoAnswerPending = on
            _health.update { it.copy(settingsPending = true) }
            callback("تم قبول الطلب؛ سيُطبق ${if (on) "تشغيل" else "إيقاف"} الرد الآلي بواسطة Gemini بعد انتهاء المكالمة الحالية.")
            return@execute
        }
        applyAiAutoAnswer(on)
        callback(if (on) "تم تشغيل الرد الآلي للمكالمات الواردة بواسطة Gemini." else "تم إيقاف الرد الآلي بواسطة Gemini.")
    }

    private fun applyAiAutoAnswer(on: Boolean) {
        settingsStore.save(settings.copy(aiAutoAnswer = on).also { _settings.value = it })
        AppLog.i("Gemini auto-answer ${if (on) "ON" else "OFF"}")
        rebuildBridge()
        updateReadiness()
    }

    private val outboundCallStarting = java.util.concurrent.atomic.AtomicBoolean(false)
    fun startAiCall(number: String, name: String, role: String, company: String) = worker.execute {
        if (!outboundCallStarting.compareAndSet(false, true)) { AppLog.w("duplicate outbound AI call request rejected"); return@execute }
        try {
            require(settings.geminiApiKey.isNotBlank()) { "Gemini API key is not configured" }
            val clean = PhoneNumberNormalizer.normalizeYemen(number)
            check(cellular.status.ready && cellular.hasReadySim() && !cellular.hasCalls && _health.value.session == null) { "SIM or cellular call resource is not ready" }
            check(!hasActiveWork()) { "Another call is already active" }
            val customer = runCatching { customers.findByPhone(clean) ?: customers.findByPhone(number.trim()) }.getOrNull()
            gemini.setContext(GeminiAiEndpoint.CallContext(
                name = name.trim(), role = role.trim(), company = company.trim(),
                instructions = customer?.instructions.orEmpty(), memories = runCatching { customers.memoriesFor(clean) }.getOrDefault(""),
            ))
            gemini.startOutbound(cellular, clean, Party(clean, name.trim().ifBlank { null }))
        } catch (e: Exception) { AppLog.w("outbound AI call rejected before dialing: ${e.message}") }
        finally { outboundCallStarting.set(false) }
    }

    private fun runDueSchedules() {
        if (!gatewayEnabled) return
        runDueSmsSchedules()
        repeat(3) {
            val schedule = runCatching { customers.claimDueSchedule() }.getOrElse {
                AppLog.w("could not claim scheduled call", it)
                return
            } ?: return
            if (customers.campaignCustomerBlocked(schedule.campaignCustomerId) ||
                customers.isPhoneOptedOut(schedule.phone)) {
                customers.updateScheduleStatus(schedule.id.orEmpty(), "cancelled", "contact is opted out or campaign customer is blocked")
                return@repeat
            }
            if (hasActiveWork()) {
                customers.deferSchedule(schedule.id.orEmpty(), System.currentTimeMillis() + 60_000L, "gateway busy")
                AppLog.w("scheduled call ${schedule.id} deferred because gateway is busy")
                return@repeat
            }
            try {
                require(settings.geminiApiKey.isNotBlank()) { "Gemini API key is not configured" }
                check(cellular.status.ready && cellular.hasReadySim() && !cellular.hasCalls && _health.value.session == null) { "SIM or cellular call resource is not ready" }
                check(!hasActiveWork()) { "gateway busy" }
                val normalizedPhone = PhoneNumberNormalizer.normalizeYemen(schedule.phone)
                val customer = customers.findById(schedule.customerId) ?: customers.findByPhone(normalizedPhone)
                gemini.setContext(GeminiAiEndpoint.CallContext(
                    name = customer?.name.orEmpty(), role = customer?.role.orEmpty(), company = customer?.company.orEmpty(),
                    instructions = buildString {
                        customer?.instructions?.takeIf(String::isNotBlank)?.let {
                            appendLine("تعليمات العميل الدائمة:")
                            appendLine(it.trim())
                        }
                        schedule.note.takeIf(String::isNotBlank)?.let {
                            appendLine("تعليمات خاصة بهذا الاتصال المجدول (تُطبق لهذه المكالمة فقط):")
                            appendLine(it.trim())
                        }
                    }.trim(),
                    memories = customers.memoriesFor(normalizedPhone),
                ))
                synchronized(scheduledCallLock) {
                    scheduledCallId = schedule.id
                    scheduledCampaignCustomerId = schedule.campaignCustomerId
                }
                customers.updateScheduleStatus(schedule.id.orEmpty(), "running")
                gemini.startOutbound(cellular, normalizedPhone, Party(normalizedPhone, customer?.name?.ifBlank { null }))
                AppLog.i("scheduled Gemini call started: ${schedule.id}")
            } catch (e: Exception) {
                synchronized(scheduledCallLock) {
                    if (scheduledCallId == schedule.id) {
                        scheduledCallId = null
                        scheduledCampaignCustomerId = null
                    }
                }
                customers.updateScheduleStatus(schedule.id.orEmpty(), "failed", e.message ?: e.javaClass.simpleName)
                AppLog.w("scheduled Gemini call failed: ${schedule.id}", e)
            }
        }
    }

    /** Sends due marketing/notification SMS independently from the call queue. */
    private fun runDueSmsSchedules() {
        repeat(3) {
            val schedule = runCatching { customers.claimDueSmsSchedule() }.getOrElse {
                AppLog.w("could not claim scheduled SMS", it)
                return
            } ?: return
            if (customers.campaignCustomerBlocked(schedule.campaignCustomerId) ||
                customers.isPhoneOptedOut(schedule.phone)) {
                customers.updateSmsScheduleStatus(schedule.id.orEmpty(), "cancelled", "contact is opted out or campaign customer is blocked")
                return@repeat
            }
            try {
                val normalizedPhone = PhoneNumberNormalizer.normalizeYemen(schedule.phone)
                SmsApiClient(settings.smsApiKey).send(normalizedPhone, schedule.message).getOrThrow()
                customers.updateSmsScheduleStatus(schedule.id.orEmpty(), "sent")
                AppLog.i("scheduled SMS sent: ${schedule.id}")
            } catch (e: Exception) {
                customers.updateSmsScheduleStatus(schedule.id.orEmpty(), "failed", e.message ?: e.javaClass.simpleName)
                AppLog.w("scheduled SMS failed: ${schedule.id}", e)
            }
        }
    }

    fun stopAiCall() = worker.execute { gemini.stop() }

    fun checkAiConnection(callback: (String) -> Unit) = worker.execute {
        gemini.checkConnection(callback)
    }

    fun testFirebaseConnection(projectId: String, apiKey: String, applicationId: String, callback: (String) -> Unit) = worker.execute {
        val result = firebaseStore.test(projectId.trim(), apiKey.trim(), applicationId.trim())
        callback(result.fold({ "نجح اختبار الاتصال خلال ${it} ms" }, { firebaseStore.lastError.ifBlank { "فشل اختبار الاتصال" } }))
    }
    fun testLocalDatabase(callback: (String) -> Unit) = worker.execute {
        callback(runCatching {
            firebaseStore.requireDatabase().all("customers").size
            "نجح اختبار قاعدة SQLite المحلية"
        }.getOrElse { "فشل اختبار قاعدة SQLite المحلية: ${it.message.orEmpty()}" })
    }
    fun localDatabaseStatus(): String = "SQLite محلية متصلة؛ البيانات محفوظة داخل الهاتف"
    fun localDatabaseSummary(): String = runCatching {
        val db = firebaseStore.requireDatabase()
        listOf("customers", "customer_memories", "call_summaries", "call_schedules", "sms_schedules", "campaigns", "campaign_customers")
            .joinToString("\n") { "$it: ${db.all(it).size}" }
    }.getOrElse { "تعذر قراءة قاعدة البيانات المحلية: ${it.javaClass.simpleName}" }
    fun localDatabaseCustomers(): String = runCatching {
        customers.search("", 20).joinToString("\n") { "${it.id} | ${it.name} | ${it.phone} | ${it.company}" }
            .ifBlank { "لا يوجد عملاء" }
    }.getOrElse { "تعذر قراءة العملاء: ${it.javaClass.simpleName}" }
    fun localDatabaseSchedules(): String = runCatching {
        val calls = customers.listSchedules(10).joinToString("\n") { "اتصال | ${it.phone} | ${it.runAt} | ${it.status}" }
        val sms = customers.listSmsSchedules(10).joinToString("\n") { "SMS | ${it.phone} | ${it.runAt} | ${it.status}" }
        listOf(calls, sms).filter(String::isNotBlank).joinToString("\n").ifBlank { "لا توجد جدولة" }
    }.getOrElse { "تعذر قراءة الجدولة: ${it.javaClass.simpleName}" }
    fun exportLocalDatabase(uri: Uri, callback: (String) -> Unit = {}) = worker.execute {
        callback(runCatching {
            app.contentResolver.openOutputStream(uri)?.use { firebaseStore.requireDatabase().exportTo(it) }
                ?: error("تعذر فتح ملف التصدير")
            "تم تصدير قاعدة SQLite المحلية بنجاح. لا يتضمن الملف مفاتيح Telegram أو Gemini أو SMS."
        }.getOrElse { "فشل تصدير قاعدة البيانات: ${it.message ?: it.javaClass.simpleName}" })
    }
    fun importLocalDatabase(uri: Uri, callback: (String) -> Unit = {}) = worker.execute {
        callback(runCatching {
            app.contentResolver.openInputStream(uri)?.use { input ->
                val backup = firebaseStore.requireDatabase().importFrom(input)
                "تم استيراد قاعدة البيانات. أُنشئت نسخة احتياطية محلية: ${backup.name}"
            } ?: error("تعذر فتح ملف الاستيراد")
        }.getOrElse { "فشل استيراد قاعدة البيانات: ${it.message ?: it.javaClass.simpleName}" })
    }
    fun saveFirebaseSettings(projectId: String, apiKey: String, applicationId: String, enable: Boolean, callback: (String) -> Unit = {}) = worker.execute {
        val cleanProject = projectId.trim(); val cleanKey = apiKey.trim(); val cleanAppId = applicationId.trim()
        require(cleanProject.matches(Regex("[a-z0-9-]{6,63}"))) { "Firebase Project ID غير صالح" }
        require(cleanKey.isNotBlank() && cleanAppId.isNotBlank()) { "بيانات Firebase غير مكتملة" }
        if (enable) firebaseStore.connect(cleanProject, cleanKey, cleanAppId).getOrElse { error(firebaseStore.lastError.ifBlank { "تعذر الاتصال بـ Firebase" }) } else firebaseStore.disconnect()
        val updated = settings.copy(firebaseProjectId = cleanProject, firebaseApiKey = cleanKey, firebaseApplicationId = cleanAppId, firebaseEnabled = enable, firebaseLastSuccessAt = firebaseStore.lastSuccessAt ?: settings.firebaseLastSuccessAt)
        settingsStore.save(updated); _settings.value = updated
        callback(if (enable) "تم تفعيل اتصال Firebase" else "تم حفظ إعدادات Firebase دون تفعيل الاتصال")
    }
    fun disconnectFirebase() = worker.execute { firebaseStore.disconnect(); settingsStore.save(settings.copy(firebaseEnabled = false).also { _settings.value = it }) }
    fun firebaseStatus(): String = when (firebaseStore.state) {
        FirebaseState.CONNECTED -> "مزامنة Firebase متصلة؛ آخر نجاح: ${firebaseStore.lastSuccessAt ?: 0L}"
        FirebaseState.ERROR -> "مزامنة Firebase غير متاحة: ${firebaseStore.lastError}. البيانات المحلية SQLite ما زالت تعمل."
        FirebaseState.DISCONNECTED -> "مزامنة Firebase متوقفة؛ البيانات محفوظة محلياً في SQLite."
    }
    fun saveCustomer(customer: VoicxCustomer, callback: (String) -> Unit = {}) = worker.execute { callback(runCatching { customers.upsert(customer) }.getOrElse { "خطأ حفظ العميل: ${it.javaClass.simpleName}" }) }
    fun createCampaign(campaign: Campaign, callback: (String) -> Unit = {}) = worker.execute {
        callback(runCatching { customers.createCampaign(campaign) }.getOrElse { "خطأ إنشاء الحملة: ${it.javaClass.simpleName}" })
    }
    fun followUp(phone: String, nextCallAt: Long?, nextSmsAt: Long?, note: String, callback: (String) -> Unit = {}) = worker.execute {
        val cc = customers.latestCampaignCustomer(phone)
        callback(if (cc == null) "لم أعثر على عضوية حملة لهذا الرقم." else if (customers.scheduleFollowUp(cc, nextCallAt, nextSmsAt, note)) "تم حفظ متابعة العميل." else "تعذر حفظ المتابعة؛ قد يكون العميل في الإدارة أو ألغى الاشتراك.")
    }
    fun markInterested(phone: String, note: String, callback: (String) -> Unit = {}) = worker.execute {
        val cc = customers.latestCampaignCustomer(phone)
        if (cc == null) callback("لم أعثر على عضوية حملة لهذا الرقم.") else { customers.setCampaignDecision(cc, "interested", note); callback("تم تحويل العميل إلى مجموعة المهتمين وإيقاف تذكيراته.") }
    }
    fun pauseCustomer(phone: String, reason: String, callback: (String) -> Unit = {}) = worker.execute {
        val cc = customers.latestCampaignCustomer(phone)
        if (cc == null) callback("لم أعثر على عضوية حملة لهذا الرقم.") else { customers.setCampaignDecision(cc, "paused", reason); callback("تم إيقاف تذكيرات العميل.") }
    }
    fun resumeCustomer(phone: String, callAt: Long, smsAt: Long?, note: String, callback: (String) -> Unit = {}) = worker.execute {
        val cc = customers.latestCampaignCustomer(phone)
        callback(if (cc == null) "لم أعثر على عضوية حملة لهذا الرقم." else if (customers.scheduleFollowUp(cc, callAt, smsAt, note)) "تم استئناف العميل بموعد صريح." else "تعذر استئناف العميل.")
    }
    fun searchCustomers(query: String, callback: (String) -> Unit) = worker.execute { callback(runCatching { customers.search(query).joinToString("\n") { "${it.id} | ${it.name} | ${it.phone} | ${it.company}" } }.getOrElse { "خطأ البحث: ${it.javaClass.simpleName}" }.ifBlank { "لا توجد نتائج" }) }
    fun saveCallSchedule(schedule: CallSchedule, callback: (String) -> Unit) = worker.execute { callback(runCatching { customers.saveSchedule(schedule) }.getOrElse { "خطأ جدولة الاتصال: ${it.javaClass.simpleName}" }) }
    fun scheduleReconciliationItems(limit: Int = 8): List<ScheduleReconciliationItem> =
        runCatching { customers.listSchedulesNeedingReconciliation(limit.coerceIn(1, 20)) }.getOrDefault(emptyList())

    internal fun resolveScheduleReconciliation(
        type: ScheduleType,
        scheduleId: String,
        decision: ScheduleReconciliationPolicy.Decision,
        callback: (String) -> Unit,
    ) = worker.execute {
        callback(runCatching { customers.resolveScheduleReconciliation(type, scheduleId, decision) }
            .fold({ if (it) "تم تحديث حالة المهمة وتسجيل دليل المراجعة." else "لم تعد المهمة موجودة؛ لم يتغير شيء." },
                { "تعذرت المصالحة: ${it.message ?: it.javaClass.simpleName}" }))
    }

    /** Read-only report for schedules quarantined after an unknown external outcome. */
    fun scheduleReconciliationReport(): String = runCatching {
        val items = customers.listSchedulesNeedingReconciliation(8)
        if (items.isEmpty()) return@runCatching "لا توجد مهام تحتاج إلى مصالحة. لا توجد اتصالات أو رسائل معلقة بنتيجة مجهولة."
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val lines = items.joinToString("\n\n") { item ->
            val kind = if (item.type == ScheduleType.CALL) "اتصال" else "SMS"
            val at = runCatching {
                java.time.Instant.ofEpochMilli(item.runAt).atZone(java.time.ZoneId.systemDefault()).format(formatter)
            }.getOrDefault("غير محدد")
            val phone = TelegramSimCall.maskNumber(item.phone)
            "النوع: $kind | ID: ${item.id}\nالرقم: $phone | الموعد: $at | المحاولات: ${item.attempts}\nالسبب: ${item.error.ifBlank { "لا يوجد وصف محفوظ" }.take(180)}"
        }
        "⚠️ مهام تحتاج مراجعة بشرية (${items.size} معروضة):\n\n$lines\n\nهذه القائمة للعرض فقط. لا تُعاد أي مهمة تلقائياً عند عودة الاتصال."
    }.getOrElse { "تعذر قراءة مهام المصالحة: ${it.javaClass.simpleName}" }
    fun listCallSchedules(callback: (String) -> Unit) = worker.execute { callback(runCatching { customers.listSchedules().joinToString("\n") { "${it.id} | ${it.phone} | ${it.runAt} | ${it.status} | ${it.note}" } }.getOrElse { "خطأ قراءة الجدولة: ${it.javaClass.simpleName}" }.ifBlank { "لا توجد مواعيد" }) }
    fun saveSmsSchedule(schedule: SmsSchedule, callback: (String) -> Unit) = worker.execute { callback(runCatching { customers.saveSmsSchedule(schedule) }.getOrElse { "خطأ جدولة SMS: ${it.javaClass.simpleName}" }) }
    fun listSmsSchedules(callback: (String) -> Unit) = worker.execute { callback(runCatching { customers.listSmsSchedules().joinToString("\n") { "${it.id} | ${it.phone} | ${it.runAt} | ${it.status} | ${it.note} | ${it.message.take(80)}" } }.getOrElse { "خطأ قراءة جدولة SMS: ${it.javaClass.simpleName}" }.ifBlank { "لا توجد رسائل مجدولة" }) }
    fun sendSms(phone: String, message: String, callback: (String) -> Unit) = worker.execute { callback(runCatching { PhoneNumberNormalizer.normalizeYemen(phone) }.fold({ normalized -> if (customers.isPhoneOptedOut(normalized)) "مرفوض: العميل ألغى الاشتراك وطلب عدم التواصل." else SmsApiClient(settings.smsApiKey).send(normalized, message).fold({ it }, { "فشل SMS: ${it.message ?: it.javaClass.simpleName}" }) }, { "فشل SMS: ${it.message}" })) }
    fun updateSmsApiKey(key: String) = worker.execute { settingsStore.save(settings.copy(smsApiKey = key.trim()).also { _settings.value = it }) }

    /** Starts an authenticated Telegram-originated SIM call and bridges it to the registered SIP phone. */
    fun startTelegramSimCall(rawNumber: String, notify: (String) -> Unit) = worker.execute {
        val number = runCatching { PhoneNumberNormalizer.normalizeYemen(rawNumber) }.getOrElse {
            notify("رقم جوال يمني غير صالح. مثال: /simcall 730501035")
            return@execute
        }
        val h = _health.value
        val sipEndpoint = sip
        when {
            telegramSimCall != null || gemini.hasActiveCall() -> notify("هناك مكالمة نشطة بالفعل. أنهِها قبل بدء اتصال آخر.")
            !gatewayEnabled || !h.takeover -> notify("البوابة متوقفة؛ فعّلها أولاً قبل إجراء اتصال SIM.")
            !cellular.status.ready || !cellular.hasReadySim() || !h.callAccessReady || !h.audioReady -> notify("اتصال SIM/SIP غير جاهز: تحقق من الشريحة والصلاحيات وShizuku والصوت.")
            sipEndpoint == null || !sipEndpoint.status.ready -> notify("لا يوجد هاتف SIP مسجّل وجاهز لاستقبال المكالمة.")
            cellular.hasCalls || h.session != null -> notify("خط SIM مشغول بمكالمة أخرى.")
            cellular.isEmergencyNumber(number) -> notify("مرفوض: لا يمكن بدء مكالمة طوارئ عبر هذا الأمر.")
            else -> {
                val prefixes = settings.allowedPrefixes.split(',').map(String::trim).filter(String::isNotEmpty)
                val digitsOnly = number.filter(Char::isDigit)
                if (prefixes.isNotEmpty() && prefixes.none { prefix ->
                        val normalized = prefix.filter { it.isDigit() || it == '+' }
                        normalized.isNotBlank() && (number.startsWith(normalized) || digitsOnly.startsWith(normalized.filter(Char::isDigit)))
                    }) {
                    notify("الرقم مرفوض وفق قائمة بادئات الاتصال المسموح بها.")
                    return@execute
                }
                val sipTarget = settings.inboundTarget.ifBlank { settings.username.ifBlank { "phone" } }
                var callRef: TelegramSimCall? = null
                val call = TelegramSimCall(
                    cellular = cellular,
                    sip = sipEndpoint,
                    number = number,
                    sipTarget = sipTarget,
                    notify = notify,
                    batteryStatus = ::batteryStatus,
                    onFinished = { record -> worker.execute {
                        TelegramCallHistory(app).add(record)
                        if (telegramSimCall === callRef) telegramSimCall = null
                    } },
                )
                callRef = call
                telegramSimCall = call
                call.start()
            }
        }
    }

    /** Ends the one active SIM call, including either leg of its SIP bridge. */
    fun cancelTelegramSimCall(): Boolean {
        val active = telegramSimCall ?: return false
        active.cancel()
        return true
    }

    fun deleteTelegramCallRecord(id: String): Boolean = TelegramCallHistory(app).delete(id)

    @Suppress("DEPRECATION")
    private fun batteryStatus(): String {
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0
        val levelText = if (percent >= 0) "$percent%" else "المستوى غير متاح"
        return "البطارية $levelText؛ ${if (charging) "قيد الشحن/موصول بالطاقة" else "غير موصولة بالشاحن"}"
    }

    /** Reports local call history, measured audio-path status, and battery state; carrier charges are not inferred. */
    fun telegramAccountReport(): String {
        val records = TelegramCallHistory(app).list(100)
        if (records.isEmpty()) return "لا توجد مكالمات SIM مسجلة حتى الآن."
        val answered = records.filter { it.answeredAtMs != null }
        val totalSeconds = answered.sumOf { it.durationSeconds }
        val lines = records.take(8).joinToString("\n") { record ->
            "#${record.id} ${TelegramSimCall.maskNumber(record.number)} · ${record.result} · ${TelegramSimCall.formatDuration(record.durationSeconds)}\n  الصوت: ${record.audioStatus.take(120)}\n  🔋 ${record.batteryStatus.take(60)}"
        }
        return "سجل مكالمات SIM: ${records.size} (آخر 8 أدناه)\nتم الرد: ${answered.size} · مجموع مدة SIM بعد الرد: ${TelegramSimCall.formatDuration(totalSeconds)}\n$lines\nلحذف سجل: /simcall_delete <id>\nلا تُستنتج رسوم المشغّل من هذا السجل."
    }

    fun applySettings(new: GatewaySettings) = worker.execute {
        require(new.audioMethod in 0..2 && new.callAccess in 0..2) { "unknown platform setting" }
        if (hasActiveWork()) {
            pendingSettings = new
            _health.update { it.copy(settingsPending = true) }
            AppLog.i("settings will apply after current calls end")
            return@execute
        }
        val old = settings
        settingsStore.save(new)
        _settings.value = new
        AppLog.i("settings saved ($new)")
        val platformOnly = old.copy(audioMethod = new.audioMethod, callAccess = new.callAccess,
            muteEarpiece = new.muteEarpiece, sipDebugLog = new.sipDebugLog,
            keepDefaultDialer = new.keepDefaultDialer) == new
        if (!platformOnly) { startSip(); rebuildBridge() }
        configureCallAccess()
        runSetup()
        updateReadiness()
    }

    /** pjsip info-level logging; takes effect immediately, persisted for the next start. */
    fun setSipDebugLog(on: Boolean) = worker.execute {
        settingsStore.save(settings.copy(sipDebugLog = on).also { _settings.value = it })
        AppLog.i("SIP debug log ${if (on) "on" else "off"}")
    }

    /** Read at each call's audio setup, so no restart is needed. */
    fun setMuteEarpiece(on: Boolean) = worker.execute {
        settingsStore.save(settings.copy(muteEarpiece = on).also { _settings.value = it })
        AppLog.i("earpiece mute ${if (on) "on" else "off"} (from next call)")
    }

    /** UI-only preference; never touches SIP or the bridge. */
    fun setUiLanguage(lang: String) = worker.execute {
        if (lang == settings.uiLanguage) return@execute
        settingsStore.save(settings.copy(uiLanguage = lang).also { _settings.value = it })
    }

    /** Changes the next switch-off, without touching the current call or any queued handoff. */
    fun setKeepDefaultDialer(on: Boolean) = worker.execute {
        settingsStore.save(settings.copy(keepDefaultDialer = on).also { _settings.value = it })
        pendingSettings = pendingSettings?.copy(keepDefaultDialer = on)
    }

    /** The master switch. Switching off mid-call waits for that call to end. */
    fun setTakeover(on: Boolean) = worker.execute {
        refreshRoleState()
        takeoverControl.request(on)
        attemptDialerChange()
        updateReadiness()
    }

    private fun applyTakeover() {
        val on = settings.takeover
        _health.update { it.copy(takeover = on, takeoverOffPending = false, takeoverOnPending = false) }
        configureCallAccess()
        if (on) startGateway() else stopGateway()
        // Withdraws or restores the Telecom companion override.
        runSetup()
        updateReadiness()
        AppLog.i(if (on) "call takeover ON" else "call takeover OFF")
    }

    fun setLoopbackTest(on: Boolean) = worker.execute {
        if (hasActiveWork()) { loopbackPending = on; _health.update { it.copy(settingsPending = true) }; return@execute }
        if (_health.value.loopbackTest == on) return@execute
        _health.update { it.copy(loopbackTest = on) }
        AppLog.i("loopback test ${if (on) "ON: inbound cellular calls are answered and echoed" else "OFF"}")
        rebuildBridge()
        updateReadiness()
    }

    fun restartSip() = worker.execute {
        if (hasActiveWork()) { restartPending = true; _health.update { it.copy(settingsPending = true) }; return@execute }
        startSip()
        rebuildBridge()
        updateReadiness()
    }

    private fun applyDeferredChanges() {
        if (hasActiveWork()) return
        takeoverControl.applyIfIdle()
        if (pendingDialerChange != null) attemptDialerChange()
        val queued = pendingSettings
        pendingSettings = null
        val restart = restartPending
        restartPending = false
        val loopback = loopbackPending
        loopbackPending = null
        val aiAutoAnswer = aiAutoAnswerPending
        aiAutoAnswerPending = null
        _health.update { it.copy(settingsPending = false) }
        if (queued != null) applySettings(queued.copy(takeover = settings.takeover))
        if (loopback != null) setLoopbackTest(loopback)
        if (aiAutoAnswer != null) applyAiAutoAnswer(aiAutoAnswer)
        if (restart) restartSip()
        configureCallAccess()
        updateReadiness()
        if (setupPending || queued != null || _health.value.privileged == null ||
            (_health.value.effectiveCallAccess == CallAccess.COMPANION && !_health.value.callAccessReady)) runSetup()
    }

    fun statusReport(): String {
        val h = _health.value
        return buildString {
            appendLine("takeover=${h.takeover} pendingOff=${h.takeoverOffPending} pendingOn=${h.takeoverOnPending} " +
                "keepDefaultDialer=${settings.keepDefaultDialer} muteEarpiece=${settings.muteEarpiece}")
            appendLine("shizuku=${h.shizuku} (${h.shizukuDetail})")
            appendLine("privileged=${h.privileged} ${h.privilegedDetail}")
            appendLine("sip=${h.sip.detail} ready=${h.sip.ready} stack=builtin " +
                "transport=${settings.sipTransport} srtp=${settings.srtp} debugLog=${settings.sipDebugLog}")
            appendLine("cellular=${cellular.status.detail} inCallServiceBound=${CellularEndpoint.isInCallServiceBound} " +
                "service=${CellularEndpoint.boundServiceDescription ?: "none"}")
            appendLine("sdk=${Build.VERSION.SDK_INT} callAccess=${settings.callAccess} effective=${h.effectiveCallAccess} " +
                "defaultDialer=${h.holdsDialer} audioMethod=${settings.audioMethod} audioReady=${h.audioReady}")
            appendLine("dialerChangePending=${h.dialerChangePending} running=${h.dialerChangeRunning} ${_dialerMessage.value}")
            appendLine("calls=${h.calls.summary} settingsPending=${h.settingsPending}")
            appendLine("loopback=${h.loopbackTest} session=${h.session}")
            shizuku.service?.let { appendLine("tap=${runCatching { it.audioStats() }.getOrDefault("?")}") }
        }
    }

    /** Save a matching customer's incoming SIM SMS before forwarding it to the SIP handset. */
    fun receiveSms(from: String?, text: String, receivedAt: Long) = worker.execute {
        runCatching { customers.recordIncomingSms(from, text, receivedAt) }
            .onFailure { AppLog.w("could not persist incoming customer SMS", it) }
        val s = sip
        if (s is SipUserAgent && s.sendMessage(from, text)) {
            AppLog.i("SMS forwarded to the registered phone")
        } else {
            AppLog.w("SMS not forwarded: no phone registered")
        }
    }

    /** Forward an incoming cellular SMS to the registered phone (server mode) as a SIP MESSAGE. */
    fun forwardSms(from: String?, text: String) = worker.execute {
        val s = sip
        if (s is SipUserAgent && s.sendMessage(from, text)) {
            AppLog.i("SMS forwarded to the registered phone")
        } else {
            AppLog.w("SMS not forwarded: no phone registered")
        }
    }

    companion object {
        @Volatile private var instance: GatewayRuntime? = null

        internal fun newWorker() = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "gateway-runtime").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(1, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }

        fun get(context: Context): GatewayRuntime =
            instance ?: synchronized(this) { instance ?: GatewayRuntime(context).also { instance = it } }
    }
}
