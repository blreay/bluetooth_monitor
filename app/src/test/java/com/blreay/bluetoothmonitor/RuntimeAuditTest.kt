package com.blreay.bluetoothmonitor

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RuntimeAuditTest {
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
    private class Fixture(throwing: Boolean = false) {
        val rows = mutableListOf<CompactLogEntry>()
        val storage = MemoryResetStorage()
        val store = CheckedResetStore(storage)
        var now = 1000L
        var serial = 0
        val engine = DurableResetEngine(store, { true }, { now }, { "command-${++serial}" },
            AuditSink { rows.add(it); if (throwing) error("sink") })
        fun arm() = engine.armAccepted(AcceptedResetMatch("run", "band", "address", null, "claimed", 900), false)!!
        fun off(mode: SwitchMode = SwitchMode.SHIZUKU_SHELL, entry: Boolean = true): ResetRecord {
            val off = engine.dispatch(arm(), CommandLease.foreground(), false)!!
            if (entry) assertTrue(engine.nativeEntered(off, 1001))
            assertNotNull(engine.complete(off, SwitchOutcome.submitted(mode)))
            return off
        }
        fun prepareOn() { assertEquals(RecoveryStep.DISPATCH_ON, engine.observe(AdapterState.OFF, 1002)) }
        fun on(mode: SwitchMode = SwitchMode.ORDINARY, entry: Boolean = true): ResetRecord {
            val on = engine.dispatch(engine.read()!!, CommandLease.foreground(), true)!!
            if (entry) assertTrue(engine.nativeEntered(on, 1003))
            assertNotNull(engine.complete(on, SwitchOutcome.submitted(mode)))
            return on
        }
    }
    @Test fun `actual OFF handoff and checked RESTORED terminal are the only successful boundaries`() {
        for (entry in listOf(true, false)) {
            val f = Fixture(); f.off(entry = entry)
            assertTrue(f.rows.isEmpty()) // submitted is not success
            f.prepareOn()
            assertEquals(listOf(CompactLogEntry.BluetoothEnded(false, SwitchMode.SHIZUKU_SHELL, "成功")), f.rows)
            f.on(entry = entry)
            assertEquals(1, f.rows.size)
            f.now = 9000L
            assertEquals(RecoveryStep.RESTORED, f.engine.observe(AdapterState.ON, 1004))
            assertEquals(CompactLogEntry.BluetoothEnded(true, SwitchMode.ORDINARY, "成功"), f.rows.last())
            assertEquals(if (entry) 1L else 0L, f.store.snapshot().history.total)
            assertEquals(entry, f.store.snapshot().history.rows.single().counted)
            assertEquals(9000L, f.store.snapshot().history.rows.single().terminalRecordedAt)
            assertNull(f.store.snapshot().pending)
            assertEquals(RecoveryStep.FAILED, f.engine.observe(AdapterState.ON, 1004))
            assertEquals(2, f.rows.size)
        }
    }
    @Test fun `failed checked terminal never logs success or counts and repeated failures dedup`() {
        val f = Fixture(); f.off(); f.prepareOn(); f.on()
        f.storage.works = false; f.storage.eagerFailure = true
        repeat(3) { assertEquals(RecoveryStep.STORAGE_FAILED, f.engine.observe(AdapterState.ON, 1004)) }
        assertEquals(CompactLogEntry.BluetoothEnded(true, SwitchMode.ORDINARY, "存储失败，状态未确认"), f.rows.last())
        assertEquals(2, f.rows.size); assertEquals(0L, f.store.snapshot().history.total); assertNotNull(f.store.snapshot().pending)
    }
    @Test fun `OFF handoff save failure does not claim OFF success`() {
        val f = Fixture(); f.off(); f.storage.works = false
        assertEquals(RecoveryStep.STORAGE_FAILED, f.engine.observe(AdapterState.OFF, 1002))
        assertEquals(listOf(CompactLogEntry.BluetoothEnded(false, SwitchMode.SHIZUKU_SHELL, "存储失败，状态未确认")), f.rows)
        assertEquals(ReEnablePolicy.Phase.WAITING_OFF, f.engine.read()!!.phase)
    }
    @Test fun `ON ready never inherits the preceding OFF mode for uninvoked ON`() {
        val f = Fixture(); f.off(); f.prepareOn()
        assertEquals(SwitchMode.SHIZUKU_SHELL, f.engine.read()!!.lastMode) // safety persistence unchanged
        assertEquals(RecoveryStep.EXHAUSTED, f.engine.observe(AdapterState.UNKNOWN, 1003))
        assertEquals(CompactLogEntry.BluetoothEnded(true, SwitchMode.NONE, "状态未确认"), f.rows.last())
        repeat(4) { f.engine.observe(AdapterState.UNKNOWN, 1003) }
        assertEquals(2, f.rows.size)
    }
    @Test fun `known no invocation actual target is not Shizuku success`() {
        val f = Fixture(); f.off(); f.prepareOn()
        val on = f.engine.dispatch(f.engine.read()!!, CommandLease.foreground(), true)!!
        f.engine.complete(on, SwitchOutcome(SwitchOutcome.Status.OBSERVING, SwitchMode.NONE, "already target", true))
        assertEquals(RecoveryStep.RESTORED, f.engine.observe(AdapterState.ON, 1004))
        assertEquals(CompactLogEntry.BluetoothEnded(true, SwitchMode.NONE, "已是目标状态，未调用"), f.rows.last())
    }
    @Test fun `unsafe authority logs one uncertainty then a reliable checked conclusion`() {
        val f = Fixture(); val command = f.engine.dispatch(f.arm(), CommandLease.foreground(), false)!!
        assertTrue(f.engine.nativeEntered(command, 1001))
        f.engine.complete(command, SwitchOutcome.uncertain(SwitchMode.SHIZUKU_ROOT, "raw private reason"))
        repeat(4) { f.engine.observe(AdapterState.OFF, 1002) }
        assertEquals(listOf(CompactLogEntry.BluetoothEnded(false, SwitchMode.SHIZUKU_ROOT, "执行结束未确认")), f.rows)
        assertFalse(f.engine.read()!!.authorityEnded)
        assertNotNull(f.engine.complete(command, SwitchOutcome.submitted(SwitchMode.SHIZUKU_ROOT)))
        assertEquals(RecoveryStep.DISPATCH_ON, f.engine.observe(AdapterState.OFF, 1003))
        assertEquals(CompactLogEntry.BluetoothEnded(false, SwitchMode.SHIZUKU_ROOT, "成功"), f.rows.last())
        assertEquals(2, f.rows.size)
    }
    @Test fun `unknown observation may later confirm once without sample spam`() {
        val f = Fixture(); f.off(); f.prepareOn(); f.on()
        assertEquals(RecoveryStep.EXHAUSTED, f.engine.observe(AdapterState.UNKNOWN, 1004))
        repeat(4) { f.engine.observe(AdapterState.UNKNOWN, 1005) }
        assertEquals(2, f.rows.size)
        assertEquals(RecoveryStep.RESTORED, f.engine.observe(AdapterState.ON, 1006))
        assertEquals(CompactLogEntry.BluetoothEnded(true, SwitchMode.ORDINARY, "成功"), f.rows.last())
        assertEquals(3, f.rows.size)
    }
    @Test fun `stale completion cannot alter current command logging mode or authority`() {
        val f = Fixture(); val off = f.off(); f.prepareOn(); val on = f.on()
        assertNull(f.engine.complete(off, SwitchOutcome.uncertain(SwitchMode.SHIZUKU_ROOT, "stale")))
        assertEquals(on.commandId, f.engine.read()!!.commandId)
        assertEquals(RecoveryStep.RESTORED, f.engine.observe(AdapterState.ON, 1004))
        assertEquals(CompactLogEntry.BluetoothEnded(true, SwitchMode.ORDINARY, "成功"), f.rows.last())
    }
    @Test fun `cold store observation and snapshot never replay a compact conclusion`() {
        val f = Fixture(); f.off(); f.prepareOn(); f.on()
        val rows = mutableListOf<CompactLogEntry>()
        val cold = DurableResetEngine(CheckedResetStore(f.storage), { true }, { 2000L }, { "cold" }, AuditSink { rows.add(it) })
        assertEquals(RecoveryStep.RESTORED, cold.observe(AdapterState.ON, 1004))
        cold.read(); f.store.snapshot(); assertTrue(rows.isEmpty())
    }
    @Test fun `explicit checked OFF to ON retry logs OFF after save and new ON has NONE mode`() {
        val f = Fixture(); f.off()
        assertTrue(f.engine.retryRecovery(AdapterState.OFF, 1002))
        assertEquals(listOf(CompactLogEntry.BluetoothEnded(false, SwitchMode.SHIZUKU_SHELL, "成功")), f.rows)
        assertEquals(RecoveryStep.EXHAUSTED, f.engine.observe(AdapterState.UNKNOWN, 1003))
        assertEquals(CompactLogEntry.BluetoothEnded(true, SwitchMode.NONE, "状态未确认"), f.rows.last())
        assertTrue(f.engine.read()!!.authorityEnded)
    }
    @Test fun `failed acceptance logs not executed without creating an OFF job or count`() {
        val f = Fixture(); f.storage.works = false
        assertNull(f.engine.armAccepted(AcceptedResetMatch("run", "band", "address", null, "claimed", 900), false))
        assertEquals(listOf(CompactLogEntry.BluetoothEnded(false, SwitchMode.NONE, "存储失败，未执行")), f.rows)
        assertNull(f.store.snapshot().pending); assertEquals(0L, f.store.snapshot().history.total)
        assertTrue(f.store.snapshot().storageFailed); assertFalse(f.store.newNativeAllowed())
        assertEquals(ResetStage.QUARANTINED, f.engine.read()!!.stage) // original unreadable-pending isolation sentinel
    }
    @Test fun `checked route history save failure does not invent a used backend`() {
        val f = Fixture(); val command = f.engine.dispatch(f.arm(), CommandLease.foreground(), false)!!
        f.storage.works = false
        assertFalse(f.engine.note(command, BackendEntry(SwitchMode.SHIZUKU_ROOT, "capability only")))
        assertEquals(listOf(CompactLogEntry.BluetoothEnded(false, SwitchMode.NONE, "存储失败，状态未确认")), f.rows)
        assertFalse(f.engine.read()!!.authorityEnded)
    }
    @Test fun `unsafe ON authority cannot log success from ON observation until checked late ended result`() {
        val f = Fixture(); f.off(); f.prepareOn()
        val on = f.engine.dispatch(f.engine.read()!!, CommandLease.foreground(), true)!!
        assertTrue(f.engine.nativeEntered(on, 1003))
        assertNotNull(f.engine.complete(on, SwitchOutcome.uncertain(SwitchMode.ORDINARY, "raw driver reason")))
        repeat(3) { f.engine.observe(AdapterState.ON, 1004) }
        assertEquals(CompactLogEntry.BluetoothEnded(true, SwitchMode.ORDINARY, "执行结束未确认"), f.rows.last())
        assertEquals(2, f.rows.size); assertNotNull(f.store.snapshot().pending); assertEquals(0L, f.store.snapshot().history.total)
        assertNotNull(f.engine.complete(on, SwitchOutcome.submitted(SwitchMode.ORDINARY)))
        assertEquals(RecoveryStep.RESTORED, f.engine.observe(AdapterState.ON, 1005))
        assertEquals(CompactLogEntry.BluetoothEnded(true, SwitchMode.ORDINARY, "成功"), f.rows.last())
        assertEquals(3, f.rows.size); assertEquals(1L, f.store.snapshot().history.total)
    }
    @Test fun `native entry without accepted route is unknown not NONE and storage failure is bounded`() {
        val f = Fixture(); val command = f.engine.dispatch(f.arm(), CommandLease.foreground(), false)!!
        f.storage.works = false
        repeat(3) { assertFalse(f.engine.nativeEntered(command, 1001)) }
        assertEquals(listOf(CompactLogEntry.BluetoothEnded(false, null, "存储失败，状态未确认")), f.rows)
        assertNull(f.engine.read()!!.offEvidence!!.enteredAt)
    }
    @Test fun `driver uses original observed ports and invocation hooks with ordinary fallback`() {
        val native = Backend(); var command = "first"; val entries = mutableListOf<String>(); var entered: Long? = null
        val invocation = SwitchInvocation({ entered = it; entries.add(command) }, { previous ->
            if (previous != null) command = "second"
            true
        })
        BluetoothSwitchDriver(native).execute(false, CommandLease.foreground(), invocation = invocation) { }
        assertSame(invocation, native.original); assertEquals(456L, entered)
        assertEquals(1, native.ordinaryCalls); assertEquals(1, native.privilegedCalls)
        assertEquals(listOf("second"), entries)
    }
    @Test fun `all six original backend ports preserve actual targets and callback results`() {
        val native = Backend(); val lease = CommandLease.foreground(); val invocation = SwitchInvocation()
        var state: AdapterState? = null; var capability: SwitchCapability? = null; var results = 0
        native.readState(lease) { state = it }; native.preflight(lease, 5000) { capability = it }
        native.privileged(false, lease, 6000) { results++ }; native.ordinary(true, lease) { results++ }
        native.privilegedObserved(true, lease, 6000, invocation) { results++ }
        native.ordinaryObserved(false, lease, invocation) { results++ }
        assertEquals(AdapterState.ON, state); assertEquals("fresh actual capability", capability!!.reason)
        assertEquals(4, results); assertEquals(2, native.privilegedCalls); assertEquals(2, native.ordinaryCalls)
        assertSame(invocation, native.original)
    }
    @Test fun `actual engine shared driver rotates both directions without losing entry or count proofs`() {
        val f = Fixture(true); val native = Backend(); f.arm()
        val commands = mutableListOf<String>()
        fun toggle(enabled: Boolean, enteredAt: Long) {
            var command = f.engine.dispatch(f.engine.read()!!, CommandLease.foreground(), enabled)!!
            commands.add(command.commandId)
            native.ordinaryEnteredAt = enteredAt
            val invocation = SwitchInvocation({ at -> assertTrue(f.engine.nativeEntered(command, at)) }, { previous ->
                if (previous != null) { command = f.engine.nextPhysicalAttempt(command, previous)!!; commands.add(command.commandId) }
                true
            })
            BluetoothSwitchDriver(native).execute(enabled, CommandLease.foreground(), invocation = invocation) { assertNotNull(f.engine.complete(command, it)) }
            assertSame(invocation, native.original)
        }
        toggle(false, 1001); f.prepareOn(); native.state = AdapterState.OFF; toggle(true, 1003)
        assertEquals(RecoveryStep.RESTORED, f.engine.observe(AdapterState.ON, 1004))
        assertEquals(listOf(false, false, true, true), native.targets)
        assertEquals(4, commands.distinct().size)
        assertEquals(List(2) { index -> CompactLogEntry.BluetoothEnded(index == 1, SwitchMode.ORDINARY, "成功") }, f.rows)
        assertEquals(1L, f.store.snapshot().history.total); assertTrue(f.store.snapshot().history.rows.single().counted)
        assertNull(f.store.snapshot().pending)
    }
    @Test fun `failed stop retains exact fence and explicit retry rather than logger cleanup claims`() {
        val queue = java.util.ArrayDeque<() -> Unit>(); var permitted = false; var confirmed = 0; var failed = 0
        val retirement = ScanRegistrationRetirement({ 100L }, { queue.add(it); true }, onRetired = { confirmed++ }, onFailure = { _, _ -> failed++ })
        val ticket = retirement.claim("automatic") { permitted }!!
        assertTrue(retirement.start(ticket) { true }); retirement.retire(ticket); queue.removeFirst()()
        assertEquals(1, failed); assertTrue(retirement.blocked()); assertEquals(0, confirmed)
        permitted = true; assertTrue(retirement.retryRetirement()); queue.removeFirst()()
        assertEquals(1, confirmed); assertFalse(retirement.blocked()); assertFalse(retirement.retire(ticket))
    }
    @Test fun `annotation observes target before cap and later name cannot erase it`() {
        val observation = ScanSummaryObservation(TargetSnapshot(1L, BandMatcher.TARGET_NAME)); val devices = DiscoveredDevices(100)
        repeat(100) { devices.add("address-$it", "Speaker", -50, "BLE") }
        val target = "11:22:33:44:35:BA"
        observation.admitted(target, BandMatcher.TARGET_NAME)
        devices.add(target, BandMatcher.TARGET_NAME, -40, "BLE")
        observation.admitted(target, "Speaker")
        devices.add(target, "Speaker", -40, "BLE")
        assertEquals(ScanSummary(100, true, true, "成功"), observation.close(devices.snapshot().size, devices.overflow, true, "成功"))
        assertNull(observation.close(0, false, false, "未执行"))
    }
    @Test fun `automatic observation latches target even rejected claim or arm and does not create new policy`() {
        val observation = ScanSummaryObservation(TargetSnapshot(1L, BandMatcher.TARGET_NAME)); val seen = mutableSetOf<String>(); val owner = RunOwnership()
        observation.admitted("11:22:33:44:35:BA", BandMatcher.TARGET_NAME)
        seen.add("11:22:33:44:35:BA")
        assertFalse(owner.claimMatch("not-owned"))
        assertEquals(ScanSummary(1, false, true, "失败（部分结果）"), observation.close(seen.size, false, true, "失败（部分结果）"))
        assertFalse(owner.current("not-owned"))
    }
    @Test fun `unexecuted and cancelled observation stay unknown or partial truthfully`() {
        assertEquals(ScanSummary(null, false, null, "未执行"), ScanSummaryObservation().close(0, false, false, "未执行"))
        assertEquals(ScanSummary(null, false, null, "未执行"), ScanSummaryObservation().close(0, false, false, "取消（部分结果）"))
        assertEquals(ScanSummary(null, false, null, "状态未确认"), ScanSummaryObservation().close(0, false, false, "状态未确认"))
        assertEquals(ScanSummary(0, false, false, "取消（部分结果）"), ScanSummaryObservation(TargetSnapshot(1L, BandMatcher.TARGET_NAME)).close(0, false, true, "取消（部分结果）"))
        val window = ManualObservationWindow(); window.captureEntry(1_000_000_000L); var expired = 0
        assertTrue(window.admit(1_500_000_000L, 2_000_000_000L, 3_000_000_000L) { expired++ })
        assertFalse(window.admit(1_500_000_000L, 2_000_000_000L, 9_000_000_000L) { expired++ })
        assertEquals(1, expired)
    }
    @Test fun `details remove scan card and log body with exactly one asynchronous resume refresh`() {
        val activity = source("PermissionActivity.kt")
        for (obsolete in listOf("manualBodies", "DetailsText.manual", "logBody", "renderLogStatus", "logRepaint", "MibandLog.status()", "postDelayed"))
            assertFalse(obsolete, activity.contains(obsolete))
        assertTrue(activity.contains("button(\"日志权限\")"))
        assertTrue(activity.contains("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION")); assertTrue(activity.contains("ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION"))
        assertEquals(1, Regex("MibandLog\\.refreshAccess\\(\\)").findAll(activity).count())
        assertTrue(activity.substringAfter("override fun onResume()").substringBefore("override fun onPause").contains("MibandLog.refreshAccess()"))
        assertTrue(activity.contains("orientation = LinearLayout.HORIZONTAL")); assertTrue(activity.contains("LinearLayout.LayoutParams(0,")); assertTrue(activity.contains("1f"))
        assertFalse(activity.contains("HorizontalScrollView"))
        val render = activity.substringAfter("private fun render(snapshot:").substringBefore("override fun onRequestPermissionsResult")
        assertFalse(render.contains("Notifier")); assertFalse(render.contains("Toast"))
    }
    @Test fun `process start only primary UID guarded application and synchronous NoDisplay launch never logs ingress`() {
        val application = source("MonitorApplication.kt")
        val guard = application.indexOf("Process.myUid() != applicationInfo.uid")
        val logger = application.indexOf("MibandLog.initialize(this)")
        assertTrue(guard >= 0 && logger > guard)
        assertTrue(application.indexOf("CompactLogEntry.ProcessStarted") > logger)
        assertTrue(application.indexOf("coordinator = ResetCoordinator(this)") > logger)
        val main = source("MainActivity.kt")
        assertTrue(main.contains("if (savedInstanceState == null)")); assertFalse(main.contains("setContentView"))
        assertEquals(1, Regex("coordinator\\.launch\\(").findAll(main).count())
        assertTrue(main.indexOf("finish()") > main.indexOf("coordinator.launch("))
        assertFalse(main.contains("MibandLog")); assertFalse(main.contains("UUID"))
    }
    @Test fun `all files access remains explicit and no background Bluetooth logging permission path`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE")); assertFalse(manifest.contains("requestLegacyExternalStorage"))
        for (file in listOf("MainActivity.kt", "ResetCoordinator.kt", "ResetService.kt", "TriggerReceiver.kt", "ReEnableReceiver.kt")) {
            assertFalse(source(file).contains("ACTION_MANAGE_")); assertFalse(source(file).contains("MibandLog.refreshAccess()"))
        }
        assertTrue(source("ResetCoordinator.kt").contains("audit: AuditSink = MibandLog"))
    }
    @Test fun `detail producers are absent not filtered at sink and original backend is direct`() {
        val files = listOf("MainActivity.kt", "TriggerReceiver.kt", "ReEnableReceiver.kt", "ResetService.kt", "ResetCoordinator.kt", "RuntimeOwnership.kt", "ManualBleScanner.kt", "BandResetController.kt", "ManualOperationController.kt", "DurableResetEngine.kt", "PermissionActivity.kt")
        for (file in files) {
            val text = source(file)
            for (obsolete in listOf("runtimeAudit.backend", "RuntimeIdentity", "auditOperation(", "ble.advertisement", "automatic.terminal.save", "receiver.ingress", "activity.dispatch", "log.permission.settings", "intent.extras", "Intent.extras"))
                assertFalse("$file $obsolete", text.contains(obsolete))
        }
        assertTrue(source("ResetCoordinator.kt").contains("BluetoothSwitchDriver(backend)"))
        val scan = source("ManualBleScanner.kt")
        val manualAnnotation = scan.indexOf("summaryObservation.admitted(")
        assertTrue(manualAnnotation >= 0 && manualAnnotation < scan.indexOf("devices.add("))
        val auto = source("BandResetController.kt")
        val automaticAnnotation = auto.indexOf("summaryObservation.admitted(")
        assertTrue(automaticAnnotation >= 0 && automaticAnnotation < auto.indexOf("ownership.claimMatch(runId)"))
    }
    private fun source(name: String) = File("src/main/java/com/blreay/bluetoothmonitor/$name").readText()
}
