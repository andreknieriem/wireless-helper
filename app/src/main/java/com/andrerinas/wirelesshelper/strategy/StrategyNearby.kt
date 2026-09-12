package com.andrerinas.wirelesshelper.strategy

import android.content.Context
import com.andrerinas.wirelesshelper.connection.NearbySocket
import com.andrerinas.wirelesshelper.utils.HelperLog
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A connection strategy using Google Nearby Connections API.
 * The Phone (WirelessHelper) acts as an ADVERTISER only.
 * Uses Stream Tunneling for robust connections.
 */
// `scope` is a property rather than a bare constructor parameter because the tunnel decision moved
// out of the callback object and into maybeBuildTunnel(), which needs it from a method body.
class StrategyNearby(context: Context, private val scope: CoroutineScope) : BaseStrategy(context, scope) {

    override val TAG = "HUREV_NEARBY"
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val SERVICE_ID = "com.andrerinas.openhu"
    // Written on the Nearby callback thread, read from the upgrade-timeout coroutine on Main.
    @Volatile
    private var activeNearbySocket: NearbySocket? = null

    private var activePipes: Array<android.os.ParcelFileDescriptor>? = null

    @Volatile
    private var activeEndpointId: String? = null

    private var upgradeTimeoutJob: kotlinx.coroutines.Job? = null

    /**
     * Highest bandwidth quality Nearby has reported for an endpoint so far.
     *
     * Kept because the tunnel decision depends on two callbacks that arrive in an order nobody
     * guarantees. [onBandwidthChanged] reporting HIGH before [onConnectionResult] has recorded the
     * endpoint used to be discarded outright -- the guard read an [activeEndpointId] that was still
     * null, the one HIGH event we were ever going to get was gone, and the connection then sat idle
     * until the upgrade timeout tore it down. Recording the quality instead of acting on it inline
     * lets either callback complete the decision, whichever lands second.
     */
    private val lastQuality: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()

    /** The head unit's stream, when it arrived before [activeNearbySocket] existed to hold it. */
    private var pendingInboundStream: java.io.InputStream? = null

    private companion object {
        /**
         * How long to wait for Nearby to upgrade the link to Wi-Fi before giving up on it.
         *
         * Giving up is deliberate: what is left without the upgrade is Bluetooth, and projection
         * over Bluetooth is worse than an honest failure.
         */
        const val UPGRADE_TIMEOUT_MS = 10_000L

        /** Lets the far side register its payload handler before we send. */
        const val TUNNEL_SETTLE_MS = 500L
    }

    override fun start() {
        HelperLog.i(TAG, "NearbyStrategy: Starting Nearby Connections (Advertiser only)...")
        startAdvertising()
    }

    override fun stop() {
        HelperLog.i(TAG, "NearbyStrategy: Stopping Nearby Connections...")
        upgradeTimeoutJob?.cancel()
        upgradeTimeoutJob = null
        connectionsClient.stopAdvertising()
        connectionsClient.stopAllEndpoints()
        activeEndpointId = null
        // stopAllEndpoints() does not call onDisconnected back for endpoints we drop ourselves, so
        // this is the only place the tunnel state gets cleared on a local stop. Leaving the socket
        // behind left a non-null activeNearbySocket that the "have we built a tunnel yet?" guard
        // reads as "yes", so the next connection would never build one.
        clearTunnelState()
        lastQuality.clear()
        cleanup()
    }

    override fun stopForLaunch() {
        // DO NOT call stop() here as it would disconnect the tunnel we just built!
        // We only stop advertising to stay clean.
        HelperLog.d(TAG, "NearbyStrategy: stopForLaunch - keeping endpoints alive for tunnel.")
        connectionsClient.stopAdvertising()
    }

    private fun clearTunnelState() {
        activeNearbySocket = null
        pendingInboundStream?.let { try { it.close() } catch (_: Exception) {} }
        pendingInboundStream = null
        activePipes?.forEach { try { it.close() } catch (_: Exception) {} }
        activePipes = null
    }

    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()

        val endpointName = android.os.Build.MODEL
        HelperLog.i(TAG, "NearbyStrategy: Advertising as $endpointName with SERVICE_ID: $SERVICE_ID")

        connectionsClient.startAdvertising(
            endpointName,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        )
            .addOnSuccessListener { HelperLog.d(TAG, "Advertising started successfully") }
            .addOnFailureListener { e -> HelperLog.e(TAG, "Advertising failed: ${e.message}", e) }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            HelperLog.i(TAG, "NearbyStrategy: Connection initiated with $endpointId. Accepting...")
            stateListener?.onConnecting()
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    HelperLog.i(TAG, "NearbyStrategy: Connected to $endpointId. Stopping advertising and waiting up to ${UPGRADE_TIMEOUT_MS}ms for Wi-Fi upgrade...")
                    connectionsClient.stopAdvertising()
                    activeEndpointId = endpointId

                    // The upgrade may already have been reported while this callback was in flight.
                    maybeBuildTunnel(endpointId)

