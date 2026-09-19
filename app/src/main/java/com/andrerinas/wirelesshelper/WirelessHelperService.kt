package com.andrerinas.wirelesshelper

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.andrerinas.wirelesshelper.strategy.BaseStrategy
import com.andrerinas.wirelesshelper.strategy.ConnectionStrategy
import com.andrerinas.wirelesshelper.strategy.StrategyHotspotPhone
import com.andrerinas.wirelesshelper.strategy.StrategyHotspotTablet
import com.andrerinas.wirelesshelper.strategy.StrategySharedNetwork
import com.andrerinas.wirelesshelper.strategy.StrategyWifiDirect
import com.andrerinas.wirelesshelper.strategy.StrategyWifiDirectDevServer
import com.andrerinas.wirelesshelper.devserver.BluetoothStopPolicy
import com.andrerinas.wirelesshelper.devserver.DevServerError
import com.andrerinas.wirelesshelper.devserver.DevModePolicy
import com.andrerinas.wirelesshelper.devserver.AndroidDevServer
import com.andrerinas.wirelesshelper.net.WifiNetworkBinding
import com.andrerinas.wirelesshelper.strategy.StrategyNearby
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WirelessHelperService : Service(), BaseStrategy.StateListener {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentStrategy: ConnectionStrategy? = null
    private var strategyLaunchJob: Job? = null
    private var reconnectJob: Job? = null
    private val strategyMutex = Mutex()
    private var stopping = false
    private var pendingStart: Intent? = null
    private var btStopJob: Job? = null
    private val bluetoothStopPolicy = BluetoothStopPolicy()
    private var devFailure: String? = null
    private var requestedMode: Int? = null
    private var destroyed = false

    companion object {
        private const val TAG = "HUREV_WIFI"
        private const val CHANNEL_ID = "WirelessHelperChannel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_RECOVER = "ACTION_RECOVER"
        const val ACTION_BT_DISCONNECTED = "ACTION_BT_DISCONNECTED"
        const val EXTRA_BT_MAC = "EXTRA_BT_MAC"
        var devStatus: String? = null
            private set
        var isRunning = false
            internal set
        var isConnected = false
            internal set
    }

    override fun onCreate() {
        super.onCreate()
        setupLocks()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1,
                createNotification(getString(R.string.notif_searching)),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(1, createNotification(getString(R.string.notif_searching)))
        }
    }

    private fun setupLocks() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("WirelessHelperLock")
            multicastLock?.setReferenceCounted(true)
            multicastLock?.acquire()
            
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WirelessHelper:WakeLock")
            wakeLock?.setReferenceCounted(false)
            
            Log.i(TAG, "Locks initialized")
        } catch (e: Exception) { Log.e(TAG, "Failed to initialize locks: ${e.message}") }
    }

    private fun acquireWakeLock(timeout: Long = 10 * 60 * 1000L) {
        try {
            if (timeout > 0) {
                wakeLock?.acquire(timeout)
                Log.d(TAG, "WakeLock acquired with timeout: $timeout ms")
            } else {
                wakeLock?.acquire()
                Log.d(TAG, "WakeLock acquired infinitely")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        }
    }

    private fun startSelectedStrategy(fromBt: Boolean = false) {
        reconnectJob?.cancel()
        reconnectJob = null
        strategyLaunchJob?.cancel()
        strategyLaunchJob = serviceScope.launch {
            val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
            if (fromBt) {
                val delaySeconds = prefs.getInt("bt_autostart_delay", 0)
                if (delaySeconds > 0) {
                    Log.i(TAG, "Delaying connection by $delaySeconds seconds (Bluetooth auto-start)...")
                    for (i in delaySeconds downTo 1) {
                        if (!isActive) break
                        updateNotification(getString(R.string.notif_starting_in, i))
                        delay(1000)
                    }
                    if (isActive) {
                        updateNotification(getString(R.string.notif_searching))
                    }
                }
            }

            if (!isActive) return@launch

            strategyMutex.withLock { withContext(Dispatchers.Main) {
                if (!isRunning || stopping) return@withContext
                val mode = prefs.getInt("connection_mode", 0)
                
                acquireWakeLock(10 * 60 * 1000L) // 10 minutes timeout while searching
                stopCurrentStrategy(recoverManagedServer = mode != StrategyWifiDirectDevServer.MODE)
                if (!isRunning || stopping) return@withContext
                if (mode != StrategyWifiDirectDevServer.MODE && prefs.getBoolean(StrategyWifiDirectDevServer.MANAGED_KEY, false)) {
                    try {
                        AndroidDevServer.create(this@WirelessHelperService).stop()
                        prefs.edit().putBoolean(StrategyWifiDirectDevServer.MANAGED_KEY, false).commit()
                    } catch (e: Exception) {
                        Log.w(TAG, "Dev server cleanup before mode switch failed", e)
                        onDevFailure(com.andrerinas.wirelesshelper.devserver.devCleanupError(e))
                        return@withContext
                    }
                }

                if (mode == 2) {
                    WifiNetworkBinding.start(this@WirelessHelperService)
                } else {
                    WifiNetworkBinding.stop(this@WirelessHelperService)
                }

                currentStrategy = when (mode) {
                    0 -> StrategySharedNetwork(this@WirelessHelperService, serviceScope)
                    1 -> StrategyHotspotPhone(this@WirelessHelperService, serviceScope)
                    2 -> StrategyHotspotTablet(this@WirelessHelperService, serviceScope)
                    3 -> StrategyWifiDirect(this@WirelessHelperService, serviceScope)
                    4 -> StrategyNearby(this@WirelessHelperService, serviceScope)
                    StrategyWifiDirectDevServer.MODE -> StrategyWifiDirectDevServer(this@WirelessHelperService, serviceScope,
                        object : StrategyWifiDirectDevServer.Listener {
                            override fun onStatus(status: StrategyWifiDirectDevServer.Status) = onDevStatus(status)
                            override fun onFailure(error: DevServerError) = onDevFailure(error)
                        })
                    else -> StrategySharedNetwork(this@WirelessHelperService, serviceScope)
                }
                
                if (currentStrategy is BaseStrategy) {
                    (currentStrategy as BaseStrategy).stateListener = this@WirelessHelperService
                }
                
                Log.i(TAG, "Starting strategy for mode $mode")
                currentStrategy?.start()
            } }
        }
    }

    private suspend fun stopCurrentStrategy(recoverManagedServer: Boolean = true) {
        val old = currentStrategy
        currentStrategy = null
        (old as? BaseStrategy)?.stateListener = null
        withContext(NonCancellable) {
            if (old is StrategyWifiDirectDevServer) old.shutdown() else old?.stop()
            (old as? BaseStrategy)?.cleanup()
            val prefs = getSharedPreferences("WirelessHelperPrefs", MODE_PRIVATE)
            if (recoverManagedServer && old == null && prefs.getBoolean(StrategyWifiDirectDevServer.MANAGED_KEY, false)) {
                try {
                    AndroidDevServer.create(this@WirelessHelperService).stop()
                    prefs.edit().putBoolean(StrategyWifiDirectDevServer.MANAGED_KEY, false).commit()
                } catch (e: Exception) {
                    Log.w(TAG, "Dev server recovery cleanup failed", e)
                    onDevFailure(com.andrerinas.wirelesshelper.devserver.devCleanupError(e))
                }
            }
        }
    }

    private fun onDevStatus(status: StrategyWifiDirectDevServer.Status) {
        if (devFailure != null) return
        val resource = when (status) {
            StrategyWifiDirectDevServer.Status.STARTING -> R.string.dev_starting
            StrategyWifiDirectDevServer.Status.SEARCHING -> R.string.dev_searching
            StrategyWifiDirectDevServer.Status.P2P_CONNECTED -> R.string.dev_p2p_connected
            StrategyWifiDirectDevServer.Status.SERVER_READY -> R.string.dev_ready
            StrategyWifiDirectDevServer.Status.TCP_CONNECTED -> R.string.dev_tcp_connected
            StrategyWifiDirectDevServer.Status.RECOVERING -> R.string.dev_recovering
            StrategyWifiDirectDevServer.Status.STOPPING -> R.string.dev_stopping
        }
        devStatus = getString(resource)
        isConnected = status == StrategyWifiDirectDevServer.Status.TCP_CONNECTED
        if (isConnected) acquireWakeLock(0)
        updateNotification(devStatus!!)
        updateAllUIs()
    }

    private fun onDevFailure(error: DevServerError) {
        Log.w(TAG, "Dev server failure: $error (stopping=$stopping)")
        // Cleanup failures must not replace the error that caused the session to stop.
        if (devFailure == null) devFailure = getString(com.andrerinas.wirelesshelper.devserver.devErrorResource(error))
        devStatus = devFailure
        updateNotification(devStatus!!)
        updateAllUIs()
        if (!stopping && !destroyed) requestStop()
    }

    private fun requestStop() {
        pendingStart = null
        if (stopping) return
        stopping = true
        requestedMode = null
        isRunning = false
        isConnected = false
        getSharedPreferences("WirelessHelperPrefs", MODE_PRIVATE).edit()
            .putBoolean(StrategyWifiDirectDevServer.REQUESTED_KEY, false)
            .remove("pending_wifi_start").remove("dev_root_pending_bt").commit()
        btStopJob?.cancel()
        bluetoothStopPolicy.clear()
        strategyLaunchJob?.cancel()
        reconnectJob?.cancel()
        serviceScope.launch {
            strategyMutex.withLock {
                withContext(Dispatchers.Main) { stopCurrentStrategy() }
            }
            withContext(Dispatchers.Main) {
                stopping = false
                val cleanupPending = getSharedPreferences("WirelessHelperPrefs", MODE_PRIVATE)
                    .getBoolean(StrategyWifiDirectDevServer.MANAGED_KEY, false)
                devStatus = DevModePolicy.statusAfterStop(
                    devStatus, devFailure, cleanupPending, getString(R.string.dev_stopped)
                )
                Log.i(TAG, "Stop sequence completed: cleanupPending=$cleanupPending, failed=${devFailure != null}")
                val next = pendingStart?.takeUnless { start ->
                    val mac = start.getStringExtra(EXTRA_BT_MAC)
                    mac != null && getSharedPreferences("WirelessHelperPrefs", MODE_PRIVATE)
                        .getStringSet("dev_root_connected_bt", emptySet())?.contains(mac) != true
                }
                pendingStart = null
                bluetoothStopPolicy.clear()
                if (next != null) onStartCommand(next, 0, 0)
                else { releaseWakeLock(); updateAllUIs(); stopSelf() }
            }
        }
    }

    override fun onConnecting() {
        updateNotification(getString(R.string.notif_connecting))
        updateAllUIs()
    }

    override fun onProxyConnected() {
        isConnected = true
        acquireWakeLock(0) // Infinite/no timeout while projection is running
        updateNotification(getString(R.string.notif_connected))
        updateAllUIs()
    }

    override fun onProxyDisconnected() {
        if (!isRunning || stopping) return
        isConnected = false
        Log.i(TAG, "AA proxy connection lost.")
        updateAllUIs()

        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val autoReconnect = prefs.getBoolean("bt_auto_reconnect", false)
        val targetMacs = prefs.getStringSet("auto_start_bt_macs", emptySet()) ?: emptySet()
        val legacyTargetMac = prefs.getString("auto_start_bt_mac", null)

        val anyTargetConnected = targetMacs.any { isBluetoothDeviceConnected(it) } || 
                                (legacyTargetMac != null && isBluetoothDeviceConnected(legacyTargetMac))

        if (autoReconnect && anyTargetConnected) {
            Log.i(TAG, "Target Bluetooth still connected and auto-reconnect enabled. Restarting strategy...")
            acquireWakeLock(10 * 60 * 1000L) // 10 minutes timeout during reconnect scan
            updateNotification(getString(R.string.notif_searching))
            reconnectJob = serviceScope.launch {
                delay(3000) // Buffer vor Neustart
                startSelectedStrategy()
            }
        } else {
            Log.i(TAG, "Stopping service.")
            releaseWakeLock()
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission")
    private fun isBluetoothDeviceConnected(mac: String, preciseOnly: Boolean = false): Boolean {
        try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val adapter = bm.adapter ?: return false
            
            val device = adapter.getRemoteDevice(mac) ?: return false
            
            // Use reflection to access hidden 'isConnected' method for precise per-device status.
            // Suggested by Gemini Code Assist to fix incorrect profile-based detection.
            return try {
                val isConnectedMethod = device.javaClass.getMethod("isConnected")
                isConnectedMethod.invoke(device) as? Boolean ?: false
            } catch (e: Exception) {
                if (preciseOnly) {
                    // ACL events are per-device; an unrelated active audio profile is not evidence.
                    return getSharedPreferences("WirelessHelperPrefs", MODE_PRIVATE)
                        .getStringSet("dev_root_connected_bt", emptySet())?.contains(mac) == true
                }
                // Fallback: check profile connection if reflection fails
                val a2dp = adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP)
                val hfp = adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET)
                a2dp == android.bluetooth.BluetoothProfile.STATE_CONNECTED || 
                hfp == android.bluetooth.BluetoothProfile.STATE_CONNECTED
            }
        } catch (e: Exception) {
            return false
        }
    }

    override fun onLaunchTimeout() {
        if (!isRunning || stopping) return
        Log.i(TAG, "Launch timeout. Resuming discovery.")
        updateNotification(getString(R.string.notif_searching))
        updateAllUIs()
        
        // Restart the strategy to resume searching
        currentStrategy?.stop()
        startSelectedStrategy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("WirelessHelperPrefs", MODE_PRIVATE)
        if (intent == null) {
            if (prefs.getBoolean(StrategyWifiDirectDevServer.REQUESTED_KEY, false)) {
                requestedMode = StrategyWifiDirectDevServer.MODE
                val connected = seedSelectedBluetooth()
                isRunning = true
                if (prefs.getBoolean("dev_root_bt_bound", false) && !connected) {
                    bluetoothStopPolicy.disconnected("", android.os.SystemClock.elapsedRealtime())
                    scheduleBluetoothStop()
                }
                startSelectedStrategy()
                return START_STICKY
            }
            if (prefs.getBoolean(StrategyWifiDirectDevServer.MANAGED_KEY, false)) {
                serviceScope.launch {
                    try {
                        withTimeout(15_000) { AndroidDevServer.create(this@WirelessHelperService).stop() }
                        prefs.edit().putBoolean(StrategyWifiDirectDevServer.MANAGED_KEY, false).commit()
                    } catch (e: Exception) {
                        Log.w(TAG, "Dev server process-recovery cleanup failed", e)
                    }
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            // Service was restarted by the system
            if (isRunning) {
                Log.i(TAG, "Service restarted by system. Resuming strategy.")
                startSelectedStrategy()
            }
            return START_STICKY
        }

        when (intent.action) {
            ACTION_RECOVER -> if (!isRunning) requestStop()
            ACTION_STOP -> {
                requestStop()
            }
            ACTION_BT_DISCONNECTED -> {
                if (prefs.getInt("connection_mode", 0) == StrategyWifiDirectDevServer.MODE && isRunning) {
                    val mac = intent.getStringExtra(EXTRA_BT_MAC) ?: return START_STICKY
                    bluetoothStopPolicy.disconnected(mac, android.os.SystemClock.elapsedRealtime())
                    scheduleBluetoothStop()
                }
            }
            ACTION_START -> {
                intent.getStringExtra(EXTRA_BT_MAC)?.let {
                    bluetoothStopPolicy.connected(it)
                    btStopJob?.cancel()
                    prefs.edit().putBoolean("dev_root_bt_bound", true).apply()
                }
                if (stopping) { pendingStart = Intent(intent); return START_STICKY }
                val devMode = prefs.getInt("connection_mode", 0) == StrategyWifiDirectDevServer.MODE
                if (DevModePolicy.reusesStart(prefs.getInt("connection_mode", 0), requestedMode, isRunning))
                    return START_STICKY
                requestedMode = prefs.getInt("connection_mode", 0)
                devFailure = null
                prefs.edit().putBoolean(StrategyWifiDirectDevServer.REQUESTED_KEY, devMode).commit()
                if (devMode) {
                    val hasBluetooth = seedSelectedBluetooth() || intent.hasExtra(EXTRA_BT_MAC)
                    prefs.edit().putBoolean("dev_root_bt_bound", hasBluetooth).apply()
                } else devStatus = null
                isRunning = true
                isConnected = false
                val fromBt = intent.getBooleanExtra("EXTRA_FROM_BT", false)
                startSelectedStrategy(fromBt)
                updateAllUIs()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        isRunning = false
        isConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        btStopJob?.cancel()
        bluetoothStopPolicy.clear()
        strategyLaunchJob?.cancel()
        strategyLaunchJob = null
        WifiNetworkBinding.stop(this)
        val devStrategy = currentStrategy as? StrategyWifiDirectDevServer
        if (devStrategy != null) {
            CoroutineScope(NonCancellable + Dispatchers.Main.immediate).launch { devStrategy.shutdown() }
        } else currentStrategy?.stop()
        if (currentStrategy is BaseStrategy) {
            (currentStrategy as BaseStrategy).stateListener = null
            (currentStrategy as BaseStrategy).cleanup()
        }
        try { 
            multicastLock?.release()
            releaseWakeLock()
        } catch (e: Exception) { }
        serviceJob.cancel()
        updateAllUIs()
        super.onDestroy()
    }

    private fun scheduleBluetoothStop() {
        btStopJob?.cancel()
        val remaining = bluetoothStopPolicy.remainingDelay(android.os.SystemClock.elapsedRealtime()) ?: return
        btStopJob = serviceScope.launch(Dispatchers.Main) {
            delay(remaining)
            if (bluetoothStopPolicy.shouldStop(android.os.SystemClock.elapsedRealtime())) requestStop()
        }
    }

    private fun seedSelectedBluetooth(): Boolean {
        val prefs = getSharedPreferences("WirelessHelperPrefs", MODE_PRIVATE)
        val macs = (prefs.getStringSet("auto_start_bt_macs", emptySet()) ?: emptySet()) +
            listOfNotNull(prefs.getString("auto_start_bt_mac", null))
        val connected = macs.filter { isBluetoothDeviceConnected(it, preciseOnly = true) }
        connected.forEach { bluetoothStopPolicy.connected(it) }
        return connected.isNotEmpty()
    }

    private fun updateAllUIs() {
        WirelessHelperWidget.triggerUpdate(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            WirelessHelperTileService.triggerUpdate(this)
        }
    }

    private fun updateNotification(content: String) {
        if (destroyed) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, createNotification(content))
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, WirelessHelperService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingContent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name)).setContentText(content)
            .setSmallIcon(R.drawable.ic_wireless_helper).setOngoing(true)
            .setContentIntent(pendingContent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_stop), pendingStop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onBind(intent: Intent?) = null
}
