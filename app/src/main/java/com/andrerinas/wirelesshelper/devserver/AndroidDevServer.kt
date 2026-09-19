package com.andrerinas.wirelesshelper.devserver

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.wifi.WifiManager
import kotlinx.coroutines.delay

object AndroidDevServer {
    suspend fun ensureWifi(context: Context, runner: RootCommandRunner) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifi.isWifiEnabled) return
        for (command in listOf("cmd wifi set-wifi-enabled enabled", "svc wifi enable")) {
            runner.run(command)
            repeat(10) {
                if (wifi.isWifiEnabled) return
                delay(500)
            }
        }
        throw DevServerException(DevServerError.WIFI_UNAVAILABLE)
    }

    fun create(context: Context, runner: RootCommandRunner = SuCommandRunner()): DevServerController {
        val pm = context.packageManager
        val uid = try {
            pm.getApplicationInfo(DevServerController.PACKAGE, 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            throw DevServerException(DevServerError.AA_MISSING)
        }
        try {
            pm.getServiceInfo(ComponentName(DevServerController.PACKAGE, DevServerController.SERVICE), 0)
        } catch (_: PackageManager.NameNotFoundException) {
            throw DevServerException(DevServerError.COMPONENT_MISSING)
        }
        return DevServerController(runner, uid, Build.VERSION.SDK_INT,
            diagnostic = { android.util.Log.i("HUREV_DEV", it) })
    }
}

fun devErrorResource(error: DevServerError): Int = when (error) {
    DevServerError.ROOT_DENIED -> com.andrerinas.wirelesshelper.R.string.dev_error_root
    DevServerError.AA_MISSING -> com.andrerinas.wirelesshelper.R.string.dev_error_aa
    DevServerError.COMPONENT_MISSING -> com.andrerinas.wirelesshelper.R.string.dev_error_component
    DevServerError.WIFI_UNAVAILABLE -> com.andrerinas.wirelesshelper.R.string.dev_error_wifi
    DevServerError.STATE_UNAVAILABLE -> com.andrerinas.wirelesshelper.R.string.dev_error_state
    DevServerError.START_FAILED -> com.andrerinas.wirelesshelper.R.string.dev_error_start
    DevServerError.STOP_FAILED -> com.andrerinas.wirelesshelper.R.string.dev_error_stop
}
