package com.blreay.bluetoothmonitor

import java.io.IOException
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

internal class ManualLogScheduler : LogScheduler {
    val tasks = ArrayDeque<() -> Unit>()
    var scheduled = 0
    var reject = false
    override fun schedule(task: () -> Unit) {
        scheduled++
        if (reject) throw RejectedExecutionException("secret scheduler")
        tasks.addLast(task)
    }
    fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().invoke() }
}
internal open class TestLogStorage : LogStorage {
    val lines = mutableListOf<String>()
    val attempts = mutableListOf<List<String>>()
    var appendWorks = true
    var readWorks = true
    var clearWorks = true
    var reads = 0
    var clears = 0
    override fun append(lines: List<String>): LogWriteResult {
        attempts.add(lines.toList())
        if (!appendWorks) return LogWriteResult(false, "IOException", true)
        this.lines.addAll(lines)
        return LogWriteResult(true)
    }
    override fun readComplete(): LogReadResult {
        reads++
        if (!readWorks) throw IOException("secret read detail")
        return LogReadResult(lines.toList(), lines.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() })
    }
    override fun clear(): LogWriteResult {
        clears++
        if (!clearWorks) return LogWriteResult(false, "IOException")
        lines.clear()
        return LogWriteResult(true)
    }
}

class BoundedLogWriterTest {
    private class Fixture(capacity: Int = 64, callback: (LogStatus) -> Unit = {}) {
        val clock = LogTestClock()
        val scheduler = ManualLogScheduler()
        val external = TestLogStorage()
        val private = TestLogStorage()
        var granted = true
        var checks = 0
        var resolutions = 0
        val boundary = LogStorageBoundary({ checks++; granted }, { resolutions++; external }, { resolutions++; private })
        val writer = BoundedLogWriter(AuditLog(clock, ZoneId.of("UTC")), scheduler, boundary,
            capacity = capacity, batchSize = 4, onStatus = callback)
        fun event(i: Int) {
            clock.wall = 1_000L + i
            writer.event(CompactLogEntry.ScanEnded(i, false, false, "成功"))
        }
    }
    @Test fun `admission and refresh perform zero file resolution permission checks or reads`() {
        val f = Fixture(128)
        repeat(100) { f.event(it) }
        f.writer.refreshAccess()
        assertEquals(0, f.checks); assertEquals(0, f.resolutions)
        assertEquals(0, f.external.reads); assertEquals(0, f.private.reads)
        assertEquals(1, f.scheduler.tasks.size); assertEquals(1, f.scheduler.scheduled)
        assertEquals(100, f.writer.status().queuedEvents)
    }
    @Test fun `one fixed drain handles all batches without scheduling more work`() {
        val f = Fixture(128)
        repeat(100) { f.event(it) }
        f.scheduler.drain()
        assertEquals(100, f.external.lines.size)
        assertEquals(1, f.scheduler.scheduled)
        assertEquals(0, f.writer.status().queuedEvents)
        f.event(100)
        assertEquals(2, f.scheduler.scheduled)
        f.scheduler.drain()
        assertEquals(101, f.external.lines.size)
        assertTrue(f.external.attempts.all { it.size <= 4 })
    }
    @Test fun `overflow evicts oldest and never generates a fourth log type`() {
        val f = Fixture(capacity = 3)
        repeat(10) { f.event(it) }
        assertEquals(3, f.writer.status().queuedEvents); assertEquals(7L, f.writer.status().droppedEvents)
        f.scheduler.drain()
        assertEquals(listOf(7, 8, 9), f.external.lines.map { Regex("扫描：(\\d+)个").find(it)!!.groupValues[1].toInt() })
        assertEquals(3, f.external.lines.size)
        assertEquals(7L, f.writer.status().droppedEvents)
        assertFalse(f.external.lines.any { it.contains("log.dropped") })
    }
    @Test fun `finite 64 default queue survives a large producer burst with one scheduled task`() {
        val f = Fixture()
        repeat(10_000) { f.event(it) }
        assertEquals(64, f.writer.status().queuedEvents)
        assertEquals(9_936L, f.writer.status().droppedEvents)
        assertEquals(1, f.scheduler.scheduled); assertEquals(1, f.scheduler.tasks.size)
        f.scheduler.drain()
        assertEquals(64, f.external.lines.size)
        assertTrue(f.external.lines.all { it.toByteArray(Charsets.UTF_8).size <= 512 })
    }
    @Test fun `capture before scheduling retains complete timestamp and entry value`() {
        val f = Fixture()
        f.clock.wall = 7_000
        f.writer.record(CompactLogEntry.ScanEnded(5, false, true, "成功"))
        f.clock.wall = 99_000
        f.writer.event(CompactLogEntry.ProcessStarted)
        f.clock.wall = 123_000
        f.scheduler.drain()
        assertEquals(listOf("1970-01-01 00:00:07 扫描：5个设备，目标手环：有\n", "1970-01-01 00:01:39 进程启动\n"), f.external.lines)
    }
    @Test fun `events arriving during worker write use existing drain and do not wait on I O`() {
        val scheduler = ManualLogScheduler()
        val private = TestLogStorage()
        lateinit var writer: BoundedLogWriter
        var inside = false
        val external = object : TestLogStorage() {
            override fun append(lines: List<String>): LogWriteResult {
                if (!inside && lines.isNotEmpty()) {
                    inside = true
                    writer.event(CompactLogEntry.BluetoothEnded(true, SwitchMode.ORDINARY, "成功"))
                    assertEquals(1, scheduler.scheduled)
                    assertEquals(0, scheduler.tasks.size)
                }
                return super.append(lines)
            }
        }
        writer = BoundedLogWriter(AuditLog(LogTestClock()), scheduler, LogStorageBoundary({ true }, { external }, { private }))
        writer.event(CompactLogEntry.ProcessStarted)
        scheduler.drain()
        assertEquals(2, external.lines.size)
        assertTrue(external.lines[0].contains("进程启动")); assertTrue(external.lines[1].contains("打开蓝牙"))
        assertEquals(1, scheduler.scheduled)
    }
    @Test fun `scheduler rejection is isolated retains bounded rows and retries only on later admission`() {
        val f = Fixture(capacity = 2); f.scheduler.reject = true
        f.event(1)
        assertEquals("RejectedExecutionException", f.writer.status().schedulerFailureType)
        assertEquals(1, f.writer.status().queuedEvents)
        assertEquals(1, f.scheduler.scheduled); assertEquals(0, f.checks)
        f.scheduler.reject = false
        f.event(2)
        f.scheduler.drain()
        assertEquals(2, f.external.lines.size)
        assertNull(f.writer.status().schedulerFailureType)
    }
    @Test fun `status callback exceptions never escape or suppress subsequent logging`() {
        val f = Fixture(callback = { throw IllegalStateException("secret callback") })
        f.event(1); f.scheduler.drain()
        f.event(2); f.scheduler.drain()
        assertEquals(2, f.external.lines.size)
        assertEquals(ExternalLogState.READY, f.writer.status().storage.external)
    }
    @Test fun `both stores failing caches loss without spin or an invented summary line`() {
        val f = Fixture(capacity = 2)
        f.external.appendWorks = false; f.private.appendWorks = false
        repeat(4) { f.event(it) }
        f.scheduler.drain()
        assertEquals(4L, f.writer.status().droppedEvents)
        assertEquals(1, f.scheduler.scheduled); assertTrue(f.scheduler.tasks.isEmpty())
        f.external.appendWorks = true; f.private.appendWorks = true
        f.writer.refreshAccess(); f.scheduler.drain()
        assertTrue(f.external.lines.isEmpty())
        f.event(5); f.scheduler.drain()
        assertEquals(1, f.external.lines.size)
        assertEquals(4L, f.writer.status().droppedEvents)
    }
    @Test fun `clock failure is isolated by event and does not schedule or perform IO`() {
        val f = Fixture()
        val writer = BoundedLogWriter(AuditLog(AuditClock { error("clock") }), f.scheduler, f.boundary)
        writer.event(CompactLogEntry.ProcessStarted)
        assertEquals(0, writer.status().queuedEvents); assertEquals(0, f.scheduler.scheduled); assertEquals(0, f.checks)
    }
    @Test fun `uninitialized singleton status and event do not touch Android APIs`() {
        assertFalse(MibandLog.status().initialized)
        MibandLog.event(CompactLogEntry.ProcessStarted)
        MibandLog.refreshAccess()
        assertFalse(MibandLog.status().initialized)
    }
}
