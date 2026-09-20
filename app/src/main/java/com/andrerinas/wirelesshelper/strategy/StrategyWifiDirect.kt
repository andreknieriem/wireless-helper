package com.andrerinas.wirelesshelper.strategy

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StrategyWifiDirect(context: Context, private val scope: CoroutineScope) : BaseStrategy(context, scope) {
    private var client: WifiDirectClient? = null
    private var verifyJob: Job? = null

    override fun start() {
        val prefs = context.getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val stored = prefs.getStringSet("wifi_direct_target_names", setOf("OpenHU", "HURev")) ?: emptySet()
        val targets = if (stored == setOf("HURev")) stored + "OpenHU" else stored
        client = WifiDirectClient(context, scope, targets, listener = object : WifiDirectClient.Listener {
            override fun onConnected(host: String) {
                launchAndroidAuto(host)
                verifyJob?.cancel()
                if (client?.reusedExistingGroup != true) return
                verifyJob = scope.launch(Dispatchers.Main.immediate) {
                    delay(7000)
                    if (!connectionEstablished.get()) client?.resetConnection()
                }
            }
            override fun onDisconnected() { }
            override fun onUnavailable() { }
        }).also { it.start() }
    }

    override fun stopForLaunch() {
        super.stop()
        client?.pauseDiscovery()
    }

    override fun stop() {
        verifyJob?.cancel()
        verifyJob = null
        val old = client
        client = null
        scope.launch(Dispatchers.Main.immediate) { old?.stop(releaseGroup = false) }
        super.stop()
    }
}
