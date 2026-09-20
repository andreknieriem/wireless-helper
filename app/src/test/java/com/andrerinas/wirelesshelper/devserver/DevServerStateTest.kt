package com.andrerinas.wirelesshelper.devserver

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Inet6Address

class DevServerStateTest {
    @Test fun syntheticStartAndStopSnapshots() {
        val empty = """
            ACTIVITY MANAGER SERVICES (dumpsys activity services)
              (nothing)
        """.trimIndent()
        val running = """
            ACTIVITY MANAGER SERVICES (dumpsys activity services)
              User 0 active services:
              * $record
                isForeground=true
                startRequested=true
        """.trimIndent()
        assertFalse(DevServerState.hasStartedService(empty))
        assertTrue(DevServerState.hasStartedService(running))
        assertTrue(DevServerState(DevServerState.hasStartedService(empty), emptyList()).stopped)
        // No service alone cannot confirm shutdown if a live AA socket still exists.
        assertFalse(DevServerState(false, listOf(DevServerSocket("0.0.0.0", "0.0.0.0", "0A"))).stopped)
        assertFalse(DevServerState(false, listOf(DevServerSocket("192.168.49.2", "192.168.49.1", "01"))).stopped)
    }

    private val record = "ServiceRecord{123 u0 ${DevServerController.PACKAGE}/.companion.DeveloperHeadUnitNetworkService}"

    @Test fun ignoresAnrHistoryAndDestroyingRecords() {
        val dump = "ACTIVITY MANAGER SERVICES\n  Last ANR service:\n  * $record\n    startRequested=true\n" +
            "  Destroying services:\n  * Destroy $record\n    startRequested=false"
        assertFalse(DevServerState.hasStartedService(dump))
    }

    @Test fun activeAndScheduledServicesStillNeedStopping() {
        for (section in listOf("User 0 active services:", "User 10 active services:", "Pending services:", "Restarting services:")) {
            assertTrue(DevServerState.hasStartedService("  $section\n  * $record\n    startRequested=true"))
        }
    }

    @Test fun stoppedBoundServiceIsNotAStartedDevServer() {
        assertFalse(DevServerState.hasStartedService("  User 0 active services:\n  * $record\n    started=false connections=1"))
        assertFalse(DevServerState.hasStartedService("  User 0 active services:\n  * $record\n    isForeground=false\n    startRequested=false"))
        assertTrue(DevServerState.hasStartedService("  User 0 active services:\n  * $record\n    isForeground=true\n    startRequested=false"))
    }

    @Test fun activeRecordAfterHistoryIsNotIgnored() {
        assertTrue(DevServerState.hasStartedService("  Last ANR service:\n  * $record\n" +
            "  User 0 active services:\n  * $record\n    started=true connections=0"))
        assertFalse(DevServerState.hasStartedService("  User 0 active services:\n  * ServiceRecord{123 u0 other/.Service}\n" +
            "    reference=$record"))
    }

    private fun row(local: String, remote: String = "00000000", state: String = "0A", uid: Int = 10123, port: String = "149D") =
        "0: $local:$port $remote:C001 $state 00000000:00000000 00:00000000 00000000 $uid 0 1"

    @Test fun filtersUidAndLocalPortWithoutOpeningSockets() {
        val text = listOf(row("00000000"), row("00000000", uid = 999), row("00000000", port = "14A9"),
            "unparseable line").joinToString("\n")
        val sockets = DevServerState.parseSockets(text, 10123)
        assertEquals(1, sockets.size)
        assertTrue(DevServerState(true, sockets).ready)
    }

    @Test fun decodesIpv4ClientAndListener() {
        val sockets = DevServerState.parseSockets(row("0231A8C0", "0131A8C0", "01"), 10123)
        assertEquals("192.168.49.2", sockets.single().localAddress)
        assertEquals("192.168.49.1", sockets.single().remoteAddress)
        assertEquals(49153, sockets.single().remotePort)
    }

