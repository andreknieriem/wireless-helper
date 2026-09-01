package com.andrerinas.wirelesshelper

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.text.HtmlCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * One-time notice informing users about Android Auto 17.4+ breaking changes
 * that disable automated launch via Wireless Helper.
 */
object Aa174Notice {

    private const val TAG = "Aa174Notice"
    private var dialog: AlertDialog? = null

    fun maybeShow(activity: Activity?) {
        if (activity == null || activity.isFinishing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) return

        val prefs = activity.getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("aa174_notice_shown", false)) return
        if (dialog?.isShowing == true) return

        try {
            val rawMessage = activity.getString(R.string.aa174_notice_message)
            val messageSpanned = HtmlCompat.fromHtml(
                rawMessage.replace("\n", "<br/>"),
                HtmlCompat.FROM_HTML_MODE_LEGACY
            )

            dialog = MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
                .setIcon(R.drawable.ic_warning_white)
                .setTitle(R.string.aa174_notice_title)
                .setMessage(messageSpanned)
                .setCancelable(false)
                .setPositiveButton(R.string.aa174_notice_button_confirm) { d: DialogInterface, _: Int ->
                    prefs.edit { putBoolean("aa174_notice_shown", true) }
                    d.dismiss()
                }
                .setNeutralButton(R.string.aa174_notice_button_guide) { d: DialogInterface, _: Int ->
                    prefs.edit { putBoolean("aa174_notice_shown", true) }
                    d.dismiss()
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://headunit.andrerinas.com/guides/wireless/"))
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch guide URL: ${e.message}")
                    }
                }
                .show()
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Window token invalid when showing dialog: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show dialog: ${e.message}")
        }
    }

    fun dismiss() {
        try {
            dialog?.dismiss()
        } catch (_: Exception) {
        } finally {
            dialog = null
        }
    }
}
