package com.andrerinas.wirelesshelper.strategy

import android.content.Context
import android.util.Log
import com.andrerinas.wirelesshelper.connection.NearbySocket
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
class StrategyNearby(context: Context, scope: CoroutineScope) : BaseStrategy(context, scope) {

    override val TAG = "HUREV_NEARBY"
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val SERVICE_ID = "com.andrerinas.openhu"
    private var activeNearbySocket: NearbySocket? = null
    private var activePipes: Array<android.os.ParcelFileDescriptor>? = null
    private var activeEndpointId: String? = null
    private var upgradeTimeoutJob: kotlinx.coroutines.Job? = null

    override fun start() {
        Log.i(TAG, "NearbyStrategy: Starting Nearby Connections (Advertiser only)...")
        startAdvertising()
    }

    override fun stop() {
        Log.i(TAG, "NearbyStrategy: Stopping Nearby Connections...")
        upgradeTimeoutJob?.cancel()
        upgradeTimeoutJob = null
        connectionsClient.stopAdvertising()
        connectionsClient.stopAllEndpoints()
        activeEndpointId = null
        cleanup()
    }

    override fun stopForLaunch() {
        // DO NOT call stop() here as it would disconnect the tunnel we just built!
        // We only stop advertising to stay clean.
        Log.d(TAG, "NearbyStrategy: stopForLaunch - keeping endpoints alive for tunnel.")
        connectionsClient.stopAdvertising()
    }

    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()

        val endpointName = android.os.Build.MODEL
        Log.i(TAG, "NearbyStrategy: Advertising as $endpointName with SERVICE_ID: $SERVICE_ID")
        
        connectionsClient.startAdvertising(
            endpointName,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        )
            .addOnSuccessListener { Log.d(TAG, "Advertising started successfully") }
            .addOnFailureListener { e -> Log.e(TAG, "Advertising failed: ${e.message}") }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "NearbyStrategy: Connection initiated with $endpointId. Accepting...")
            stateListener?.onConnecting()
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.i(TAG, "NearbyStrategy: Connected to $endpointId. Stopping advertising and waiting up to 10s for Wi-Fi upgrade...")
                    connectionsClient.stopAdvertising()
                    activeEndpointId = endpointId

                    // Start a 10-second timeout for the Wi-Fi bandwidth upgrade
                    upgradeTimeoutJob?.cancel()
                    upgradeTimeoutJob = scope.launch {
                        kotlinx.coroutines.delay(10_000)
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            if (activeNearbySocket == null && activeEndpointId == endpointId) {
                                Log.w(TAG, "NearbyStrategy: Wi-Fi Bandwidth upgrade timed out. Disconnecting endpoint to avoid Bluetooth fallback.")
                                stop()
                            }
                        }
                    }
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> Log.w(TAG, "NearbyStrategy: Connection rejected by $endpointId")
                ConnectionsStatusCodes.STATUS_ERROR -> Log.e(TAG, "NearbyStrategy: Connection error with $endpointId")
            }
        }

        override fun onBandwidthChanged(endpointId: String, bandwidthInfo: BandwidthInfo) {
            Log.i(TAG, "NearbyStrategy: Bandwidth changed for $endpointId: Quality=${bandwidthInfo.quality}")
            if (bandwidthInfo.quality == BandwidthInfo.Quality.HIGH) {
                if (activeEndpointId == endpointId && activeNearbySocket == null) {
                    Log.i(TAG, "NearbyStrategy: High bandwidth connection established (Quality: HIGH)! Initiating stream tunnel...")
                    
                    upgradeTimeoutJob?.cancel()
                    upgradeTimeoutJob = null

                    scope.launch {
                        // Small delay to ensure both sides are ready for the stream registration
                        kotlinx.coroutines.delay(500) 

                        val socket = NearbySocket()
                        activeNearbySocket = socket

                        // 1. Create outgoing pipe (Phone -> Tablet) and send it immediately
                        val pipes = android.os.ParcelFileDescriptor.createPipe()
                        activePipes = pipes
                        socket.outputStreamWrapper = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
                        val phoneToTabletPayload = Payload.fromStream(pipes[0])
                        
                        Log.i(TAG, "NearbyStrategy: Sending Phone->Tablet stream payload...")
                        connectionsClient.sendPayload(endpointId, phoneToTabletPayload)
                            .addOnSuccessListener { Log.i(TAG, "NearbyStrategy: [OK] Phone->Tablet payload registered.") }
                            .addOnFailureListener { e -> Log.e(TAG, "NearbyStrategy: [ERROR] Failed to send payload: ${e.message}") }

                        // 2. Launch Android Auto - it will block on socket.read() until the Tablet's stream arrives
                        launchAndroidAuto("127.0.0.1", preConnectedSocket = socket)
                    }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "NearbyStrategy: Disconnected from $endpointId")
            if (activeEndpointId == endpointId) {
                activeEndpointId = null
                activeNearbySocket = null
                upgradeTimeoutJob?.cancel()
                upgradeTimeoutJob = null
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            Log.i(TAG, "NearbyStrategy: Payload RECEIVED from $endpointId. Type: ${payload.type}")
            if (payload.type == Payload.Type.STREAM) {
                Log.i(TAG, "NearbyStrategy: Received incoming STREAM payload. Tunnel is B-DIR now.")
                activeNearbySocket?.inputStreamWrapper = payload.asStream()?.asInputStream()
            } else if (payload.type == Payload.Type.BYTES) {
                val msg = String(payload.asBytes() ?: byteArrayOf())
                Log.i(TAG, "NearbyStrategy: Received BYTES payload: $msg")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                Log.d(TAG, "NearbyStrategy: Payload transfer SUCCESS")
            } else if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                Log.e(TAG, "NearbyStrategy: Payload transfer FAILURE")
            }
        }
    }
}
