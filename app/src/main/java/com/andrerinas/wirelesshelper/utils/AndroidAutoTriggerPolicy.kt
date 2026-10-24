package com.andrerinas.wirelesshelper.utils

/**
 * Whether this phone's Android Auto still takes the start request this app sends it.
 *
 * Android Auto 17.4 closed every door the trigger chain uses (see TransparentTriggerActivity):
 * WirelessStartupActivity is no longer exported and the WIFI bridge checks for a Google signature.
 * Measured on 17.4.663034, and on a Galaxy Z Fold3 with 17.8.663874 (2026-10-23), whose helper
 * searched for 14 hours while every session came from the head unit's Native mode instead. A
 * phone like that has nothing to gain from this app, and its owner should be told so.
 */
object AndroidAutoTriggerPolicy {

    /** The first Android Auto release that refuses a start request from another app. */
    private const val CLOSED_FROM_MAJOR = 17
    private const val CLOSED_FROM_MINOR = 4

    private val leadingVersion = Regex("""^(\d+)\.(\d+)""")

    /** True when [versionName] still takes the request, false when it refuses it, null when unknown. */
    fun acceptsAppTrigger(versionName: String?): Boolean? {
        val match = versionName?.let { leadingVersion.find(it) } ?: return null
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        return major < CLOSED_FROM_MAJOR || (major == CLOSED_FROM_MAJOR && minor < CLOSED_FROM_MINOR)
    }

    /** The release a person would name, "17.8" for "17.8.663874-release", or null when unreadable. */
    fun release(versionName: String?): String? = versionName?.let { leadingVersion.find(it) }?.value
}
