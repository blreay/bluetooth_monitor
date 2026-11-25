package com.blreay.bluetoothmonitor

import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class BluetoothCommandRunnerTest {
    private fun command(mode: String): List<String> {
        val classpath = listOf(CommandFixture::class.java, kotlin.Unit::class.java)
            .map { File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).path }
            .joinToString(File.pathSeparator)
        return listOf(File(System.getProperty("java.home"), "bin/java").path,
            "-cp", classpath, CommandFixture::class.java.name, mode)
    }

    @Test fun `destroy during spawn waits for publication and kills the spawned process`() {
        val spawned = CountDownLatch(1)
        val publish = CountDownLatch(1)
        val destroying = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val process = WaitingProcess()
        val runner = BluetoothCommandRunner {
            spawned.countDown()
            check(publish.await(2, TimeUnit.SECONDS))
            process
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val operation = workers.submit<BluetoothCommandResult> { runner.run(listOf("fixed-test-command")) }
            assertTrue(spawned.await(1, TimeUnit.SECONDS))
            val shutdown = workers.submit {
                destroying.countDown()
                runner.destroyChild()
                destroyed.countDown()
            }
            assertTrue(destroying.await(1, TimeUnit.SECONDS))
            assertFalse("Shutdown returned while the spawned child was still unpublished", destroyed.await(100, TimeUnit.MILLISECONDS))
            publish.countDown()
            shutdown.get(1, TimeUnit.SECONDS)
            assertFalse(process.isAlive)
            assertEquals(BluetoothCommandResult.Status.UNCERTAIN, operation.get(1, TimeUnit.SECONDS).status)
        } finally {
            publish.countDown()
            process.destroyForcibly()
            runner.destroyChild()
            workers.shutdownNow()
        }
    }

    @Test fun `terminal shutdown rejects subsequent commands before spawning`() {
        var spawned = false
        val runner = BluetoothCommandRunner { spawned = true; WaitingProcess() }
        runner.destroyChild()
        val result = runner.run(listOf("fixed-test-command"), 50)
        assertFalse(spawned)
        assertEquals(BluetoothCommandResult.Status.NOT_INVOKED, result.status)
    }

    @Test fun `destroy never waits behind the long command wait`() {
        val process = WaitingProcess()
        val runner = BluetoothCommandRunner { process }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val operation = worker.submit<BluetoothCommandResult> { runner.run(listOf("fixed-test-command")) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (!runner.hasLiveChild() && System.nanoTime() < deadline) Thread.yield()
            assertTrue(runner.hasLiveChild())
            val before = System.nanoTime()
            runner.destroyChild()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500)
            assertEquals(BluetoothCommandResult.Status.UNCERTAIN, operation.get(1, TimeUnit.SECONDS).status)
        } finally { process.destroyForcibly(); runner.destroyChild(); worker.shutdownNow() }
    }

    private class WaitingProcess : Process() {
        private val alive = AtomicBoolean(true)
        private val done = CountDownLatch(1)
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int { done.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)
        override fun exitValue(): Int { if (alive.get()) throw IllegalThreadStateException(); return 0 }
        override fun destroy() { alive.set(false); done.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive(): Boolean = alive.get()
    }

    @Test fun `exited wrapper with inherited open pipe keeps descendant authority unresolved and runner bounded`() {
        val release = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val process = object : Process() {
            override fun getInputStream() = object : java.io.InputStream() {
                override fun read(): Int { reading.countDown(); release.await(); return -1 }
                override fun close() { release.await() }
            }
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun waitFor() = 0
            override fun waitFor(timeout: Long, unit: TimeUnit) = true
            override fun exitValue() = 0
            override fun destroy() { }
            override fun isAlive() = false
        }
        var spawns = 0
        val runner = BluetoothCommandRunner { spawns++; process }
        try {
            val first = runner.run(listOf("fixed-test-command"), 100)
            assertEquals(BluetoothCommandResult.Status.UNCERTAIN, first.status)
            assertFalse("Direct wrapper exit cannot exclude a pipe-holding descendant", first.authorityEnded)
            assertTrue(reading.await(1, TimeUnit.SECONDS))
            repeat(5) {
                val refused = runner.run(listOf("fixed-test-command"), 100)
                assertEquals(BluetoothCommandResult.Status.NOT_INVOKED, refused.status)
                assertFalse("Terminal refusal cannot end inherited descendant authority", refused.authorityEnded)
            }
            assertEquals(1, spawns)
        } finally { release.countDown(); runner.destroyChild() }
    }

    @Test fun `killed wrapper with inherited pipe holder is not a command tree execution fence`() {
        val release = CountDownLatch(1)
        val reading = CountDownLatch(1)
        var waits = 0
        val process = object : Process() {
            override fun getInputStream() = object : java.io.InputStream() {
                override fun read(): Int { reading.countDown(); release.await(); return -1 }
                override fun close() { release.await() }
            }
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun waitFor() = 0
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { waits++; return waits > 1 }
            override fun exitValue() = 0
            override fun destroy() { }
            override fun destroyForcibly(): Process = this
            override fun isAlive() = false
        }
        val runner = BluetoothCommandRunner { process }
        try {
            val result = runner.run(listOf("fixed-test-command"), 100)
            assertTrue(reading.await(1, TimeUnit.SECONDS))
            assertEquals(2, waits)
            assertEquals(BluetoothCommandResult.Status.UNCERTAIN, result.status)
            assertFalse("Killed direct wrapper does not rule out the inherited pipe holder", result.authorityEnded)
            val refused = runner.run(listOf("fixed-test-command"), 100)
            assertEquals(BluetoothCommandResult.Status.NOT_INVOKED, refused.status)
            assertFalse(refused.authorityEnded)
        } finally { release.countDown(); runner.destroyChild() }
    }

    @Test fun `terminal not invoked does not establish quiescence of an older unkillable child`() {
        val release = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val process = object : Process() {
            override fun getInputStream() = object : java.io.InputStream() {
                override fun read(): Int { reading.countDown(); release.await(); return -1 }
                override fun close() { release.await() }
            }
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun waitFor(): Int = throw IllegalStateException("child did not exit")
            override fun waitFor(timeout: Long, unit: TimeUnit) = false
            override fun exitValue(): Int = throw IllegalThreadStateException()
            override fun destroy() { }
            override fun destroyForcibly(): Process = this
            override fun isAlive() = true
        }
        var spawns = 0
        val runner = BluetoothCommandRunner { spawns++; process }
        try {
            val timeout = runner.run(listOf("fixed-test-command"), 100)
            assertEquals(BluetoothCommandResult.Status.UNCERTAIN, timeout.status)
            assertFalse(timeout.authorityEnded)
            assertTrue(reading.await(1, TimeUnit.SECONDS))
            assertTrue(process.isAlive)
            val refused = runner.run(listOf("fixed-test-command"), 100)
            assertEquals(BluetoothCommandResult.Status.NOT_INVOKED, refused.status)
            assertFalse("Old child remains live; terminal refusal is not an execution fence", refused.authorityEnded)
            assertEquals(1, spawns)
        } finally { release.countDown(); runner.destroyChild() }
    }

    @Test fun `reader failure before EOF cannot establish ended authority even when wrapper exits`() {
        for (failure in listOf(java.io.IOException("inherited pipe failed"), java.io.InterruptedIOException("reader interrupted"))) {
            val attemptedRead = CountDownLatch(1)
            val process = object : Process() {
                override fun getInputStream() = object : java.io.InputStream() {
                    override fun read(): Int { attemptedRead.countDown(); throw failure }
                }
                override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun waitFor() = 0
                override fun waitFor(timeout: Long, unit: TimeUnit) = true
                override fun exitValue() = 0
                override fun destroy() { }
                override fun isAlive() = false
            }
            var spawns = 0
            val runner = BluetoothCommandRunner { spawns++; process }
            try {
                val result = runner.run(listOf("fixed-test-command"), 100)
                assertTrue(attemptedRead.await(1, TimeUnit.SECONDS))
                assertEquals(BluetoothCommandResult.Status.UNCERTAIN, result.status)
                assertFalse("Reader termination without EOF cannot exclude descendant execution", result.authorityEnded)
                val refused = runner.run(listOf("fixed-test-command"), 100)
                assertEquals(BluetoothCommandResult.Status.NOT_INVOKED, refused.status)
                assertFalse(refused.authorityEnded)
                assertEquals(1, spawns)
            } finally { runner.destroyChild() }
        }
    }

    @Test fun `output is drained beyond the saved cap so a large pipe cannot block completion`() {
        val result = BluetoothCommandRunner().run(command("large"), 5_000)
        assertEquals(BluetoothCommandResult.Status.SUBMITTED, result.status)
        assertEquals(BluetoothCommandResult.OUTPUT_LIMIT, result.output.length)
    }

    @Test fun `stderr and nonzero exit are visible errors`() {
        val result = BluetoothCommandRunner().run(command("refused"), 5_000)
        assertEquals(BluetoothCommandResult.Status.ERROR, result.status)
        assertTrue(result.output.contains("OEM refused"))
        assertTrue(result.output.contains("7"))
    }

    @Test fun `timeout kills the process and returns without an unbounded output join`() {
        val runner = BluetoothCommandRunner()
        val before = System.nanoTime()
        val result = runner.run(command("wait"), 500)
        val elapsedMs = (System.nanoTime() - before) / 1_000_000
        assertEquals(BluetoothCommandResult.Status.TIMEOUT, result.status)
        assertTrue("Took ${elapsedMs}ms", elapsedMs < 3_000)
        assertFalse(runner.hasLiveChild())
    }
}

/** Child JVM avoids shell/test-machine utilities; never part of the APK. */
object CommandFixture {
    @JvmStatic fun main(args: Array<String>) {
        when (args.single()) {
            "large" -> print("x".repeat(200_000))
            "refused" -> { System.err.println("OEM refused"); kotlin.system.exitProcess(7) }
            "wait" -> { println("request started"); Thread.sleep(10_000) }
        }
    }
}
