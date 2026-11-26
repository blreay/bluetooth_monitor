package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

/** Exercises the controller that ResetCoordinator owns, not a second policy imitation. */
class DetailsSnapshotTest {
    private class Runtime : ManualRuntime {
        var fgsWorks = true
        val starts = mutableListOf<String>()
        val commands = mutableListOf<ManualOperation>()
        val results = mutableListOf<(SwitchOutcome) -> Unit>()
        val entries = mutableListOf<(Long) -> Unit>()
        val events = mutableListOf<(BackendEntry) -> Unit>()
        val samples = mutableListOf<(StateSample) -> Unit>()
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val ended = mutableListOf<String>()
        var scanner: ManualScanEvents? = null
        var retired = 0
        override fun requestForeground(operation: ManualOperation): Boolean { starts.add(operation.operationId); return fgsWorks }
        override fun execute(operation: ManualOperation, lease: CommandLease, ordinaryOnly: Boolean,
                             event: (BackendEntry) -> Unit, entered: (Long) -> Unit,
                             nextAttempt: (SwitchOutcome) -> Boolean, result: (SwitchOutcome) -> Unit) {
            commands.add(operation); events.add(event); entries.add(entered); results.add(result)
        }
        override fun sample(lease: CommandLease, result: (StateSample) -> Unit) { samples.add(result) }
        override fun scan(operation: ManualOperation, lease: CommandLease, events: ManualScanEvents) { scanner = events }
        override fun retireScan(operationId: String) { retired++ }
        override fun later(delayMs: Long, task: () -> Unit) { timers.add(delayMs to task) }
        override fun endForeground(operationId: String) { ended.add(operationId) }
    }
    private class Fixture {
        var now = 1000L
        val storage = MemoryResetStorage()
        val store = CheckedResetStore(storage)
        val runtime = Runtime()
        var owners = OperationAdmission()
        var serial = 0
        val controller = ManualOperationController(store, { owners }, runtime, { now }, { now }, { "id-${++serial}" })
        fun start(action: ManualAction = ManualAction.OFF): ManualOperation {
            assertTrue(controller.request(action, true, true))
            val op = store.snapshot().manualFence!!
            controller.serviceStarted(op.operationId, CommandLease({ now }, now + 45_000L))
            return op
        }
    }
    @Test fun `visible manual request persists fence then waits exact foreground success before adapter work`() {
        val f = Fixture()
        assertTrue(f.controller.request(ManualAction.OFF, true, true))
        val op = f.store.snapshot().manualFence!!
        assertEquals(listOf(op.operationId), f.runtime.starts)
        assertTrue(f.runtime.commands.isEmpty())
        f.controller.serviceStarted("stale", CommandLease.foreground())
        assertTrue(f.runtime.commands.isEmpty())
        f.controller.serviceStarted(op.operationId, CommandLease.foreground())
        assertEquals(1, f.runtime.commands.size)
        assertFalse(f.store.snapshot().manualFence!!.authorityEnded)
    }
    @Test fun `all automatic setup service scan retirement pending and durable owners reject manual new work`() {
        val blockers = listOf(OperationAdmission(automaticOwner = true), OperationAdmission(setupWaiting = true),
            OperationAdmission(serviceOwned = true), OperationAdmission(activeScan = true), OperationAdmission(scanRetirement = true),
            OperationAdmission(pending = ResetRecord("auto", "command")), OperationAdmission(storageFailed = true),
            OperationAdmission(integrityBlocked = true))
        for (blocker in blockers) {
            val f = Fixture(); f.owners = blocker
            assertFalse(f.controller.request(ManualAction.ON, true, true))
            assertNull(f.store.snapshot().manualFence)
            assertTrue(f.runtime.commands.isEmpty()); assertTrue(f.runtime.starts.isEmpty())
        }
        val f = Fixture(); f.start()
        assertFalse(f.controller.request(ManualAction.OFF, true, true))
        assertFalse(f.controller.request(ManualAction.ON, true, true))
        assertEquals(1, f.runtime.commands.size)
        assertEquals(ManualAction.OFF, f.controller.activeResult()!!.action)
        assertTrue(f.controller.rejectionNotices().isNotEmpty())
    }
    @Test fun `manual off observes actual off and never creates automatic pending alarm ledger or on`() {
        val f = Fixture(); val op = f.start()
        f.runtime.entries.single()(f.now)
        f.runtime.results.single()(SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL))
        assertNotEquals(ManualResultStatus.COMPLETED, f.controller.activeResult()!!.status)
        f.now++
        f.runtime.samples.last()(StateSample(AdapterState.OFF, f.now))
        assertNull(f.store.snapshot().manualFence)
        assertEquals(ManualResultStatus.COMPLETED, f.store.snapshot().manualResults[ManualAction.OFF]!!.status)
        assertEquals(op.operationId, f.store.snapshot().manualResults[ManualAction.OFF]!!.operationId)
        assertNull(f.store.snapshot().pending); assertTrue(f.store.snapshot().history.rows.isEmpty())
        assertEquals(0L, f.store.snapshot().history.total)
        assertEquals(listOf(ManualAction.OFF), f.runtime.commands.map { it.action })
    }
    @Test fun `uncertain native late proof only reconciles same fence after service lease end without new work`() {
        val f = Fixture(); val op = f.start()
        f.runtime.entries.single()(f.now)
        f.runtime.results.single()(SwitchOutcome.uncertain(SwitchMode.SHIZUKU_SHELL, "RPC still entered"))
        f.controller.serviceEnded(op.operationId)
        assertFalse(f.store.snapshot().manualFence!!.authorityEnded)
        assertFalse(f.controller.request(ManualAction.ON, true, true))
        val samples = f.runtime.samples.size
        f.now += 50_000L
        f.runtime.results.single()(SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL))
        assertNull(f.store.snapshot().manualFence)
        assertEquals(samples, f.runtime.samples.size)
        assertEquals(1, f.runtime.commands.size)
        assertNotEquals(ManualResultStatus.COMPLETED, f.store.snapshot().manualResults[ManualAction.OFF]!!.status)
    }
    @Test fun `cold manual fence is never replayed or implicitly released`() {
        val f = Fixture(); val op = f.start()
        val cold = ManualOperationController(CheckedResetStore(f.storage), { OperationAdmission() }, f.runtime,
            { f.now }, { f.now }, { "cold" })
        cold.serviceStarted(op.operationId, CommandLease.foreground())
        assertFalse(cold.request(ManualAction.ON, true, true))
        assertNotNull(f.store.snapshot().manualFence)
        assertEquals(1, f.runtime.commands.size)
    }
    @Test fun `failed persistence or foreground admission and nonvisible requests never enter driver`() {
        val invisible = Fixture()
        assertFalse(invisible.controller.request(ManualAction.ON, false, true))
        assertFalse(invisible.controller.request(ManualAction.ON, true, false))
        assertTrue(invisible.runtime.starts.isEmpty())
        val failed = Fixture(); failed.storage.works = false
        assertFalse(failed.controller.request(ManualAction.OFF, true, true))
        assertTrue(failed.runtime.commands.isEmpty()); assertTrue(failed.runtime.starts.isEmpty())
        val fgs = Fixture(); fgs.runtime.fgsWorks = false
        assertFalse(fgs.controller.request(ManualAction.OFF, true, true))
        assertNull(fgs.store.snapshot().manualFence); assertTrue(fgs.runtime.commands.isEmpty())
    }
    @Test fun `failed native completion commit prohibits new adapter sampling and retains fence`() {
        val f = Fixture(); f.start()
        f.runtime.entries.single()(f.now)
        val confirmed = f.store.snapshot().manualFence
        f.storage.works = false; f.storage.eagerFailure = true
        f.runtime.results.single()(SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL))
        assertEquals(confirmed, f.store.snapshot().manualFence)
        assertTrue(f.store.snapshot().storageFailed)
        assertTrue("failed storage must not authorize new observation work", f.runtime.samples.isEmpty())
        assertFalse(f.controller.request(ManualAction.ON, true, true))
        assertTrue(f.controller.rejectionNotices().isNotEmpty())
    }
    @Test fun `manual foreground notification cannot present its owner as automatic target reset or recovery`() {
        val automatic = DetailsText.foreground(ForegroundAdmission.Owner.AUTOMATIC)
        val manual = DetailsText.foreground(ForegroundAdmission.Owner.MANUAL)
        assertTrue(automatic.second.contains("仅命中目标广播才切换"))
        assertTrue(manual.first.contains("手动"))
        assertTrue(manual.second.contains("不自动重置"))
        assertTrue(manual.second.contains("不创建自动恢复/计数"))
        assertFalse(manual.second.contains("仅命中目标广播才切换"))
    }
    @Test fun `manual details retain independent backend decision reasons after actual state observation`() {
        val f = Fixture(); f.start()
        f.runtime.events.single()(BackendEntry(SwitchMode.NONE, "Shizuku unavailable reason"))
        f.runtime.results.single()(SwitchOutcome.submitted(SwitchMode.ORDINARY, "ordinary submitted reason"))
        f.runtime.samples.last()(StateSample(AdapterState.OFF, f.now))
        val snapshot = DetailsSnapshot(f.store.snapshot(), "automatic status", "authorization", null, emptyMap())
        val text = DetailsText.manual(ManualAction.OFF, snapshot)
        assertTrue("completed manual result must retain actual decision reason", text.contains("Shizuku unavailable reason"))
        assertTrue(text.contains("ordinary submitted reason"))
        assertTrue(DetailsText.manual(ManualAction.ON, snapshot).contains("尚未请求"))
    }
    @Test fun `manual ordinary fallback uses captured sample elapsed time not delayed main delivery`() {
        val f = Fixture(); f.start()
        f.runtime.results.single()(SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL))
        f.runtime.samples.last()(StateSample(AdapterState.ON, f.now, 100L))
        f.now += 10_000L
        f.runtime.timers.last { it.first == 500L }.second()
        f.runtime.samples.last()(StateSample(AdapterState.ON, f.now, 101L))
        assertEquals("two captured wrong-state samples only one ms apart are not ten seconds", 1, f.runtime.commands.size)
        f.now++
        f.runtime.timers.last { it.first == 500L }.second()
        f.runtime.samples.last()(StateSample(AdapterState.ON, f.now, 10_100L))
        assertEquals(2, f.runtime.commands.size)
        assertEquals(listOf(ManualAction.OFF, ManualAction.OFF), f.runtime.commands.map { it.action })
    }
    @Test fun `same target ordinary fallback waits ten seconds stable wrong and rotates command without fence release`() {
        val f = Fixture(); f.start()
        f.runtime.entries.single()(f.now)
        f.runtime.results.single()(SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL))
        f.runtime.samples.last()(StateSample(AdapterState.ON, f.now))
        val first = f.store.snapshot().manualFence!!
        f.now += 9_999L
        f.runtime.timers.last { it.first == 500L }.second()
        f.runtime.samples.last()(StateSample(AdapterState.ON, f.now))
        assertEquals(1, f.runtime.commands.size)
        f.now++
        f.runtime.timers.last { it.first == 500L }.second()
        f.runtime.samples.last()(StateSample(AdapterState.ON, f.now))
        assertEquals(2, f.runtime.commands.size)
        val second = f.store.snapshot().manualFence!!
        assertEquals(first.operationId, second.operationId)
        assertNotEquals(first.commandId, second.commandId)
        assertEquals(listOf(ManualAction.OFF, ManualAction.OFF), f.runtime.commands.map { it.action })
        f.runtime.results.first()(SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL))
        assertEquals(second, f.store.snapshot().manualFence)
    }
    @Test fun `coordinator gate blocks new work but preserves only the exact pending recovery`() {
        var state = OperationAdmission()
        val gate = RuntimeOperationGate { state }
        assertTrue(gate.newRequest())
        state = state.copy(manualFence = ManualOperation("manual", "command", action = ManualAction.OFF))
        assertFalse(gate.newRequest()); assertFalse(gate.recovery("auto"))
        state = OperationAdmission(pending = ResetRecord("auto", "command"), automaticOwner = true, serviceOwned = true)
        assertFalse(gate.newRequest()); assertTrue(gate.recovery("auto")); assertFalse(gate.recovery("other"))
        assertTrue(gate.automaticContinuation("auto", "auto"))
        assertFalse(gate.automaticContinuation("other", "auto"))
        state = state.copy(scanRetirement = true)
        assertFalse(gate.recovery("auto"))
        state = OperationAdmission(setupWaiting = true, automaticOwner = true)
        assertFalse(gate.newRequest()); assertTrue(gate.automaticContinuation("setup", "setup", true))
        assertFalse(gate.automaticContinuation("other", "setup", true))
    }
    @Test fun `visible snapshot recreation and closing presentation do not cancel application command`() {
        val f = Fixture(); val op = f.start()
        val old = ManualResult("old-on", ManualAction.ON, ManualResultStatus.COMPLETED, "old actual ON")
        val history = ResetHistory().accept(AcceptedResetMatch("selected", "target", "address", null, "own scan", 10L))
        val snapshot = { DetailsSnapshot(f.store.snapshot().copy(history = history, manualResults = mapOf(ManualAction.ON to old)),
            "current automatic", "current auth", f.controller.activeResult(), f.controller.rejectionNotices()) }
        val subscription = VisibleDetailsSubscription(snapshot)
        val first = mutableListOf<DetailsSnapshot>()
        val token = subscription.attach { first.add(it) }
        assertEquals(1, first.size)
        assertEquals(op.operationId, first.single().manual(ManualAction.OFF)!!.operationId)
        assertEquals("old-on", first.single().manual(ManualAction.ON)!!.operationId)
        assertEquals("selected", first.single().selected("selected")!!.runId)
        subscription.detach(token)
        subscription.publish(); assertEquals(1, first.size)
        assertFalse(f.store.snapshot().manualFence!!.authorityEnded)
        val recreated = mutableListOf<DetailsSnapshot>()
        subscription.attach { recreated.add(it) }
        assertEquals(1, recreated.size)
        assertEquals(op.operationId, recreated.single().activeManual!!.operationId)
        assertEquals(1, f.runtime.commands.size)
    }
    @Test fun `old outstanding adapter sample cannot starve new exact manual operation after service end`() {
        val f = Fixture(); val old = f.start()
        f.runtime.results.single()(SwitchOutcome.submitted(SwitchMode.ORDINARY))
        val oldSample = f.runtime.samples.single()
        f.controller.serviceEnded(old.operationId)
        assertNull(f.store.snapshot().manualFence)
        val newer = f.start(ManualAction.ON)
        f.runtime.results.last()(SwitchOutcome.submitted(SwitchMode.ORDINARY))
        assertEquals("new owner must get its own bounded state sample", 2, f.runtime.samples.size)
        oldSample(StateSample(AdapterState.ON, f.now))
        assertEquals(newer.operationId, f.store.snapshot().manualFence!!.operationId)
        f.runtime.samples.last()(StateSample(AdapterState.ON, f.now + 1))
        assertNull(f.store.snapshot().manualFence)
        assertEquals(newer.operationId, f.store.snapshot().manualResults[ManualAction.ON]!!.operationId)
    }
    @Test fun `hung manual scan preparation preserves cold fence until exact late preparation proof without start time`() {
        val f = Fixture(); val op = f.start(ManualAction.SCAN)
        val events = f.runtime.scanner!!
        f.controller.serviceEnded(op.operationId)
        assertFalse(f.store.snapshot().manualFence!!.authorityEnded)
        assertNull(f.store.snapshot().manualFence!!.scannerIdentity)
        val cold = ManualOperationController(CheckedResetStore(f.storage), { OperationAdmission() }, f.runtime,
            { f.now }, { f.now }, { "cold" })
        assertFalse(cold.request(ManualAction.ON, true, true))
        events.preparationEnded(f.now + 5_000)
        assertNull(f.store.snapshot().manualFence)
        val result = f.store.snapshot().manualResults[ManualAction.SCAN]!!
        assertEquals(ManualResultStatus.FAILED, result.status)
        assertNull(result.scanStartedAt); assertNull(result.scanReturnedAt)
        assertEquals(f.now + 5_000, result.authorityEndedAt)
        assertTrue(f.runtime.commands.isEmpty()); assertEquals(0L, f.store.snapshot().history.total)
    }
    @Test fun `late manual scan start needs both native return and exact cleanup even after window destruction`() {
        val f = Fixture(); val op = f.start(ManualAction.SCAN)
        val events = f.runtime.scanner!!
        assertTrue(events.registered("scanner-ticket")); events.entered(f.now)
        f.controller.serviceEnded(op.operationId)
        events.retired("scanner-ticket")
        assertNotNull(f.store.snapshot().manualFence)
        assertFalse(f.controller.request(ManualAction.ON, true, true))
        events.returned(f.now + 50_000, true)
        assertNull(f.store.snapshot().manualFence)
        assertEquals(ManualResultStatus.FAILED, f.store.snapshot().manualResults[ManualAction.SCAN]!!.status)
        val newer = f.start(ManualAction.SCAN)
        events.returned(f.now + 60_000, false); events.retired("scanner-ticket")
        assertEquals(newer.operationId, f.store.snapshot().manualFence!!.operationId)
        assertFalse(f.store.snapshot().manualFence!!.authorityEnded)
        assertTrue(f.runtime.commands.isEmpty())
    }
    @Test fun `failed scan start and permission revoked stop keep actual retirement until explicit successful retry`() {
        val f = Fixture(); f.start(ManualAction.SCAN)
        val events = f.runtime.scanner!!
        val queue = java.util.ArrayDeque<() -> Unit>()
        var permitted = false
        val retirement = ScanRegistrationRetirement({ f.now }, { queue.add(it); true },
            onRetired = { events.retired(ManualBleScanner.identity(it)) }, onFailure = { _, _ -> })
        val ticket = retirement.claim(f.store.snapshot().manualFence!!.operationId) { permitted }!!
        assertTrue(events.registered(ManualBleScanner.identity(ticket)))
        assertFalse(retirement.start(ticket) { events.entered(f.now); false })
        events.returned(f.now + 1, false)
        queue.removeFirst()()
        assertTrue(retirement.blocked()); assertNotNull(f.store.snapshot().manualFence)
        assertTrue(f.store.snapshot().manualFence!!.authorityEnded)
        assertFalse(f.store.snapshot().manualFence!!.scannerRetired)
        assertFalse(f.controller.request(ManualAction.ON, true, true))
        permitted = true; assertTrue(retirement.retryRetirement()); queue.removeFirst()()
        assertFalse(retirement.blocked()); assertNull(f.store.snapshot().manualFence)
        assertEquals(ManualResultStatus.FAILED, f.store.snapshot().manualResults[ManualAction.SCAN]!!.status)
        assertTrue(f.runtime.commands.isEmpty()); assertNull(f.store.snapshot().pending)
    }
    @Test fun `scan registration write failure retains confirmed dispatch fence and never marks success`() {
        val f = Fixture(); f.start(ManualAction.SCAN)
        val events = f.runtime.scanner!!; val confirmed = f.store.snapshot().manualFence
        f.storage.works = false; f.storage.eagerFailure = true
        assertFalse(events.registered("claimed-never-entered"))
        events.finished("registration write failed", true)
        events.preparationEnded(null)
        assertEquals(confirmed, f.store.snapshot().manualFence)
        assertTrue(f.store.snapshot().storageFailed)
        assertNotEquals(ManualResultStatus.COMPLETED, f.controller.activeResult()!!.status)
        assertTrue(f.runtime.commands.isEmpty())
        assertTrue(f.runtime.retired > 0)
    }
    @Test fun `manual target annotation uses actual configured advertisement matcher and persists without reset work`() {
        val f = Fixture(); f.start(ManualAction.SCAN)
        val events = f.runtime.scanner!!
        assertTrue(events.registered("exact-ticket")); events.entered(f.now); events.returned(f.now + 1, true)
        val devices = DiscoveredDevices(100).apply {
            add("11:22:33:44:35:BA", BandMatcher.TARGET_NAME, -42, "BLE")
            add("22:22:33:44:35:BA", null, -50, "BLE")
            add("33:22:33:44:35:BA", "Headphones", -30, "BLE")
            add("44:22:33:44:35:BA", "未知设备", -60, "BLE")
            add("55:22:33:44:00:01", "Speaker", -70, "BLE")
        }.snapshot()
        events.devices(devices, false)
        fun text(store: ResetStoreSnapshot, live: ManualResult? = null) = DetailsText.manual(ManualAction.SCAN,
            DetailsSnapshot(store, "automatic", "authorization", live, emptyMap()))
        fun assertMarkers(rendered: String) {
            assertTrue(rendered.contains("【目标手环，仅展示】 ${BandMatcher.TARGET_NAME} 11:22:33:44:35:BA"))
            assertTrue(rendered.contains("【目标手环，仅展示】 未知设备 22:22:33:44:35:BA"))
            for (address in listOf("33:22:33:44:35:BA", "44:22:33:44:35:BA", "55:22:33:44:00:01"))
                assertFalse(rendered.lines().single { it.contains(address) }.contains("【目标手环"))
        }
        assertMarkers(text(f.store.snapshot(), f.controller.activeResult()))
        events.finished("BLE observation complete", false); events.retired("exact-ticket")
        assertNull(f.store.snapshot().manualFence)
        assertMarkers(text(f.store.snapshot()))
        assertMarkers(text(CheckedResetStore(f.storage).snapshot()))
        assertTrue(f.runtime.commands.isEmpty()); assertTrue(f.runtime.samples.isEmpty())
        assertNull(f.store.snapshot().pending); assertTrue(f.store.snapshot().history.rows.isEmpty())
        assertEquals(0L, f.store.snapshot().history.total)
    }
    @Test fun `manual target advertisement only displays and exact retirement is required`() {
        val f = Fixture(); f.start(ManualAction.SCAN)
        val events = requireNotNull(f.runtime.scanner)
        assertTrue(events.registered("exact-ticket"))
        events.entered(f.now); events.returned(f.now + 1, true)
        val devices = DiscoveredDevices().apply { add("11:22:33:44:35:BA", "Xiaomi Smart Band 8 35BA", -42, "BLE") }.snapshot()
        events.devices(devices, false)
        events.finished("8秒观察结束", false)
        assertNotNull(f.store.snapshot().manualFence)
        assertFalse(f.controller.request(ManualAction.ON, true, true))
        assertTrue(f.runtime.commands.isEmpty()); assertNull(f.store.snapshot().pending)
        events.retired("wrong-ticket"); assertNotNull(f.store.snapshot().manualFence)
        events.retired("exact-ticket"); assertNull(f.store.snapshot().manualFence)
        assertEquals(0L, f.store.snapshot().history.total)
    }
}
