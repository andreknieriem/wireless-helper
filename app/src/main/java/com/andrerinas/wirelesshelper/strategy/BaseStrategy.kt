package com.andrerinas.wirelesshelper.strategy

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Parcelable
import android.util.Log
import com.andrerinas.wirelesshelper.TransparentTriggerActivity
import com.andrerinas.wirelesshelper.connection.AapProxy
import com.andrerinas.wirelesshelper.net.HotspotAutoConnect
import com.andrerinas.wirelesshelper.net.WifiNetworkBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

abstract class BaseStrategy(protected val context: Context, private val scope: CoroutineScope) : ConnectionStrategy {

    interface StateListener {
        fun onConnecting()
        fun onProxyConnected()
        fun onProxyDisconnected()
        fun onLaunchTimeout()
    }

    protected open val TAG = "HUREV_WIFI"
    private var activeProxy: AapProxy? = null
    var stateListener: StateListener? = null
    protected val isLaunching = AtomicBoolean(false)
    protected var connectionEstablished = AtomicBoolean(false)
    protected var strategyJob: Job? = null

    protected fun getStrategyScope(): CoroutineScope {
        val job = Job(scope.coroutineContext[Job])
        strategyJob = job
        return CoroutineScope(scope.coroutineContext + job)
    }

    companion object {
        const val ACTION_TRIGGER_INTENT = "com.andrerinas.wirelesshelper.ACTION_TRIGGER_INTENT"
    }


    protected fun launchAndroidAuto(hostIp: String, preConnectedSocket: java.net.Socket? = null) {
        if (isLaunching.get()) return
        if (!isLaunching.compareAndSet(false, true)) return
        
        Log.i(TAG, "Strategy triggering PROXY launch for $hostIp")
        connectionEstablished.set(false)
        
        stopForLaunch()

        scope.launch {
            try {
                val hotspotNetwork = HotspotAutoConnect.currentNetwork
                val boundWifi = WifiNetworkBinding.currentNetwork

                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

                val targetNetwork = when {
                    hotspotNetwork != null -> hotspotNetwork
                    boundWifi != null -> boundWifi
                    else -> (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) connectivityManager.activeNetwork else null)
                }

                // 1. Start the Proxy Server with a listener
                val proxy = AapProxy(hostIp, network = targetNetwork, preConnectedSocket = preConnectedSocket, listener = object : AapProxy.Listener {
                    override fun onConnected() {
                        Log.i(TAG, "AA is now flowing through proxy")
                        connectionEstablished.set(true)
                        stateListener?.onProxyConnected()
                    }
                    override fun onDisconnected() {
                        Log.i(TAG, "AA proxy connection lost")
                        stateListener?.onProxyDisconnected()
                    }
                })
                
                activeProxy = proxy
                val localPort = proxy.start()

                val wifiInfo: Parcelable? = try {
                    val clazz = Class.forName("android.net.wifi.WifiInfo")
                    val constructor = clazz.getDeclaredConstructor()
                    constructor.isAccessible = true
                    constructor.newInstance() as Parcelable
                } catch (e: Exception) { null }

                val intent = Intent().apply {
                    setClassName("com.google.android.projection.gearhead", "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    // Activity params
                    putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
                    putExtra("PARAM_SERVICE_PORT", localPort)
                    
                    // Receiver params (fallback/alternative trigger)
                    putExtra("ip_address", "127.0.0.1")
                    putExtra("projection_port", localPort)
                    
                    targetNetwork?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
                    wifiInfo?.let { putExtra("wifi_info", it) }
                }

                Log.i(TAG, "Firing triggers for AA Localhost. Port=$localPort, Network=$targetNetwork")
                
                // Sequential trigger: Activity first, then Broadcast fallback (handled in TransparentTriggerActivity)
                // 2. Start our transparent activity to "surface" the app for Activity-based trigger
                val triggerIntent = Intent(context, TransparentTriggerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putExtra("intent", intent)
                }
                context.startActivity(triggerIntent)

                // The lock stays active until proxy confirms connection or timeout
                delay(15000) 
                
                if (!connectionEstablished.get()) {
                    Log.w(TAG, "Launch timed out without connection.")
                    activeProxy?.stop()
                    activeProxy = null
                    stateListener?.onLaunchTimeout()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Proxy Launch failed: ${e.message}")
                activeProxy?.stop()
                activeProxy = null
            } finally {
                isLaunching.set(false)
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "Stopping discovery/strategy jobs")
        strategyJob?.cancel()
        strategyJob = null
    }

    /**
     * Stops discovery before launching AA. Strategies can override this to keep
     * transport-specific state alive, for example a hotspot we turned on ourselves.
     */
    protected open fun stopForLaunch() {
        stop()
    }

    fun cleanup() {
        activeProxy?.stop()
        activeProxy = null
        isLaunching.set(false)
    }
}
