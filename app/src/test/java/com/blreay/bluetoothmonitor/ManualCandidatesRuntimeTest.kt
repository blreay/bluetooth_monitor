package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class ManualCandidatesRuntimeTest {
    private class Runtime : ManualRuntime {
        var foreground = true
        var events: ManualScanEvents? = null
        override fun requestForeground(operation: ManualOperation) = foreground
        override fun execute(operation: ManualOperation, lease: CommandLease, ordinaryOnly: Boolean, event: (BackendEntry) -> Unit,
            entered: (Long) -> Unit, nextAttempt: (SwitchOutcome) -> Boolean, result: (SwitchOutcome) -> Unit) { }
        override fun sample(lease: CommandLease, result: (StateSample) -> Unit) { }
        override fun scan(operation: ManualOperation, lease: CommandLease, events: ManualScanEvents) { this.events = events }
        override fun retireScan(operationId: String) { }
        override fun later(delayMs: Long, task: () -> Unit) { }
        override fun endForeground(operationId: String) { }
    }
    private class Fixture {
        val store = CheckedResetStore(MemoryResetStorage())
        val runtime = Runtime()
        val candidates = ManualScanCandidates()
        var serial = 0
        val controller = ManualOperationController(store, { OperationAdmission() }, runtime, { 100L }, { 1L },
            { "id-${++serial}" }, candidates = candidates)
        fun scan(): ManualOperation {
            assertTrue(controller.request(ManualAction.SCAN, true, true))
            val operation = store.snapshot().manualFence!!
            controller.serviceStarted(operation.operationId, CommandLease({ 1L }, 45_001L))
            return operation
        }
        fun devices(name: String) = DiscoveredDevices().apply { add("AA", name, -40, "BLE") }.snapshot()
    }
    @Test fun `candidate snapshot begins only at real accepted scan foreground not rejected or pending click`() {
        val f = Fixture()
        assertTrue(f.controller.request(ManualAction.SCAN, true, true))
        assertNull(f.candidates.snapshot().identity)
        val operation = f.store.snapshot().manualFence!!
        f.controller.serviceStarted(operation.operationId, CommandLease({ 1L }, 45_001L))
        assertEquals(ManualScanIdentity(operation.operationId, operation.commandId), f.candidates.snapshot().identity)
        assertTrue(f.candidates.snapshot().devices.isEmpty())
        f.controller.request(ManualAction.ON, false, true)
        assertEquals(operation.operationId, f.candidates.snapshot().identity!!.operationId)
    }
    @Test fun `exact final devices update then close precedes live clear and stale callbacks cannot overwrite`() {
        val f = Fixture(); val op = f.scan(); val e = f.runtime.events!!
        assertTrue(e.registered("exact")); e.entered(101); e.returned(102, true)
        e.devices(f.devices("  Raw  "), false)
        e.summary(ScanSummary(1, false, true, "成功"), f.devices("  Final  "))
        assertEquals("real controller must retain the exact current final collector", 1, f.candidates.snapshot().devices.size)
        assertEquals("  Final  ", f.candidates.snapshot().devices.single().advertisementName)
        assertTrue(f.candidates.snapshot().closed)
        e.finished("finished", false); e.retired("exact")
        assertNull(f.controller.activeResult()); assertNull(f.store.snapshot().manualFence)
        assertEquals(op.operationId, f.candidates.snapshot().identity!!.operationId)
        e.devices(f.devices("Stale"), false); e.summary(ScanSummary(1, false, false, "成功"), f.devices("Stale"))
        assertEquals("  Final  ", f.candidates.snapshot().devices.single().advertisementName)
        f.scan(); assertTrue(f.candidates.snapshot().devices.isEmpty()); assertFalse(f.candidates.snapshot().closed)
        e.devices(f.devices("Very stale"), false)
        assertTrue(f.candidates.snapshot().devices.isEmpty())
    }
    @Test fun `foreground refusal and cold old manual results never seed fresh candidate identity`() {
        val f = Fixture(); f.runtime.foreground = false
        assertFalse(f.controller.request(ManualAction.SCAN, true, true))
        assertNotNull(f.store.snapshot().manualResults[ManualAction.SCAN])
        assertNull(f.candidates.snapshot().identity)
        val cold = ManualScanCandidates()
        ManualOperationController(f.store, { OperationAdmission() }, f.runtime, { 100L }, { 1L }, { "new" }, candidates = cold)
        assertNull(cold.snapshot().identity); assertTrue(cold.snapshot().devices.isEmpty())
    }
    @Test fun `candidate observer throwing cannot suppress final authority reconciliation or retirement`() {
        val store = CheckedResetStore(MemoryResetStorage()); val runtime = Runtime(); var serial = 0
        val candidates = ManualScanCandidates { throw IllegalStateException("presentation") }
        val controller = ManualOperationController(store, { OperationAdmission() }, runtime, { 100L }, { 1L },
            { "id-${++serial}" }, candidates = candidates)
        assertTrue(controller.request(ManualAction.SCAN, true, true)); val op = store.snapshot().manualFence!!
        controller.serviceStarted(op.operationId, CommandLease({ 1L }, 45_001L)); val e = runtime.events!!
        assertTrue(e.registered("exact")); e.entered(101); e.returned(102, true)
        e.summary(ScanSummary(0, false, false, "成功"), emptyList()); e.finished("finished", false); e.retired("exact")
        assertTrue(candidates.snapshot().closed); assertNull(store.snapshot().manualFence)
    }
}
