package com.andrerinas.wirelesshelper.devserver

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DevServerBridgeTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private fun server() = ServerSocket(0, 1, loopback).apply { soTimeout = 4000 }
    private fun endpoint(server: ServerSocket) = InetSocketAddress(loopback, server.localPort)

    @Test(timeout = 15_000) fun forwardsBinaryBothWaysToSlowRecipientWithoutChangingOrder() = runBlocking {
        server().use { headServer -> server().use { devServer ->
            val bridge = DevServerBridge()
            val connected = CompletableDeferred<Unit>()
            val task = async { runCatching { bridge.run(endpoint(headServer), loopback, endpoint(devServer)) { connected.complete(Unit) } } }
            try {
                withContext(Dispatchers.IO) { headServer.accept() }.use { head ->
                    withContext(Dispatchers.IO) { devServer.accept() }.use { dev ->
                        withTimeout(4000) { connected.await() }
                        assertNotNull(bridge.connection)
                        assertTrue(bridge.needsServerRecovery)
                        head.soTimeout = 5000
                        dev.soTimeout = 5000
                        val outbound = ByteArray(1024 * 1024) { (it * 31).toByte() }
                        val inbound = ByteArray(256 * 1024) { (it * 17).toByte() }
                        coroutineScope {
                            launch(Dispatchers.IO) { head.getOutputStream().write(outbound) }
                            launch(Dispatchers.IO) { dev.getOutputStream().write(inbound) }
                            launch(Dispatchers.IO) {
                                val received = ByteArray(outbound.size)
                                val input = DataInputStream(dev.getInputStream())
                                var offset = 0
                                while (offset < received.size) {
                                    val count = minOf(8192, received.size - offset)
                                    input.readFully(received, offset, count)
                                    offset += count
                                    delay(1)
                                }
                                assertArrayEquals(outbound, received)
                            }
                            launch(Dispatchers.IO) {
                                val received = ByteArray(inbound.size)
                                DataInputStream(head.getInputStream()).readFully(received)
                                assertArrayEquals(inbound, received)
                            }
                        }
                    }
                }
                withTimeout(4000) { task.await() }
            } finally { bridge.close(); task.cancelAndJoin() }
        } }
        Unit
    }

    @Test(timeout = 15_000) fun eofOnEitherSideClosesOtherSideAndAllOwnedSockets() = runBlocking {
        for (closeHead in listOf(true, false)) {
            server().use { headServer -> server().use { devServer ->
                val owned = mutableListOf<Socket>()
                val bridge = DevServerBridge { Socket().also { owned.add(it) } }
                val connected = CompletableDeferred<Unit>()
                val task = async { runCatching { bridge.run(endpoint(headServer), loopback, endpoint(devServer)) { connected.complete(Unit) } } }
                try {
                    withContext(Dispatchers.IO) { headServer.accept() }.use { head ->
                        withContext(Dispatchers.IO) { devServer.accept() }.use { dev ->
                            withTimeout(4000) { connected.await() }
                            (if (closeHead) head else dev).close()
                            val remaining = if (closeHead) dev else head
                            remaining.soTimeout = 4000
                            assertEquals(-1, withContext(Dispatchers.IO) { remaining.getInputStream().read() })
                            withTimeout(4000) { task.await() }
                            assertEquals(2, owned.size)
                            assertTrue(owned.all { it.isClosed })
                        }
                    }
                } finally { bridge.close(); task.cancelAndJoin() }
            } }
        }
    }

    private class BlockedConnectSocket : Socket() {
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        var timeout = 0
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            this.timeout = timeout
            entered.countDown()
            check(released.await(4, TimeUnit.SECONDS)) { "Cancellation did not close connecting socket" }
            throw SocketException("Closed while connecting")
        }
        override fun close() { super.close(); released.countDown() }
    }

    @Test(timeout = 15_000) fun cancellationClosesSocketDuringEitherConnect() = runBlocking {
        for (blockHead in listOf(true, false)) {
            server().use { headServer ->
                val blocked = BlockedConnectSocket()
                val owned = mutableListOf<Socket>()
                val bridge = DevServerBridge {
                    (if (blockHead || owned.isNotEmpty()) blocked else Socket()).also { owned.add(it) }
                }
                val task = launch {
                    bridge.run(endpoint(headServer), loopback, InetSocketAddress(loopback, 5277)) { fail("Must not connect") }
                }
                try {
                    assertTrue(withContext(Dispatchers.IO) { blocked.entered.await(3, TimeUnit.SECONDS) })
                    withTimeout(3000) { task.cancelAndJoin() }
                    assertEquals(if (blockHead) 5000 else 2000, blocked.timeout)
                    assertTrue(owned.all { it.isClosed })
                } finally { bridge.close(); task.cancelAndJoin() }
            }
        }
    }

    @Test(timeout = 10_000) fun devConnectFailureClosesHeadAndRequestsRecovery() = runBlocking {
        server().use { headServer ->
            val owned = mutableListOf<Socket>()
            val bridge = DevServerBridge {
                (if (owned.isEmpty()) Socket() else object : Socket() {
                    override fun connect(endpoint: SocketAddress, timeout: Int) { throw SocketException("Synthetic failure") }
                }).also { owned.add(it) }
            }
            try {
                bridge.run(endpoint(headServer), loopback, InetSocketAddress(loopback, 5277)) { fail("Must not connect") }
                fail("Expected failure")
            } catch (e: DevServerBridge.Failure) {
                assertEquals(DevServerBridge.Stage.DEV_SERVER, e.stage)
                assertTrue(bridge.needsServerRecovery)
                assertTrue(owned.all { it.isClosed })
            }
        }
    }

    @Test(timeout = 10_000) fun headConnectFailureDoesNotTouchDevServer() = runBlocking {
        val owned = mutableListOf<Socket>()
        val bridge = DevServerBridge {
            object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) { throw SocketException("Synthetic failure") }
            }.also { owned.add(it) }
        }
        try {
            bridge.run(InetSocketAddress(loopback, 5288), loopback, InetSocketAddress(loopback, 5277)) { fail("Must not connect") }
            fail("Expected failure")
        } catch (e: DevServerBridge.Failure) {
            assertEquals(DevServerBridge.Stage.HEADUNIT, e.stage)
            assertEquals(1, owned.size)
            assertTrue(owned.single().isClosed)
            assertFalse(bridge.needsServerRecovery)
        }
    }

    @Test(timeout = 10_000) fun cancellationUnblocksIdlePumps() = runBlocking {
        server().use { headServer -> server().use { devServer ->
            val bridge = DevServerBridge()
            val connected = CompletableDeferred<Unit>()
            val task = launch { bridge.run(endpoint(headServer), loopback, endpoint(devServer)) { connected.complete(Unit) } }
            try {
                withContext(Dispatchers.IO) { headServer.accept() }.use { head ->
                    withContext(Dispatchers.IO) { devServer.accept() }.use { dev ->
                        withTimeout(4000) { connected.await() }
                        withTimeout(3000) { task.cancelAndJoin() }
                        for (socket in listOf(head, dev)) {
                            socket.soTimeout = 2000
                            assertEquals(-1, withContext(Dispatchers.IO) { socket.getInputStream().read() })
                        }
                    }
                }
            } finally { bridge.close(); task.cancelAndJoin() }
        } }
    }

    @Test(timeout = 10_000) fun cancellationUnblocksBackpressuredWrite() = runBlocking {
        server().use { headServer ->
            val writeEntered = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val dev = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) { }
                override fun getInetAddress(): InetAddress = loopback
                override fun getLocalAddress(): InetAddress = loopback
                override fun getLocalPort(): Int = 40000
                override fun getInputStream(): InputStream = object : InputStream() {
                    override fun read(): Int {
                        check(closed.await(4, TimeUnit.SECONDS))
                        return -1
                    }
                }
                override fun getOutputStream(): OutputStream = object : OutputStream() {
                    override fun write(value: Int) {
                        writeEntered.countDown()
                        check(closed.await(4, TimeUnit.SECONDS))
                        throw SocketException("Closed while writing")
                    }
                }
                override fun close() { super.close(); closed.countDown() }
            }
            var sockets = 0
            val bridge = DevServerBridge { if (sockets++ == 0) Socket() else dev }
            val task = launch { bridge.run(endpoint(headServer), loopback, InetSocketAddress(loopback, 5277)) { } }
            try {
                withContext(Dispatchers.IO) { headServer.accept() }.use { head ->
                    head.getOutputStream().write(byteArrayOf(1, 2, 3))
                    assertTrue(withContext(Dispatchers.IO) { writeEntered.await(3, TimeUnit.SECONDS) })
                    withTimeout(3000) { task.cancelAndJoin() }
                    assertTrue(dev.isClosed)
                    head.soTimeout = 2000
                    assertEquals(-1, withContext(Dispatchers.IO) { head.getInputStream().read() })
                }
            } finally { bridge.close(); task.cancelAndJoin() }
        }
    }
}
