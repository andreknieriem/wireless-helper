package com.andrerinas.wirelesshelper.strategy

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*

class StrategyWifiDirect(context: Context, scope: CoroutineScope) : BaseStrategy(context, scope) {

    // WiFi Direct (P2P)
    private val p2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver? = null
    private var targetDeviceNames: Set<String> = emptySet()
    private var isConnectingToPeer = false

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var connectTimeoutRunnable: Runnable? = null
    private val CONNECT_TIMEOUT_MS = 20_000L // long enough for a human to tap the dialog
    private val GROUP_REUSE_VERIFY_TIMEOUT_MS = 7_000L // shorter than launchAndroidAuto's own 15s timeout

    private fun scheduleConnectTimeout() {
        clearConnectTimeout()
        val runnable = Runnable {
            connectTimeoutRunnable = null
            Log.w(TAG, "P2P connect attempt timed out; cancelling and resetting for retry")
            val channel = p2pChannel
            if (channel != null && p2pManager != null) {
                @SuppressLint("MissingPermission")
                p2pManager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { Log.d(TAG, "cancelConnect succeeded after timeout") }
                    override fun onFailure(reason: Int) { Log.d(TAG, "cancelConnect failed after timeout: $reason") }
                })
            }
            isConnectingToPeer = false
        }
        connectTimeoutRunnable = runnable
        timeoutHandler.postDelayed(runnable, CONNECT_TIMEOUT_MS)
    }

    private fun clearConnectTimeout() {
        connectTimeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        connectTimeoutRunnable = null
    }

    // deletePersistentGroup isn't public SDK, called via reflection like headunit-revived's
    // WifiDirectManager.kt does. No reflectable way to list real netIds, so sweep a small fixed
    // range — onFailure for a netId that doesn't exist is expected/harmless.
    @SuppressLint("MissingPermission")
    private fun forgetAllPersistentGroups(channel: WifiP2pManager.Channel) {
        val mgr = p2pManager ?: return
        try {
            val method = mgr.javaClass.getMethod(
                "deletePersistentGroup",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java
            )
            for (netId in 0..9) {
                method.invoke(mgr, channel, netId, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { Log.d(TAG, "Forgot persistent group netId=$netId") }
                    override fun onFailure(reason: Int) { /* expected when netId doesn't exist */ }
                })
            }
        } catch (e: Exception) {
            Log.w(TAG, "deletePersistentGroup reflection unavailable: ${e.message}")
        }
    }

    override fun start() {
        val prefs = context.getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        targetDeviceNames = prefs.getStringSet("wifi_direct_target_names", setOf("OpenHU", "HURev")) ?: setOf("OpenHU", "HURev")

        Log.i(TAG, "Strategy: WiFi Direct (Targets: $targetDeviceNames)")

        setupP2p()
    }

    private fun setupP2p() {
        if (p2pManager == null) return
        val channel = p2pManager.initialize(context, context.mainLooper, null)
        p2pChannel = channel
        forgetAllPersistentGroups(channel)

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }

        p2pReceiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        p2pManager.requestPeers(p2pChannel) { peers ->
                            if (targetDeviceNames.isEmpty()) return@requestPeers

                            // Log found peers for debugging
                            if (peers.deviceList.isNotEmpty()) {
                                Log.d(TAG, "P2P Peers found: ${peers.deviceList.size}")
                                for (device in peers.deviceList) {
                                    val statusText = when(device.status) {
                                        WifiP2pDevice.CONNECTED -> "CONNECTED"
                                        WifiP2pDevice.INVITED -> "INVITED"
                                        WifiP2pDevice.FAILED -> "FAILED"
                                        WifiP2pDevice.AVAILABLE -> "AVAILABLE"
                                        WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
                                        else -> "UNKNOWN (${device.status})"
                                    }
                                    Log.d(TAG, "  - Found: ${device.deviceName} Status: $statusText")
                                }
                            }

                            // Match against any of the target names
                            val match = peers.deviceList.find { device ->
                                targetDeviceNames.any { target -> device.deviceName.contains(target, ignoreCase = true) }
                            }

                            if (match != null && match.status == WifiP2pDevice.FAILED && isConnectingToPeer) {
                                Log.w(TAG, "P2P peer ${match.deviceName} reported FAILED status; resetting for retry")
                                clearConnectTimeout()
                                isConnectingToPeer = false
                            }

                            if (match != null && !isConnectingToPeer) {
                                if (match.status == WifiP2pDevice.AVAILABLE) {
                                    connectToPeer(match)
                                } else if (match.status == WifiP2pDevice.INVITED) {
                                    Log.i(TAG, "Already invited to ${match.deviceName}, waiting for acceptance...")
                                }
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            p2pManager.requestConnectionInfo(p2pChannel) { info ->
                                if (info.groupFormed) {
                                    val host = info.groupOwnerAddress.hostAddress
                                    Log.i(TAG, "WiFi Direct connected. Group Owner: $host")
                                    clearConnectTimeout()
                                    isConnectingToPeer = false
                                    // FORCE FAKE NETWORK 0 for correct P2P routing
                                    launchAndroidAuto(host)
                                }
                            }
                        } else {
                            // Not a reliable success/failure signal on its own — can fire on
                            // transient intermediate states while an invitation is still
                            // outstanding. Don't clear isConnectingToPeer here; the connect
                            // timeout watchdog and FAILED peer-status check own that instead.
                            Log.d(TAG, "P2P connection changed: not connected")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            Log.d(TAG, "WiFi Direct is enabled")
                        } else {
                            Log.w(TAG, "WiFi Direct is disabled")
                        }
                    }
                }
            }
        }

        context.registerReceiver(p2pReceiver, intentFilter)

        p2pManager.requestConnectionInfo(channel) { info ->
            val host = info?.groupOwnerAddress?.hostAddress
            if (info != null && info.groupFormed && host != null) {
                // Reuse the existing group instead of always tearing it down (PR #60 made
                // teardown unconditional as a workaround, not the real fix). Fall back to a
                // clean teardown + fresh discovery if this group turns out to be stale.
                Log.i(TAG, "Existing WiFi Direct group found. Owner: $host — reusing without renegotiating")
                isConnectingToPeer = false
                launchAndroidAuto(host)

                getStrategyScope().launch {
                    delay(GROUP_REUSE_VERIFY_TIMEOUT_MS)
                    if (!connectionEstablished.get()) {
                        Log.w(TAG, "Reused group produced no connection within ${GROUP_REUSE_VERIFY_TIMEOUT_MS}ms — falling back to clean teardown and fresh discovery")
                        p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { Log.d(TAG, "Fallback group removal success") }
                            override fun onFailure(reason: Int) { Log.d(TAG, "Fallback group removal failed: $reason") }
                        })
                        startDiscoveryLoop()
                    }
                }

            } else {
                startDiscoveryLoop()
            }
        }
    }

    private fun startDiscoveryLoop() {
        getStrategyScope().launch {
            while (isActive) {
                if (!isConnectingToPeer && !isLaunching.get()) {
                    discoverPeers()
                }
                delay(10000) // Restart discovery every 10 seconds
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeers() {
        val channel = p2pChannel ?: return

        // Always stop previous discovery to refresh the list
        p2pManager?.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                p2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { Log.d(TAG, "P2P Peer Discovery Started (Loop)") }
                    override fun onFailure(reason: Int) { Log.e(TAG, "P2P Peer Discovery failed: $reason") }
                })
            }
            override fun onFailure(reason: Int) {
                // If stop fails, try start anyway
                p2pManager.discoverPeers(channel, null)
            }
        })
    }

    @Deprecated("Use discoverPeers in loop", ReplaceWith("startDiscoveryLoop"))
    private fun discoverPeersWithRetry() {
        discoverPeers()
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: WifiP2pDevice) {
        val channel = p2pChannel ?: return
        val config = android.net.wifi.p2p.WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = android.net.wifi.WpsInfo.PBC
        }

        isConnectingToPeer = true
        scheduleConnectTimeout()
        Log.i(TAG, "Attempting to connect to P2P device (PBC): ${device.deviceName}")
        p2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "P2P Connect initiated") }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "P2P Connect failed: $reason")
                clearConnectTimeout()
                isConnectingToPeer = false
            }
        })
    }

    override fun stopForLaunch() {
        Log.d(TAG, "P2P stopForLaunch: pausing discovery loop while keeping active P2P connection")
        super.stop()
        val channel = p2pChannel
        if (channel != null && p2pManager != null) {
            @SuppressLint("MissingPermission")
            p2pManager.stopPeerDiscovery(channel, null)
        }
    }

    override fun stop() {
        val channel = p2pChannel
        val manager = p2pManager
        super.stop()

        clearConnectTimeout()

        try {
            p2pReceiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister P2P receiver: ${e.message}")
        }
        p2pReceiver = null

        if (channel != null && manager != null) {
            Log.i(TAG, "Stopping WiFi Direct Strategy and removing P2P group")
            @SuppressLint("MissingPermission")
            manager.stopPeerDiscovery(channel, null)
        }
        p2pChannel = null
        isConnectingToPeer = false
    }
}
