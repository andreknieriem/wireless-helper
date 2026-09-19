package com.andrerinas.wirelesshelper

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class AutoStartReceiver : BroadcastReceiver() {
    private val TAG = "HUREV_AUTOSTART"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Broadcast received: $action")

        val prefs = context.getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val autoStartMode = prefs.getInt("auto_start_mode", 0) // 0=Off, 1=BT
        val connectionMode = prefs.getInt("connection_mode", 0)

        val devMode = connectionMode == com.andrerinas.wirelesshelper.strategy.StrategyWifiDirectDevServer.MODE
        if (autoStartMode == 0 && !devMode) return

        val targetMacs = prefs.getStringSet("auto_start_bt_macs", emptySet()) ?: emptySet()
        val legacyTargetMac = prefs.getString("auto_start_bt_mac", null)

        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        val deviceAddress = device?.address ?: return
        val isTarget = targetMacs.contains(deviceAddress) || deviceAddress == legacyTargetMac

        if (devMode && isTarget) {
            val connected = (prefs.getStringSet("dev_root_connected_bt", emptySet()) ?: emptySet()).toMutableSet()
            if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                connected.remove(deviceAddress)
                prefs.edit().putStringSet("dev_root_connected_bt", connected).apply()
                if (connected.isEmpty()) prefs.edit().remove("pending_wifi_start").remove("dev_root_pending_bt").apply()
                if (WirelessHelperService.isRunning) {
                    context.startService(Intent(context, WirelessHelperService::class.java).apply {
                        this.action = WirelessHelperService.ACTION_BT_DISCONNECTED
                        putExtra(WirelessHelperService.EXTRA_BT_MAC, deviceAddress)
                    })
                }
            } else if (action == BluetoothDevice.ACTION_ACL_CONNECTED && (autoStartMode == 1 || WirelessHelperService.isRunning)) {
                connected.add(deviceAddress)
                prefs.edit().putStringSet("dev_root_connected_bt", connected)
                    .putBoolean("dev_root_pending_bt", true).apply()
                if (WirelessHelperService.isRunning) startService(context, deviceAddress)
                else WifiNotificationHelper.checkWifiAndConnect(context, connectionMode = connectionMode) {
                    if (prefs.getBoolean("dev_root_pending_bt", false) &&
                        prefs.getInt("connection_mode", 0) == connectionMode &&
                        prefs.getStringSet("dev_root_connected_bt", emptySet())?.contains(deviceAddress) == true) {
                        startService(context, deviceAddress)
                    }
                }
            }
            return
        }

        val deviceDisplayName = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                device.alias ?: device.name ?: "Unknown"
            } else {
                device.name ?: "Unknown"
            }
        } catch (e: SecurityException) {
            deviceAddress
        }

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            Log.i(TAG, "BT Device connected: $deviceDisplayName ($deviceAddress)")

            if (isTarget) {
                // Precise check if the device is actually fully connected (not just ACL)
                // Using reflection
                val isFullyConnected = try {
                    val isConnectedMethod = device.javaClass.getMethod("isConnected")
                    isConnectedMethod.invoke(device) as? Boolean ?: true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to check isConnected via reflection, falling back to true.", e)
                    true // Fallback to true if reflection fails
                }

                if (!isFullyConnected) {
                    Log.w(TAG, "Device ACL is connected but isConnected() is false. Skipping start.")
                    return
                }

                Log.i(TAG, "MATCH! Checking Wi-Fi state before starting service...")

                // Wrap the service start logic to ensure Wi-Fi is enabled
                WifiNotificationHelper.checkWifiAndConnect(context, connectionMode = connectionMode) {
                    // If we reach this lambda, WiFi is ready or check was skipped (Hotspot mode)
                    prefs.edit().remove("pending_wifi_start").apply()
                    startService(context)
                }
                
                // If we didn't start the service yet and it's not Phone Hotspot mode, set the pending flag
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                if (connectionMode != 1 && !wifiManager.isWifiEnabled) {
                    Log.i(TAG, "WiFi is disabled. Setting pending_wifi_start flag.")
                    prefs.edit().putBoolean("pending_wifi_start", true).apply()
                }
            }
        } else if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            Log.i(TAG, "BT Device disconnected: $deviceDisplayName ($deviceAddress)")

            if (isTarget) {
                // Clear pending start on disconnect
                prefs.edit().remove("pending_wifi_start").apply()
                
                val stopOnDisconnect = prefs.getBoolean("bt_disconnect_stop", false)
                if (stopOnDisconnect) {
                    Log.i(TAG, "MATCH! Stopping service as requested by settings...")
                    stopService(context)
                }
            }
        }
    }

    private fun startService(context: Context, mac: String? = null) {
        val serviceIntent = Intent(context, WirelessHelperService::class.java).apply {
            action = WirelessHelperService.ACTION_START
            putExtra("EXTRA_FROM_BT", true)
            mac?.let { putExtra(WirelessHelperService.EXTRA_BT_MAC, it) }
        }
        try {
            Log.i(TAG, "Firing Foreground Service Intent with EXTRA_FROM_BT...")
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-start service: ${e.message}", e)
        }
    }

    private fun stopService(context: Context) {
        val serviceIntent = Intent(context, WirelessHelperService::class.java).apply {
            action = WirelessHelperService.ACTION_STOP
        }
        context.startService(serviceIntent)
    }
}
