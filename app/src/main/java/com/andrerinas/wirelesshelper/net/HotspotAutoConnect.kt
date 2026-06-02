package com.andrerinas.wirelesshelper.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Handles programmatic connection to a WiFi hotspot using WifiNetworkSpecifier (Android 10+).
 *
 * First connection shows a system dialog for user approval; subsequent connections are automatic.
 */
object HotspotAutoConnect {

    private const val TAG = "HUREV_HOTSPOT_CONNECT"
    private const val CONNECTION_TIMEOUT_MS = 30_000L

    sealed class Result {
        data class Connected(val network: Network, val gateway: String?) : Result()
        data object UserDeclined : Result()
        data object NetworkNotFound : Result()
        data object Timeout : Result()
        data object UnsupportedApi : Result()
    }

    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null

    @Volatile
    var currentNetwork: Network? = null
        private set

    /**
     * Attempts to connect to a WiFi hotspot with the given credentials.
     *
     * @param context Application context
     * @param ssid The SSID of the hotspot to connect to
     * @param password The WPA2/WPA3 password for the hotspot
     * @return Result indicating success or failure reason
     */
    suspend fun connect(context: Context, ssid: String, password: String): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "WifiNetworkSpecifier requires Android 10+")
            return Result.UnsupportedApi
        }

        return connectInternal(context, ssid, password)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun connectInternal(context: Context, ssid: String, password: String): Result {
        // Release any previous request
        release()

        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        Log.i(TAG, "Requesting connection to hotspot: $ssid")

        val result = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.i(TAG, "Connected to hotspot: $ssid")
                        currentNetwork = network
                        // Bind process to this network so sockets use it
                        cm.bindProcessToNetwork(network)

                        // Log gateway info for debugging
                        val gateway = getGatewayAddress(cm, network)
                        Log.i(TAG, "=== HOTSPOT CONNECTED ===")
                        Log.i(TAG, "Network: $network")
                        Log.i(TAG, "Gateway (headunit): $gateway")
                        Log.i(TAG, "=========================")

                        if (continuation.isActive) {
                            continuation.resume(Result.Connected(network, gateway))
                        }
                    }

                    override fun onLost(network: Network) {
                        // Android may report transient "lost" during handoff or signal fluctuation.
                        // Unbinding here would route traffic to home WiFi, breaking AA.
                        // Stay bound - if hotspot recovers, we're ready; if not, sockets fail visibly.
                        Log.w(TAG, "Hotspot connection lost (staying bound): $ssid")
                    }

                    override fun onUnavailable() {
                        Log.w(TAG, "Hotspot unavailable (user declined or not found): $ssid")
                        release()
                        if (continuation.isActive) {
                            continuation.resume(Result.UserDeclined)
                        }
                    }
                }

                activeCallback = callback

                continuation.invokeOnCancellation {
                    Log.d(TAG, "Connection request cancelled")
                    release()
                }

                try {
                    cm.requestNetwork(request, callback)
                } catch (e: SecurityException) {
                    Log.e(TAG, "requestNetwork failed: ${e.message}")
                    release()
                    if (continuation.isActive) {
                        continuation.resume(Result.NetworkNotFound)
                    }
                }
            }
        }

        return result ?: Result.Timeout.also {
            Log.w(TAG, "Connection timed out for: $ssid")
            release()
        }
    }

    /**
     * Releases the network request and unbinds from the network.
     */
    fun release() {
        val cm = connectivityManager
        val cb = activeCallback

        if (cb != null && cm != null) {
            try {
                cm.unregisterNetworkCallback(cb)
                Log.d(TAG, "Network callback unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "unregisterNetworkCallback: ${e.message}")
            }
        }

        if (cm != null) {
            try {
                cm.bindProcessToNetwork(null)
            } catch (e: Exception) {
                Log.w(TAG, "bindProcessToNetwork(null): ${e.message}")
            }
        }

        activeCallback = null
        connectivityManager = null
        currentNetwork = null
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getGatewayAddress(cm: ConnectivityManager, network: Network): String? {
        return try {
            val linkProps = cm.getLinkProperties(network)
            linkProps?.routes
                ?.find { it.isDefaultRoute && it.gateway is java.net.Inet4Address }
                ?.gateway
                ?.hostAddress
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get gateway: ${e.message}")
            null
        }
    }
}
