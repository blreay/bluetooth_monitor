package com.blreay.bluetoothmonitor

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RuntimeAuditTest {
    private data class Row(val name: String, val fields: Map<String, Any?>, val at: Long?)
    private class Backend : SwitchBackend {
        var state = AdapterState.ON
        var mode: SwitchMode? = SwitchMode.SHIZUKU_SHELL
        var privilegedResult = SwitchOutcome.notInvoked("not entered")
        var hold = false
        var held: ((SwitchOutcome) -> Unit)? = null
        var ordinaryCalls = 0
        var privilegedCalls = 0
        var original: SwitchInvocation? = null
        var ordinaryEnteredAt = 456L
        val targets = mutableListOf<Boolean>()
        override fun readState(lease: CommandLease, callback: (AdapterState) -> Unit) { callback(state) }
        override fun preflight(lease: CommandLease, budgetMs: Long, callback: (SwitchCapability) -> Unit) { callback(SwitchCapability(mode, "fresh actual capability")) }
        override fun privileged(enabled: Boolean, lease: CommandLease, budgetMs: Long, callback: (SwitchOutcome) -> Unit) { privilegedCalls++; callback(privilegedResult) }
        override fun ordinary(enabled: Boolean, lease: CommandLease, callback: (SwitchOutcome) -> Unit) { ordinaryCalls++; callback(SwitchOutcome.submitted(SwitchMode.ORDINARY)) }
        override fun privilegedObserved(enabled: Boolean, lease: CommandLease, budgetMs: Long, invocation: SwitchInvocation, callback: (SwitchOutcome) -> Unit) {
            original = invocation; privilegedCalls++; targets.add(enabled)
            if (hold) held = callback else callback(privilegedResult.copy(returnedAt = 123))
        }
        override fun ordinaryObserved(enabled: Boolean, lease: CommandLease, invocation: SwitchInvocation, callback: (SwitchOutcome) -> Unit) {
            original = invocation; ordinaryCalls++; targets.add(enabled); invocation.entered(ordinaryEnteredAt)
            callback(SwitchOutcome.submitted(SwitchMode.ORDINARY).copy(enteredAt = ordinaryEnteredAt,
                returnedAt = ordinaryEnteredAt + 1, submittedAt = ordinaryEnteredAt + 1))
        }
    }
    @Test fun `repeat and refused ingress is never deduplicated or mislabeled caller`() {
        val rows = mutableListOf<Row>(); val audit = RuntimeAudit(AuditSink { n, f, at -> rows.add(Row(n, f, at)) })
        repeat(3) { audit.ingress("launcher", "request-$it", "android.intent.action.MAIN", "actual/component") }
        audit.ingress("activity", "other", "foo.VIEW", "actual/component")
        assertEquals(4, rows.size)
        assertEquals(4, rows.map { it.fields["requestId"] }.distinct().size)
        assertEquals("foo.VIEW", rows.last().fields["action"])
        assertEquals("activity", rows.last().fields["source"])
    }
    @Test fun `privileged not invoked fallback logs new ordinary identity and preserves invocation hooks`() {
        val rows = mutableListOf<Row>(); val audit = RuntimeAudit(AuditSink { n, f, at -> rows.add(Row(n, f, at)) })
        val native = Backend(); var identity = RuntimeIdentity("manual", "operation", "first", "request")
        var entered: Long? = null
        val invocation = SwitchInvocation({ entered = it }, { previous ->
            if (previous != null) identity = identity.copy(commandId = "second")
            true
        })
        BluetoothSwitchDriver(audit.backend(native) { identity }).execute(false, CommandLease.foreground(), invocation = invocation) { }
        assertSame(invocation, native.original)
        assertEquals(456L, entered)
        assertEquals(1, native.ordinaryCalls); assertEquals(1, native.privilegedCalls)
        assertEquals("first", rows.single { it.name == "switch.privileged.outcome" }.fields["commandId"])
        assertEquals("second", rows.single { it.name == "switch.ordinary.request" }.fields["commandId"])
        assertEquals("second", rows.single { it.name == "switch.ordinary.outcome" }.fields["commandId"])
        assertEquals(457L, rows.single { it.name == "switch.ordinary.outcome" }.at)
        assertEquals("fresh actual capability", rows.single { it.name == "switch.preflight" }.fields["reason"])
    }
    @Test fun `late result retains original request identity not future mutable owner`() {
        val rows = mutableListOf<Row>(); val audit = RuntimeAudit(AuditSink { n, f, at -> rows.add(Row(n, f, at)) })
        val native = Backend().apply { hold = true }
        var identity = RuntimeIdentity("automatic", "run-old", "command-old")
        audit.backend(native) { identity }.privilegedObserved(true, CommandLease.foreground(), 6000, SwitchInvocation()) { }
        identity = RuntimeIdentity("manual", "new-owner", "new-command")
        native.held!!(SwitchOutcome.uncertain(SwitchMode.SHIZUKU_ROOT, "authority unknown").copy(returnedAt = 900))
        val result = rows.single { it.name == "switch.privileged.outcome" }
        assertEquals("run-old", result.fields["runId"]); assertEquals("command-old", result.fields["commandId"])
        assertEquals(false, result.fields["authorityEnded"]); assertEquals("UNCERTAIN", result.fields["status"])
        assertEquals(900L, result.at)
    }
    @Test fun `throwing audit preserves six backend ports and actual on off targets`() {
        val native = Backend(); val audit = RuntimeAudit(AuditSink { _, _, _ -> error("audit") })
        val backend = audit.backend(native) { RuntimeIdentity("manual", "operation", "command") }
        val lease = CommandLease.foreground(); val invocation = SwitchInvocation()
        var state: AdapterState? = null; var capability: SwitchCapability? = null; var results = 0
        backend.readState(lease) { state = it }; backend.preflight(lease, 5000) { capability = it }
        backend.privileged(false, lease, 6000) { results++ }; backend.ordinary(true, lease) { results++ }
        backend.privilegedObserved(true, lease, 6000, invocation) { results++ }
        backend.ordinaryObserved(false, lease, invocation) { results++ }
        assertEquals(AdapterState.ON, state); assertEquals("fresh actual capability", capability!!.reason)
        assertEquals(4, results); assertEquals(2, native.privilegedCalls); assertEquals(2, native.ordinaryCalls)
        assertSame(invocation, native.original)
    }
    @Test fun `details only scan result card log permission explicit and cached repaint stopped`() {
        val source = source("PermissionActivity.kt")
        assertFalse(source.contains("manualBodies[ManualAction.ON] = text()"))
        assertFalse(source.contains("manualBodies[ManualAction.OFF] = text()"))
        assertTrue(source.contains("manualBodies[ManualAction.SCAN] = text()"))
        assertTrue(source.contains("授权日志写入（所有文件访问）"))
        assertTrue(source.contains("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"))
        assertTrue(source.contains("ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION"))
        assertTrue(source.contains("MibandLog.status()")); assertTrue(source.contains("MibandLog.refreshAccess()"))
        assertTrue(source.contains("removeCallbacks(logRepaint)"))
        val render = source.substringAfter("private fun render(snapshot:").substringBefore("override fun onRequestPermissionsResult")
        assertFalse(render.contains("Notifier.always")); assertFalse(render.contains("Toast"))
    }
    @Test fun `process logger starts only behind original privilege guard before coordinator`() {
        val source = source("MonitorApplication.kt")
        val guard = source.indexOf("Process.myUid() == 0 || Process.myUid() == 2000 || getProcessName() != packageName")
        val logger = source.indexOf("MibandLog.initialize(this)")
        assertTrue(guard >= 0 && logger > guard)
        assertTrue(source.indexOf("process.start") > logger)
        assertTrue(source.indexOf("coordinator = ResetCoordinator(this)") > logger)
        val main = source("MainActivity.kt")
        assertTrue(main.contains("requestId")); assertTrue(main.contains("intent.action")); assertTrue(main.contains("intent.component"))
        assertFalse(main.contains("setContentView"))
    }
    @Test fun `all files permission does not enter any background Bluetooth path`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE")); assertFalse(manifest.contains("requestLegacyExternalStorage"))
        for (file in listOf("MainActivity.kt", "ResetCoordinator.kt", "ResetService.kt", "TriggerReceiver.kt", "ReEnableReceiver.kt")) {
            assertFalse(file, source(file).contains("ACTION_MANAGE_"))
            assertFalse(file, source(file).contains("MibandLog.refreshAccess()"))
        }
        assertTrue(source("ResetCoordinator.kt").contains("audit: AuditSink = MibandLog"))
    }
    @Test fun `retirement request start return and failed stop never log exact cleanup proof`() {
        val rows = mutableListOf<Row>(); val queue = java.util.ArrayDeque<() -> Unit>()
        var stopWorks = false; var confirmed = 0
        val retirement = ScanRegistrationRetirement({ 100L }, { queue.add(it); true },
            onRetired = { confirmed++ }, onFailure = { _, _ -> }, audit = AuditSink { n, f, at -> rows.add(Row(n, f, at)) })
        val ticket = retirement.claim("manual") { stopWorks }!!
        retirement.start(ticket) { retirement.retire(ticket); true }
        assertFalse(rows.any { it.name == "ble.retired" })
        queue.removeFirst()()
        assertEquals(0, confirmed); assertTrue(retirement.blocked())
        assertEquals(false, rows.single { it.name == "ble.retirement.failed" }.fields["registrationRetired"])
        assertFalse(rows.any { it.name == "ble.retired" })
        stopWorks = true; assertTrue(retirement.retryRetirement()); queue.removeFirst()()
        assertEquals(1, confirmed); assertFalse(retirement.blocked())
        assertEquals(ticket.serial, rows.single { it.name == "ble.retired" }.fields["serial"])
        assertEquals(true, rows.single { it.name == "ble.retired" }.fields["registrationRetired"])
    }
    @Test fun `throwing retirement sink cannot swallow native cleanup success or failure retry`() {
        val queue = java.util.ArrayDeque<() -> Unit>(); var permitted = false; var confirmed = 0; var failed = 0
        val retirement = ScanRegistrationRetirement({ 100L }, { queue.add(it); true },
            onRetired = { confirmed++ }, onFailure = { _, _ -> failed++ }, audit = AuditSink { _, _, _ -> error("sink") })
        val ticket = retirement.claim("automatic") { permitted }!!
        assertTrue(retirement.start(ticket) { true }); retirement.retire(ticket); queue.removeFirst()()
        assertEquals(1, failed); assertTrue(retirement.blocked())
        permitted = true; assertTrue(retirement.retryRetirement()); queue.removeFirst()()
        assertEquals(1, confirmed); assertFalse(retirement.blocked())
    }
    @Test fun `actual checked engine terminal save records restored separately from counted and total`() {
        for (entry in listOf(true, false)) {
            val rows = mutableListOf<Row>(); val storage = MemoryResetStorage(); val store = CheckedResetStore(storage)
            var now = 1000L; var serial = 0
            val engine = DurableResetEngine(store, { true }, { now++ }, { "command-${++serial}" },
                audit = AuditSink { n, f, at -> rows.add(Row(n, f, at)) })
            val accepted = engine.armAccepted(AcceptedResetMatch("run", "band", "address", null, "claimed", 900), false)!!
            val off = engine.dispatch(accepted, CommandLease.foreground(), false)!!
            if (entry) assertTrue(engine.nativeEntered(off, 1001))
            engine.complete(off, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL))
            assertEquals(RecoveryStep.DISPATCH_ON, engine.observe(AdapterState.OFF, 1002))
            val on = engine.dispatch(engine.read()!!, CommandLease.foreground(), true)!!
            if (entry) assertTrue(engine.nativeEntered(on, 1003))
            engine.complete(on, SwitchOutcome.submitted(SwitchMode.ORDINARY))
            now = 9000L // delayed Main delivery of the worker's earlier 1004ms sample
            assertEquals(RecoveryStep.RESTORED, engine.observe(AdapterState.ON, 1004))
            val terminal = rows.single { it.name == "automatic.terminal.save" }
            assertEquals(true, terminal.fields["saved"]); assertEquals(entry, terminal.fields["counted"])
            assertEquals(if (entry) 1L else 0L, terminal.fields["total"])
            assertEquals("RESTORED", terminal.fields["disposition"])
            assertEquals(store.snapshot().history.rows.single().terminalRecordedAt, terminal.at)
            assertNotEquals(1004L, terminal.at)
        }
    }
    @Test fun `failed checked terminal commit never logs successful completion or counted`() {
        val rows = mutableListOf<Row>(); val storage = MemoryResetStorage(); val store = CheckedResetStore(storage)
        val engine = DurableResetEngine(store, { true }, { 1000L }, { "command" },
            audit = AuditSink { n, f, at -> rows.add(Row(n, f, at)) })
        engine.armAccepted(AcceptedResetMatch("run", null, null, null, "match", 900), false)
        val pending = store.snapshot().pending!!
        assertTrue(store.commit(pending, pending.copy(version = pending.version + 1, stage = ResetStage.ON_SETTLED,
            phase = ReEnablePolicy.Phase.WAITING_ON)))
        storage.works = false; storage.eagerFailure = true
        assertEquals(RecoveryStep.STORAGE_FAILED, engine.observe(AdapterState.ON, 950))
        val terminal = rows.single { it.name == "automatic.terminal.save" }
        assertEquals(false, terminal.fields["saved"]); assertNull(terminal.fields["counted"])
        assertEquals(0L, terminal.fields["total"]); assertNotNull(store.snapshot().pending)
    }
    @Test fun `captured sample source time and manual delivery deadline are independent of receipt`() {
        val rows = mutableListOf<Row>(); val audit = RuntimeAudit(AuditSink { n, f, at -> rows.add(Row(n, f, at)) })
        audit.sample(RuntimeIdentity("manual", "op", "cmd"), StateSample(AdapterState.ON, 123, 456))
        assertEquals(123L, rows.single().at); assertEquals(456L, rows.single().fields["sampledElapsedMs"])
        val window = ManualObservationWindow(); window.captureEntry(1_000_000_000L); var expired = 0
        assertTrue(window.admit(1_500_000_000L, 2_000_000_000L, 3_000_000_000L) { expired++ })
        assertFalse(window.admit(1_500_000_000L, 2_000_000_000L, 9_000_000_000L) { expired++ })
        assertEquals(1, expired)
    }
    @Test fun `admitted advertisements keep batch receipt source sample delivery and display only target facts`() {
        val rows = mutableListOf<Row>(); val audit = RuntimeAudit(AuditSink { n, f, at -> rows.add(Row(n, f, at)) })
        val identity = RuntimeIdentity("manual", "op", "cmd")
        AdvertisementDelivery.deliver(listOf("11:22:33:44:35:BA" to BandMatcher.TARGET_NAME, "22:22:33:44:00:01" to "Speaker"), { 123L }, { it() }) { advertisement, receipt ->
            audit.advertisement(identity, "op/7", advertisement.first, advertisement.second, -42, 300, receipt, 400, 500, true)
        }
        assertEquals(2, rows.size); assertEquals(listOf(123L, 123L), rows.map { it.at })
        assertTrue(rows.all { it.fields["sampledElapsedNanos"] == 300L && it.fields["receivedElapsedNanos"] == 400L &&
            it.fields["deliveredElapsedNanos"] == 500L && it.fields["displayOnly"] == true })
        assertEquals(listOf(true, false), rows.map { it.fields["target"] })
        assertTrue(rows.all { it.fields["scannerIdentity"] == "op/7" && it.fields["commandId"] == "cmd" })
        assertTrue(source("ManualBleScanner.kt").contains("runtimeAudit.advertisement("))
        assertTrue(source("BandResetController.kt").contains("runtimeAudit.advertisement("))
    }
    @Test fun `actual engine and shared driver both directions rotate fallback identity without losing entry hooks`() {
        val rows = mutableListOf<Row>(); val sink = AuditSink { n, f, at -> rows.add(Row(n, f, at)) }
        val store = CheckedResetStore(MemoryResetStorage()); var serial = 0; var now = 2000L
        val engine = DurableResetEngine(store, { true }, { now }, { "physical-${++serial}" }, audit = sink)
        val native = Backend(); val audit = RuntimeAudit(sink)
        engine.armAccepted(AcceptedResetMatch("cycle", "band", "address", null, "claimed", 1900), false)
        fun toggle(enabled: Boolean, enteredAt: Long) {
            var command = engine.dispatch(engine.read()!!, CommandLease.foreground(), enabled)!!
            native.ordinaryEnteredAt = enteredAt
            val invocation = SwitchInvocation({ at -> assertTrue(engine.nativeEntered(command, at)) }, { previous ->
                if (previous != null) command = engine.nextPhysicalAttempt(command, previous)!!
                true
            })
            val driver = BluetoothSwitchDriver(audit.backend(native) { RuntimeIdentity("automatic", command.runId, command.commandId, "request") })
            driver.execute(enabled, CommandLease.foreground(), invocation = invocation) { assertNotNull(engine.complete(command, it)) }
            assertSame(invocation, native.original)
            assertEquals(command.commandId, rows.last { it.name == "switch.ordinary.outcome" }.fields["commandId"])
        }
        toggle(false, 2001)
        now = 2003
        assertEquals(RecoveryStep.DISPATCH_ON, engine.observe(AdapterState.OFF, now))
        native.state = AdapterState.OFF
        toggle(true, 2004)
        now = 2006
        assertEquals(RecoveryStep.RESTORED, engine.observe(AdapterState.ON, now))
        assertEquals(listOf(false, false, true, true), native.targets)
        val requests = rows.filter { it.name in listOf("switch.privileged.request", "switch.ordinary.request") }
        assertEquals(4, requests.map { it.fields["commandId"] }.distinct().size)
        assertEquals(1L, store.snapshot().history.total)
        assertTrue(store.snapshot().history.rows.single().counted)
        assertNull(store.snapshot().pending)
    }
    @Test fun `logger permission flush changes cached visible status without Bluetooth callbacks or main disk reads`() {
        val io = MemoryLogFileIo(); val root = File("/virtual/root.log"); val pending = File("/virtual/pending.log")
        var granted = false; val queue = java.util.ArrayDeque<() -> Unit>()
        val boundary = LogStorageBoundary({ granted }, { RollingLogFile(root, io) }, { RollingLogFile(pending, io) })
        val writer = BoundedLogWriter(AuditLog(7, LogTestClock()), LogScheduler { queue.add(it) }, boundary)
        writer.event("process.start")
        assertFalse(io.files.containsKey(root)); queue.removeFirst()()
        val before = writer.status()
        assertEquals(ExternalLogState.PERMISSION_REQUIRED, before.storage.external)
        assertEquals(PrivateLogState.PENDING, before.storage.private)
        granted = true; writer.refreshAccess()
        assertEquals(before.storage, writer.status().storage) // enqueue is asynchronous, not a Main-thread file check
        queue.removeFirst()()
        assertEquals(ExternalLogState.READY, writer.status().storage.external)
        assertEquals(PrivateLogState.EMPTY, writer.status().storage.private)
        assertTrue(io.files[root]!!.isNotEmpty())
        val activity = source("PermissionActivity.kt")
        val loop = activity.substringAfter("private val logRepaint").substringBefore("private val prefs")
        assertTrue(loop.contains("if (!visible")); assertTrue(loop.contains("renderLogStatus()"))
        assertFalse(loop.contains("refreshAccess")); assertFalse(loop.contains("scan(")); assertFalse(loop.contains("Environment."))
        val repaint = activity.substringAfter("private fun renderLogStatus()").substringBefore("private fun render(snapshot")
        assertTrue(repaint.contains("MibandLog.status()")); assertFalse(repaint.contains("File("))
        assertEquals(3, Regex("removeCallbacks\\(logRepaint\\)").findAll(activity).count())
    }
    @Test fun `every production ingress is recorded before gate or owner allocation and no intent extras dump`() {
        val coordinator = source("ResetCoordinator.kt")
        val launch = coordinator.substringAfter("fun launch(").substringBefore("fun awaitStartupForSetup")
        assertTrue(launch.indexOf("runtimeAudit.ingress(") < launch.indexOf("gate.newRequest()"))
        assertTrue(launch.indexOf("runtimeAudit.ingress(") < launch.indexOf("ownership.acquire(id)"))
        val broadcast = coordinator.substringAfter("fun broadcastScan(").substringBefore("private fun startScan")
        assertTrue(broadcast.indexOf("runtimeAudit.ingress(") < broadcast.indexOf("BtPermissions.allGranted"))
        val alarm = coordinator.substringAfter("fun alarm(").substringBefore("private fun recoveryStep")
        assertTrue(alarm.indexOf("runtimeAudit.ingress(") < alarm.indexOf("engine.read()"))
        for (file in listOf("TriggerReceiver.kt", "ReEnableReceiver.kt")) {
            val receiver = source(file)
            assertTrue(receiver.indexOf("receiver.ingress") < receiver.indexOf("if (intent.action"))
        }
        val service = source("ResetService.kt")
        assertTrue(service.indexOf("service.ingress") < service.indexOf("if (lifetime.owned"))
        for (file in listOf("MainActivity.kt", "TriggerReceiver.kt", "ReEnableReceiver.kt", "ResetCoordinator.kt", "RuntimeAudit.kt")) {
            assertFalse(source(file).contains("intent.extras")); assertFalse(source(file).contains("Intent.extras"))
        }
    }
    @Test fun `NoDisplay dispatcher observes synchronous return and finish request with correlated request id`() {
        val main = source("MainActivity.kt")
        val launch = main.indexOf("coordinator.launch(")
        val returned = main.indexOf("\"activity.dispatch.return\"")
        val skipped = main.indexOf("\"activity.dispatch.skip\"")
        val finishing = main.indexOf("\"activity.finish.request\"")
        val finish = main.indexOf("finish()")
        assertTrue("dispatch-return event is missing", returned >= 0)
        assertTrue("dispatch skip distinguishes recreation", skipped >= 0)
        assertTrue("finish-request event is missing", finishing >= 0)
        assertTrue("return is observed only after synchronous launch", returned > launch)
        assertTrue("finish request precedes finish()", finishing > returned && finishing > skipped && finishing < finish)
        for (name in listOf("activity.dispatch.return", "activity.dispatch.skip", "activity.finish.request")) {
            val event = main.substringAfter("\"$name\"").substringBefore(")")
            assertTrue("$name must correlate the original request", event.contains("\"requestId\" to requestId"))
        }
        assertTrue(main.contains("if (savedInstanceState == null)"))
        assertFalse(main.contains("override fun onDestroy"))
        assertFalse(main.contains("setContentView"))
        assertEquals(1, Regex("coordinator\\.launch\\(").findAll(main).count())
    }
    private fun source(name: String) = File("src/main/java/com/blreay/bluetoothmonitor/$name").readText()
}
