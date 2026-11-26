package com.blreay.bluetoothmonitor

import java.io.IOException
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import org.json.JSONObject
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
    private class Fixture(capacity: Int = 512, callback: (LogStatus) -> Unit = {}) {
        val clock = LogTestClock()
        val scheduler = ManualLogScheduler()
        val external = TestLogStorage()
        val private = TestLogStorage()
        var granted = true
        var checks = 0
        var resolutions = 0
        val boundary = LogStorageBoundary({ checks++; granted }, { resolutions++; external }, { resolutions++; private })
        val writer = BoundedLogWriter(AuditLog(111, clock, "session-queue", ZoneId.of("UTC")), scheduler, boundary,
            capacity = capacity, batchSize = 4, onStatus = callback)
        fun event(i: Int, at: Long? = null) {
            clock.wall = 1_000L + i
            writer.event("event-$i", "commandId" to "cmd-$i", at = at)
        }
    }
    private fun json(lines: List<String>) = lines.map { assertTrue(it.startsWith("{")); JSONObject(it) }

    @Test fun `admission and refresh perform zero file resolution permission checks or reads`() {
        val f = Fixture()
        repeat(100) { f.event(it) }
        f.writer.refreshAccess()
        assertEquals(0, f.checks); assertEquals(0, f.resolutions)
        assertEquals(0, f.external.reads); assertEquals(0, f.private.reads)
        assertEquals(1, f.scheduler.tasks.size); assertEquals(1, f.scheduler.scheduled)
        assertEquals(100, f.writer.status().queuedEvents)
    }
    @Test fun `one fixed drain handles all batches without scheduling more work`() {
        val f = Fixture()
        repeat(100) { f.event(it) }
        f.scheduler.drain()
        assertEquals(100, f.external.lines.size)
        assertEquals(1, f.scheduler.scheduled)
        assertEquals(0, f.writer.status().queuedEvents)
        f.event(100)
        assertEquals(2, f.scheduler.scheduled)
        f.scheduler.drain()
        assertEquals(101, f.external.lines.size)
    }
    @Test fun `overflow evicts oldest pending for newest and records exact dropped time range`() {
        val f = Fixture(capacity = 3)
        repeat(10) { f.event(it) }
        val before = f.writer.status()
        assertEquals(3, before.queuedEvents); assertEquals(7L, before.droppedEvents)
        assertEquals(7L, before.unreportedDrops!!.count)
        assertEquals(1_000L, before.unreportedDrops!!.firstOccurredAt)
        assertEquals(1_006L, before.unreportedDrops!!.lastOccurredAt)
        f.scheduler.drain()
        val rows = json(f.external.lines)
        assertEquals(listOf("event-7", "event-8", "event-9"), rows.filter { it.getString("kind") != "log.dropped" }.map { it.getString("kind") })
        val drops = rows.single { it.getString("kind") == "log.dropped" }.getJSONObject("fields")
        assertEquals(7L, drops.getLong("count")); assertEquals(7L, drops.getLong("queueCount"))
        assertEquals(1_000L, drops.getLong("firstOccurredAtMs")); assertEquals(1_006L, drops.getLong("lastOccurredAtMs"))
        assertEquals(7L, f.writer.status().droppedEvents); assertNull(f.writer.status().unreportedDrops)
    }
    @Test fun `finite 512 event queue survives a large producer burst with one scheduled task`() {
        val f = Fixture()
        repeat(10_000) { f.event(it) }
        assertEquals(512, f.writer.status().queuedEvents)
        assertEquals(9_488L, f.writer.status().droppedEvents)
        assertEquals(1, f.scheduler.scheduled); assertEquals(1, f.scheduler.tasks.size)
        f.scheduler.drain()
        assertEquals(513, f.external.lines.size)
        assertTrue(f.external.lines.all { it.toByteArray(Charsets.UTF_8).size <= 8_192 })
    }
    @Test fun `occurrence time and immutable primitive values are captured before scheduling`() {
        val f = Fixture()
        val fields = mutableMapOf<String, Any?>("commandId" to "first")
        f.clock.wall = 7_000
        f.writer.record("received", fields, null)
        fields["commandId"] = "changed"
        f.clock.wall = 99_000
        f.writer.event("source", "operationId" to "source-op", at = 2_000)
        f.clock.wall = 123_000
        f.scheduler.drain()
        val rows = json(f.external.lines)
        assertEquals(7_000L, rows[0].getLong("occurredAtMs"))
        assertEquals("first", rows[0].getJSONObject("fields").getString("commandId"))
        assertEquals(2_000L, rows[1].getLong("occurredAtMs")); assertEquals(99_000L, rows[1].getLong("receivedAtMs"))
        assertEquals("source", rows[1].getString("timeSource"))
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
                    writer.event("while-writing", "id" to "two")
                    assertEquals(1, scheduler.scheduled)
                    assertEquals(0, scheduler.tasks.size)
                }
                return super.append(lines)
            }
        }
        writer = BoundedLogWriter(AuditLog(1, LogTestClock()), scheduler,
            LogStorageBoundary({ true }, { external }, { private }))
        writer.event("first")
        scheduler.drain()
        assertEquals(listOf("first", "while-writing"), json(external.lines).map { it.getString("kind") })
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
    @Test fun `both stores failing makes loss explicit and unsaved summary retries on refresh without spin`() {
        val f = Fixture(capacity = 2)
        f.external.appendWorks = false; f.private.appendWorks = false
        repeat(4) { f.event(it) }
        f.scheduler.drain()
        assertEquals(4L, f.writer.status().droppedEvents)
        assertEquals(4L, f.writer.status().unreportedDrops!!.count)
        assertEquals(2L, f.writer.status().unreportedDrops!!.queueCount)
        assertEquals(2L, f.writer.status().unreportedDrops!!.storageCount)
        assertEquals(1, f.scheduler.scheduled); assertTrue(f.scheduler.tasks.isEmpty())
        f.external.appendWorks = true; f.private.appendWorks = true
        f.writer.refreshAccess(); f.scheduler.drain()
        val rows = json(f.external.lines)
        assertEquals(1, rows.size); assertEquals("log.dropped", rows.single().getString("kind"))
        assertEquals(4L, rows.single().getJSONObject("fields").getLong("count"))
        assertNull(f.writer.status().unreportedDrops)
    }
    @Test fun `dropped source occurrence range is truthful when supplied times are out of order`() {
        val f = Fixture(capacity = 1)
        f.event(1, 9_000); f.event(2, 100); f.event(3, 500)
        assertEquals(100L, f.writer.status().unreportedDrops!!.firstOccurredAt)
        assertEquals(9_000L, f.writer.status().unreportedDrops!!.lastOccurredAt)
        assertEquals(1_001L, f.writer.status().unreportedDrops!!.firstReceivedAt)
        assertEquals(1_002L, f.writer.status().unreportedDrops!!.lastReceivedAt)
    }
    @Test fun `uninitialized singleton status and event do not touch Android APIs`() {
        assertFalse(MibandLog.status().initialized)
        MibandLog.event("not-initialized")
        MibandLog.refreshAccess()
        assertFalse(MibandLog.status().initialized)
    }
}
