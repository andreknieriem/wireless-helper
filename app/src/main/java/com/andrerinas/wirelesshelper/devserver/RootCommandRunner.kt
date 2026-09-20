package com.andrerinas.wirelesshelper.devserver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.thread

data class RootCommandResult(val exitCode: Int, val output: String)

fun interface RootCommandRunner {
    suspend fun run(command: String): RootCommandResult
}

class SuCommandRunner(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = COMMAND_TIMEOUT_MS,
    private val spawn: (String) -> Process = { ProcessBuilder("su", "-c", it).redirectErrorStream(true).start() },
) : RootCommandRunner {
    companion object { const val COMMAND_TIMEOUT_MS = 10_000L }

    override suspend fun run(command: String): RootCommandResult = withContext(dispatcher) {
        val process = spawn(command)
        val output = StringBuffer()
        val reader = thread(isDaemon = true, name = "dev-server-root-output") {
            try {
                process.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = 256 * 1024 - output.length
                            if (remaining > 0) output.append(buffer, 0, minOf(count, remaining))
                        }
                    }
                }
            } catch (_: Exception) { /* The process streams close on cancellation. */ }
        }
        try {
            withTimeout(timeoutMs) {
                var code: Int? = null
                while (code == null) {
                    code = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                    if (code == null) delay(50)
                }
                reader.join(1000)
                RootCommandResult(code, output.toString())
            }
        } finally {
            process.destroy()
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}
