package com.andrerinas.wirelesshelper

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.andrerinas.wirelesshelper.utils.HelperLog

/**
 * A transparent activity that surfaces the app to the foreground.
 * This is required to bypass "Background Activity Launch" (BAL) restrictions
 * introduced in modern Android versions (14+ / SDK 36).
 *
 * From the foreground it fires a chain of triggers at Android Auto, newest-compatible last:
 *
 * 1. `WirelessStartupActivity` — the historical entry point, carrying the proxy's host/port.
 *    Gearhead 17.4 ships it with `exported=false`, so on current phones this throws
 *    a SecurityException and on older ones it just works.
 * 2. The WIFI bridge — what that activity *did inside*: launch `FirstActivityImpl` (Android R+)
 *    or `com.google.android.gms/.car.FirstActivity` (older) with action
 *    `com.google.android.gms.car.WIFI_ACTION_BRIDGE` and the same extras. Read out of gearhead
 *    17.4.663034's own bytecode, so the extras match what the trampoline forwarded, not folklore.
 * 3. The Bluetooth broadcast (`START_WIRELESS_PROJECTION`) — wakes gearhead's wireless setup.
 *
 * IMPORTANT — verified on gearhead 17.4.663034 (Android 16 tablet), the bridge is caller-gated:
 * FirstActivityImpl calls getCallingPackage() and runs it through a GoogleCertificatesLookup
 * signature check, rejecting anything not Google-signed with "Unknown caller for bridge intent".
 * So on 17.4+ step 1 throws (unexported), step 2 launches but is refused inside gearhead, and
 * step 3 carries no host/port and times out. On those builds NO intent from a third-party app can
 * start wireless projection -- which is why this device fails identically with the original head
 * unit app. The chain below still helps older/intermediate gearhead builds where one of these
 * doors is open, and degrades cleanly (with an explanatory log) where none is. Because gearhead's
 * verdict on the bridge is internal to its own process, we cannot see it from here and so still
 * fire the broadcast afterward rather than assuming the bridge took.
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
            HelperLog.i(TAG, "TransparentTriggerActivity in foreground. Attempting AA launch via Activity...")
            try {
                startActivity(targetIntent)
                HelperLog.i(TAG, "WirelessStartupActivity accepted the launch.")
            } catch (e: Exception) {
                HelperLog.w(TAG, "Activity launch failed (${e.message}). Trying the WIFI bridge it used to wrap...")
                // The bridge may launch yet be refused inside gearhead (Google-signed callers only
                // on 17.4+); we cannot observe that from here, so always fall through to the
                // broadcast rather than trusting a launch that gearhead might reject.
                launchWifiBridge(targetIntent)
                sendBroadcastFallbacks(targetIntent)
            }
        } else {
            HelperLog.w(TAG, "No target intent provided to TransparentTriggerActivity.")
        }

        // Close the activity immediately after firing the trigger
        finish()
    }

    /**
     * Replays what gearhead's own (now unexported) trampoline did with our intent.
     *
     * Returns true if some bridge activity accepted the launch. The extras are copied one by one
     * rather than via putExtras() so this stays a faithful copy of the trampoline's behaviour —
     * it forwarded exactly these and nothing else.
     */
    private fun launchWifiBridge(source: Intent): Boolean {
        val bridge = Intent("com.google.android.gms.car.WIFI_ACTION_BRIDGE").apply {
            putExtra("PARAM_HOST_ADDRESS", source.getStringExtra("PARAM_HOST_ADDRESS"))
            putExtra("PARAM_SERVICE_PORT", source.getIntExtra("PARAM_SERVICE_PORT", 5288))
            @Suppress("DEPRECATION")
            source.getParcelableExtra<android.os.Parcelable>("wifi_info")?.let { putExtra("wifi_info", it) }
            @Suppress("DEPRECATION")
            source.getParcelableExtra<android.os.Parcelable>("PARAM_SERVICE_WIFI_NETWORK")?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
            // The trampoline forwarded this flag from its caller; Q+ hosts run the socket flow.
            putExtra("WIFI_Q_ENABLED", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        // R+ gearhead hosts the bridge itself; before that it lived in GMS. Same order the
        // trampoline chose in.
        val candidates = listOf(
            ComponentName(
                "com.google.android.projection.gearhead",
                "com.google.android.apps.auto.carservice.gmscorecompat.FirstActivityImpl"
            ),
            ComponentName("com.google.android.gms", "com.google.android.gms.car.FirstActivity")
        )

        for (component in candidates) {
            try {
                startActivity(Intent(bridge).setComponent(component))
                // "launched", not "accepted": gearhead validates the caller's signature after this
                // returns and may still refuse it. See the class comment.
                HelperLog.i(TAG, "WIFI bridge launched at ${component.flattenToShortString()} (gearhead may still refuse it on 17.4+).")
                return true
            } catch (e: Exception) {
                HelperLog.w(TAG, "WIFI bridge via ${component.flattenToShortString()} failed: ${e.message}")
            }
        }
        return false
    }

    private fun sendBroadcastFallbacks(targetIntent: Intent) {
        try {
            // Fallback: WirelessStartupReceiver (default-disabled in 17.4, kept for older builds)
            val port = targetIntent.getIntExtra("PARAM_SERVICE_PORT", 5288)
            val receiverIntent = Intent().apply {
                setClassName("com.google.android.projection.gearhead", "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver")
                action = "com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START"
                putExtra("ip_address", "127.0.0.1")
                putExtra("projection_port", port)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            sendBroadcast(receiverIntent)
            HelperLog.i(TAG, "Broadcast fallback 1 (WirelessStartupReceiver) sent.")

            // Fallback: WifiBluetoothReceiver (START_WIRELESS_PROJECTION). Cannot carry our
            // host/port, but wakes the :car process and re-enables the bridge activity.
            val bondedDevice = try {
                val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                val bonded = adapter?.bondedDevices
                val connectedDevice = bonded?.firstOrNull { dev ->
                    try {
                        val m = dev.javaClass.getMethod("isConnected")
                        (m.invoke(dev) as? Boolean) == true
                    } catch (e: Exception) { false }
                }
                val targetDev = connectedDevice ?: bonded?.firstOrNull()
                HelperLog.i(TAG, "BT Discovery: bondedCount=${bonded?.size ?: 0}, connectedMac=${connectedDevice?.address}, selectedMac=${targetDev?.address}")
                targetDev
            } catch (e: Exception) {
                null
            }

            val btReceiverIntent = Intent("com.google.android.projection.gearhead.START_WIRELESS_PROJECTION").apply {
                setClassName("com.google.android.projection.gearhead", "com.google.android.apps.auto.wireless.bluetooth.WifiBluetoothReceiver")
                // The real parcelable, not just the MAC string: gearhead's wireless service
                // dereferences EXTRA_DEVICE and crashes its :car process on null.
                bondedDevice?.let { putExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, it) }
                putExtra("DEVICE_ADDRESS", bondedDevice?.address ?: "00:11:22:33:44:55")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            sendBroadcast(btReceiverIntent)
            HelperLog.i(TAG, "Broadcast fallback 2 (WifiBluetoothReceiver START_WIRELESS_PROJECTION, device=${bondedDevice?.address}) sent.")
        } catch (e2: Exception) {
            HelperLog.e(TAG, "All triggers failed: ${e2.message}", e2)
        }
    }
}
