package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class ManualOperationTest {
    @Test fun `every durable or runtime owner blocks new automatic and manual admission`() {
        val clear = OperationAdmission()
        assertTrue(clear.allowsNewOperation())
        val pending = ResetRecord("run", "command", stage = ResetStage.EXHAUSTED)
        val readyManual = ManualOperation("manual", "command", action = ManualAction.OFF)
        val blockers = listOf(
            clear.copy(pending = pending),
            clear.copy(pending = pending.copy(stage = ResetStage.QUARANTINED, authorityEnded = false)),
            clear.copy(manualFence = readyManual),
            clear.copy(manualFence = readyManual.copy(stage = ManualStage.QUARANTINED, authorityEnded = false)),
            clear.copy(activeScan = true), clear.copy(scanRetirement = true),
            clear.copy(automaticOwner = true), clear.copy(setupWaiting = true),
            clear.copy(serviceOwned = true), clear.copy(storageFailed = true), clear.copy(integrityBlocked = true),
        )
        blockers.forEachIndexed { index, admission ->
            assertFalse("blocker $index", admission.allowsNewOperation())
        }
    }

    @Test fun `scan fence requires exact scanner retirement not elapsed time or ended command authority`() {
        val scan = ManualOperation("manual", "command", action = ManualAction.SCAN,
            stage = ManualStage.RETIRING, nativeEntered = true, authorityEnded = true, scannerIdentity = "scanner-A")
        assertFalse(scan.canFinish())
        assertFalse(scan.copy(stage = ManualStage.READY, nativeEntered = false).canFinish())
        assertTrue(scan.copy(stage = ManualStage.READY, nativeEntered = false, scannerIdentity = null).canFinish())
        assertTrue(scan.copy(scannerRetired = true).canFinish())
        assertFalse(scan.copy(scannerRetired = true, authorityEnded = false).canFinish())
        val off = ManualOperation("manual", "command", action = ManualAction.OFF,
            stage = ManualStage.QUARANTINED, nativeEntered = true, authorityEnded = false)
        assertFalse(off.canFinish())
        assertTrue(off.copy(authorityEnded = true).canFinish())
    }

    @Test fun `rejected per action result preserves the active durable operation identity`() {
        val storage = MemoryResetStorage()
        val store = CheckedResetStore(storage)
        val off = ManualOperation("off-operation", "off-command", action = ManualAction.OFF)
        assertTrue(store.beginManual(off, OperationAdmission()))
        val accepted = store.snapshot().manualFence
        assertFalse(store.beginManual(ManualOperation("new-operation", "new-command", action = ManualAction.ON), OperationAdmission()))
        assertTrue(store.rejectManual(ManualResult("rejected-on", ManualAction.ON, ManualResultStatus.REJECTED, "busy")))
        assertEquals(accepted, store.snapshot().manualFence)
        assertEquals("rejected-on", store.snapshot().manualResults[ManualAction.ON]!!.operationId)
        assertFalse(store.finishManual(off.copy(revision = 1), ManualResult(off.operationId, ManualAction.OFF, ManualResultStatus.COMPLETED, "late")))
        assertEquals(accepted, store.snapshot().manualFence)
        assertNull(store.snapshot().manualResults[ManualAction.OFF])
    }

    @Test fun `uncertain fence survives cold store and cannot be overwritten or finished`() {
        val storage = MemoryResetStorage()
        val store = CheckedResetStore(storage)
        val ready = ManualOperation("manual", "off", action = ManualAction.OFF)
        assertTrue(store.beginManual(ready, OperationAdmission()))
        val uncertain = ready.copy(revision = 1, stage = ManualStage.QUARANTINED, nativeEntered = true, authorityEnded = false)
        assertTrue(store.updateManual(ready, uncertain))
        val cold = CheckedResetStore(storage)
        assertEquals(uncertain, cold.snapshot().manualFence)
        assertFalse(cold.beginManual(ManualOperation("new", "new", action = ManualAction.ON), OperationAdmission()))
        assertFalse(cold.commit(null, ResetRecord("auto", "off")))
        assertFalse(cold.finishManual(uncertain, ManualResult("manual", ManualAction.OFF, ManualResultStatus.UNCERTAIN, "still executing")))
        assertEquals(uncertain, cold.snapshot().manualFence)
    }

    @Test fun `manual off completion stores only off result and never creates pending or automatic count`() {
        val store = CheckedResetStore(MemoryResetStorage())
        val ready = ManualOperation("manual", "off", action = ManualAction.OFF)
        assertTrue(store.beginManual(ready, OperationAdmission()))
        val settled = ready.copy(revision = 1, stage = ManualStage.SETTLED, nativeEntered = true, authorityEnded = true)
        assertTrue(store.updateManual(ready, settled))
        assertTrue(store.finishManual(settled, ManualResult("manual", ManualAction.OFF, ManualResultStatus.COMPLETED, "actual OFF")))
        val snapshot = store.snapshot()
        assertNull(snapshot.pending)
        assertNull(snapshot.manualFence)
        assertTrue(snapshot.history.rows.isEmpty())
        assertEquals(0L, snapshot.history.total)
        assertEquals(setOf(ManualAction.OFF), snapshot.manualResults.keys)
    }
}

/** The same adapter contract used for SharedPreferences; false may eagerly mutate its cache. */
internal class MemoryResetStorage(initial: Map<String, String> = emptyMap()) : ResetStorageAdapter {
    val values = initial.toMutableMap()
    var writes = 0
    var works = true
    var throws = false
    var eagerFailure = false
    override fun read(): Map<String, String> = values.toMap()
    override fun commit(changes: Map<String, String?>): Boolean {
        writes++
        if (works || eagerFailure) changes.forEach { (key, value) ->
            if (value == null) values.remove(key) else values[key] = value
        }
        if (throws) throw IllegalStateException("adapter write failed")
        return works
    }
}
