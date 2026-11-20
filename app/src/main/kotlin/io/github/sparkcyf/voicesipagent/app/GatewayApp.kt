package io.github.sparkcyf.voicesipagent.app

import android.app.Application

class GatewayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Telecom may start our process just to bind the InCallService, so the runtime (and with
        // it the CellularEndpoint) must exist before any activity or service runs.
        val runtime = GatewayRuntime.get(this)
        runtime.start()
        GatewayService.start(this)
    }
}
