package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class HeadlessRuntimeTest {
    private class Fixture {
        var record: ResetRecord? = null
        var storeWorks = true
        var alarmWorks = true
        var alarmScheduled = false
        var failStoreDuringAlarm = false
        var now = 1_000L
        val effects = mutableListOf<String>()
        private var serial = 0
        val engine = DurableResetEngine(
            object : ResetPersistence {
                override fun read() = record
                override fun commit(expected: ResetRecord?, next: ResetRecord?): Boolean {
                    effects.add("commit")
                    if (!storeWorks || record != expected) return false
                    record = next
                    return true
                }
            }, {
                effects.add("alarm")
                if (failStoreDuringAlarm) storeWorks = false
                if (alarmWorks) alarmScheduled = true
                alarmWorks
            }, { now }, { "id-${++serial}" },
        )
        fun armed(): ResetRecord = requireNotNull(engine.arm("run", false))
        fun dispatched(): ResetRecord = requireNotNull(engine.dispatch(armed(), CommandLease.foreground(), false))
        fun settledOff(): ResetRecord {
            val dispatched = dispatched()
            return requireNotNull(engine.complete(dispatched, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL)))
        }
    }

    @Test fun `automatic request commits marker before actual request and never storms`() {
        var attempted = false
        val events = mutableListOf<String>()
        val auto = AutomaticAuthorization({ attempted }, { attempted = true; events.add("commit"); true }, { events.add("request") })
        assertEquals(AutomaticAuthorization.Decision.REQUESTED, auto.authorize(true, false))
        assertEquals(listOf("commit", "request"), events)
        repeat(5) { assertEquals(AutomaticAuthorization.Decision.PREVIOUS_ATTEMPT, auto.authorize(true, false)) }
        assertEquals(2, events.size)
    }

    @Test fun `missing binder does not consume first actual request and explicit retry is allowed`() {
        var attempted = false
        var requests = 0
        val auto = AutomaticAuthorization({ attempted }, { attempted = true; true }, { requests++ })
        assertEquals(AutomaticAuthorization.Decision.NO_BINDER, auto.authorize(false, false))
        assertFalse(attempted)
        auto.authorize(true, false)
        auto.authorize(true, false, explicit = true)
        assertEquals(2, requests)
    }

    @Test fun `failed marker commit prevents actual request`() {
        var requests = 0
        val auto = AutomaticAuthorization({ false }, { false }, { requests++ })
        assertEquals(AutomaticAuthorization.Decision.STORE_FAILED, auto.authorize(true, false))
        assertEquals(0, requests)
    }

    @Test fun `late permission grant cannot reopen a completed startup continuation`() {
        val gate = CompletionGate()
        var launches = 0
        if (gate.finish()) launches++ // human wait timed out; ordinary run starts
        if (gate.finish()) launches++ // late grant is readiness for a future run only
        assertEquals(1, launches)
    }

    @Test fun `duplicate run and queued batch matches have only one owner`() {
        val owner = RunOwnership()
        assertTrue(owner.acquire("one"))
        assertFalse(owner.acquire("two"))
        assertTrue(owner.claimMatch("one"))
        assertFalse(owner.claimMatch("one"))
        owner.release("two")
        assertTrue(owner.current("one"))
        owner.release("one")
        assertTrue(owner.acquire("two"))
        assertFalse(owner.current("one"))
    }

    @Test fun `durable commit alarm then dispatch commit all precede off`() {
        val f = Fixture()
        val ready = f.armed()
        assertEquals(listOf("commit", "alarm"), f.effects)
        val dispatched = f.engine.dispatch(ready, CommandLease.foreground(), false)
        assertNotNull(dispatched)
        assertEquals(listOf("commit", "alarm", "commit"), f.effects)
        assertEquals(ResetStage.OFF_DISPATCHED, f.record!!.stage)
    }

    @Test fun `failed arm or failed alarm never authorizes off`() {
        for (storage in listOf(true, false)) {
            val f = Fixture()
            if (storage) f.storeWorks = false else f.alarmWorks = false
            assertNull(f.engine.arm("run", false))
            assertNull(f.record?.let { f.engine.dispatch(it, CommandLease.foreground(), false) })
        }
    }

    @Test fun `expected run command version reject stale writes`() {
        val f = Fixture()
        val ready = f.armed()
        val dispatched = requireNotNull(f.engine.dispatch(ready, CommandLease.foreground(), false))
        assertNull(f.engine.dispatch(ready, CommandLease.foreground(), false))
        assertNull(f.engine.complete(dispatched.copy(runId = "other"), SwitchOutcome.submitted(SwitchMode.ORDINARY)))
        assertNull(f.engine.complete(dispatched.copy(commandId = "other"), SwitchOutcome.submitted(SwitchMode.ORDINARY)))
    }

    @Test fun `off in flight tick never issues on even when actual state off`() {
        val f = Fixture()
        f.dispatched()
        assertEquals(RecoveryStep.OBSERVE, f.engine.observe(AdapterState.OFF))
        assertEquals(ResetStage.OFF_DISPATCHED, f.record!!.stage)
    }

    @Test fun `completion rebases observation version only same off command not on stage`() {
        val f = Fixture()
        val command = f.dispatched()
        f.engine.observe(AdapterState.TURNING_OFF)
        assertTrue(f.record!!.version > command.version)
        val settled = requireNotNull(f.engine.complete(command, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL)))
        assertEquals(ResetStage.OFF_SETTLED, settled.stage)
        assertEquals(RecoveryStep.DISPATCH_ON, f.engine.observe(AdapterState.OFF))
        val on = f.record!!
        assertNull(f.engine.complete(command, SwitchOutcome.submitted(SwitchMode.ORDINARY)))
        assertEquals(on, f.record)
    }

    @Test fun `cold dispatched off remains quarantined no replay or early on`() {
        val f = Fixture()
        f.dispatched()
        // A new runtime has no in-memory Future, but the durable dispatch is still uncertain.
        repeat(121) { f.engine.observe(AdapterState.OFF) }
        assertEquals(ResetStage.QUARANTINED, f.record!!.stage)
        assertFalse(f.engine.retryRecovery())
        assertNull(f.engine.arm("new", false))
    }

    @Test fun `uncertain off remains durable whereas definitely not invoked can terminate safely`() {
        val f = Fixture()
        val off = f.dispatched()
        f.engine.complete(off, SwitchOutcome.uncertain(SwitchMode.SHIZUKU_ROOT, "RPC timeout"))
        assertEquals(RecoveryStep.OBSERVE, f.engine.observe(AdapterState.OFF))
        assertNotNull(f.record)
        val safe = Fixture()
        val notCalled = safe.dispatched()
        safe.engine.complete(notCalled, SwitchOutcome.notInvoked("expired"))
        assertEquals(RecoveryStep.FAILED, safe.engine.observe(AdapterState.ON))
        assertNull(safe.record)
    }

    @Test fun `submitted waits for hardware state never claims success from exit zero`() {
        val f = Fixture()
        f.settledOff()
        assertEquals(RecoveryStep.OBSERVE, f.engine.observe(AdapterState.TURNING_OFF))
        assertEquals(RecoveryStep.DISPATCH_ON, f.engine.observe(AdapterState.OFF))
        val on = requireNotNull(f.engine.dispatch(f.record!!, CommandLease.foreground(), true))
        f.engine.complete(on, SwitchOutcome.submitted(SwitchMode.ORDINARY))
        assertEquals(RecoveryStep.OBSERVE, f.engine.observe(AdapterState.TURNING_ON))
        assertEquals(RecoveryStep.RESTORED, f.engine.observe(AdapterState.ON))
        assertNull(f.record)
    }

    @Test fun `actual modes report mixed shizuku off ordinary on independently of authorization`() {
        val history = BackendHistory()
        assertEquals("尚未切换", history.label())
        history.add(SwitchMode.SHIZUKU_SHELL, "OFF 已提交")
        history.add(SwitchMode.ORDINARY, "ON 已提交；Shizuku 拒绝授权")
        assertEquals("混合（Shizuku shell + 普通方式）", history.label())
        repeat(100) { history.add(SwitchMode.ORDINARY, "x".repeat(1000)) }
        assertTrue(history.entries.size <= 12)
        assertTrue(history.entries.all { it.reason.length <= 512 })
        assertTrue(history.label().contains("混合"))
    }

    @Test fun `receiver expiry cancels new queued command authority`() {
        var now = 1L
        val lease = CommandLease({ now }, 9L)
        assertTrue(lease.valid())
        now = 9
        assertFalse(lease.valid())
        val f = Fixture()
        val ready = f.armed()
        assertNull(f.engine.dispatch(ready, lease, false))
        assertEquals(ResetStage.OFF_READY, f.record!!.stage)
    }

    @Test fun `on refusals at most three ordinary calls at least five seconds apart`() {
        val f = Fixture()
        f.settledOff()
        f.engine.observe(AdapterState.OFF)
        repeat(3) { index ->
            val ready = f.record!!
            val on = requireNotNull(f.engine.dispatch(ready, CommandLease.foreground(), true))
            f.engine.complete(on, SwitchOutcome.rejected(SwitchMode.ORDINARY, "OEM refusal"))
            assertEquals(RecoveryStep.OBSERVE, f.engine.observe(AdapterState.OFF))
            f.now += 4_999
            assertEquals(RecoveryStep.OBSERVE, f.engine.observe(AdapterState.OFF))
            f.now++
            assertEquals(if (index == 2) RecoveryStep.EXHAUSTED else RecoveryStep.DISPATCH_ON, f.engine.observe(AdapterState.OFF))
        }
        assertNotNull(f.record)
        assertTrue(f.engine.retryRecovery())
    }

    @Test fun `explicit retry of exhausted recovery observes already on as restored not eternal ready`() {
        val f = Fixture()
        val off = f.settledOff()
        f.record = off.copy(stage = ResetStage.EXHAUSTED)
        assertTrue(f.engine.retryRecovery())
        assertEquals(RecoveryStep.RESTORED, f.engine.observe(AdapterState.ON))
        assertNull(f.record)
    }

    @Test fun `late native result cannot erase unknown authority after newer phase was issued`() {
        val f = Fixture()
        val off = f.dispatched()
        f.engine.complete(off, SwitchOutcome.uncertain(SwitchMode.SHIZUKU_SHELL, "native still executing"))
        assertEquals(ResetStage.QUARANTINED, f.record!!.stage)
        f.engine.complete(off, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL))
        f.engine.observe(AdapterState.OFF)
        val on = requireNotNull(f.engine.dispatch(f.record!!, CommandLease.foreground(), true))
        val snapshot = f.record
        assertNull(f.engine.complete(off, SwitchOutcome.notInvoked("late old callback")))
        assertEquals(snapshot, f.record)
        assertEquals(ResetStage.ON_DISPATCHED, on.stage)
    }

    @Test fun `waiting on cap still gets an actual off recovery chance within ordinary retry budget`() {
        val f = Fixture()
        f.settledOff()
        f.engine.observe(AdapterState.OFF)
        val on = requireNotNull(f.engine.dispatch(f.record!!, CommandLease.foreground(), true))
        f.engine.complete(on, SwitchOutcome.rejected(SwitchMode.ORDINARY, "first refusal"))
        f.record = f.record!!.copy(attempts = 120)
        f.now += 5_000
        assertEquals(RecoveryStep.DISPATCH_ON, f.engine.observe(AdapterState.OFF))
    }

    @Test fun `deferred cold ready job has independent command lease after scan receiver expiry`() {
        val f = Fixture()
        val ready = f.armed()
        val scanLease = CommandLease.foreground()
        scanLease.cancel()
        assertNull(f.engine.dispatch(ready, scanLease, false))
        assertNotNull(f.engine.dispatch(ready, CommandLease.foreground(), false))
        assertNull(f.engine.arm("duplicate", false))
    }

    @Test fun `late safe off after quarantine teardown rearms alarm without issuing inline command`() {
        val f = Fixture()
        val lease = CommandLease({ f.now }, f.now + 8_000L)
        val command = requireNotNull(f.engine.dispatch(f.armed(), lease, false))
        f.now += 8_000L
        repeat(ReEnablePolicy.MAX_ATTEMPTS) { f.engine.observe(AdapterState.OFF) }
        assertEquals(RecoveryStep.QUARANTINED, f.engine.observe(AdapterState.OFF))
        f.alarmScheduled = false // coordinator canceled the alarm and stopped the service at quarantine cap
        f.effects.clear()
        val result = requireNotNull(f.engine.completeAndRearm(command, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL)))
        assertFalse(lease.valid())
        assertEquals(listOf("commit", "alarm"), f.effects)
        assertTrue(f.alarmScheduled)
        assertEquals(true, result.rearmed)
        assertEquals(ResetStage.OFF_SETTLED, f.record!!.stage)
        assertTrue(f.record!!.authorityEnded)
        assertEquals(command.commandId, f.record!!.commandId)
        assertNull(f.engine.dispatch(f.record!!, lease, true))
    }

    @Test fun `expired receiver reconciliation rearms independently of its expired execution lease`() {
        val f = Fixture()
        val lease = CommandLease({ f.now }, f.now + 8_000L)
        val command = requireNotNull(f.engine.dispatch(f.armed(), lease, false))
        f.now += 8_000L
        f.alarmScheduled = false // receiver's prior alarm has fired; no service owns continuation
        f.effects.clear()
        requireNotNull(f.engine.completeAndRearm(command, SwitchOutcome.submitted(SwitchMode.SHIZUKU_ROOT)))
        assertFalse(lease.valid())
        assertEquals(listOf("commit", "alarm"), f.effects)
        assertTrue(f.alarmScheduled)
        assertEquals(ResetStage.OFF_SETTLED, f.record!!.stage)
    }

    @Test fun `stale late completion never rearms newer on command or newer run`() {
        val f = Fixture()
        val off = f.dispatched()
        f.engine.complete(off, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL))
        f.engine.observe(AdapterState.OFF)
        val on = requireNotNull(f.engine.dispatch(f.record!!, CommandLease.foreground(), true))
        f.alarmScheduled = false
        f.effects.clear()
        assertNull(f.engine.completeAndRearm(off, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL)))
        assertEquals(on, f.record)
        assertFalse(f.alarmScheduled)
        assertTrue(f.effects.isEmpty())
        f.engine.complete(on, SwitchOutcome.submitted(SwitchMode.ORDINARY))
        assertEquals(RecoveryStep.RESTORED, f.engine.observe(AdapterState.ON))
        val newer = requireNotNull(f.engine.arm("newer-run", false))
        f.alarmScheduled = false
        f.effects.clear()
        assertNull(f.engine.completeAndRearm(off, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL)))
        assertEquals(newer, f.record)
        assertFalse(f.alarmScheduled)
        assertTrue(f.effects.isEmpty())
    }

    @Test fun `accepted late completion persists and discloses failed alarm rearm`() {
        val f = Fixture()
        val off = f.dispatched()
        f.alarmScheduled = false
        f.alarmWorks = false
        f.effects.clear()
        val result = requireNotNull(f.engine.completeAndRearm(off, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL)))
        assertEquals(false, result.rearmed)
        assertFalse(f.alarmScheduled)
        assertEquals(listOf("commit", "alarm", "commit"), f.effects)
        assertTrue(result.failure!!.contains("重新安排失败"))
        assertTrue(f.record!!.reason.contains("重新安排失败"))
        assertEquals(f.record, result.record)
        assertEquals(ResetStage.OFF_SETTLED, f.record!!.stage)
        assertTrue(f.record!!.authorityEnded)
    }

    @Test fun `failed rearm detail commit is disclosed without inventing a persisted record version`() {
        val f = Fixture()
        val off = f.dispatched()
        f.alarmWorks = false
        f.failStoreDuringAlarm = true
        val result = requireNotNull(f.engine.completeAndRearm(off, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL)))
        assertEquals(false, result.rearmed)
        assertTrue(result.failure!!.contains("存储更新失败"))
        assertEquals(f.record, result.record)
        assertEquals(off.version + 1, result.record.version)
        assertTrue(result.record.authorityEnded)
        assertEquals(ResetStage.OFF_SETTLED, result.record.stage)
    }

    @Test fun `uncertain late completion cannot rearm recovery or override quarantine`() {
        val f = Fixture()
        val off = f.dispatched()
        f.alarmScheduled = false
        f.effects.clear()
        val result = requireNotNull(f.engine.completeAndRearm(off, SwitchOutcome.uncertain(SwitchMode.SHIZUKU_ROOT, "still executing")))
        assertNull(result.rearmed)
        assertEquals(listOf("commit"), f.effects)
        assertFalse(f.alarmScheduled)
        assertEquals(ResetStage.QUARANTINED, f.record!!.stage)
        assertFalse(f.engine.retryRecovery(AdapterState.OFF))
    }

    @Test fun `explicit details continuation of safe settled off requires observed off and cannot replay off`() {
        val f = Fixture()
        val settled = f.settledOff()
        assertFalse(f.engine.retryRecovery(AdapterState.UNKNOWN))
        assertFalse(f.engine.retryRecovery(AdapterState.ON))
        assertEquals(settled, f.record)
        assertTrue(f.engine.retryRecovery(AdapterState.OFF))
        val ready = f.record!!
        assertEquals(ResetStage.ON_READY, ready.stage)
        assertEquals(ReEnablePolicy.Phase.WAITING_ON, ready.phase)
        assertNotEquals(settled.commandId, ready.commandId)
        assertNull(f.engine.dispatch(ready, CommandLease.foreground(), false))
        f.alarmScheduled = false
        assertEquals(true, f.engine.rearmContinuation(ready)!!.rearmed)
        assertTrue(f.alarmScheduled)
        assertNotNull(f.engine.dispatch(ready, CommandLease.foreground(), true))
    }

    @Test fun `continuation rearm rejects changed expected record version without an alarm effect`() {
        val f = Fixture()
        val settled = f.settledOff()
        assertTrue(f.engine.note(settled, BackendEntry(SwitchMode.NONE, "newer observation details")))
        val snapshot = f.record
        f.alarmScheduled = false
        f.effects.clear()
        assertNull(f.engine.rearmContinuation(settled))
        assertEquals(snapshot, f.record)
        assertTrue(f.effects.isEmpty())
        assertFalse(f.alarmScheduled)
    }

    @Test fun `failed details continuation alarm can be rearmed by a later explicit tap without a new command`() {
        val f = Fixture()
        f.settledOff()
        f.alarmScheduled = false // no service or alarm remains
        assertTrue(f.engine.retryRecovery(AdapterState.OFF))
        val ready = f.record!!
        f.alarmWorks = false
        val first = requireNotNull(f.engine.rearmContinuation(ready))
        assertEquals(false, first.rearmed)
        assertFalse(f.alarmScheduled)
        assertTrue(f.record!!.reason.contains("重新安排失败"))
        val failed = f.record!!
        assertEquals(ResetStage.ON_READY, failed.stage)
        assertEquals(ReEnablePolicy.Phase.WAITING_ON, failed.phase)
        assertEquals(ready.commandId, failed.commandId)
        f.alarmWorks = true
        f.effects.clear()
        assertTrue(f.engine.retryRecovery(AdapterState.OFF)) // second details tap, same Android-used route
        assertEquals(failed, f.record)
        val second = requireNotNull(f.engine.rearmContinuation(f.record!!))
        assertEquals(true, second.rearmed)
        assertTrue(f.alarmScheduled)
        assertEquals(listOf("alarm"), f.effects)
        assertEquals(failed, f.record)
        assertNull(f.engine.dispatch(f.record!!, CommandLease.foreground(), false))
        assertNotNull(f.engine.dispatch(f.record!!, CommandLease.foreground(), true))
    }

    @Test fun `repeated failed scheduling of safe on ready does not reset counters or planned backend`() {
        val f = Fixture()
        f.settledOff()
        assertEquals(RecoveryStep.DISPATCH_ON, f.engine.observe(AdapterState.OFF))
        f.record = f.record!!.copy(attempts = 17, extraObservations = 3, ordinaryOnAttempts = 2,
            lastOrdinaryOnAt = f.now - 5_000L, ordinaryOnly = true)
        val planned = f.record!!
        f.alarmWorks = false
        f.alarmScheduled = false
        repeat(3) {
            val before = f.record!!
            assertTrue(f.engine.retryRecovery(AdapterState.OFF))
            assertEquals(before, f.record)
            assertEquals(false, f.engine.rearmContinuation(before)!!.rearmed)
            val after = f.record!!
            assertTrue(after.reason.contains("重新安排失败"))
            assertEquals(planned.runId, after.runId)
            assertEquals(planned.commandId, after.commandId)
            assertEquals(planned.phase, after.phase)
            assertEquals(planned.stage, after.stage)
            assertEquals(planned.attempts, after.attempts)
            assertEquals(planned.extraObservations, after.extraObservations)
            assertEquals(planned.ordinaryOnAttempts, after.ordinaryOnAttempts)
            assertEquals(planned.lastOrdinaryOnAt, after.lastOrdinaryOnAt)
            assertEquals(planned.ordinaryOnly, after.ordinaryOnly)
        }
        val failed = f.record!!
        f.alarmWorks = true
        f.effects.clear()
        assertTrue(f.engine.retryRecovery(AdapterState.OFF))
        assertEquals(true, f.engine.rearmContinuation(f.record!!)!!.rearmed)
        assertEquals(failed, f.record)
        assertEquals(listOf("alarm"), f.effects)
        assertTrue(f.alarmScheduled)
    }

    @Test fun `on ready details continuation still rejects missing off evidence wrong phase and unresolved authority`() {
        val f = Fixture()
        f.settledOff()
        f.engine.observe(AdapterState.OFF)
        val ready = f.record!!
        assertFalse(f.engine.retryRecovery(AdapterState.UNKNOWN))
        assertFalse(f.engine.retryRecovery(AdapterState.ON))
        assertEquals(ready, f.record)
        f.record = ready.copy(authorityEnded = false)
        assertFalse(f.engine.retryRecovery(AdapterState.OFF))
        f.record = ready.copy(phase = ReEnablePolicy.Phase.WAITING_OFF)
        assertFalse(f.engine.retryRecovery(AdapterState.OFF))
    }

    @Test fun `actual off gets recovery opportunity at policy cap and transitions get thirty extra ticks`() {
        val f = Fixture()
        val off = f.settledOff()
        f.record = off.copy(attempts = 120)
        assertEquals(RecoveryStep.DISPATCH_ON, f.engine.observe(AdapterState.OFF))
        val t = Fixture()
        val settled = t.settledOff()
        t.record = settled.copy(attempts = 120)
        repeat(30) { assertEquals(RecoveryStep.OBSERVE, t.engine.observe(AdapterState.TURNING_OFF)) }
        assertEquals(RecoveryStep.EXHAUSTED, t.engine.observe(AdapterState.TURNING_OFF))
    }
}
