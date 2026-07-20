package com.andrerinas.wirelesshelper.strategy

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import kotlinx.coroutines.*

class StrategyWifiDirect(context: Context, scope: CoroutineScope) : BaseStrategy(context, scope) {

    // WiFi Direct (P2P)
    private val p2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver? = null
    private var targetDeviceNames: Set<String> = emptySet()
    private var isConnectingToPeer = false

    override fun start() {
        val prefs = context.getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        targetDeviceNames = prefs.getStringSet("wifi_direct_target_names", setOf("HURev")) ?: setOf("HURev")

        Log.i(TAG, "Strategy: WiFi Direct (Targets: $targetDeviceNames)")
        
        setupP2p()
    }

    private fun setupP2p() {
        if (p2pManager == null) return
        val channel = p2pManager.initialize(context, context.mainLooper, null)
        p2pChannel = channel

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
                                    isConnectingToPeer = false
                                    // FORCE FAKE NETWORK 0 for correct P2P routing
                                    launchAndroidAuto(host)
                                }
                            }
                        } else {
                            isConnectingToPeer = false
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

                Log.i(TAG, "Existing WiFi Direct group found. Owner: $host")

                isConnectingToPeer = false
                launchAndroidAuto(host)

            } else {
                Log.i(TAG, "No existing WiFi Direct group start discovering")
                startDiscoveryLoop()
            }
        }
    }

    private fun startDiscoveryLoop() {
        getStrategyScope().launch {
            while (isActive) {
                if (!isConnectingToPeer && !isLaunching.get()) {
                    Log.i(TAG, "Scanning....")
                    discoverPeers()
                }
                delay(5000) // Restart discovery every 5 seconds
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
        Log.i(TAG, "Attempting to connect to P2P device (PBC): ${device.deviceName}")
        p2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "P2P Connect initiated") }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "P2P Connect failed: $reason")
                isConnectingToPeer = false
            }
        })
    }

    override fun stop() {
        val channel = p2pChannel
        super.stop()

        try { context.unregisterReceiver(p2pReceiver) } catch (e: Exception) {}
        p2pReceiver = null
        
        if (channel != null) {
            @SuppressLint("MissingPermission")
            p2pManager?.stopPeerDiscovery(channel, null)
        }
        p2pChannel = null
        isConnectingToPeer = false
    }
}
