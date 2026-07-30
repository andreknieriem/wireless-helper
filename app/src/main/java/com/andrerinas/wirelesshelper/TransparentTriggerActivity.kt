package com.andrerinas.wirelesshelper

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.andrerinas.wirelesshelper.strategy.BaseStrategy

/**
 * A transparent activity that surfaces the app to the foreground.
 * This is required to bypass "Background Activity Launch" (BAL) restrictions
 * introduced in modern Android versions (14+ / SDK 36).
 */
class TransparentTriggerActivity : AppCompatActivity() {

    private val TAG = "HUREV_TRIGGER"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make activity invisible
        window.setBackgroundDrawableResource(android.R.color.transparent)
        
        val targetIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("intent", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("intent")
        }

        if (targetIntent != null) {
            Log.i(TAG, "TransparentTriggerActivity in foreground. Attempting AA launch via Activity...")
            try {
                startActivity(targetIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Activity launch failed (${e.message}). Attempting Broadcast fallback...")
                try {
                    // Fallback 1: WirelessStartupReceiver
                    val port = targetIntent.getIntExtra("PARAM_SERVICE_PORT", 5288)
                    val receiverIntent = Intent().apply {
                        setClassName("com.google.android.projection.gearhead", "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver")
                        action = "com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START"
                        putExtra("ip_address", "127.0.0.1")
                        putExtra("projection_port", port)
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    }
                    sendBroadcast(receiverIntent)
                    Log.i(TAG, "Broadcast fallback 1 (WirelessStartupReceiver) sent.")

                    // Fallback 2: WifiBluetoothReceiver (START_WIRELESS_PROJECTION) for AA 17.4+
                    val bondedAddress = try {
                        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                        val bonded = adapter?.bondedDevices
                        val connectedDevice = bonded?.firstOrNull { dev ->
                            try {
                                val m = dev.javaClass.getMethod("isConnected")
                                (m.invoke(dev) as? Boolean) == true
                            } catch (e: Exception) { false }
                        }
                        val targetDev = connectedDevice ?: bonded?.firstOrNull()
                        Log.i(TAG, "BT Discovery: bondedCount=${bonded?.size ?: 0}, connectedMac=${connectedDevice?.address}, selectedMac=${targetDev?.address}")
                        targetDev?.address
                    } catch (e: Exception) {
                        null
                    } ?: "00:11:22:33:44:55"

                    val btReceiverIntent = Intent("com.google.android.projection.gearhead.START_WIRELESS_PROJECTION").apply {
                        setClassName("com.google.android.projection.gearhead", "com.google.android.apps.auto.wireless.bluetooth.WifiBluetoothReceiver")
                        putExtra("DEVICE_ADDRESS", bondedAddress)
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    }
                    sendBroadcast(btReceiverIntent)
                    Log.i(TAG, "Broadcast fallback 2 (WifiBluetoothReceiver START_WIRELESS_PROJECTION with MAC $bondedAddress) sent.")
                } catch (e2: Exception) {
                    Log.e(TAG, "All triggers failed. Activity Error: ${e.message}, Broadcast Error: ${e2.message}")
                }
            }
        } else {
            Log.w(TAG, "No target intent provided to TransparentTriggerActivity.")
        }

        // Close the activity immediately after firing the trigger
        finish()
    }
}
