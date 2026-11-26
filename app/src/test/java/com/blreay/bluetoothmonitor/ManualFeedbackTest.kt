package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

/** The real checked controller, with only Android effects replaced. */
class ManualFeedbackTest {
    private class Runtime : ManualRuntime {
        var foreground = true
        var onForeground: ((ManualOperation) -> Unit)? = null
        var result: ((SwitchOutcome) -> Unit)? = null
        var entered: ((Long) -> Unit)? = null
        var next: ((SwitchOutcome) -> Boolean)? = null
        var event: ((BackendEntry) -> Unit)? = null
        val samples = mutableListOf<(StateSample) -> Unit>()
        var scan: ManualScanEvents? = null
        var scanLease: CommandLease? = null
        var calls = 0
        var retirements = 0
        var onRetire: (() -> Unit)? = null
        val later = mutableListOf<() -> Unit>()
        override fun requestForeground(operation: ManualOperation): Boolean { onForeground?.invoke(operation); return foreground }
        override fun execute(operation: ManualOperation, lease: CommandLease, ordinaryOnly: Boolean,
                             event: (BackendEntry) -> Unit, entered: (Long) -> Unit,
                             nextAttempt: (SwitchOutcome) -> Boolean, result: (SwitchOutcome) -> Unit) {
            calls++; this.result = result; this.entered = entered; next = nextAttempt; this.event = event
        }
        override fun sample(lease: CommandLease, result: (StateSample) -> Unit) { samples.add(result) }
        override fun scan(operation: ManualOperation, lease: CommandLease, events: ManualScanEvents) { scan = events; scanLease = lease }
        override fun retireScan(operationId: String) { retirements++; onRetire?.invoke() }
        override fun later(delayMs: Long, task: () -> Unit) { later.add(task) }
        override fun endForeground(operationId: String) { }
    }
    private class Fixture(throwing: Boolean = false) {
        var now = 1000L
        var serial = 0
        val storage = MemoryResetStorage()
        val store = CheckedResetStore(storage)
        val runtime = Runtime()
        val messages = mutableListOf<ManualFeedback>()
        val rows = mutableListOf<CompactLogEntry>()
        val controller = ManualOperationController(store, { OperationAdmission() }, runtime, { now }, { now },
            { "id-${++serial}" }, feedback = { messages.add(it); if (throwing) error("feedback") },
            audit = AuditSink { rows.add(it); if (throwing) error("sink") })
        fun start(action: ManualAction = ManualAction.OFF): ManualOperation {
            assertTrue(controller.request(action, true, true))
            return store.snapshot().manualFence!!.also { controller.serviceStarted(it.operationId, CommandLease.foreground()) }
        }
        fun complete(state: AdapterState = AdapterState.OFF) {
            runtime.entered!!(now)
            runtime.result!!(SwitchOutcome.submitted(SwitchMode.ORDINARY).copy(returnedAt = now + 1, submittedAt = now + 1))
            runtime.samples.last()(StateSample(state, now + 2, 42))
        }
    }
    /** Replace Android dispatch/native effects only; delivery, annotations, owner and controller are production seams. */
    private class ScanStartedFixture {
        val f = Fixture()
        val operation = f.start(ManualAction.SCAN)
        val events = requireNotNull(f.runtime.scan)
        val lease = requireNotNull(f.runtime.scanLease)
        val observation = ScanSummaryObservation()
        val window = ManualObservationWindow()
        val devices = DiscoveredDevices(100)
        val owner = ExactScannerOwner<ScanRegistrationRetirement.Ticket> { it }
        val main = java.util.ArrayDeque<() -> Unit>()
        val cleanup = java.util.ArrayDeque<() -> Unit>()
        val order = mutableListOf<String>()
        var finished = false
        var stops = 0
        val retirement = ScanRegistrationRetirement({ f.now }, { cleanup.add(it); true },
            onRetired = { exact -> main.add {
                owner.retired(exact) { events.retired(ManualBleScanner.identity(it)) }
            } }, onFailure = { _, reason -> fail(reason) })
        val ticket = requireNotNull(retirement.claim(operation.operationId) { stops++; true })
        init {
            owner.install(operation.operationId, ticket)
            assertTrue(events.registered(ManualBleScanner.identity(ticket)))
            f.runtime.onRetire = { owner.retire(operation.operationId) { retire() } }
        }
        fun startReturned(ok: Boolean = false, capture: Boolean = true): ManualScanStartedDelivery.Started {
            assertEquals(ok, retirement.start(ticket) {
                if (capture) window.captureEntry(1_000_000_000L)
                main.add { events.entered(f.now) }
                ok
            })
            return ManualScanStartedDelivery.Started(ok, f.now + 1, true)
        }
        fun admit(address: String, name: String?) {
            assertTrue(lease.valid())
            assertTrue(window.admit(1_000_000_001L, 1_000_000_002L, 1_000_000_003L) { fail("window expired") })
            observation.admitted(address, name)
            devices.add(address, name, -42, "BLE")
        }
        private fun close(result: String, start: ManualScanStartedDelivery.Started? = null) {
            finished = true
            val snapshot = devices.snapshot()
            observation.closeManual(snapshot.size, devices.overflow, window.capturedEntryElapsedNanos() != null,
                true, result, start)?.let {
                order.add("summary:${it.result}")
                events.devices(snapshot, devices.overflow)
                events.summary(it, snapshot)
            }
        }
        private fun failed(start: ManualScanStartedDelivery.Started) {
            if (finished) return
            close("失败（部分结果）", start)
            order.add("finished")
            events.finished("start failed or window ended", true)
            retire()
        }
        fun retire() { close("取消（部分结果）"); retirement.retire(ticket) }
        fun deliver(start: ManualScanStartedDelivery.Started, late: Boolean = false) {
            ManualScanStartedDelivery.deliver(start, late, { finished }, { lease.valid() },
                { at, ok -> order.add("returned:$ok"); events.returned(at, ok) }, ::failed, ::retire,
                { order.add("observing") })
        }
        fun drainMain() { while (main.isNotEmpty()) main.removeFirst()() }
        fun drainCleanup() { while (cleanup.isNotEmpty()) cleanup.removeFirst()() }
        fun assertNoReset() {
            assertEquals(0, f.runtime.calls)
            assertNull(f.store.snapshot().pending)
            assertEquals(0L, f.store.snapshot().history.total)
        }
    }
    @Test fun `production start failure publishes summary before returned can synchronously retire retained scanner`() {
        val s = ScanStartedFixture(); val start = s.startReturned(); s.drainMain()
        s.admit("11:22:33:44:35:BA", BandMatcher.TARGET_NAME)
        s.admit("00:00:00:00:00:02", null)
        val snapshot = s.devices.snapshot()
        s.deliver(start)
        assertTrue(s.order.indexOf("summary:失败（部分结果）") >= 0)
        assertTrue(s.order.indexOf("summary:失败（部分结果）") < s.order.indexOf("returned:false"))
        assertEquals(1, s.order.count { it.startsWith("summary:") })
        assertTrue(s.f.store.snapshot().manualFence!!.authorityEnded)
        assertFalse(s.f.store.snapshot().manualFence!!.scannerRetired)
        assertFalse(s.lease.valid())
        assertFalse(s.f.messages.any { it.type == ManualFeedbackType.TERMINAL })
        s.drainCleanup(); s.drainMain()
        assertEquals(listOf(CompactLogEntry.ScanEnded(2, false, true, "失败（部分结果）")), s.f.rows)
        assertEquals(ScanSummary(2, false, true, "失败（部分结果）"), s.f.messages.last().scanSummary)
        assertEquals(ManualResultStatus.FAILED, s.f.messages.last().status)
        assertEquals(snapshot, CheckedResetStore(s.f.storage).snapshot().manualResults[ManualAction.SCAN]!!.devices)
        assertEquals(1, s.stops); assertNull(s.f.store.snapshot().manualFence)
        s.assertNoReset()
    }
    @Test fun `production failure survives exact cleanup before Started delivery with capped final snapshot and sticky target`() {
        val s = ScanStartedFixture(); val start = s.startReturned(); s.drainMain()
        repeat(100) { s.admit("00:00:00:00:00:${it.toString(16).padStart(2, '0')}", null) }
        s.admit("11:22:33:44:35:BA", BandMatcher.TARGET_NAME) // target 101, before the original cap
        val snapshot = s.devices.snapshot()
        s.drainCleanup(); s.drainMain() // real stop and owner removal before the bounded worker result
        assertNull(s.owner.current)
        assertTrue(s.f.store.snapshot().manualFence!!.scannerRetired)
        assertFalse(s.f.store.snapshot().manualFence!!.authorityEnded)
        assertFalse(s.f.messages.any { it.type == ManualFeedbackType.TERMINAL })
        s.deliver(start)
        assertEquals(listOf(CompactLogEntry.ScanEnded(100, true, true, "失败（部分结果）")), s.f.rows)
        assertEquals("手动 SCAN：100+个设备，目标手环：有，失败（部分结果）", s.f.messages.last().conciseMessage())
        assertEquals(snapshot, CheckedResetStore(s.f.storage).snapshot().manualResults[ManualAction.SCAN]!!.devices)
        assertEquals(100, snapshot.size)
        assertEquals(ManualResultStatus.FAILED, s.f.messages.last().status)
        assertEquals(1, s.stops); assertNull(s.f.store.snapshot().manualFence)
        assertTrue(s.order.indexOf("summary:失败（部分结果）") < s.order.indexOf("returned:false"))
        s.assertNoReset()
    }
    @Test fun `production conclusive not invoked with allocated ticket reports unknown count target and not executed`() {
        val s = ScanStartedFixture()
        assertNotNull(s.owner.current); assertNull(s.window.capturedEntryElapsedNanos())
        s.deliver(ManualScanStartedDelivery.Started(false, null, true)); s.drainMain()
        assertEquals(listOf(CompactLogEntry.ScanEnded(null, false, null, "未执行")), s.f.rows)
        assertEquals("手动 SCAN：设备数未知，目标手环：未知，未执行", s.f.messages.last().conciseMessage())
        assertEquals(ManualResultStatus.FAILED, s.f.messages.last().status)
        assertNull(s.f.store.snapshot().manualResults[ManualAction.SCAN]!!.nativeEnteredAt)
        assertNull(s.f.store.snapshot().manualFence)
        assertEquals(0, s.stops); assertTrue(s.cleanup.isEmpty())
        assertEquals(listOf("summary:未执行", "finished", "returned:false"), s.order)
        s.assertNoReset()
    }
    @Test fun `production unresolved timeout without entry capture keeps authority fence and repeated late uncertainty cannot fake zero`() {
        val s = ScanStartedFixture(); val unresolved = ManualScanStartedDelivery.Started(false, null, false)
        s.deliver(unresolved); s.drainMain()
        val fence = s.f.store.snapshot().manualFence!!
        assertFalse(fence.authorityEnded); assertTrue(fence.scannerRetired)
        assertFalse(s.f.messages.any { it.type == ManualFeedbackType.TERMINAL })
        repeat(3) { s.deliver(unresolved, true) }
        assertEquals(fence, s.f.store.snapshot().manualFence)
        assertFalse(s.order.any { it.startsWith("returned:") })
        assertEquals(1, s.order.count { it == "summary:状态未确认" })
        s.f.controller.serviceEnded(s.operation.operationId)
        assertEquals(ScanSummary(null, false, null, "状态未确认"), s.f.messages.last().scanSummary)
        assertTrue(s.f.rows.all { it is CompactLogEntry.ScanEnded && it.count == null && it.targetSeen == null })
        assertFalse(s.f.messages.any { it.status == ManualResultStatus.COMPLETED })
        assertNotNull(s.f.store.snapshot().manualFence)
        assertEquals(0, s.stops)
        s.assertNoReset()
    }
    @Test fun `production late entered and ended start after timeout retains unknown summary and stale result cannot settle newer owner`() {
        val s = ScanStartedFixture()
        assertTrue(s.retirement.start(s.ticket) {
            s.deliver(ManualScanStartedDelivery.Started(false, null, false)) // native start still owns the real slot
            assertTrue(s.retirement.unresolved(s.ticket))
            assertFalse(s.f.store.snapshot().manualFence!!.authorityEnded)
            assertFalse(s.f.store.snapshot().manualFence!!.scannerRetired)
            s.window.captureEntry(1_000_000_000L) // existing source capture arrives after observation already closed
            s.main.add { s.events.entered(s.f.now) }
            true
        })
        s.drainMain(); s.drainCleanup(); s.drainMain()
        assertTrue(s.f.store.snapshot().manualFence!!.nativeEntered)
        assertTrue(s.f.store.snapshot().manualFence!!.scannerRetired)
        assertFalse(s.f.store.snapshot().manualFence!!.authorityEnded)
        assertFalse(s.f.messages.any { it.type == ManualFeedbackType.TERMINAL })
        val ended = ManualScanStartedDelivery.Started(true, s.f.now + 2, true)
        s.deliver(ended, true)
        assertNull(s.f.store.snapshot().manualFence)
        assertEquals(ManualResultStatus.FAILED, s.f.messages.last().status)
        assertEquals(ScanSummary(null, false, null, "状态未确认"), s.f.messages.last().scanSummary)
        assertEquals(listOf(CompactLogEntry.ScanEnded(null, false, null, "状态未确认")), s.f.rows)
        assertEquals(1, s.f.messages.count { it.type == ManualFeedbackType.TERMINAL })
        assertEquals(1, s.stops); s.assertNoReset()
        s.f.start(ManualAction.ON)
        val newer = s.f.store.snapshot().manualFence!!
        val messages = s.f.messages.toList(); val rows = s.f.rows.toList()
        repeat(3) { s.deliver(ended, true) }
        assertEquals(newer, s.f.store.snapshot().manualFence)
        assertEquals(messages, s.f.messages); assertEquals(rows, s.f.rows)
        assertEquals(1, s.f.runtime.calls)
    }
    @Test fun `production ended entry source still reports failed execution when observation clock capture is absent`() {
        val s = ScanStartedFixture(); val start = s.startReturned(capture = false); s.drainMain()
        assertNull(s.window.capturedEntryElapsedNanos())
        assertTrue(s.f.store.snapshot().manualFence!!.nativeEntered)
        s.drainCleanup(); s.drainMain(); s.deliver(start)
        assertEquals(listOf(CompactLogEntry.ScanEnded(0, false, false, "失败（部分结果）")), s.f.rows)
        assertEquals(ManualResultStatus.FAILED, s.f.messages.last().status)
        assertNull(s.f.store.snapshot().manualFence)
        s.assertNoReset()
    }
    @Test fun `production failure publication exception cannot suppress exact returned authority or retirement`() {
        val s = ScanStartedFixture(); val start = s.startReturned(); s.drainMain()
        var returned = false; var retired = false
        try {
            ManualScanStartedDelivery.deliver(start, false, { s.finished }, { s.lease.valid() },
                { at, ok -> returned = true; s.events.returned(at, ok) },
                { error("failed observation callback") },
                { retired = true; s.retire() }, { fail("failed start cannot observe") })
            fail("the original callback exception must still propagate")
        } catch (failure: IllegalStateException) {
            assertEquals("failed observation callback", failure.message)
        }
        assertTrue(returned); assertTrue(retired)
        assertTrue(s.f.store.snapshot().manualFence!!.authorityEnded)
        assertFalse(s.f.store.snapshot().manualFence!!.scannerRetired)
        s.drainCleanup(); s.drainMain()
        assertNull(s.f.store.snapshot().manualFence)
        assertEquals(1, s.stops)
        assertEquals(ManualResultStatus.FAILED, s.f.messages.last().status)
        assertFalse(s.f.messages.any { it.status == ManualResultStatus.COMPLETED })
        s.assertNoReset()
    }
    @Test fun `production successful registration return is not scan completion or a successful zero summary`() {
        val s = ScanStartedFixture(); val start = s.startReturned(ok = true); s.drainMain(); s.deliver(start)
        assertEquals(listOf("returned:true", "observing"), s.order)
        assertTrue(s.lease.valid())
        assertTrue(s.f.store.snapshot().manualFence!!.authorityEnded)
        assertFalse(s.f.store.snapshot().manualFence!!.scannerRetired)
        assertEquals(listOf(ManualFeedbackType.ACCEPTED), s.f.messages.map { it.type })
        assertTrue(s.f.rows.isEmpty()); assertTrue(s.cleanup.isEmpty())
        assertEquals(0, s.stops)
        s.assertNoReset()
    }
    @Test fun `checked acceptance is transient and submitted is not completion`() {
        val f = Fixture(); val op = f.start()
        assertEquals(listOf(ManualFeedbackType.ACCEPTED), f.messages.map { it.type })
        assertEquals(op.operationId, f.messages.single().operationId)
        assertNotEquals("", f.messages.single().requestId)
        f.runtime.result!!(SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL))
        assertEquals(1, f.messages.size)
        f.runtime.samples.last()(StateSample(AdapterState.OFF, 1234, 456))
        assertEquals(ManualResultStatus.COMPLETED, f.messages.last().status)
        assertEquals(AdapterState.OFF, f.messages.last().observedState)
        assertEquals(true, f.messages.last().authorityEnded)
        assertEquals(SwitchMode.SHIZUKU_SHELL, f.messages.last().mode)
        assertEquals(listOf(CompactLogEntry.BluetoothEnded(false, SwitchMode.SHIZUKU_SHELL, "成功")), f.rows)
    }
    @Test fun `each rejected click is independent and logs one not executed conclusion`() {
        val f = Fixture()
        repeat(3) { assertFalse(f.controller.request(ManualAction.ON, false, true)) }
        assertEquals(3, f.messages.size)
        assertEquals(3, f.messages.map { it.requestId }.distinct().size)
        assertTrue(f.messages.all { it.type == ManualFeedbackType.REJECTED })
        assertEquals(List(3) { CompactLogEntry.BluetoothEnded(true, SwitchMode.NONE, "未执行") }, f.rows)
        assertEquals(0, f.runtime.calls)
    }
    @Test fun `failed fence acceptance never says accepted or completed`() {
        val f = Fixture(); f.storage.works = false
        assertFalse(f.controller.request(ManualAction.OFF, true, true))
        assertEquals(listOf(ManualFeedbackType.REJECTED), f.messages.map { it.type })
        assertEquals(0, f.runtime.calls)
    }
    @Test fun `foreground false can already finish failed and gets truthful terminal message`() {
        val f = Fixture(); f.runtime.foreground = false
        assertFalse(f.controller.request(ManualAction.ON, true, true))
        assertEquals(listOf(ManualFeedbackType.ACCEPTED, ManualFeedbackType.TERMINAL), f.messages.map { it.type })
        assertEquals(ManualResultStatus.FAILED, f.messages.last().status)
        assertNull(f.store.snapshot().manualFence)
    }
    @Test fun `synchronous foreground completion cannot put accepted after terminal`() {
        val f = Fixture()
        f.runtime.onForeground = { op ->
            f.controller.serviceStarted(op.operationId, CommandLease.foreground())
            f.complete()
        }
        assertTrue(f.controller.request(ManualAction.OFF, true, true))
        assertEquals(listOf(ManualFeedbackType.ACCEPTED, ManualFeedbackType.TERMINAL), f.messages.map { it.type })
    }
    @Test fun `unknown quarantine and later ended terminal are distinct even with unknown state`() {
        val f = Fixture(); val op = f.start()
        f.runtime.entered!!(f.now)
        f.runtime.result!!(SwitchOutcome.uncertain(SwitchMode.SHIZUKU_SHELL, "unknown native"))
        f.controller.serviceEnded(op.operationId)
        f.runtime.result!!(SwitchOutcome.uncertain(SwitchMode.SHIZUKU_SHELL, "unknown native"))
        assertEquals(1, f.messages.count { it.type == ManualFeedbackType.QUARANTINED })
        assertFalse(f.store.snapshot().manualFence!!.authorityEnded)
        f.runtime.result!!(SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL))
        assertEquals(ManualFeedbackType.TERMINAL, f.messages.last().type)
        assertEquals(ManualResultStatus.FAILED, f.messages.last().status)
        assertEquals(true, f.messages.last().authorityEnded)
        assertNull(f.store.snapshot().manualFence)
    }
    @Test fun `failed terminal checked commit is not a completion message`() {
        val f = Fixture(); f.start()
        f.runtime.result!!(SwitchOutcome.submitted(SwitchMode.ORDINARY))
        f.storage.works = false
        f.runtime.samples.last()(StateSample(AdapterState.OFF, f.now))
        assertEquals(ManualFeedbackType.STORAGE_FAILED, f.messages.last().type)
        assertEquals(ManualResultStatus.UNCERTAIN, f.messages.last().status)
        assertFalse(f.messages.any { it.status == ManualResultStatus.COMPLETED })
        assertNotNull(f.store.snapshot().manualFence)
    }
    @Test fun `completed callbacks repeats service and cold presentation never replay feedback`() {
        val f = Fixture(); val op = f.start(); val result = f.runtime.result!!
        f.complete(); result(SwitchOutcome.submitted(SwitchMode.ORDINARY))
        f.controller.serviceEnded(op.operationId); f.controller.serviceStarted(op.operationId, CommandLease.foreground())
        assertEquals(2, f.messages.size)
        val coldMessages = mutableListOf<ManualFeedback>()
        val cold = ManualOperationController(CheckedResetStore(f.storage), { OperationAdmission() }, f.runtime,
            { f.now }, { f.now }, { "cold" }, feedback = { coldMessages.add(it) })
        val subscription = VisibleDetailsSubscription { DetailsSnapshot(f.store.snapshot(), "status", "auth", cold.activeResult(), cold.rejectionNotices()) }
        val token = subscription.attach { it.selected("history"); it.manual(ManualAction.OFF) }
        subscription.publish(); subscription.detach(token); subscription.attach { }
        cold.activeResult(); cold.rejectionNotices()
        assertTrue(coldMessages.isEmpty())
    }
    @Test fun `scan advertisements have no feedback and exact cleanup required for terminal`() {
        val f = Fixture(); f.start(ManualAction.SCAN); val e = f.runtime.scan!!
        assertTrue(e.registered("exact")); e.entered(1000); e.returned(1001, true)
        repeat(20) { e.devices(DiscoveredDevices().apply { add("11:22:33:44:35:BA", BandMatcher.TARGET_NAME, -42, "BLE") }.snapshot(), false) }
        assertEquals(1, f.messages.size)
        e.summary(ScanSummary(1, false, true, "成功"))
        e.finished("window ended", false)
        assertFalse(f.messages.any { it.status == ManualResultStatus.COMPLETED })
        e.retired("wrong"); assertNotNull(f.store.snapshot().manualFence)
        e.retired("exact"); e.retired("exact")
        assertEquals(1, f.messages.count { it.type == ManualFeedbackType.TERMINAL })
        assertEquals(ScanSummary(1, false, true, "成功"), f.messages.last().scanSummary)
        assertEquals("手动 SCAN：1个设备，目标手环：有，完成", f.messages.last().conciseMessage())
        assertEquals(listOf(CompactLogEntry.ScanEnded(1, false, true, "成功")), f.rows)
        assertEquals(0, f.runtime.calls)
        assertNull(f.store.snapshot().pending); assertEquals(0L, f.store.snapshot().history.total)
    }
    @Test fun `throwing observers do not change checked transactions native calls or scan retirement`() {
        val f = Fixture(true); f.start(); f.complete()
        assertEquals(1, f.runtime.calls); assertNull(f.store.snapshot().manualFence)
        assertFalse(f.store.snapshot().storageFailed)
        f.start(ManualAction.SCAN); val e = f.runtime.scan!!
        assertTrue(e.registered("exact")); e.entered(1000); e.returned(1001, true)
        e.finished("end", false); e.retired("exact")
        assertTrue(f.runtime.retirements > 0); assertNull(f.store.snapshot().manualFence)
        assertFalse(f.store.snapshot().storageFailed)
        assertEquals(2, f.messages.count { it.type == ManualFeedbackType.TERMINAL })
    }
    @Test fun `actual successful controller notifier payload distinguishes root shell and ordinary with key result reason`() {
        val payloads = listOf(SwitchMode.SHIZUKU_ROOT, SwitchMode.SHIZUKU_SHELL, SwitchMode.ORDINARY).map { mode ->
            val f = Fixture(); f.start()
            f.runtime.entered!!(f.now)
            f.runtime.result!!(SwitchOutcome.submitted(mode).copy(returnedAt = f.now + 1, submittedAt = f.now + 1))
            f.runtime.samples.last()(StateSample(AdapterState.OFF, f.now + 2, 42))
            val terminal = f.messages.last()
            assertEquals(ManualFeedbackType.TERMINAL, terminal.type)
            assertEquals(ManualResultStatus.COMPLETED, terminal.status)
            val payload = terminal.conciseMessage()
            assertTrue("actual Notifier payload must identify $mode", payload.contains(mode.label))
            assertTrue(payload.contains("已观察到实际 OFF"))
            assertTrue(payload.contains("执行结束已确认"))
            assertTrue(payload.length <= 300)
            payload
        }
        assertEquals(3, payloads.distinct().size)
    }
    @Test fun `actual failed and quarantined notifier payloads retain known route and truthful key reason`() {
        for (mode in listOf(SwitchMode.SHIZUKU_ROOT, SwitchMode.SHIZUKU_SHELL, SwitchMode.ORDINARY)) {
            val unknown = Fixture(); unknown.start()
            unknown.runtime.entered!!(unknown.now)
            unknown.runtime.result!!(SwitchOutcome.uncertain(mode, "native authority remains unknown"))
            val quarantine = unknown.messages.last()
            assertEquals(ManualFeedbackType.QUARANTINED, quarantine.type)
            assertTrue(quarantine.conciseMessage().contains(mode.label))
            assertTrue(quarantine.conciseMessage().contains("native authority remains unknown"))
            assertFalse(quarantine.conciseMessage().contains("已观察到实际 OFF"))
            val failed = Fixture(); val operation = failed.start()
            failed.runtime.result!!(SwitchOutcome.submitted(mode))
            failed.runtime.samples.last()(StateSample(AdapterState.UNKNOWN, failed.now))
            failed.controller.serviceEnded(operation.operationId)
            val terminal = failed.messages.last()
            assertEquals(ManualResultStatus.FAILED, terminal.status)
            assertTrue(terminal.conciseMessage().contains(mode.label))
            assertTrue(terminal.conciseMessage().contains("实际目标状态未确认"))
            assertTrue(terminal.conciseMessage().length <= 300)
        }
    }
    @Test fun `actual accepted rejected and not invoked payloads never invent a used backend`() {
        val f = Fixture(); f.start()
        val accepted = f.messages.single()
        assertNull(accepted.mode)
        assertTrue(accepted.conciseMessage().contains("实际状态尚未确认"))
        assertFalse(f.controller.request(ManualAction.ON, false, true))
        val rejected = f.messages.last()
        assertNull(rejected.mode)
        assertTrue(rejected.conciseMessage().contains("未执行"))
        for (value in listOf(accepted, rejected)) {
            for (mode in listOf(SwitchMode.SHIZUKU_ROOT, SwitchMode.SHIZUKU_SHELL, SwitchMode.ORDINARY))
                assertFalse(value.conciseMessage().contains(mode.label))
        }
        f.runtime.result!!(SwitchOutcome(SwitchOutcome.Status.OBSERVING, SwitchMode.NONE, "already target; observe only", true))
        f.runtime.samples.last()(StateSample(AdapterState.OFF, f.now))
        val noop = f.messages.last()
        assertEquals(SwitchMode.NONE, noop.mode)
        assertTrue(noop.conciseMessage().contains(SwitchMode.NONE.label))
        assertTrue(noop.conciseMessage().contains("未调用开关"))
        assertFalse(noop.conciseMessage().contains(SwitchMode.ORDINARY.label))
        val longReason = noop.copy(reason = "可核对的关键原因；" + "长".repeat(1000))
        assertTrue(longReason.conciseMessage().contains("可核对的关键原因"))
        assertEquals(300, longReason.conciseMessage().length)
    }
    @Test fun `service end premarked finished still caches final summary from silent retirement`() {
        val f = Fixture(); val operation = f.start(ManualAction.SCAN); val e = f.runtime.scan!!
        assertTrue(e.registered("exact")); e.entered(1000); e.returned(1001, true)
        f.runtime.onRetire = { e.summary(ScanSummary(100, true, true, "取消（部分结果）")) }
        f.controller.serviceEnded(operation.operationId)
        assertFalse(f.messages.any { it.type == ManualFeedbackType.TERMINAL })
        e.retired("wrong"); assertNotNull(f.store.snapshot().manualFence)
        e.retired("exact")
        assertEquals(ManualResultStatus.FAILED, f.messages.last().status)
        assertEquals("手动 SCAN：100+个设备，目标手环：有，取消（部分结果）", f.messages.last().conciseMessage())
        assertEquals(listOf(CompactLogEntry.ScanEnded(100, true, true, "执行/清理未确认（部分结果）"),
            CompactLogEntry.ScanEnded(100, true, true, "取消（部分结果）")), f.rows)
        val count = f.messages.size
        e.summary(ScanSummary(0, false, false, "成功")); e.finished("stale", false); e.retired("exact")
        assertEquals(count, f.messages.size)
    }
    @Test fun `silent retirement saves final capped snapshot despite premarked finished and ignores stale final summary`() {
        val f = Fixture(); val op = f.start(ManualAction.SCAN); val e = f.runtime.scan!!
        assertTrue(e.registered("exact")); e.entered(1000); e.returned(1001, true)
        val devices = DiscoveredDevices(100).apply { add("11:22:33:44:35:BA", BandMatcher.TARGET_NAME, -42, "BLE") }.snapshot()
        f.runtime.onRetire = { e.summary(ScanSummary(1, false, true, "取消（部分结果）"), devices) }
        f.controller.serviceEnded(op.operationId)
        assertEquals(devices, f.controller.activeResult()!!.devices)
        e.summary(ScanSummary(0, false, false, "成功"), emptyList())
        e.retired("wrong"); assertNotNull(f.store.snapshot().manualFence)
        e.retired("exact")
        assertEquals(devices, f.store.snapshot().manualResults[ManualAction.SCAN]!!.devices)
        assertEquals(devices, CheckedResetStore(f.storage).snapshot().manualResults[ManualAction.SCAN]!!.devices)
        assertEquals(ScanSummary(1, false, true, "取消（部分结果）"), f.messages.last().scanSummary)
        assertNull(f.store.snapshot().pending); assertEquals(0L, f.store.snapshot().history.total)
        assertEquals(0, f.runtime.calls)
    }
    @Test fun `successful scan snapshot cannot claim successful terminal after service loss before cleanup`() {
        val f = Fixture(); val op = f.start(ManualAction.SCAN); val e = f.runtime.scan!!
        assertTrue(e.registered("exact")); e.entered(1000); e.returned(1001, true)
        e.summary(ScanSummary(3, false, true, "成功")); e.finished("window ended", false)
        f.controller.serviceEnded(op.operationId); e.retired("exact")
        assertEquals(ManualResultStatus.FAILED, f.messages.last().status)
        assertEquals(CompactLogEntry.ScanEnded(3, false, true, "取消（部分结果）"), f.rows.last())
        assertEquals("手动 SCAN：3个设备，目标手环：有，取消（部分结果）", f.messages.last().conciseMessage())
        assertNull(f.store.snapshot().manualFence)
    }
    @Test fun `unknown scan service loss reports one uncertainty then checked final cleanup without invented zero`() {
        val f = Fixture(); val op = f.start(ManualAction.SCAN); val e = f.runtime.scan!!
        assertTrue(e.registered("exact")); e.entered(1000)
        f.runtime.onRetire = { e.summary(ScanSummary(2, false, true, "取消（部分结果）")) }
        repeat(3) { f.controller.serviceEnded(op.operationId) }
        assertEquals(1, f.messages.count { it.type == ManualFeedbackType.QUARANTINED })
        assertEquals(listOf(CompactLogEntry.ScanEnded(2, false, true, "执行/清理未确认（部分结果）")), f.rows)
        assertFalse(f.messages.any { it.type == ManualFeedbackType.TERMINAL })
        assertFalse(f.store.snapshot().manualFence!!.authorityEnded)
        e.retired("exact"); assertNotNull(f.store.snapshot().manualFence)
        e.returned(1002, true)
        assertNull(f.store.snapshot().manualFence)
        assertEquals(CompactLogEntry.ScanEnded(2, false, true, "取消（部分结果）"), f.rows.last())
        assertEquals(2, f.rows.size); assertEquals(1, f.messages.count { it.type == ManualFeedbackType.TERMINAL })
        assertEquals(0, f.runtime.calls); assertEquals(0L, f.store.snapshot().history.total)
    }
    @Test fun `SCAN transient result preserves bounded prefix with hostile multi megabyte reason`() {
        val value = ManualFeedback(ManualAction.SCAN, "op", "req", ManualFeedbackType.TERMINAL,
            ManualResultStatus.FAILED, "unused", scanSummary = ScanSummary(100, true, true, "失败（部分结果）；" + "长".repeat(2_000_000)))
        val text = value.conciseMessage()
        assertTrue(text.startsWith("手动 SCAN：100+个设备，目标手环：有，失败（部分结果）"))
        assertTrue(text.length <= 300)
    }
    @Test fun `prepare not executed gives unknown count target and no completed scan wording`() {
        val f = Fixture(); f.start(ManualAction.SCAN); val e = f.runtime.scan!!
        e.summary(ScanSummary(null, false, null, "未执行")); e.finished("preparation rejected", true)
        e.preparationEnded(null)
        assertEquals(ManualFeedbackType.TERMINAL, f.messages.last().type)
        assertEquals("手动 SCAN：设备数未知，目标手环：未知，未执行", f.messages.last().conciseMessage())
        assertEquals(listOf(CompactLogEntry.ScanEnded(null, false, null, "未执行")), f.rows)
        assertEquals(0, f.runtime.calls)
    }
    @Test fun `unknown scan authority may improve after checked cleanup without replay or fake zero`() {
        val f = Fixture(); val op = f.start(ManualAction.SCAN); val e = f.runtime.scan!!
        assertTrue(e.registered("exact")); e.entered(1000)
        e.summary(ScanSummary(2, false, true, "失败（部分结果）")); e.finished("hung registration", true)
        f.controller.serviceEnded(op.operationId)
        assertFalse(f.messages.any { it.type == ManualFeedbackType.TERMINAL })
        e.retired("exact"); assertNotNull(f.store.snapshot().manualFence)
        e.returned(1002, false)
        assertEquals(1, f.messages.count { it.type == ManualFeedbackType.TERMINAL })
        assertEquals(2, f.messages.last().scanSummary!!.count)
        assertEquals(true, f.messages.last().scanSummary!!.targetSeen)
    }
    @Test fun `storage failure deduplicates log uncertainty and never reports success`() {
        val f = Fixture(); f.start(); f.runtime.result!!(SwitchOutcome.submitted(SwitchMode.ORDINARY))
        f.storage.works = false
        repeat(3) { f.runtime.samples.last()(StateSample(AdapterState.OFF, f.now)) }
        assertEquals(listOf(CompactLogEntry.BluetoothEnded(false, SwitchMode.ORDINARY, "存储失败，状态未确认")), f.rows)
        assertNotNull(f.store.snapshot().manualFence)
    }
    @Test fun `no op logs none not ordinary or Shizuku success`() {
        val f = Fixture(); f.start()
        f.runtime.result!!(SwitchOutcome(SwitchOutcome.Status.OBSERVING, SwitchMode.NONE, "already target", true))
        f.runtime.samples.last()(StateSample(AdapterState.OFF, f.now))
        assertEquals(listOf(CompactLogEntry.BluetoothEnded(false, SwitchMode.NONE, "已是目标状态，未调用")), f.rows)
    }
    @Test fun `manual ordinary fallback log uses only current command outcome`() {
        val f = Fixture(); f.start()
        f.runtime.result!!(SwitchOutcome.submitted(SwitchMode.SHIZUKU_ROOT))
        f.runtime.samples.last()(StateSample(AdapterState.ON, f.now, f.now))
        f.now += 10_000
        f.runtime.later.last()()
        f.runtime.samples.last()(StateSample(AdapterState.ON, f.now, f.now))
        assertEquals(2, f.runtime.calls)
        f.runtime.result!!(SwitchOutcome.submitted(SwitchMode.ORDINARY))
        f.runtime.samples.last()(StateSample(AdapterState.OFF, f.now))
        assertEquals(listOf(CompactLogEntry.BluetoothEnded(false, SwitchMode.ORDINARY, "成功")), f.rows)
    }
    @Test fun `rotated physical command has new identity and stale proof cannot settle it`() {
        val f = Fixture(); val first = f.start(); val callback = f.runtime.result!!
        assertTrue(f.runtime.next!!(SwitchOutcome.notInvoked("ended")))
        val next = f.store.snapshot().manualFence!!
        assertNotEquals(first.commandId, next.commandId)
        f.runtime.entered!!(777)
        callback(SwitchOutcome.submitted(SwitchMode.ORDINARY).copy(returnedAt = 778))
        assertEquals(next.commandId, f.store.snapshot().manualFence!!.commandId)
        assertTrue(f.store.snapshot().manualFence!!.nativeEntered)
        assertEquals(777L, f.controller.activeResult()!!.nativeEnteredAt)
        assertTrue(f.rows.isEmpty()) // accepted submission is not a terminal conclusion
        assertEquals(1, f.messages.count { it.type == ManualFeedbackType.ACCEPTED })
    }
}
