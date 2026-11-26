package com.blreay.bluetoothmonitor

import java.io.File
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal class LogTestClock(var wall: Long = 1_700_000_000_123L, var nanos: Long = 10_000L) : AuditClock {
    override fun wallMillis() = wall
    override fun monotonicNanos() = nanos
}
internal fun parsed(row: AuditRow): JSONObject {
    assertTrue("A complete JSON row must exist", row.line.startsWith("{"))
    assertTrue(row.line.endsWith("\n"))
    return JSONObject(row.line)
}

class AuditLogTest {
    private val clock = LogTestClock()
    private val session = "12345678-1234-1234-1234-123456789abc"
    private fun logger() = AuditLog(1234, clock, session, ZoneId.of("Asia/Shanghai"))

    @Test fun `receipt timestamp is captured before any later scheduling`() {
        val log = logger()
        val row = log.capture("scan.received", mapOf("runId" to "exact-run"))
        clock.wall += 60_000
        val json = parsed(row)
        assertEquals(1_700_000_000_123L, row.occurredAt)
        assertEquals(row.occurredAt, row.receivedAt)
        assertEquals("2023-11-15T06:13:20.123+08:00", json.getString("time"))
        assertEquals("receipt", json.getString("timeSource"))
    }
    @Test fun `explicit source timestamp is distinct from receipt and is never replaced`() {
        val row = logger().capture("native.entered", mapOf("commandId" to "exact-command"), 1_600_000_000_007L)
        val json = parsed(row)
        assertEquals(1_600_000_000_007L, json.getLong("occurredAtMs"))
        assertEquals(clock.wall, json.getLong("receivedAtMs"))
        assertEquals("source", json.getString("timeSource"))
        assertEquals("2020-09-13T20:26:40.007+08:00", json.getString("time"))
        assertEquals("2023-11-15T06:13:20.123+08:00", json.getString("receivedTime"))
    }
    @Test fun `same PID repeated events retain individual sequence and session identity`() {
        val log = logger()
        val rows = (1..4).map { log.capture("same", mapOf("pid" to "supplied-not-metadata")) }
        assertEquals(4, rows.map { it.eventId }.toSet().size)
        rows.forEachIndexed { index, row ->
            val json = parsed(row)
            assertEquals(1234, json.getInt("pid"))
            assertEquals(session, json.getString("session"))
            assertEquals(index + 1L, json.getLong("sequence"))
            assertEquals("$session:${index + 1}", json.getString("eventId"))
        }
    }
    @Test fun `new process session is UUID even when PID repeats`() {
        val first = AuditLog(1234, clock).capture("one")
        val second = AuditLog(1234, clock).capture("two")
        val a = parsed(first).getString("session")
        val b = parsed(second).getString("session")
        UUID.fromString(a); UUID.fromString(b)
        assertNotEquals(a, b)
        assertEquals(1L, parsed(second).getLong("sequence"))
    }
    @Test fun `monotonic process time is independent of wall clock reversal`() {
        val log = logger()
        clock.wall = 0
        clock.nanos += 55_555
        val json = parsed(log.capture("sample"))
        assertEquals(55_555L, json.getLong("processElapsedNanos"))
        assertFalse(json.has("durationMs"))
    }
    @Test fun `structured fields preserve exact IDs mode state reason and primitive values`() {
        val fields = linkedMapOf<String, Any?>("runId" to "run-EXACT", "operationId" to "op-EXACT",
            "commandId" to "cmd-EXACT", "mode" to SwitchMode.SHIZUKU_SHELL,
            "state" to "TURNING_OFF", "reason" to "已提交；尚未观察 OFF", "count" to 3, "ok" to false, "absent" to null)
        val json = parsed(logger().capture("command.returned", fields))
        assertEquals("command.returned", json.getString("kind"))
        val stored = json.getJSONObject("fields")
        assertEquals("run-EXACT", stored.getString("runId")); assertEquals("op-EXACT", stored.getString("operationId"))
        assertEquals("cmd-EXACT", stored.getString("commandId")); assertEquals("SHIZUKU_SHELL", stored.getString("mode"))
        assertEquals("TURNING_OFF", stored.getString("state")); assertEquals(fields["reason"], stored.getString("reason"))
        assertEquals(3, stored.getInt("count")); assertFalse(stored.getBoolean("ok")); assertTrue(stored.isNull("absent"))
    }
    @Test fun `quotes control characters Chinese and emoji occupy one escaped UTF8 line`() {
        val value = "中文😀\n\r\t\u0000\u0001\"\\end"
        val row = logger().capture("quoted\nkind", mapOf("reason" to value))
        val json = parsed(row)
        assertEquals(1, row.line.count { it == '\n' })
        assertFalse(row.line.dropLast(1).any { it.code < 32 })
        assertEquals(value, json.getJSONObject("fields").getString("reason"))
        assertEquals("quoted\nkind", json.getString("kind"))
        assertFalse(json.getBoolean("truncated"))
    }
    @Test fun `C1 controls and Unicode line separators are escaped rather than emitted literally`() {
        val value = "a" + listOf(0x7f, 0x85, 0x9b, 0x2028, 0x2029).map { it.toChar() }.joinToString("") + "z"
        val row = logger().capture("controls", mapOf("reason" to value))
        val json = parsed(row)
        assertFalse(row.line.dropLast(1).any { Character.isISOControl(it) || it.code == 0x2028 || it.code == 0x2029 })
        assertEquals(value, json.getJSONObject("fields").getString("reason"))
    }
    @Test fun `huge names fields keys and strings remain at most 8KiB including newline`() {
        val fields = linkedMapOf<String, Any?>()
        repeat(40) { fields["字段😀$it".repeat(100)] = "中文😀\u0001\"".repeat(10_000) }
        val row = logger().capture("event😀".repeat(20_000), fields)
        assertTrue(row.line.toByteArray(Charsets.UTF_8).size <= AuditLog.MAX_LINE_BYTES)
        val json = parsed(row)
        assertTrue(json.getBoolean("truncated"))
        assertTrue(json.getJSONObject("fields").length() <= 32)
        assertFalse(row.line.contains('�'))
        assertFalse(row.line.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8).contains('�'))
    }
    @Test fun `combined bounded Unicode fields are trimmed to the complete line byte budget`() {
        val fields = (0 until 32).associate { "field-$it" to "😀中文\u0001".repeat(1_000) }
        val row = logger().capture("combined", fields)
        val json = parsed(row)
        assertTrue(row.line.toByteArray(Charsets.UTF_8).size <= AuditLog.MAX_LINE_BYTES)
        assertTrue(json.getJSONObject("fields").length() in 1..31)
        assertTrue(json.getBoolean("truncated"))
        assertEquals(row.line, row.line.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }
    @Test fun `internal truncation marker never invokes arbitrary object equality`() {
        var invoked = false
        val objectValue = object {
            override fun equals(other: Any?): Boolean { invoked = true; return true }
            override fun hashCode() = 0
        }
        val row = logger().capture("marker", mapOf("_auditTruncated" to objectValue))
        assertFalse(invoked)
        assertTrue(parsed(row).getBoolean("truncated"))
    }
    @Test fun `unpaired surrogates are replaced safely without breaking JSON or UTF8`() {
        val row = logger().capture("bad\uD800", mapOf("reason" to "x\uDC00y"))
        val json = parsed(row)
        assertTrue(json.getBoolean("truncated"))
        assertFalse(row.line.any { Character.isSurrogate(it) })
        assertEquals(row.line, row.line.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }
    @Test fun `mutable source fields and arbitrary objects are never retained or stringified`() {
        var stringified = false
        val objectValue = object { override fun toString(): String { stringified = true; error("not allowed") } }
        val fields = mutableMapOf<String, Any?>("reason" to "before", "operation" to objectValue)
        val row = logger().capture("snapshot", fields)
        fields["reason"] = "after"
        val json = parsed(row)
        assertEquals("before", json.getJSONObject("fields").getString("reason"))
        assertFalse(stringified)
        assertEquals("[unsupported]", json.getJSONObject("fields").getString("operation"))
        assertTrue(json.getBoolean("truncated"))
    }
    @Test fun `sensitive field names and full extras are not logged`() {
        val row = logger().capture("safe", mapOf("token" to "sensitive-a", "privateKey" to "sensitive-b",
            "password" to "sensitive-c", "intentExtras" to "sensitive-d", "commandId" to "allowed"))
        val json = parsed(row)
        assertFalse(row.line.contains("sensitive-"))
        assertEquals("allowed", json.getJSONObject("fields").getString("commandId"))
        assertTrue(json.getBoolean("truncated"))
    }
    @Test fun `nonfinite floats are represented safely not invalid JSON`() {
        val json = parsed(logger().capture("number", mapOf("nan" to Double.NaN, "inf" to Float.POSITIVE_INFINITY)))
        assertTrue(json.getJSONObject("fields").isNull("nan"))
        assertTrue(json.getJSONObject("fields").isNull("inf"))
        assertTrue(json.getBoolean("truncated"))
    }
    @Test fun `oversized field names are omitted before full string normalization`() {
        val json = parsed(logger().capture("bounded-key", mapOf("a".repeat(2_000_000) to "must-not-log")))
        assertEquals(0, json.getJSONObject("fields").length())
        assertTrue(json.getBoolean("truncated"))
        assertFalse(json.toString().contains("must-not-log"))
    }
    @Test fun `safe convenience event bounds the map passed to an arbitrary sink`() {
        var supplied = 0
        val sink = AuditSink { _, fields, _ -> supplied = fields.size }
        sink.event("bounded", *(0 until 1_000).map { "field-$it" to it }.toTypedArray())
        assertTrue("Convenience admission must not copy an unbounded map", supplied <= 33)
    }
    @Test fun `convenience field overflow remains explicit even if many keys repeat`() {
        var captured: AuditRow? = null
        val log = logger()
        val sink = AuditSink { name, fields, at -> captured = log.capture(name, fields, at) }
        sink.event("duplicates", *Array(1_000) { "same" to it })
        assertTrue(parsed(requireNotNull(captured)).getBoolean("truncated"))
    }
    @Test fun `event omits a fresh multi megabyte key before an arbitrary sink receives its map`() {
        // Fresh String has no previously computed hash. Construction is outside event admission.
        val oversized = String(CharArray(2_000_000) { if (it % 2 == 0) 'a' else 'b' })
        var supplied: Map<String, Any?>? = null
        var suppliedAt: Long? = null
        val sink = AuditSink { _, fields, at -> supplied = fields; suppliedAt = at }
        sink.event("bounded-key", oversized to "must-not-reach-sink", "runId" to "exact-run", at = 123L)
        assertNotNull(supplied)
        val fields = requireNotNull(supplied)
        assertTrue("Oversized keys must never enter the sink map", fields.keys.all { it.length <= 96 })
        assertFalse(fields.values.contains("must-not-reach-sink"))
        assertEquals("exact-run", fields["runId"])
        assertEquals(true, fields["_auditTruncated"])
        assertEquals(123L, suppliedAt)
        AuditSink.NONE.event("bounded-none", oversized to "ignored")
    }
    @Test fun `oversized key at the 32 field edge leaves an explicit marker and 31 valid fields`() {
        val oversized = String(CharArray(2_000_000) { 'x' })
        val input = (0 until 31).map { "field-$it" to it } + (oversized to "omit")
        var supplied: Map<String, Any?>? = null
        var row: AuditRow? = null
        val log = logger()
        val sink = AuditSink { name, fields, at -> supplied = fields; row = log.capture(name, fields, at) }
        sink.event("edge", *input.toTypedArray())
        val fields = requireNotNull(supplied)
        assertTrue("All sink keys must already have bounded character length", fields.keys.all { it.length <= 96 })
        assertEquals(32, fields.size)
        assertEquals(true, fields["_auditTruncated"])
        assertEquals(30, fields["field-30"])
        val json = parsed(requireNotNull(row))
        assertTrue(json.getBoolean("truncated"))
        assertEquals(31, json.getJSONObject("fields").length())
    }
    @Test fun `omitted key remains explicit when exactly 32 input fields mostly duplicate`() {
        val oversized = String(CharArray(2_000_000) { 'z' })
        val input = listOf(oversized to "omit") + (0 until 31).map { "same" to it }
        var supplied: Map<String, Any?>? = null
        var row: AuditRow? = null
        val log = logger()
        val sink = AuditSink { name, fields, at -> supplied = fields; row = log.capture(name, fields, at) }
        sink.event("duplicate-edge", *input.toTypedArray())
        val fields = requireNotNull(supplied)
        assertTrue(fields.keys.all { it.length <= 96 })
        assertEquals(2, fields.size)
        assertEquals(30, fields["same"])
        assertEquals(true, fields["_auditTruncated"])
        val json = parsed(requireNotNull(row))
        assertTrue(json.getBoolean("truncated"))
        assertEquals(1, json.getJSONObject("fields").length())
    }
    @Test fun `event source checks key length before any key map insertion or hashing`() {
        val source = File("src/main/java/com/blreay/bluetoothmonitor/AuditLog.kt").readText()
        val event = source.substringAfter("fun event(").substringBefore("companion object")
        val check = event.indexOf("if (key.length > 96)")
        val insert = event.indexOf("boundedFields[key] = value")
        assertTrue("Admission needs a cheap character-length check", check >= 0)
        assertTrue("The check must precede the first field insertion", insert > check)
        val beforeCheck = event.substring(0, check)
        for (unbounded in listOf(".associate", ".toMap(", ".toMutableMap(", ".hashCode(")) {
            assertFalse("No full-key hashing before the bound: $unbounded", beforeCheck.contains(unbounded))
        }
    }
    @Test fun `event isolation catches failing sink and NONE is harmless`() {
        var invoked = false
        val sink = AuditSink { _, _, _ -> invoked = true; throw IllegalStateException("secret failure") }
        sink.event("test", "runId" to "id", at = 42L)
        assertTrue(invoked)
        AuditSink.NONE.event("none")
    }
}
