package com.andrerinas.wirelesshelper.strategy

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** All framework calls and callbacks are confined to the main looper. */
@SuppressLint("MissingPermission")
class WifiDirectClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val targets: Set<String>,
    private val requireClientRole: Boolean = false,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected(host: String)
        fun onDisconnected()
        fun onUnavailable()
    }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var running = false
    private var generation = 0
    private var registered = false
    private var connectedHost: String? = null
    private var joinedTarget = false
    private var targetAddress: String? = null
    private var connectingAt: Long? = null
    private var discoveryJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var discoveryPaused = false
    var reusedExistingGroup = true
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!running) return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestInfo()
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED) requestInfo()
                    else { disconnected(); listener.onUnavailable() }
                }
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        generation++
        if (manager == null) { listener.onUnavailable(); return }
        initializeChannel()
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        // These are protected framework broadcasts; some OEM Wi-Fi services use a non-system UID.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        requestInfo()
        discoveryPaused = false
        startLoop()
    }

    private fun startLoop() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch(Dispatchers.Main.immediate) {
            var nextDiscoveryAt = 0L
            while (running) {
                if (channel == null) initializeChannel()
                requestInfo()
                val now = SystemClock.elapsedRealtime()
                if (!discoveryPaused && connectedHost == null && connectingAt == null && now >= nextDiscoveryAt) {
                    discover()
                    nextDiscoveryAt = now + 10_000
                }
                delay(if (requireClientRole) 1000 else 10_000)
            }
        }
    }

    private fun initializeChannel() {
        channel = manager?.initialize(context, context.mainLooper) {
            if (running) {
                generation++
                channel = null
                connectingAt = null
                disconnected()
                listener.onUnavailable()
            }
        }
        if (!requireClientRole) channel?.let { forgetLegacyGroups(it) }
    }

    private fun forgetLegacyGroups(ch: WifiP2pManager.Channel) {
        // Preserve the old strategy's workaround, but never purge saved groups in Dev mode.
        runCatching {
            val mgr = manager ?: return
            val method = mgr.javaClass.getMethod("deletePersistentGroup", WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType, WifiP2pManager.ActionListener::class.java)
            for (id in 0..9) method.invoke(mgr, ch, id, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { }
                override fun onFailure(reason: Int) { }
            })
        }.onFailure { Log.d("HUREV_WIFI", "Legacy persistent-group workaround unavailable") }
    }

    private fun discover() {
        val ch = channel ?: return
        val gen = generation
        manager?.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = again()
            override fun onFailure(reason: Int) = again()
            private fun again() {
                if (running && gen == generation && connectedHost == null && !discoveryPaused) {
                    manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { requestPeers() }
                        override fun onFailure(reason: Int) { Log.d("HUREV_WIFI", "P2P discovery: $reason") }
                    })
                }
            }
        })
    }

    private fun requestPeers() {
        val ch = channel ?: return
        val gen = generation
        manager?.requestPeers(ch) { peers ->
            if (!running || gen != generation || connectedHost != null || discoveryPaused) return@requestPeers
            val peer = peers.deviceList.firstOrNull { matches(it.deviceName) } ?: return@requestPeers
            if (peer.status == WifiP2pDevice.FAILED) connectingAt = null
            if (connectingAt != null || peer.status != WifiP2pDevice.AVAILABLE) return@requestPeers
            val config = WifiP2pConfig().apply {
                deviceAddress = peer.deviceAddress
                wps.setup = WpsInfo.PBC
                if (requireClientRole) groupOwnerIntent = 0
            }
            connectingAt = SystemClock.elapsedRealtime()
            targetAddress = peer.deviceAddress
            reusedExistingGroup = false
            connectTimeoutJob?.cancel()
            connectTimeoutJob = scope.launch(Dispatchers.Main.immediate) {
                delay(20_000)
                if (running && gen == generation && connectedHost == null) {
                    manager?.cancelConnect(ch, null)
                    connectingAt = null
                }
            }
            manager?.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { }
                override fun onFailure(reason: Int) {
                    if (gen == generation) { connectingAt = null; connectTimeoutJob?.cancel() }
                    Log.d("HUREV_WIFI", "P2P connect: $reason")
                }
            })
        }
    }

    private fun matches(name: String?): Boolean = name != null && targets.any { name.contains(it, true) }

    private fun requestInfo() {
        val ch = channel ?: return
        val gen = generation
        manager?.requestConnectionInfo(ch) { info ->
            if (!running || gen != generation) return@requestConnectionInfo
            if (info?.groupFormed != true) { disconnected(); return@requestConnectionInfo }
            manager?.requestGroupInfo(ch) { group ->
                if (!running || gen != generation) return@requestGroupInfo
                // Never adopt or remove a group belonging to another device/application.
                val ownTarget = matches(group?.owner?.deviceName) || matches(group?.networkName) ||
                    (targetAddress != null && group?.owner?.deviceAddress == targetAddress)
                if (requireClientRole && (info.isGroupOwner || !ownTarget)) {
                    disconnected()
                    listener.onUnavailable()
                    return@requestGroupInfo
                }
                connected(info)
            }
        }
    }

    private fun connected(info: WifiP2pInfo) {
        val host = info.groupOwnerAddress?.hostAddress ?: return
        connectingAt = null
        connectTimeoutJob?.cancel()
        joinedTarget = true
        if (connectedHost == host) return
        connectedHost = host
        channel?.let { manager?.stopPeerDiscovery(it, null) }
        listener.onConnected(host)
    }

    private fun disconnected() {
        if (connectedHost == null) return // Intermediate broadcasts must not cancel an invitation.
        connectedHost = null
        joinedTarget = false
        discoveryPaused = false
        listener.onDisconnected()
    }

    fun pauseDiscovery() {
        discoveryPaused = true
        channel?.let { manager?.stopPeerDiscovery(it, null) }
    }

    suspend fun resetConnection() {
        val ch = channel ?: return
        removeGroup(ch)
        connectedHost = null
        joinedTarget = false
        connectingAt = null
        targetAddress = null
        discoveryPaused = false
        if (running) discover()
    }

    suspend fun stop(releaseGroup: Boolean) {
        running = false
        generation++
        discoveryJob?.cancel()
        connectTimeoutJob?.cancel()
        discoveryJob = null
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        val ch = channel
        if (ch != null) {
            manager?.stopPeerDiscovery(ch, null)
            if (connectingAt != null) manager?.cancelConnect(ch, null)
            // Stop can beat the connection-info callback after an invitation was accepted.
            if (releaseGroup && ownsTargetGroup(ch)) removeGroup(ch)
            if (android.os.Build.VERSION.SDK_INT >= 27) ch.close()
        }
        channel = null
        connectedHost = null
        joinedTarget = false
        connectingAt = null
    }

    private suspend fun ownsTargetGroup(ch: WifiP2pManager.Channel): Boolean = withTimeoutOrNull(3000) {
        suspendCancellableCoroutine<Boolean> { continuation ->
            manager?.requestGroupInfo(ch) { group ->
                val owned = group != null && (matches(group.owner?.deviceName) || matches(group.networkName) ||
                    (targetAddress != null && group.owner?.deviceAddress == targetAddress) ||
                    group.clientList.any { it.deviceAddress == targetAddress || matches(it.deviceName) })
                if (continuation.isActive) continuation.resume(owned)
            } ?: continuation.resume(false)
        }
    } ?: joinedTarget

    private suspend fun removeGroup(ch: WifiP2pManager.Channel) {
        withTimeoutOrNull(3000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                    override fun onFailure(reason: Int) { if (continuation.isActive) continuation.resume(Unit) }
                }) ?: continuation.resume(Unit)
            }
        }
    }
}
