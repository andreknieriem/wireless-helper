package com.andrerinas.wirelesshelper.devserver

object DevModePolicy {
    const val MODE_ID = 5
    const val URI_MODE = "wifi-direct-dev-root"

    fun reusesStart(mode: Int, requestedMode: Int?, running: Boolean): Boolean =
        running && mode == MODE_ID && requestedMode == MODE_ID

    fun statusAfterStop(current: String?, failure: String?, cleanupPending: Boolean, stopped: String): String? =
        failure ?: if (current != null && !cleanupPending) stopped else current
}
