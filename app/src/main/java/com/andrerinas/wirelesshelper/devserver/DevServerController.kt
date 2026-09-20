package com.andrerinas.wirelesshelper.devserver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteOrder

enum class DevServerError { ROOT_DENIED, AA_MISSING, COMPONENT_MISSING, WIFI_UNAVAILABLE, STATE_UNAVAILABLE, START_FAILED, STOP_FAILED, LOCAL_ENDPOINT_UNAVAILABLE }
class DevServerException(val error: DevServerError) : Exception(error.name)

fun devCleanupError(cause: Exception): DevServerError = when (cause) {
    is DevServerException -> cause.error
    else -> DevServerError.STATE_UNAVAILABLE
}

class DevServerController(
    private val runner: RootCommandRunner,
    private val aaUid: Int,
    private val sdkInt: Int,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val diagnostic: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private var lastStateSummary: String? = null

    companion object {
        const val PACKAGE = "com.google.android.projection.gearhead"
        const val SERVICE = "$PACKAGE.companion.DeveloperHeadUnitNetworkService"
        const val COMPONENT = "$PACKAGE/$SERVICE"
        // Matches AA's notification Stop PendingIntent (type=startService, action=shutdown).
        // stopservice removes the Android service but can leave its TCP listener alive.
        const val STOP_COMMAND = "am startservice -a shutdown -n $COMPONENT"
        const val SOCKETS_COMMAND = "cat /proc/net/tcp /proc/net/tcp6"
        const val STATE_COMMAND = "dumpsys activity services $COMPONENT"

        fun startCommand(sdkInt: Int): String = if (sdkInt >= 26)
            "am start-foreground-service -W $COMPONENT" else "am startservice $COMPONENT"

        suspend fun checkRoot(runner: RootCommandRunner) {
            val result = try {
                runner.run("id -u")
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { throw DevServerException(DevServerError.ROOT_DENIED) }
            if (result.exitCode != 0 || result.output.trim() != "0")
                throw DevServerException(DevServerError.ROOT_DENIED)
        }
    }

    suspend fun readState(): DevServerState = mutex.withLock { readUnlocked() }

    suspend fun start(): DevServerState = mutex.withLock {
        val current = readUnlocked()
        if (current.ready || current.hasConnection) return@withLock current
        // A service record without a listener is not a reason to stop before Start.
        // Android can retain a bound/stopping record; deliver the explicit start instead.
        startUnlocked()
    }

    suspend fun stop() = mutex.withLock { stopUnlocked() }

    suspend fun restart(): DevServerState = mutex.withLock {
        stopUnlocked()
        startUnlocked()
    }

    private suspend fun startUnlocked(): DevServerState {
        return bounded(DevServerError.START_FAILED) {
            val command = runner.run(startCommand(sdkInt))
            diagnostic("Start command: exit=${command.exitCode}, ${command.output.trim().take(400)}")
            repeat(40) {
                val state = readUnlocked()
                if (state.ready || state.hasConnection) return@bounded state
                pause(250)
            }
            throw DevServerException(DevServerError.START_FAILED)
        }
    }

    private suspend fun stopUnlocked() = bounded(DevServerError.STOP_FAILED) {
        // Ask AA to release its server resources, then confirm shutdown passively.
        val command = runner.run(STOP_COMMAND)
        diagnostic("Stop command: exit=${command.exitCode}, ${command.output.trim().take(400)}")
        repeat(40) {
            if (readUnlocked().stopped) return@bounded
            pause(250)
        }
        throw DevServerException(DevServerError.STOP_FAILED)
    }

    private suspend fun <T> bounded(error: DevServerError, block: suspend () -> T): T {
        // Each shell invocation is limited to 10 seconds; readiness polling is bounded too.
        return try { withTimeout(12_000) { block() } }
        catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw DevServerException(error)
        }
    }

    private suspend fun readUnlocked(): DevServerState {
        val sockets = runner.run(SOCKETS_COMMAND)
        val service = runner.run(STATE_COMMAND)
        if (sockets.exitCode != 0 || service.exitCode != 0 || !sockets.output.contains("local_address") ||
            !service.output.contains("ACTIVITY MANAGER SERVICES")) {
            diagnostic("State read failed: socketsExit=${sockets.exitCode}, " +
                "socketsHeader=${sockets.output.contains("local_address")}, serviceExit=${service.exitCode}, " +
                "serviceHeader=${service.output.contains("ACTIVITY MANAGER SERVICES")}")
            throw DevServerException(DevServerError.STATE_UNAVAILABLE)
        }
        val state = DevServerState(
            serviceRunning = DevServerState.hasStartedService(service.output),
            sockets = DevServerState.parseSockets(sockets.output, aaUid, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN),
        )
        val summary = "Dev server: started=${state.serviceRunning}, listeners=${state.sockets.count { it.listening }}, " +
            "tcp=${state.hasConnection}, socketStates=${state.sockets.map { it.state }.distinct()}"
        if (summary != lastStateSummary) { diagnostic(summary); lastStateSummary = summary }
        return state
    }
}
