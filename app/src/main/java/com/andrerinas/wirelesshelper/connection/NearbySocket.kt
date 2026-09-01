package com.andrerinas.wirelesshelper.connection

import com.andrerinas.wirelesshelper.utils.HelperLog
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A [Socket] whose two halves are Nearby stream payloads that arrive independently.
 *
 * The outgoing half is ours and is attached as soon as we send our payload. The incoming half only
 * exists once the head unit sends its own payload, so a read issued before that has to wait. The
 * wait is bounded: an unbounded one parked Android Auto's reader thread for as long as the process
 * lived when the far side never completed the tunnel, with nothing in the log to say why.
 */
class NearbySocket : Socket() {
    private var internalInputStream: InputStream? = null
    private var internalOutputStream: OutputStream? = null

    private val inputLatch = CountDownLatch(1)
    private val outputLatch = CountDownLatch(1)

    private companion object {
        const val TAG = "HUREV_NEARBY"

        /**
         * Comfortably longer than a working handshake and shorter than the ~16 s Nearby itself takes
         * to fail the payload, so the log records why the link died rather than only that it did.
         */
        const val STREAM_WAIT_MS = 12_000L
    }

    var inputStreamWrapper: InputStream?
        get() = internalInputStream
        set(value) {
            internalInputStream = value
            if (value != null) {
                HelperLog.i(TAG, "NearbySocket: InputStream is now AVAILABLE. Releasing latch.")
                inputLatch.countDown()
            }
        }

    var outputStreamWrapper: OutputStream?
        get() = internalOutputStream
        set(value) {
            internalOutputStream = value
            if (value != null) outputLatch.countDown()
        }

    override fun isConnected() = true

    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()

    override fun getInputStream(): InputStream {
        HelperLog.d(TAG, "NearbySocket: getInputStream() called")
        return object : InputStream() {
            private fun waitForStream(): InputStream {
                if (inputLatch.count > 0L) {
                    HelperLog.i(TAG, "NearbySocket: Blocking read until InputStream is AVAILABLE via Nearby Payload (up to ${STREAM_WAIT_MS}ms)...")
                }
                if (!inputLatch.await(STREAM_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    HelperLog.e(
                        TAG,
                        "NearbySocket: head unit never sent its half of the stream tunnel within ${STREAM_WAIT_MS}ms. " +
                                "The Nearby link is up but no inbound payload arrived, so nothing can be read."
                    )
                    throw IOException("Nearby stream tunnel incomplete: no inbound payload from head unit")
                }
                return internalInputStream!!
            }

            override fun read(): Int = waitForStream().read()
            override fun read(b: ByteArray): Int = read(b, 0, b.size)
            override fun read(b: ByteArray, off: Int, len: Int): Int = waitForStream().read(b, off, len)
            override fun available(): Int = if (inputLatch.count == 0L) internalInputStream!!.available() else 0
            override fun close() = if (inputLatch.count == 0L) internalInputStream!!.close() else Unit
        }
    }

    override fun getOutputStream(): OutputStream {
        HelperLog.d(TAG, "NearbySocket: getOutputStream() called")
        return object : OutputStream() {
            private fun waitForStream(): OutputStream {
                if (outputLatch.count > 0L) {
                    HelperLog.d(TAG, "NearbySocket: Waiting for outputLatch...")
                }
                // Ours to attach, so this should never actually wait -- but parking the writer
                // thread forever costs the teardown path its only chance to run.
                if (!outputLatch.await(STREAM_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    throw IOException("Nearby stream tunnel incomplete: outbound payload never registered")
                }
                return internalOutputStream!!
            }

            override fun write(b: Int) {
                waitForStream().write(b)
            }

            override fun write(b: ByteArray) = write(b, 0, b.size)
            override fun write(b: ByteArray, off: Int, len: Int) {
                waitForStream().write(b, off, len)
                waitForStream().flush()
            }

            override fun flush() {
                if (outputLatch.count == 0L) internalOutputStream!!.flush()
            }

            override fun close() = if (outputLatch.count == 0L) internalOutputStream!!.close() else Unit
        }
    }
}
