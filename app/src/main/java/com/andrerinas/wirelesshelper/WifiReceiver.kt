package com.andrerinas.wirelesshelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log

class WifiReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("HUREV_WIFI", "WifiReceiver: Received intent ${action ?: "PendingIntent Callback"}")
        
        if (action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
            val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
            if (state == WifiManager.WIFI_STATE_ENABLED) {
                Log.i("HUREV_WIFI", "WifiReceiver: Wi-Fi enabled detected!")
                checkWifiAndStart(context)
            }
        } else {
            checkWifiAndStart(context)
        }
    }

    private fun checkWifiAndStart(context: Context) {
        WifiJobService.checkWifiAndStart(context)
        WifiNotificationHelper.handlePendingWifiStart(context)
    }
}