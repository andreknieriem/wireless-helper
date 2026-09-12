package com.andrerinas.wirelesshelper.utils

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Logging facade that always writes to logcat and, when capture is enabled, also to a file the
 * user can pull off the device.
 *
 * A connection fault on the phone side used to be invisible after the fact: everything went to
 * logcat, which needs a cable attached at the moment it happens. The head unit keeps its own file
 * capture, so a failed pairing left us with a detailed account of one end and nothing at all from
 * the other -- which is the end that decides whether the stream tunnel gets built.
 *
 * Unlike the head unit's AppLog this does not *replace* the logcat destination, it adds to it.
 * Existing `Log.x` call sites elsewhere in the app keep working unchanged, and a capture costs
 * nothing when it is switched off.
 */
object HelperLog {

    const val PREF_CAPTURE_ENABLED = "debug_log_capture"

    /** Prefix and pattern shared by the writer and the rotation/pull tooling. */
    private const val FILE_PREFIX = "WH_Log_"
    private const val MAX_FILES = 8

    @Volatile
    private var fileLogger: FileLogger? = null

    @Volatile
    private var currentFile: File? = null

    val isCapturing: Boolean get() = fileLogger != null
    val captureFile: File? get() = currentFile

    /**
     * Starts or stops file capture. Safe to call repeatedly; starting while already capturing keeps
     * the existing file rather than beginning a new one, so a settings screen that re-applies its
     * state does not shred the log into fragments.
     */
    fun init(context: Context, enabled: Boolean) {
        if (!enabled) {
            fileLogger?.close()
            fileLogger = null
            currentFile = null
            return
        }
        if (fileLogger != null) return

        val dir = context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir
        rotate(dir)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(dir, "$FILE_PREFIX$stamp.txt")
        try {
            fileLogger = FileLogger(file)
            currentFile = file
            i("HelperLog", "Capture started -> ${file.absolutePath}")
        } catch (e: IOException) {
            fileLogger = null
            currentFile = null
            Log.e("HelperLog", "Failed to open capture file ${file.absolutePath}", e)
        }
    }

    /** Keeps the newest [MAX_FILES] captures so a long-running helper cannot fill the card. */
    private fun rotate(dir: File) {
        val logs = dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) } ?: return
        logs.sortedByDescending { it.lastModified() }
            .drop(MAX_FILES - 1)
            .forEach { try { it.delete() } catch (_: Exception) {} }
    }

    fun d(tag: String, msg: String) = write(Log.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = write(Log.INFO, tag, msg, null)
    fun w(tag: String, msg: String) = write(Log.WARN, tag, msg, null)
    fun e(tag: String, msg: String, tr: Throwable? = null) = write(Log.ERROR, tag, msg, tr)

    private fun write(priority: Int, tag: String, msg: String, tr: Throwable?) {
        if (tr != null) Log.println(priority, tag, "$msg\n${Log.getStackTraceString(tr)}")
        else Log.println(priority, tag, msg)

        val logger = fileLogger ?: return
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> priority.toString()
        }
        // The trace goes to the file too. Recording the message and dropping the cause is what
        // makes a capture useless at exactly the moment it is needed.
        val body = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        logger.enqueue("[$tag:$level] [${Thread.currentThread().id}] $body")
    }

    /**
     * Writes from one background thread fed by a bounded queue.
     *
     * Callers are the Nearby callback thread, the proxy's two pump threads and the main thread;
     * none of them can afford to block on eMMC. When the queue is full the oldest line is dropped
     * rather than stalling the producer, and the count is reported so a capture never silently
     * under-reports.
     */
    private class FileLogger(file: File) : Closeable {
        private val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        private val queue = ArrayBlockingQueue<Any>(4096)
        private val dropped = AtomicInteger(0)

        @Volatile
        private var closed = false

        init {
            Thread({ drainLoop() }, "HelperLog-file-writer").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }

        fun enqueue(msg: String) {
            if (closed) return
            // Timestamped here, not on the writer thread: the queue can lag, and a log whose
            // timestamps say when a line was written rather than when it happened cannot be lined
            // up against the head unit's capture, which is the whole point of having one.
            val line = synchronized(dateFormat) { dateFormat.format(Date()) } + " $msg"
            while (!queue.offer(line)) {
                if (queue.poll() == null) return
                dropped.incrementAndGet()
            }
        }

        private fun drainLoop() {
            try {
                while (true) {
                    val item = queue.take()
                    if (item !is String) break
                    try {
                        writer.write(item)
                        writer.newLine()
                        val lost = dropped.getAndSet(0)
                        if (lost > 0) {
                            writer.write("--- HelperLog: $lost lines dropped, writer could not keep up ---")
                            writer.newLine()
                        }
                        // Flush when the burst is over rather than per line, so a kill loses at most
                        // what is still queued instead of a whole buffer.
                        if (queue.isEmpty()) writer.flush()
                    } catch (e: IOException) {
                        Log.e("HelperLog", "Failed to write capture file", e)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            try { writer.flush() } catch (_: IOException) {}
            try { writer.close() } catch (_: IOException) {}
        }

        /**
         * Does not wait for the writer to finish. This runs on the main thread whenever the setting
         * changes, and joining a thread mid-flush to slow storage there is an ANR waiting to happen.
         * The drain thread owns the writer and closes it once it sees the sentinel.
         */
        override fun close() {
            if (closed) return
            closed = true
            while (!queue.offer(POISON)) {
                if (queue.poll() == null) break
            }
        }

        private companion object {
            /** End marker; a distinct type so it can never collide with a real line. */
            val POISON = Any()
        }
    }
}
