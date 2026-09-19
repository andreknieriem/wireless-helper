package com.andrerinas.wirelesshelper.devserver

import java.net.InetAddress

data class DevServerSocket(val localAddress: String, val remoteAddress: String, val state: String) {
    val listening: Boolean get() = state == "0A"
    val established: Boolean get() = state == "01"
}

data class DevServerState(val serviceRunning: Boolean, val sockets: List<DevServerSocket>) {
    val hasConnection: Boolean get() = sockets.any { it.established }
    val stopped: Boolean get() = !serviceRunning && sockets.none { it.listening || it.established }
    val ready: Boolean get() = serviceRunning && sockets.any {
        it.listening && !InetAddress.getByName(it.localAddress).isLoopbackAddress
    }

    fun connectedTo(host: String): Boolean = sockets.any { it.established && it.remoteAddress == host }

    companion object {
        /** Ignore ANR history and destroyed records; neither proves a live server. */
        fun hasStartedService(dump: String): Boolean {
            var section = ""
            var target = false
            var started: Boolean? = null
            var foreground = false
            fun activeRecord() = target && (started != false || foreground)
            for (line in dump.lineSequence()) {
                val text = line.trim()
                val isSection = line.startsWith("  ") && !line.startsWith("   ") && text.endsWith(":")
                if (isSection || text.startsWith("* ")) {
                    if (activeRecord()) return true
                    target = false
                    started = null
                    foreground = false
                }
                if (isSection) section = text
                val liveSection = section.matches(Regex("User \\d+ active services:")) ||
                    section == "Pending services:" || section == "Restarting services:"
                if (liveSection && text.startsWith("* ") && text.contains("ServiceRecord{") &&
                    (text.contains(DevServerController.COMPONENT) ||
                        text.contains("${DevServerController.PACKAGE}/.companion.DeveloperHeadUnitNetworkService"))) {
                    target = true
                }
                if (target) {
                    Regex("\\b(?:startRequested|started)=(true|false)\\b").find(text)?.let {
                        started = it.groupValues[1] == "true"
                    }
                    if (text.contains("isForeground=true")) foreground = true
                }
            }
            return activeRecord()
        }

        /** /proc addresses use host-order 32-bit words, including each word of an IPv6 address. */
        fun parseSockets(text: String, uid: Int, littleEndian: Boolean = true): List<DevServerSocket> =
            text.lineSequence().mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size < 8 || fields[7].toIntOrNull() != uid) return@mapNotNull null
                if (fields[1].substringAfterLast(':').toIntOrNull(16) != 5277) return@mapNotNull null
                val local = decodeAddress(fields[1].substringBeforeLast(':'), littleEndian)
                val remote = decodeAddress(fields[2].substringBeforeLast(':'), littleEndian)
                if (local == null || remote == null) null else DevServerSocket(local, remote, fields[3])
            }.toList()

        private fun decodeAddress(hex: String, littleEndian: Boolean): String? = runCatching {
            require(hex.length == 8 || hex.length == 32)
            val bytes = hex.chunked(8).flatMap { word ->
                val pairs = word.chunked(2)
                (if (littleEndian) pairs.reversed() else pairs).map { it.toInt(16).toByte() }
            }.toByteArray()
            InetAddress.getByAddress(bytes).hostAddress
        }.getOrNull()
    }
}
