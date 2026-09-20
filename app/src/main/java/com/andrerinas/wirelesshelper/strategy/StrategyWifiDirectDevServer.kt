package com.andrerinas.wirelesshelper.strategy

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.andrerinas.wirelesshelper.devserver.*
import com.andrerinas.wirelesshelper.net.P2pLink
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

class StrategyWifiDirectDevServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener,
) : ConnectionStrategy {
    enum class Status { STARTING, SEARCHING, P2P_CONNECTED, SERVER_READY, WAITING_SERVER, CONNECTING, TCP_CONNECTED, RECOVERING, STOPPING }
    interface Listener {
        fun onStatus(status: Status)
        fun onFailure(error: DevServerError)
    }

    private val prefs = context.getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
    private val policy = DevSessionPolicy()
    private var job: Job? = null
    private var client: WifiDirectClient? = null
    private var controller: DevServerController? = null
    private var peer: P2pLink? = null
    private var running = false
    private var bridge: DevServerBridge? = null
    private var bridgeJob: Job? = null
    private var bridgeToken: Long? = null
    private var lastOwnedConnection: DevServerConnection? = null
    private var lastStatus: Status? = null

    companion object {
        const val MODE = DevModePolicy.MODE_ID
        const val REQUESTED_KEY = "dev_root_requested"
        const val MANAGED_KEY = "dev_root_managed"
    }

    override fun start() {
        if (job?.isActive == true) return
        running = true
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                status(Status.STARTING)
                val runner = SuCommandRunner()
                DevServerController.checkRoot(runner)
                val server = AndroidDevServer.create(context, runner)
                controller = server
                AndroidDevServer.ensureWifi(context, runner)
                // Persist before the command: cancellation may arrive after AA has started.
                if (!prefs.edit().putBoolean(MANAGED_KEY, true).commit())
                    throw DevServerException(DevServerError.STATE_UNAVAILABLE)
                server.start()
                val stored = prefs.getStringSet("wifi_direct_target_names", setOf("OpenHU", "HURev")) ?: emptySet()
                val targets = if (stored == setOf("HURev")) stored + "OpenHU" else stored
                client = WifiDirectClient(context, scope, targets, requireClientRole = true, listener = object : WifiDirectClient.Listener {
                    override fun onConnected(host: String) {
                        if (running && peer == null) status(Status.P2P_CONNECTED)
                    }
                    override fun onDisconnected() {
                        if (running) status(Status.SEARCHING)
                    }
                    override fun onUnavailable() { if (running) status(Status.SEARCHING) }
                    override fun onNetworkChanged(link: P2pLink?) {
                        if (!running) return
                        peer = link
                        if (policy.onPeer(link, now())) bridge?.close()
                        status(if (link == null) Status.SEARCHING else Status.P2P_CONNECTED)
                    }
                }).also { it.start() }
                status(Status.SEARCHING)
                var stoppedForLoss = false
                var nextLossCheck = 0L
                while (isActive) {
                    if (bridgeJob?.isCompleted == true) {
                        finishBridge()
                        status(if (peer == null) Status.SEARCHING else Status.RECOVERING)
                    }
                    if (bridgeJob == null) {
                        val link = peer
                        if (link == null) {
                            if (!stoppedForLoss && now() >= nextLossCheck && policy.shouldStopServer(now())) {
                                val state = server.readState()
                                nextLossCheck = now() + 2000
                                if (policy.shouldStopServer(now()) && !state.hasOtherConnection(lastOwnedConnection)) {
                                    server.stop()
                                    stoppedForLoss = true
                                    policy.serverRecovered()
                                    lastOwnedConnection = null
                                }
                            }
                        } else {
                            stoppedForLoss = false
                            val token = policy.beginAttempt(now())
                            if (token != null) prepareBridge(server, link, token)
                        }
                    }
                    // No root reads while connecting/streaming: socket events own the session.
                    delay(250)
                }
            } catch (_: TimeoutCancellationException) {
                listener.onFailure(DevServerError.STATE_UNAVAILABLE)
            } catch (e: CancellationException) { throw e
            } catch (e: DevServerException) {
                listener.onFailure(e.error)
            } catch (e: Exception) {
                Log.e("HUREV_DEV", "Dev server session failed", e)
                listener.onFailure(DevServerError.STATE_UNAVAILABLE)
            } finally {
                running = false
                policy.stop()
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    bridge?.close()
                    bridgeJob?.cancelAndJoin()
                    bridgeJob = null
                    bridge = null
                    if (prefs.getBoolean(MANAGED_KEY, false)) {
                        try {
                            withTimeout(15_000) { controller?.stop() }
                            if (controller != null) prefs.edit().putBoolean(MANAGED_KEY, false).commit()
                        } catch (e: Exception) {
                            Log.w("HUREV_DEV", "Dev server cleanup incomplete; keeping recovery marker", e)
                            listener.onFailure(devCleanupError(e))
                        }
                    }
                    client?.stop(releaseGroup = true)
                    client = null
                }
            }
        }
    }

    private suspend fun prepareBridge(server: DevServerController, link: P2pLink, token: Long) {
        try {
            var state = server.readState()
            if (!policy.isCurrent(token)) {
                policy.finished(token, now(), recoverServer = false, failed = false)
                return
            }
            if (state.hasOtherConnection(lastOwnedConnection)) {
                status(Status.WAITING_SERVER)
                policy.waitForOtherSession(token, now())
                return
            }
            if (policy.restartRequired) {
                status(Status.RECOVERING)
                state = server.restart()
                policy.serverRecovered()
                lastOwnedConnection = null
            } else if (!state.ready) {
                state = server.start()
            }
            if (!policy.isCurrent(token)) {
                policy.finished(token, now(), recoverServer = false, failed = false)
                return
            }
            if (state.hasOtherConnection(null)) {
                status(Status.WAITING_SERVER)
                policy.waitForOtherSession(token, now())
                return
            }
            val localAddresses = withContext(Dispatchers.IO) {
                NetworkInterface.getNetworkInterfaces()?.toList()
                    ?.flatMap { it.inetAddresses.toList() }?.toSet() ?: emptySet()
            }
            if (!policy.isCurrent(token)) {
                policy.finished(token, now(), recoverServer = false, failed = false)
                return
            }
            val endpoint = state.localEndpoint(localAddresses)
                ?: throw DevServerException(DevServerError.LOCAL_ENDPOINT_UNAVAILABLE)
            status(Status.SERVER_READY)
            val next = DevServerBridge()
            bridge = next
            bridgeToken = token
            status(Status.CONNECTING)
            bridgeJob = CoroutineScope(currentCoroutineContext()).launch {
                try {
                    next.run(InetSocketAddress(link.host, 5288), InetAddress.getByName(link.localAddress),
                        InetSocketAddress(endpoint, 5277)) {
                        withContext(Dispatchers.Main.immediate) {
                            if (!policy.isCurrent(token)) throw CancellationException("P2P link changed")
                            policy.connected(token, now())
                            status(Status.TCP_CONNECTED)
                        }
                    }
                } catch (e: DevServerBridge.Failure) {
                    Log.w("HUREV_DEV", "Bridge ended at ${e.stage}; retry is delayed")
                }
            }
        } catch (e: DevServerException) {
            if (e.error != DevServerError.START_FAILED) throw e
            policy.finished(token, now(), recoverServer = false)
            status(Status.RECOVERING)
            Log.w("HUREV_DEV", "Dev server not ready; retry is delayed")
        }
    }

    private suspend fun finishBridge() {
        bridgeJob?.join()
        val old = bridge
        lastOwnedConnection = old?.connection ?: lastOwnedConnection
        bridgeToken?.let { policy.finished(it, now(), recoverServer = old?.needsServerRecovery == true) }
        bridgeJob = null
        bridgeToken = null
        bridge = null
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun status(status: Status) {
        if (status == lastStatus) return
        lastStatus = status
        listener.onStatus(status)
    }

    suspend fun shutdown() {
        running = false
        policy.stop()
        bridge?.close()
        status(Status.STOPPING)
        job?.cancelAndJoin()
        job = null
    }

    override fun stop() {
        running = false
        policy.stop()
        bridge?.close()
        job?.cancel()
    }
}
