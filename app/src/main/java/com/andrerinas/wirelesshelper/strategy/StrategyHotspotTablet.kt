package com.andrerinas.wirelesshelper.strategy

import android.content.Context
import android.os.Build
import android.util.Log
import com.andrerinas.wirelesshelper.net.HotspotAutoConnect
import com.andrerinas.wirelesshelper.utils.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket

class StrategyHotspotTablet(context: Context, private val scope: CoroutineScope) : BaseStrategy(context, scope) {

    private var serverSocket: ServerSocket? = null
    private val PORT_DISCOVERY = 5289

    override fun start() {
        val prefs = context.getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val staticIp = prefs.getString("static_ip_address", "") ?: ""

        if (staticIp.isNotEmpty()) {
            Log.i(TAG, "Strategy: Hotspot Tablet (Direct Connect to Static IP: $staticIp)")
            getStrategyScope().launch {
                launchAndroidAuto(staticIp)
            }
            return
        }

        // Check for hotspot auto-connect credentials
        val hotspotSsid = prefs.getString("hotspot_ssid", "") ?: ""
        val hotspotPassword = if (hotspotSsid.isNotEmpty()) {
            Prefs.getSecure(context).getString("hotspot_password", "") ?: ""
        } else ""

        if (hotspotSsid.isNotEmpty() && hotspotPassword.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.i(TAG, "Strategy: Hotspot Tablet (Auto-Connect to $hotspotSsid)")
            startWithAutoConnect(hotspotSsid, hotspotPassword)
        } else {
            Log.i(TAG, "Strategy: Hotspot Tablet (Passive TCP Listener)")
            startTcpListener()
        }
    }

    private fun startWithAutoConnect(ssid: String, password: String) {
        getStrategyScope().launch(Dispatchers.IO) {
            val result = HotspotAutoConnect.connect(context, ssid, password)

            when (result) {
                is HotspotAutoConnect.Result.Connected -> {
                    val gateway = result.gateway
                    Log.i(TAG, "Connected to hotspot, gateway=$gateway")

                    // Try direct connection to headunit first
                    if (gateway != null) {
                        if (tryDirectConnect(gateway)) {
                            Log.i(TAG, "Direct connection to headunit succeeded")
                        } else {
                            Log.w(TAG, "Direct connect failed, falling back to TCP listener")
                            startTcpListener()
                        }
                    } else {
                        Log.w(TAG, "No gateway detected, falling back to TCP listener")
                        startTcpListener()
                    }
                }
                is HotspotAutoConnect.Result.UserDeclined -> {
                    Log.w(TAG, "User declined hotspot connection, falling back to passive listener")
                    startTcpListener()
                }
                is HotspotAutoConnect.Result.NetworkNotFound -> {
                    Log.w(TAG, "Hotspot not found, falling back to passive listener")
                    startTcpListener()
                }
                is HotspotAutoConnect.Result.Timeout -> {
                    Log.w(TAG, "Hotspot connection timed out, falling back to passive listener")
                    startTcpListener()
                }
                is HotspotAutoConnect.Result.UnsupportedApi -> {
                    Log.w(TAG, "WifiNetworkSpecifier not supported, using passive listener")
                    startTcpListener()
                }
            }
        }
    }

    private fun tryDirectConnect(gateway: String): Boolean {
        val port = 5288
        return try {
            Log.i(TAG, "Attempting direct connection to $gateway:$port")
            val socket = java.net.Socket()
            socket.connect(InetSocketAddress(gateway, port), 3000)
            Log.i(TAG, "Direct connection established to headunit")
            launchAndroidAuto(gateway, socket)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Direct connection to $gateway:$port failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun startTcpListener() {
        getStrategyScope().launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT_DISCOVERY))
                }
                Log.i(TAG, "TCP listener started on port $PORT_DISCOVERY")
                while (isActive) {
                    val client = serverSocket?.accept()
                    val remoteIp = client?.inetAddress?.hostAddress
                    client?.close()

                    if (remoteIp != null) {
                        Log.i(TAG, "Trigger from $remoteIp")
                        launchAndroidAuto(remoteIp)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP Server error: ${e.message}")
            }
        }
    }

    override fun stopForLaunch() {
        // Only close TCP listener, keep hotspot connection alive for the proxy
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
    }

    override fun stop() {
        HotspotAutoConnect.release()
        stopForLaunch()
    }
}