                    upgradeTimeoutJob?.cancel()
                    upgradeTimeoutJob = scope.launch {
                        kotlinx.coroutines.delay(UPGRADE_TIMEOUT_MS)
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            if (activeNearbySocket == null && activeEndpointId == endpointId) {
                                val seen = lastQuality[endpointId]
                                HelperLog.w(
                                    TAG,
                                    "NearbyStrategy: Wi-Fi bandwidth upgrade timed out after ${UPGRADE_TIMEOUT_MS}ms " +
                                            "(best quality seen: ${qualityName(seen)}). Only Bluetooth is left and " +
                                            "projection over it is unusable, so the endpoint is dropped."
                                )
                                stop()
                            }
                        }
                    }
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> HelperLog.w(TAG, "NearbyStrategy: Connection rejected by $endpointId")
                ConnectionsStatusCodes.STATUS_ERROR -> HelperLog.e(TAG, "NearbyStrategy: Connection error with $endpointId")
            }
        }

        override fun onBandwidthChanged(endpointId: String, bandwidthInfo: BandwidthInfo) {
            HelperLog.i(TAG, "NearbyStrategy: Bandwidth changed for $endpointId: Quality=${bandwidthInfo.quality} (${qualityName(bandwidthInfo.quality)})")
            val best = maxOf(lastQuality[endpointId] ?: Int.MIN_VALUE, bandwidthInfo.quality)
            lastQuality[endpointId] = best
            maybeBuildTunnel(endpointId)
        }

        override fun onDisconnected(endpointId: String) {
            HelperLog.i(TAG, "NearbyStrategy: Disconnected from $endpointId")
            if (activeEndpointId == endpointId) {
                activeEndpointId = null
                clearTunnelState()
                upgradeTimeoutJob?.cancel()
                upgradeTimeoutJob = null
            }
            lastQuality.remove(endpointId)
        }
    }

    /**
     * Builds the stream tunnel once both preconditions hold, whichever callback satisfies the last
     * one. Called from [onConnectionResult] and [onBandwidthChanged]; both run on the Nearby
     * callback thread, so the check-then-set on [activeNearbySocket] is not racing itself.
     */
    private fun maybeBuildTunnel(endpointId: String) {
        if (activeEndpointId != endpointId) return
        if (activeNearbySocket != null) return
        if (lastQuality[endpointId] != BandwidthInfo.Quality.HIGH) return

        HelperLog.i(TAG, "NearbyStrategy: High bandwidth connection established (Quality: HIGH)! Initiating stream tunnel...")

        upgradeTimeoutJob?.cancel()
        upgradeTimeoutJob = null

        val socket = NearbySocket()
        activeNearbySocket = socket

        // The head unit may already have sent its half while we were still getting here.
        pendingInboundStream?.let {
            HelperLog.i(TAG, "NearbyStrategy: Attaching the inbound STREAM that arrived before the socket existed.")
            socket.inputStreamWrapper = it
            pendingInboundStream = null
        }

        scope.launch {
            // Small delay to ensure both sides are ready for the stream registration
            kotlinx.coroutines.delay(TUNNEL_SETTLE_MS)

            // 1. Create outgoing pipe (Phone -> Tablet) and send it immediately
            val pipes = android.os.ParcelFileDescriptor.createPipe()
            activePipes = pipes
            socket.outputStreamWrapper = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
            val phoneToTabletPayload = Payload.fromStream(pipes[0])

            HelperLog.i(TAG, "NearbyStrategy: Sending Phone->Tablet stream payload (ID: ${phoneToTabletPayload.id})...")
            connectionsClient.sendPayload(endpointId, phoneToTabletPayload)
                .addOnSuccessListener { HelperLog.i(TAG, "NearbyStrategy: [OK] Phone->Tablet payload registered.") }
                .addOnFailureListener { e -> HelperLog.e(TAG, "NearbyStrategy: [ERROR] Failed to send payload: ${e.message}", e) }

            // 2. Launch Android Auto - it will block on socket.read() until the Tablet's stream arrives
            launchAndroidAuto("127.0.0.1", preConnectedSocket = socket)
        }
    }

    private fun qualityName(quality: Int?): String = when (quality) {
        null -> "none reported"
        BandwidthInfo.Quality.LOW -> "LOW"
        BandwidthInfo.Quality.MEDIUM -> "MEDIUM"
        BandwidthInfo.Quality.HIGH -> "HIGH"
        else -> "unknown($quality)"
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            HelperLog.i(TAG, "NearbyStrategy: Payload RECEIVED from $endpointId. Type: ${payload.type}")
            if (payload.type == Payload.Type.STREAM) {
                HelperLog.i(TAG, "NearbyStrategy: Received incoming STREAM payload. Tunnel is B-DIR now.")
                val inbound = payload.asStream()?.asInputStream()
                val socket = activeNearbySocket
                if (socket != null) {
                    socket.inputStreamWrapper = inbound
                } else {
                    // The head unit sends its half on its own schedule and never sends it twice.
                    // A null-safe assignment dropped it here without a word, which left the head
                    // unit blocked on a read that could no longer be satisfied -- the exact shape
                    // of "connects, then hangs and dies ~16s later".
                    HelperLog.w(TAG, "NearbyStrategy: Inbound STREAM arrived before the socket existed; holding it until the tunnel is built.")
                    pendingInboundStream = inbound
                }
            } else if (payload.type == Payload.Type.BYTES) {
                val msg = String(payload.asBytes() ?: byteArrayOf())
                HelperLog.i(TAG, "NearbyStrategy: Received BYTES payload: $msg")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                HelperLog.d(TAG, "NearbyStrategy: Payload transfer SUCCESS for $endpointId")
            } else if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                HelperLog.e(TAG, "NearbyStrategy: Payload transfer FAILURE for $endpointId")
            }
        }
    }
}
