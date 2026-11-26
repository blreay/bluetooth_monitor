package com.blreay.bluetoothmonitor

import java.io.File
import java.io.IOException
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LogStorageBoundaryTest {
    private class Fixture {
        val clock = LogTestClock()
        val audit = AuditLog(7, clock, "session-storage", ZoneId.of("UTC"))
        val root = TestLogStorage()
        val private = TestLogStorage()
        var granted = false
        var checks = 0
        var rootResolutions = 0
        var privateResolutions = 0
        val boundary = LogStorageBoundary({ checks++; granted }, { rootResolutions++; root }, { privateResolutions++; private })
        fun row(name: String, at: Long? = null) = audit.capture(name, mapOf("runId" to "exact-run"), at)
    }
    @Test fun `construction and cached status perform no filesystem or permission work`() {
        val f = Fixture()
        repeat(10) { f.boundary.status() }
        assertEquals(0, f.checks); assertEquals(0, f.rootResolutions); assertEquals(0, f.privateResolutions)
        assertEquals(ExternalLogState.UNCHECKED, f.boundary.status().external)
    }
    @Test fun `missing special access retains private rows and never resolves root`() {
        val f = Fixture(); val row = f.row("no-access")
        assertTrue(f.boundary.persist(listOf(row.line)))
        assertEquals(listOf(row.line), f.private.lines)
        assertEquals(0, f.rootResolutions); assertTrue(f.root.attempts.isEmpty())
        assertEquals(ExternalLogState.PERMISSION_REQUIRED, f.boundary.status().external)
        assertEquals(PrivateLogState.PENDING, f.boundary.status().private)
        assertEquals(row.line.toByteArray(Charsets.UTF_8).size.toLong(), f.boundary.status().pendingBytes)
    }
    @Test fun `grant refresh exports pending in order with original IDs source and receipt times`() {
        val f = Fixture()
        val original = listOf(f.row("first", 123L), f.row("second", 456L))
        assertTrue(f.boundary.persist(original.map { it.line }))
        f.clock.wall += 60_000; f.granted = true
        assertTrue(f.boundary.persist(emptyList()))
        assertEquals(original.map { it.line }, f.root.lines)
        assertTrue(f.private.lines.isEmpty())
        assertEquals(1, f.private.clears)
        assertEquals(ExternalLogState.READY, f.boundary.status().external)
        assertEquals(PrivateLogState.EMPTY, f.boundary.status().private)
        original.forEachIndexed { index, row ->
            val json = JSONObject(f.root.lines[index])
            assertEquals(row.eventId, json.getString("eventId"))
            assertEquals(row.occurredAt, json.getLong("occurredAtMs"))
            assertEquals(row.receivedAt, json.getLong("receivedAtMs"))
        }
    }
    @Test fun `new event after authorization replays old rows before the new row`() {
        val f = Fixture(); val old = f.row("old"); val next = f.row("new")
        assertTrue(f.boundary.persist(listOf(old.line)))
        f.granted = true
        assertTrue(f.boundary.persist(listOf(next.line)))
        assertEquals(listOf(old.line, next.line), f.root.lines)
        assertTrue(f.private.lines.isEmpty())
    }
    @Test fun `revocation returns to private pending then restored access exports without timestamp rewriting`() {
        val f = Fixture(); f.granted = true
        val before = f.row("before"); val revoked = f.row("revoked", 99L); val after = f.row("after")
        assertTrue(f.boundary.persist(listOf(before.line)))
        f.granted = false
        assertTrue(f.boundary.persist(listOf(revoked.line)))
        assertEquals(listOf(before.line), f.root.lines)
        assertEquals(listOf(revoked.line), f.private.lines)
        f.granted = true
        assertTrue(f.boundary.persist(listOf(after.line)))
        assertEquals(listOf(before.line, revoked.line, after.line), f.root.lines)
        assertTrue(f.private.lines.isEmpty()); assertEquals(3, f.checks)
    }
    @Test fun `root write failure saves new rows privately and exposes only exception type`() {
        val f = Fixture(); f.granted = true; f.root.appendWorks = false
        val row = f.row("failed-root")
        assertTrue(f.boundary.persist(listOf(row.line)))
        assertEquals(listOf(row.line), f.private.lines)
        assertEquals(ExternalLogState.WRITE_FAILED, f.boundary.status().external)
        assertEquals("IOException", f.boundary.status().externalFailureType)
        assertFalse(f.boundary.status().toString().contains("secret"))
    }
    @Test fun `failed export never clears pending and still retains new rows privately`() {
        val f = Fixture(); val old = f.row("old"); val next = f.row("new")
        assertTrue(f.boundary.persist(listOf(old.line)))
        f.granted = true; f.root.appendWorks = false
        assertTrue(f.boundary.persist(listOf(next.line)))
        assertEquals(listOf(old.line, next.line), f.private.lines)
        assertEquals(0, f.private.clears)
        f.root.appendWorks = true
        assertTrue(f.boundary.persist(emptyList()))
        assertEquals(listOf(old.line, next.line), f.root.lines)
        assertTrue(f.private.lines.isEmpty())
    }
    @Test fun `root success followed by failed private acknowledgement permits same ID replay and discloses it`() {
        val f = Fixture(); val row = f.row("repeatable")
        assertTrue(f.boundary.persist(listOf(row.line)))
        f.granted = true; f.private.clearWorks = false
        assertTrue(f.boundary.persist(emptyList()))
        assertEquals(listOf(row.line), f.root.lines); assertEquals(listOf(row.line), f.private.lines)
        assertEquals(PrivateLogState.ACK_FAILED, f.boundary.status().private)
        assertEquals("IOException", f.boundary.status().privateFailureType)
        f.private.clearWorks = true
        assertTrue(f.boundary.persist(emptyList()))
        assertEquals(listOf(row.line, row.line), f.root.lines)
        assertTrue(f.private.lines.isEmpty())
    }
    @Test fun `uncertain external sync failure retains same event IDs for later retry`() {
        val f = Fixture(); val row = f.row("uncertain")
        f.granted = true; f.root.appendWorks = false
        assertTrue(f.boundary.persist(listOf(row.line)))
        assertEquals(listOf(row.line), f.private.lines)
        f.root.appendWorks = true
        assertTrue(f.boundary.persist(emptyList()))
        assertEquals(listOf(row.line), f.root.lines)
    }
    @Test fun `private read failure is explicit but does not prevent new external rows`() {
        val f = Fixture(); f.granted = true; f.private.readWorks = false
        val row = f.row("root-still-works")
        assertTrue(f.boundary.persist(listOf(row.line)))
        assertEquals(listOf(row.line), f.root.lines)
        assertEquals(PrivateLogState.READ_FAILED, f.boundary.status().private)
        assertEquals("IOException", f.boundary.status().privateFailureType)
        assertEquals(0, f.private.clears)
    }
    @Test fun `private write failure with missing access is explicit and not acknowledged success`() {
        val f = Fixture(); f.private.appendWorks = false
        assertFalse(f.boundary.persist(listOf(f.row("lost").line)))
        assertEquals(ExternalLogState.PERMISSION_REQUIRED, f.boundary.status().external)
        assertEquals(PrivateLogState.WRITE_FAILED, f.boundary.status().private)
        assertEquals("IOException", f.boundary.status().privateFailureType)
    }
    @Test fun `permission check exception falls back to private with safe exception type`() {
        val private = TestLogStorage(); var roots = 0
        val boundary = LogStorageBoundary({ throw SecurityException("secret token") }, { roots++; TestLogStorage() }, { private })
        assertTrue(boundary.persist(listOf("{\"id\":1}\n")))
        assertEquals(0, roots); assertEquals(ExternalLogState.ACCESS_CHECK_FAILED, boundary.status().external)
        assertEquals("SecurityException", boundary.status().externalFailureType)
        assertFalse(boundary.status().toString().contains("secret"))
    }
    @Test fun `throwing resolution and storage ports are isolated from caller`() {
        val boundary = LogStorageBoundary({ true }, { throw IOException("secret external") }, { throw IllegalStateException("secret private") })
        assertFalse(boundary.persist(listOf("{}\n")))
        assertEquals(ExternalLogState.WRITE_FAILED, boundary.status().external)
        assertEquals("IOException", boundary.status().externalFailureType)
        assertEquals(PrivateLogState.WRITE_FAILED, boundary.status().private)
        assertEquals("IllegalStateException", boundary.status().privateFailureType)
    }
    @Test fun `uncertain root and private writes are exposed separately without false acknowledgement`() {
        val f = Fixture(); f.granted = true; f.root.appendWorks = false; f.private.appendWorks = false
        assertFalse(f.boundary.persist(listOf(f.row("unconfirmed").line)))
        assertTrue(f.boundary.status().externalWriteUncertain)
        assertTrue(f.boundary.status().privateWriteUncertain)
    }
    @Test fun `private partial bytes discarded during bounded replay are disclosed to UI`() {
        val privateFile = File("/virtual/private-partial.log"); val rootFile = File("/virtual/root-partial.log")
        val io = MemoryLogFileIo(); io.files[privateFile] = "{}\npartial".toByteArray()
        val boundary = LogStorageBoundary({ false }, { RollingLogFile(rootFile, io) }, { RollingLogFile(privateFile, io) })
        assertTrue(boundary.persist(emptyList()))
        assertEquals(7L, boundary.status().discardedPrivateBytes)
        assertEquals(PrivateLogState.PENDING, boundary.status().private)
    }
    @Test fun `notification dedup also ignores discarded byte counters`() {
        var posted = 0
        val changes = LogStatusChanges { posted++ }
        val state = LogStorageStatus(ExternalLogState.PERMISSION_REQUIRED, PrivateLogState.PENDING, discardedPrivateBytes = 3)
        changes.update(state); changes.update(state.copy(discardedPrivateBytes = 4))
        assertEquals(1, posted)
    }
    @Test fun `root directory failure falls back privately without touching the directory`() {
        val directory = java.nio.file.Files.createTempDirectory("miband-root-directory-test").toFile()
        try {
            val root = File(directory, "miband.log").apply { mkdir() }
            val private = File(directory, "private.log")
            val boundary = LogStorageBoundary({ true }, { RollingLogFile(root) }, { RollingLogFile(private) })
            assertTrue(boundary.persist(listOf("{}\n")))
            assertTrue(root.isDirectory)
            assertEquals(listOf("{}\n"), RollingLogFile(private).readComplete().lines)
            assertEquals(ExternalLogState.WRITE_FAILED, boundary.status().external)
        } finally { directory.deleteRecursively() }
    }
    @Test fun `private retention uses same 3MiB cap 1MiB trim and exports only bounded complete rows`() {
        val privateFile = File("/virtual/private.log"); val rootFile = File("/virtual/root.log")
        val io = MemoryLogFileIo(); var granted = false
        val boundary = LogStorageBoundary({ granted }, { RollingLogFile(rootFile, io) }, { RollingLogFile(privateFile, io) })
        val log = AuditLog(7, LogTestClock())
        repeat(12) { batch ->
            val rows = (0 until 200).map { log.capture("batch-$batch-$it", mapOf("reason" to "中😀".repeat(500))).line }
            assertTrue(boundary.persist(rows))
            assertTrue(io.files[privateFile]!!.size <= RollingLogFile.MAX_BYTES)
        }
        assertTrue(io.replacements > 0)
        val retained = RollingLogFile(privateFile, io).readComplete().lines
        assertTrue(retained.size < 2_400)
        granted = true
        assertTrue(boundary.persist(emptyList()))
        assertEquals(retained, RollingLogFile(rootFile, io).readComplete().lines)
        assertEquals(emptyList<String>(), RollingLogFile(privateFile, io).readComplete().lines)
    }
    @Test fun `notifications are emitted only for state changes not changing pending bytes`() {
        val posted = mutableListOf<LogStorageStatus>()
        val changes = LogStatusChanges { posted.add(it) }
        val missing = LogStorageStatus(ExternalLogState.PERMISSION_REQUIRED, PrivateLogState.PENDING, pendingBytes = 10)
        changes.update(missing); changes.update(missing); changes.update(missing.copy(pendingBytes = 20))
        changes.update(LogStorageStatus(ExternalLogState.READY, PrivateLogState.EMPTY, pendingBytes = 0))
        assertEquals(2, posted.size)
    }
    @Test fun `notification failure is isolated and never modifies storage state or retries unchanged state`() {
        var posts = 0
        val changes = LogStatusChanges { posts++; throw SecurityException("secret notification") }
        val state = LogStorageStatus(ExternalLogState.WRITE_FAILED, PrivateLogState.PENDING, "IOException", pendingBytes = 3)
        changes.update(state); changes.update(state)
        assertEquals(1, posts)
        changes.update(state.copy(external = ExternalLogState.READY, externalFailureType = null))
        assertEquals(2, posts)
        assertEquals(ExternalLogState.WRITE_FAILED, state.external)
    }
    private fun source(name: String) = File("src/main/java/com/blreay/bluetoothmonitor/$name.kt").readText()
    @Test fun `Android adapter defers exact primary shared file access and private path resolution to worker ports`() {
        val text = source("MibandLog")
        assertTrue(text.contains("Environment.isExternalStorageManager()"))
        assertTrue(text.contains("File(Environment.getExternalStorageDirectory(), \"miband.log\")"))
        assertTrue(text.contains("File(app.filesDir, \"miband-log-pending.log\")"))
        assertTrue(text.contains("Executors.newSingleThreadExecutor"))
        assertTrue(text.contains("context.applicationContext"))
        assertFalse(text.contains("newCachedThreadPool")); assertFalse(text.contains("WRITE_EXTERNAL_STORAGE"))
    }
    @Test fun `Android notification ID channel silent direct immutable details action are isolated`() {
        val text = source("LogStatusNotification")
        assertTrue(text.contains("105")); assertTrue(text.contains("bt_log_status"))
        assertTrue(text.contains("PendingIntent.getActivity(")); assertTrue(text.contains("PermissionActivity::class.java"))
        assertTrue(text.contains("FLAG_IMMUTABLE")); assertTrue(text.contains("setSound(null"))
        assertTrue(text.contains("enableVibration(false)")); assertTrue(text.contains("setOnlyAlertOnce(true)"))
        assertFalse(text.contains("startActivity(")); assertFalse(text.contains("getBroadcast("))
    }
    @Test fun `all logger files avoid Bluetooth ownership safety prefs privileged shell and unrelated runtime`() {
        for (name in listOf("AuditLog", "RollingLogFile", "BoundedLogWriter", "MibandLog", "LogStatusNotification")) {
            val text = source(name)
            for (forbidden in listOf("bt_reset_pending", "PendingResetStore", "ResetCoordinator", "Shizuku.",
                "ProcessBuilder", "Runtime.getRuntime", "startForeground", "startActivity(", "connectGatt(", "createBond(",
                "ScanResult", "getSharedPreferences", "printStackTrace", ".message", ".stackTrace", "Intent.extras")) {
                assertFalse("$name must not contain $forbidden", text.contains(forbidden))
            }
        }
    }
}
