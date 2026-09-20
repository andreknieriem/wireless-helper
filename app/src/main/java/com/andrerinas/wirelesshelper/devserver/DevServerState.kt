package com.andrerinas.wirelesshelper.devserver

import java.net.InetAddress

data class DevServerSocket(val localAddress: String, val remoteAddress: String, val state: String, val remotePort: Int = 0) {
    val listening: Boolean get() = state == "0A"
    val established: Boolean get() = state == "01"
}

data class DevServerConnection(val localAddress: String, val remoteAddress: String, val remotePort: Int) {
    fun matches(socket: DevServerSocket): Boolean = remotePort == socket.remotePort &&
        sameAddress(localAddress, socket.localAddress) && sameAddress(remoteAddress, socket.remoteAddress)

    private fun sameAddress(left: String, right: String): Boolean =
        InetAddress.getByName(left).address.contentEquals(InetAddress.getByName(right).address)
}

data class DevServerState(val serviceRunning: Boolean, val sockets: List<DevServerSocket>) {
    val hasConnection: Boolean get() = sockets.any { it.established }
    val stopped: Boolean get() = !serviceRunning && sockets.none { it.listening || it.established }
    val ready: Boolean get() = serviceRunning && sockets.any { it.listening }

    fun hasOtherConnection(owned: DevServerConnection?): Boolean = sockets.any { it.established && owned?.matches(it) != true }

    fun localEndpoint(localAddresses: Set<InetAddress>): InetAddress? = sockets.asSequence()
        .filter { it.listening }.mapNotNull { socket ->
            val address = InetAddress.getByName(socket.localAddress)
            when {
                address.isAnyLocalAddress -> InetAddress.getByName(if (address.address.size == 4) "127.0.0.1" else "::1")
                address.isLoopbackAddress -> address
                // Keep the interface scope of a concrete IPv6 address; /proc omits it.
                else -> localAddresses.firstOrNull { it == address }
            }
        }.sortedBy { it.address.size }.firstOrNull()

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
                val remotePort = fields[2].substringAfterLast(':').toIntOrNull(16)
                if (local == null || remote == null || remotePort == null) null else DevServerSocket(local, remote, fields[3], remotePort)
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
