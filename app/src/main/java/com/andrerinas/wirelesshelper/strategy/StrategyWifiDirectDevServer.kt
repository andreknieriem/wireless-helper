package com.andrerinas.wirelesshelper.strategy

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.andrerinas.wirelesshelper.devserver.*
import kotlinx.coroutines.*

class StrategyWifiDirectDevServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener,
) : ConnectionStrategy {
    enum class Status { STARTING, SEARCHING, P2P_CONNECTED, SERVER_READY, TCP_CONNECTED, RECOVERING, STOPPING }
    interface Listener {
        fun onStatus(status: Status)
        fun onFailure(error: DevServerError)
    }

    private val prefs = context.getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
    private val policy = DevSessionPolicy()
    private var job: Job? = null
    private var client: WifiDirectClient? = null
    private var controller: DevServerController? = null
    private var peer: String? = null
    private var lastStatus: Status? = null

    companion object {
        const val MODE = DevModePolicy.MODE_ID
        const val REQUESTED_KEY = "dev_root_requested"
        const val MANAGED_KEY = "dev_root_managed"
    }

    override fun start() {
        if (job?.isActive == true) return
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
                        peer = host
                        policy.onPeer(host, SystemClock.elapsedRealtime())
                        status(Status.P2P_CONNECTED)
                    }
                    override fun onDisconnected() {
                        peer = null
                        policy.onPeer(null, SystemClock.elapsedRealtime())
                        status(Status.SEARCHING)
                    }
                    override fun onUnavailable() { status(Status.SEARCHING) }
                }).also { it.start() }
                status(Status.SEARCHING)
                while (isActive) {
                    val state = server.readState()
                    val now = SystemClock.elapsedRealtime()
                    when (val action = policy.action(now, state)) {
                        DevSessionPolicy.Action.NONE -> status(when {
                            peer == null -> Status.SEARCHING
                            state.connectedTo(peer!!) -> Status.TCP_CONNECTED
                            state.ready -> Status.SERVER_READY
                            else -> Status.P2P_CONNECTED
                        })
                        else -> {
                            status(Status.RECOVERING)
                            try {
                                when (action) {
                                    DevSessionPolicy.Action.START -> server.start()
                                    DevSessionPolicy.Action.STOP -> server.stop()
                                    DevSessionPolicy.Action.RESTART -> server.restart()
                                    else -> Unit
                                }
                                policy.attempted(SystemClock.elapsedRealtime())
                            } catch (e: DevServerException) {
                                if (e.error != DevServerError.START_FAILED) throw e
                                policy.attempted(SystemClock.elapsedRealtime(), failed = true)
                                Log.w("HUREV_DEV", "Dev server recovery failed; retry is delayed")
                            }
                        }
                    }
                    delay(1000)
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
                withContext(NonCancellable + Dispatchers.Main.immediate) {
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

    private fun status(status: Status) {
        if (status == lastStatus) return
        lastStatus = status
        listener.onStatus(status)
    }

    suspend fun shutdown() {
        status(Status.STOPPING)
        job?.cancelAndJoin()
        job = null
    }

    override fun stop() { job?.cancel() }
}
