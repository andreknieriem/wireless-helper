package com.andrerinas.wirelesshelper.devserver

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DevServerControllerTest {
    private class Shell : RootCommandRunner {
        val calls = mutableListOf<String>()
        var running = false
        var listening = false
        var stopWorks = true
        var startWorks = true
        var root = true
        var readable = true
        var anrHistory = false
        var shutdownExitCode = 0
        var beforeCommand: suspend (String) -> Unit = {}
        override suspend fun run(command: String): RootCommandResult {
            calls += command
            beforeCommand(command)
            return when (command) {
                "id -u" -> RootCommandResult(if (root) 0 else 1, if (root) "0\n" else "denied")
                DevServerController.STOP_COMMAND -> {
                    if (stopWorks) { running = false; listening = false }
                    RootCommandResult(shutdownExitCode, "Starting service: Intent { act=shutdown }")
                }
                "am stopservice ${DevServerController.COMPONENT}" -> {
                    // Device regression: Android removes the service, but AA keeps port 5277.
                    running = false
                    RootCommandResult(1, "Service stopped")
                }
                DevServerController.SOCKETS_COMMAND -> RootCommandResult(if (readable) 0 else 1,
                    if (!readable) "Permission denied" else "sl local_address rem_address\n" +
                        if (listening) "0: 00000000:149D 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123 0 1" else "")
                DevServerController.STATE_COMMAND -> RootCommandResult(0,
                    "ACTIVITY MANAGER SERVICES (dumpsys activity services)\n" +
                        (if (anrHistory) "  Last ANR service:\n    ServiceRecord{old u0 ${DevServerController.COMPONENT}}\n" else "") +
                        if (running) "  User 0 active services:\n  * ServiceRecord{123 u0 ${DevServerController.COMPONENT}}\n    startRequested=true" else "(nothing)")
                else -> {
                    check(command == DevServerController.startCommand(36) || command == DevServerController.startCommand(25))
                    running = true
                    listening = startWorks
                    RootCommandResult(0, "Starting service")
                }
            }
        }
    }
    private suspend fun expectError(error: DevServerError, block: suspend () -> Unit) {
        try { block(); fail("Expected $error") }
        catch (e: DevServerException) { assertEquals(error, e.error) }
    }

    @Test fun usesConfirmedCommandAndLegacyFallback() {
        assertEquals("am start-foreground-service -W ${DevServerController.COMPONENT}", DevServerController.startCommand(26))
        assertEquals("am startservice ${DevServerController.COMPONENT}", DevServerController.startCommand(25))
        assertEquals("am startservice -a shutdown -n ${DevServerController.COMPONENT}", DevServerController.STOP_COMMAND)
    }

    @Test fun adoptsManuallyStartedServerAndRepeatedStartIsIdempotent() = runTest {
        val shell = Shell().apply { running = true; listening = true }
        val controller = DevServerController(shell, 10123, 36)
        repeat(3) { assertTrue(controller.start().ready) }
        assertTrue(shell.calls.all { it == DevServerController.SOCKETS_COMMAND || it == DevServerController.STATE_COMMAND })
        controller.stop()
        assertTrue(controller.readState().stopped)
    }

    @Test fun ignoresStopExitCodeWhenPassiveStateConfirmsShutdown() = runTest {
        val shell = Shell().apply { running = true; listening = true; shutdownExitCode = 1 }
        DevServerController(shell, 10123, 36).stop()
        assertFalse(shell.running)
        assertEquals(DevServerController.STOP_COMMAND, shell.calls.first())
    }

    @Test fun startExitZeroIsNotReadiness() = runTest {
        val shell = Shell().apply { startWorks = false }
        expectError(DevServerError.START_FAILED) { DevServerController(shell, 10123, 36).start() }
    }

    @Test fun stopIsAnErrorWhileListenerRemains() = runTest {
        val shell = Shell().apply { running = true; listening = true; stopWorks = false }
        expectError(DevServerError.STOP_FAILED) { DevServerController(shell, 10123, 36).stop() }
    }

    @Test fun rootAndPassiveStateErrorsAreDistinct() = runTest {
        val shell = Shell().apply { root = false }
        expectError(DevServerError.ROOT_DENIED) { DevServerController.checkRoot(shell) }
        shell.readable = false
        expectError(DevServerError.STATE_UNAVAILABLE) { DevServerController(shell, 10123, 36).readState() }
    }

    @Test fun successfulStopWithUnreadableStateIsNotReportedAsStopFailure() = runTest {
        val shell = Shell().apply { running = true; listening = true; readable = false }
        expectError(DevServerError.STATE_UNAVAILABLE) { DevServerController(shell, 10123, 36).stop() }
        assertFalse(shell.running)
        assertFalse(shell.listening)
        assertEquals(DevServerError.STATE_UNAVAILABLE,
            devCleanupError(DevServerException(DevServerError.STATE_UNAVAILABLE)))
    }

    @Test fun cleanupPreservesKnownErrorsAndReportsUnknownVerificationFailures() {
        assertEquals(DevServerError.ROOT_DENIED, devCleanupError(DevServerException(DevServerError.ROOT_DENIED)))
        assertEquals(DevServerError.STOP_FAILED, devCleanupError(DevServerException(DevServerError.STOP_FAILED)))
        assertEquals(DevServerError.STATE_UNAVAILABLE, devCleanupError(java.io.IOException("root output unavailable")))
    }

    @Test fun hungStartIsBoundedAndCleanupCanFollow() = runTest {
        val shell = Shell().apply {
            beforeCommand = { if (it == DevServerController.startCommand(36)) awaitCancellation() }
        }
        val controller = DevServerController(shell, 10123, 36)
        expectError(DevServerError.START_FAILED) { controller.start() }
        assertEquals(12_000L, currentTime)
        controller.stop()
    }

    @Test fun stopWaitsForCancellationOfStartAndNothingRestartsAfterIt() = runTest {
        val started = CompletableDeferred<Unit>()
        val shell = Shell().apply {
            beforeCommand = { if (it == DevServerController.startCommand(36)) { started.complete(Unit); awaitCancellation() } }
        }
        val controller = DevServerController(shell, 10123, 36)
        val start = launch { controller.start() }
        started.await()
        val stop = launch { controller.stop() }
        runCurrent()
        assertFalse(shell.calls.contains(DevServerController.STOP_COMMAND))
        start.cancelAndJoin()
        stop.join()
        advanceUntilIdle()
        assertEquals(1, shell.calls.count { it == DevServerController.startCommand(36) })
        assertTrue(controller.readState().stopped)
    }

    @Test fun restartAlwaysStopsBeforeStarting() = runTest {
        val shell = Shell().apply { running = true; listening = true }
        assertTrue(DevServerController(shell, 10123, 36).restart().ready)
        assertTrue(shell.calls.indexOf(DevServerController.STOP_COMMAND) <
            shell.calls.indexOf(DevServerController.startCommand(36)))
    }

    @Test fun startWithServiceRecordButNoListenerDoesNotAttemptStop() = runTest {
        val shell = Shell().apply { running = true; stopWorks = false }
        assertTrue(DevServerController(shell, 10123, 36).start().ready)
        assertFalse(shell.calls.contains(DevServerController.STOP_COMMAND))
    }

    @Test fun repeatedStartStopWorksWithRetainedAnrHistory() = runTest {
        val shell = Shell().apply { anrHistory = true }
        val controller = DevServerController(shell, 10123, 36)
        repeat(10) {
            assertTrue(controller.start().ready)
            controller.stop()
            assertTrue(controller.readState().stopped)
        }
        assertFalse(shell.calls.any { it.startsWith("am stopservice") })
    }

    @Test fun notificationShutdownReleasesListenerInsteadOfOnlyRemovingService() = runTest {
        val shell = Shell().apply { running = true; listening = true }
        val controller = DevServerController(shell, 10123, 36)
        shell.run("am stopservice ${DevServerController.COMPONENT}")
        assertFalse(controller.readState().stopped)
        assertFalse(shell.running)
        assertTrue(shell.listening)

        controller.stop()
        assertTrue(controller.readState().stopped)
        assertFalse(shell.listening)
        assertTrue(controller.start().ready)
    }
}
