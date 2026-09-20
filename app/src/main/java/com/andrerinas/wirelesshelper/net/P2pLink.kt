package com.andrerinas.wirelesshelper.net

import java.net.InetAddress

data class P2pLink(val host: String, val localAddress: String, val interfaceName: String) {
    companion object {
        /** Only addresses supplied by the actual group interface are eligible. */
        fun select(host: String, interfaceName: String?, addresses: List<Pair<InetAddress, Int>>): P2pLink? {
            if (interfaceName.isNullOrBlank()) return null
            val remote = InetAddress.getByName(host).address
            val local = addresses.firstOrNull { (address, prefix) ->
                val bytes = address.address
                !address.isLoopbackAddress && !address.isAnyLocalAddress && !address.isMulticastAddress &&
                    bytes.size == remote.size && !bytes.contentEquals(remote) &&
                    prefix in 1..bytes.size * 8 && (0 until prefix).all { bit ->
                        val mask = 1 shl (7 - bit % 8)
                        (bytes[bit / 8].toInt() and mask) == (remote[bit / 8].toInt() and mask)
                    }
            }?.first ?: return null
            return P2pLink(host, local.hostAddress!!, interfaceName)
        }
    }
}
