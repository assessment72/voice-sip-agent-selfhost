package io.github.sparkcyf.voicesipagent.app

import android.app.Application

class GatewayApp : Application() {
    private var webControl: WebControlServer? = null

    override fun onCreate() {
        super.onCreate()
        // Telecom may start our process just to bind the InCallService, so the runtime (and with
        // it the CellularEndpoint) must exist before any activity or service runs.
        GatewayRuntime.get(this).start()
        GatewayService.start(this)
        webControl = WebControlServer(GatewayRuntime.get(this), ClinicEmployeeStore(this)).also { it.start() }
    }
}
