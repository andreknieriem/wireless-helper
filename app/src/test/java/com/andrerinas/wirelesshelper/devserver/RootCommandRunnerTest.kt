package com.andrerinas.wirelesshelper.devserver

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class RootCommandRunnerTest {
    private class Child(private val exit: Int? = null) : Process() {
        var destroyed = false
        override fun getInputStream() = ByteArrayInputStream("output\n".toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int = exitValue()
        override fun exitValue(): Int = exit ?: throw IllegalThreadStateException()
        override fun destroy() { destroyed = true }
    }

    @Test fun rootCommandHasTenSecondTimeoutAndDestroysHungChild() = runTest {
        val child = Child()
        val runner = SuCommandRunner(StandardTestDispatcher(testScheduler), spawn = { child })
        try { runner.run("id -u"); fail("Expected timeout") }
        catch (_: TimeoutCancellationException) { }
        assertEquals(10_000L, currentTime)
        assertTrue(child.destroyed)
    }

    @Test fun cancellationDestroysChildBeforeReturning() = runTest {
        val child = Child()
        val runner = SuCommandRunner(StandardTestDispatcher(testScheduler), spawn = { child })
        val job = launch { runner.run("am startservice component") }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(child.destroyed)
    }

    @Test fun outputAndNonzeroExitArePreservedForControllerVerification() = runTest {
        val child = Child(1)
        var received = ""
        val result = SuCommandRunner(StandardTestDispatcher(testScheduler), spawn = { received = it; child })
            .run("am startservice -a shutdown -n component")
        assertEquals("am startservice -a shutdown -n component", received)
        assertEquals(1, result.exitCode)
        assertEquals("output\n", result.output)
        assertTrue(child.destroyed)
    }
}
