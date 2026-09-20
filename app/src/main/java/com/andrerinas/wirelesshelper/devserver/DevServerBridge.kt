package com.andrerinas.wirelesshelper.devserver

import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

/** One session, two outgoing sockets. No AA framing or extra bytes are injected. */
class DevServerBridge(private val socketFactory: () -> Socket = { Socket() }) {
    enum class Stage { HEADUNIT, DEV_SERVER, TRANSFER }
    class Failure(val stage: Stage, cause: Exception) : IOException("Bridge failed at $stage", cause)

    private val lock = Any()
    private val sockets = mutableListOf<Socket>()
    private var closed = false
    @Volatile var needsServerRecovery = false
        private set
    @Volatile var connection: DevServerConnection? = null
        private set

    suspend fun run(
        headunit: InetSocketAddress,
        source: InetAddress,
        devServer: InetSocketAddress,
        onConnected: suspend () -> Unit,
    ) = coroutineScope {
        val completion = CompletableDeferred<Unit>()
        val worker = launch(Dispatchers.IO) {
            var stage = Stage.HEADUNIT
            try {
                val head = newSocket()
                head.bind(InetSocketAddress(source, 0))
                head.connect(headunit, 5000)
                ensureActive()
                stage = Stage.DEV_SERVER
                val dev = newSocket()
                needsServerRecovery = true
                dev.connect(devServer, 2000)
                connection = DevServerConnection(dev.inetAddress.hostAddress!!, dev.localAddress.hostAddress!!, dev.localPort)
                ensureActive()
                stage = Stage.TRANSFER
                onConnected()
                coroutineScope {
                    launch { pump(head, dev) }
                    launch { pump(dev, head) }
                }
                completion.complete(Unit)
            } catch (e: CancellationException) {
                completion.cancel(e)
            } catch (e: Exception) {
                completion.completeExceptionally(Failure(stage, e))
            } finally {
                close()
            }
        }
        try {
            completion.await()
        } finally {
            // Cancel alone cannot interrupt Socket.connect/read/write. Close before joining.
            close()
            withContext(NonCancellable) { worker.cancelAndJoin() }
        }
    }

    private fun newSocket(): Socket = synchronized(lock) {
        if (closed) throw SocketException("Bridge closed")
        val socket = socketFactory()
        sockets.add(socket)
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.sendBufferSize = 64 * 1024
        socket.receiveBufferSize = 64 * 1024
        socket
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return
                output.write(buffer, 0, count)
            }
        } finally {
            close()
        }
    }

    fun close() {
        val owned = synchronized(lock) {
            closed = true
            sockets.toList()
        }
        owned.forEach { runCatching { it.close() } }
    }
}
