package com.andrerinas.wirelesshelper.net

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class P2pLinkTest {
    private fun address(ip: String, prefix: Int) = InetAddress.getByName(ip) to prefix

    @Test fun waitsForActualGroupInterfaceAndAddress() {
        assertNull(P2pLink.select("192.0.2.1", null, listOf(address("192.0.2.2", 24))))
        assertNull(P2pLink.select("192.0.2.1", "p2p-test", emptyList()))
        assertNull(P2pLink.select("192.0.2.1", "p2p-test", listOf(address("198.51.100.2", 24))))
        assertNull(P2pLink.select("192.0.2.1", "p2p-test", listOf(address("192.0.2.1", 24))))
    }

    @Test fun choosesAddressInOwnersSubnetWithoutFixedGateway() {
        val link = P2pLink.select("192.0.2.65", "p2p-test", listOf(address("192.0.2.2", 26), address("192.0.2.66", 26)))
        assertEquals(P2pLink("192.0.2.65", "192.0.2.66", "p2p-test"), link)
    }

    @Test fun matchesIpv6WithoutUsingIpv4Station() {
        val link = P2pLink.select("2001:db8::1", "p2p-test", listOf(address("192.0.2.2", 24), address("2001:db8::2", 64)))
        assertEquals(InetAddress.getByName("2001:db8::2"), InetAddress.getByName(link!!.localAddress))
    }

    @Test fun invalidPrefixAndUnusableAddressesAreRejected() {
        for (entry in listOf(address("127.0.0.2", 8), address("0.0.0.0", 0), address("192.0.2.2", 33))) {
            assertNull(P2pLink.select("192.0.2.1", "p2p-test", listOf(entry)))
        }
    }
}
