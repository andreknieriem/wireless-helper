package com.andrerinas.wirelesshelper.connection

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class AapProxy(
    private val remoteIp: String, 
    private val remotePort: Int = 5288, 
    private val network: android.net.Network? = null,
    private val preConnectedSocket: Socket? = null,
    private val listener: Listener? = null
) {
    
    interface Listener {
        fun onConnected()
        fun onDisconnected()
    }

    private val TAG = "HUREV_PROXY"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeBridges = AtomicInteger(0)
    
    // Track active connection to tablet to send ByeBye
    @Volatile private var activeTabletSocket: Socket? = null

    fun start(): Int {
        serverSocket = ServerSocket(0)
        val localPort = serverSocket!!.localPort
        isRunning = true
        
        Log.i(TAG, "Proxy started on localhost:$localPort, forwarding to " + if (preConnectedSocket != null) "pre-connected socket" else "$remoteIp:$remotePort")
        
        scope.launch {
            try {
                while (isRunning) {
                    val aaSocket = serverSocket?.accept() ?: break
                    Log.i(TAG, "Android Auto connected to proxy")
                    launchBridge(aaSocket)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Proxy server stopped: ${e.message}")
            }
        }
        
        return localPort
    }

    private fun launchBridge(aaSocket: Socket) {
        scope.launch {
            var tabletSocket: Socket? = null
            try {
                activeBridges.incrementAndGet()
                listener?.onConnected()

                if (preConnectedSocket != null) {
                    tabletSocket = preConnectedSocket
                    Log.i(TAG, "Bridge established: AA <-> Tablet (Pre-connected socket)")
                } else {
                    tabletSocket = Socket()
                    // network?.bindSocket(tabletSocket) // Removed to match 1.5.1 stability
                    tabletSocket.connect(InetSocketAddress(remoteIp, remotePort), 5000)
                    Log.i(TAG, "Bridge established: AA <-> Tablet ($remoteIp)")
                }
                
                activeTabletSocket = tabletSocket

                val aaIn = aaSocket.getInputStream()
                val aaOut = aaSocket.getOutputStream()
                val tabletIn = tabletSocket.getInputStream()
                val tabletOut = tabletSocket.getOutputStream()

                // Each direction force-closes BOTH sockets as soon as it ends (source hung up
                // or errored) — this is what unblocks whichever pump is still stuck in a
                // blocking read() on its own end, so joinAll() below can't deadlock waiting on
                // a peer (e.g. Google's AA app) that never proactively closes its socket on
                // its own after the tablet side hangs up.
                val job1 = launch {
                    pump(aaIn, tabletOut, "AA -> Tablet")
                    closeBridgeSockets(aaSocket, tabletSocket)
                }
                val job2 = launch {
                    pump(tabletIn, aaOut, "Tablet -> AA")
                    closeBridgeSockets(aaSocket, tabletSocket)
                }

                joinAll(job1, job2)
            } catch (e: Exception) {
                Log.e(TAG, "Bridge error: ${e.message}")
            } finally {
                Log.i(TAG, "Bridge closed")
                activeTabletSocket = null
                closeBridgeSockets(aaSocket, tabletSocket)

                if (activeBridges.decrementAndGet() <= 0) {
                    listener?.onDisconnected()
                }
            }
        }
    }

    /** Force-closes both ends of the bridge. Safe to call repeatedly/concurrently -
     *  Socket.close() is internally guarded (`if (isClosed()) return`), so repeat calls are
     *  harmless no-ops. */
    private fun closeBridgeSockets(aaSocket: Socket, tabletSocket: Socket?) {
        try { aaSocket.close() } catch (e: Exception) {}
        try { tabletSocket?.close() } catch (e: Exception) {}
    }

    private suspend fun pump(input: InputStream, output: OutputStream, name: String) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(16384)
        try {
            while (isRunning) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (e: Exception) {
            Log.d(TAG, "$name error: ${e.message}")
        }
    }

    /**
     * Sends a 'Magic Garbage' signal (16 bytes of 0xFF) to the tablet.
     * This will cause a decryption error on the tablet, which we can catch
     * and interpret as a clean disconnect.
     */
    private fun sendDisconnectSignal() {
        val socket = activeTabletSocket ?: return
        Thread {
            try {
                Log.i(TAG, "Sending Magic Garbage disconnect signal...")
                val signal = ByteArray(16) { 0xFF.toByte() }
                socket.getOutputStream().write(signal)
                socket.getOutputStream().flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send disconnect signal: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        if (isRunning) {
            sendDisconnectSignal()
            // Wait slightly for the signal to leave the buffer
            try { Thread.sleep(150) } catch (e: Exception) {}
        }
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        scope.cancel()
    }
}
