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
        var calls = 0
        var retirements = 0
        override fun requestForeground(operation: ManualOperation): Boolean { onForeground?.invoke(operation); return foreground }
        override fun execute(operation: ManualOperation, lease: CommandLease, ordinaryOnly: Boolean,
                             event: (BackendEntry) -> Unit, entered: (Long) -> Unit,
                             nextAttempt: (SwitchOutcome) -> Boolean, result: (SwitchOutcome) -> Unit) {
            calls++; this.result = result; this.entered = entered; next = nextAttempt; this.event = event
        }
        override fun sample(lease: CommandLease, result: (StateSample) -> Unit) { samples.add(result) }
        override fun scan(operation: ManualOperation, lease: CommandLease, events: ManualScanEvents) { scan = events }
        override fun retireScan(operationId: String) { retirements++ }
        override fun later(delayMs: Long, task: () -> Unit) { }
        override fun endForeground(operationId: String) { }
    }
    private class Fixture(throwing: Boolean = false) {
        var now = 1000L
        var serial = 0
        val storage = MemoryResetStorage()
        val store = CheckedResetStore(storage)
        val runtime = Runtime()
        val messages = mutableListOf<ManualFeedback>()
        val rows = mutableListOf<Triple<String, Map<String, Any?>, Long?>>()
        val controller = ManualOperationController(store, { OperationAdmission() }, runtime, { now }, { now },
            { "id-${++serial}" }, feedback = { messages.add(it); if (throwing) error("feedback") },
            audit = AuditSink { name, fields, at -> rows.add(Triple(name, fields, at)); if (throwing) error("sink") })
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
        assertEquals(1234L, f.rows.single { it.first == "manual.sample" }.third)
    }
    @Test fun `each rejected click is independent and ingress precedes refusal`() {
        val f = Fixture()
        repeat(3) { assertFalse(f.controller.request(ManualAction.ON, false, true)) }
        assertEquals(3, f.messages.size)
        assertEquals(3, f.messages.map { it.requestId }.distinct().size)
        assertTrue(f.messages.all { it.type == ManualFeedbackType.REJECTED })
        assertEquals(3, f.rows.count { it.first == "manual.request" })
        assertEquals("manual.request", f.rows.first().first)
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
        e.finished("window ended", false)
        assertFalse(f.messages.any { it.status == ManualResultStatus.COMPLETED })
        e.retired("wrong"); assertNotNull(f.store.snapshot().manualFence)
        e.retired("exact"); e.retired("exact")
        assertEquals(1, f.messages.count { it.type == ManualFeedbackType.TERMINAL })
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
    @Test fun `rotated physical command has new identity and stale proof cannot settle it`() {
        val f = Fixture(); val first = f.start(); val callback = f.runtime.result!!
        assertTrue(f.runtime.next!!(SwitchOutcome.notInvoked("ended")))
        val next = f.store.snapshot().manualFence!!
        assertNotEquals(first.commandId, next.commandId)
        f.runtime.entered!!(777)
        callback(SwitchOutcome.submitted(SwitchMode.ORDINARY).copy(returnedAt = 778))
        assertEquals(next.commandId, f.rows.last { it.first == "manual.outcome" }.second["commandId"])
        assertEquals(next.commandId, f.rows.last { it.first == "manual.native.entered" }.second["commandId"])
        assertEquals(777L, f.rows.last { it.first == "manual.native.entered" }.third)
        assertEquals(1, f.messages.count { it.type == ManualFeedbackType.ACCEPTED })
    }
}