    @Test fun decodesIpv6AndMappedIpv4() {
        val sockets = DevServerState.parseSockets(
            row("00000000000000000000000000000000") + "\n" +
                row("0000000000000000FFFF00000231A8C0", "0000000000000000FFFF00000131A8C0", "01"), 10123)
        assertEquals(2, sockets.size)
        assertTrue(DevServerState(true, sockets).ready)
        assertEquals("192.168.49.1", sockets.last().remoteAddress)
    }

    @Test fun acceptsBigEndianProcWords() {
        val sockets = DevServerState.parseSockets(row("C0A83102", "C0A83101", "01"), 10123, littleEndian = false)
        assertEquals("192.168.49.2", sockets.single().localAddress)
        assertEquals("192.168.49.1", sockets.single().remoteAddress)
    }

    @Test fun loopbackIsReadyButMissingServiceOrListenerIsNot() {
        assertTrue(DevServerState(true, DevServerState.parseSockets(row("0100007F"), 10123)).ready)
        assertFalse(DevServerState(true, emptyList()).ready)
        assertFalse(DevServerState(false, listOf(DevServerSocket("0.0.0.0", "0.0.0.0", "0A"))).ready)
    }

    @Test fun timeWaitDoesNotPreventConfirmedStop() {
        assertTrue(DevServerState(false, listOf(DevServerSocket("192.168.49.2", "192.168.49.1", "06"))).stopped)
        assertFalse(DevServerState(true, emptyList()).stopped)
    }

    @Test fun wildcardAndLoopbackEndpointsStayOnPhone() {
        for ((listener, expected) in listOf("0.0.0.0" to "127.0.0.1", "::" to "::1", "::1" to "::1", "127.0.0.1" to "127.0.0.1")) {
            val state = DevServerState(true, listOf(DevServerSocket(listener, "::", "0A")))
            assertEquals(InetAddress.getByName(expected), state.localEndpoint(emptySet()))
        }
    }

    @Test fun specificEndpointMustBelongToPhoneAndIpv4IsPreferred() {
        val address = InetAddress.getByName("192.0.2.2")
        val state = DevServerState(true, listOf(DevServerSocket(address.hostAddress!!, "0.0.0.0", "0A")))
        assertNull(state.localEndpoint(emptySet()))
        assertEquals(address, state.localEndpoint(setOf(address)))
        val dual = DevServerState(true, listOf(DevServerSocket("::", "::", "0A"), DevServerSocket("0.0.0.0", "0.0.0.0", "0A")))
        assertEquals(InetAddress.getByName("127.0.0.1"), dual.localEndpoint(emptySet()))
    }

    @Test fun ownConnectionIsIdentifiedByFullTupleNotJustLoopback() {
        val owned = DevServerConnection("127.0.0.1", "127.0.0.1", 40000)
        val socket = DevServerSocket("127.0.0.1", "127.0.0.1", "01", 40000)
        assertFalse(DevServerState(true, listOf(socket)).hasOtherConnection(owned))
        assertTrue(DevServerState(true, listOf(socket)).hasOtherConnection(null))
        assertTrue(DevServerState(true, listOf(socket.copy(remotePort = 40001))).hasOtherConnection(owned))
        assertTrue(DevServerState(true, listOf(socket.copy(localAddress = "192.0.2.2"))).hasOtherConnection(owned))
    }

    @Test fun specificIpv6KeepsInterfaceScopeAndOwnershipMatchesProcAddress() {
        val raw = InetAddress.getByName("fe80::2")
        val scoped = Inet6Address.getByAddress(null, raw.address, 2)
        val state = DevServerState(true, listOf(DevServerSocket(raw.hostAddress!!, "::", "0A")))
        assertSame(scoped, state.localEndpoint(setOf(scoped)))
        val owned = DevServerConnection(scoped.hostAddress!!, scoped.hostAddress!!, 40000)
        assertTrue(owned.matches(DevServerSocket(raw.hostAddress!!, raw.hostAddress!!, "01", 40000)))
    }
}
