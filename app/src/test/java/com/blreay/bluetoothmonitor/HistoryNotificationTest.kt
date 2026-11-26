package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class HistoryNotificationTest {
    private fun hundredCompleted(): ResetStoreSnapshot = ResetStoreSnapshot(history = ResetHistory(
        rows = (1..100).map { ResetHistoryRow("run-$it", it.toLong(), finalDisposition = ResetDisposition.FAILED) },
        total = 0L, nextSequence = 101L))
    private fun encoded(snapshot: ResetStoreSnapshot) = ResetStoreCodec.encode(snapshot).filterValues { it != null }.mapValues { it.value!! }
    private class HistoryDispatch {
        val storage = MemoryResetStorage()
        val active = mutableListOf<PostedHistoryCard>()
        val cancellationAttempts = mutableListOf<Pair<String, Int>>()
        val cancellations = mutableListOf<Pair<String, Int>>()
        val posts = mutableListOf<Pair<String, Int>>()
        var cancelWorks = true
        val store = CheckedResetStore(storage, ::reconcile)
        fun reconcile(snapshot: ResetStoreSnapshot) {
            HistoryNotificationRetirement.reconcile(snapshot, { active.toList() }) { tag, id ->
                cancellationAttempts.add(tag to id)
                if (!cancelWorks) throw IllegalStateException("notification cancellation unavailable")
                cancellations.add(tag to id); active.removeAll { it.tag == tag && it.id == id }
            }
        }
        fun post(runId: String) {
            val snapshot = store.snapshot()
            reconcile(snapshot) // same retry-before-post sequence as RunNotification.history
            val row = HistoryNotificationPlan.visible(snapshot, runId) ?: return
            val identity = HistoryNotificationPlan.identity("com.blreay.bluetoothmonitor.nogui", row.runId)
            posts.add(identity.tag to identity.id)
            active.removeAll { it.tag == identity.tag && it.id == identity.id }
            active.add(PostedHistoryCard(identity.tag, identity.id, HistoryNotificationRetirement.CHANNEL))
        }
        fun swipe(runId: String) { active.removeAll { it.tag == runId && it.id == 103 } }
        fun accept(runId: String): ResetRecord {
            val pending = ResetRecord(runId, "command-$runId")
            assertTrue(store.accept(AcceptedResetMatch(runId, null, null, null, "actual target", 1L), pending))
            return pending
        }
    }
    @Test fun `swipe runtime repost then confirmed delete dispatches exact cancellation without touching retained or unrelated cards`() {
        val f = HistoryDispatch()
        val retained = f.accept("retained")
        assertTrue(f.store.terminal(retained, retained.copy(version = 1), ResetDisposition.FAILED)); f.post("retained")
        val run = "12345678-1111-1111-1111-111111111111"
        val pending = f.accept(run)
        assertTrue(f.store.terminal(pending, pending.copy(version = 1), ResetDisposition.FAILED)); f.post(run)
        val unrelated = (100..104).filter { it != 103 }.map { PostedHistoryCard(run, it, HistoryNotificationRetirement.CHANNEL) } +
            PostedHistoryCard("other-channel", 103, "another-channel") + PostedHistoryCard(null, 103, HistoryNotificationRetirement.CHANNEL)
        f.active.addAll(unrelated)
        f.swipe(run) // NMS removes the card, but delete broadcast has not been delivered
        assertTrue(f.cancellations.isEmpty()); assertFalse(f.store.snapshot().history.rows.last().dismissed)
        f.post(run) // an earlier automatic callback posts before deletion delivery
        assertTrue(f.active.any { it.tag == run && it.id == 103 })
        val before = f.store.snapshot(); val postCount = f.posts.size
        assertTrue(f.store.dismiss(run)) // actual confirmed-snapshot listener dispatches cancellation
        assertEquals(listOf(run to 103), f.cancellations)
        assertFalse(f.active.any { it.tag == run && it.id == 103 })
        assertTrue(f.active.any { it.tag == "retained" && it.id == 103 }); assertTrue(unrelated.all { it in f.active })
        assertEquals(before.copy(history = before.history.dismiss(run)), f.store.snapshot())
        f.post(run); assertEquals(postCount, f.posts.size) // no repost/upsert after confirmed deletion
    }
    @Test fun `explicit dismissal cancels active pending card but preserves unresolved authority identity recovery and row`() {
        val f = HistoryDispatch(); var alarms = 0
        val engine = DurableResetEngine(f.store, { alarms++; true }, { 10_000L }, { "physical-command" })
        val ready = engine.armAccepted(AcceptedResetMatch("pending-run", null, null, null, "actual target", 1L), false)!!
        val command = engine.dispatch(ready, CommandLease.foreground(), false)!!
        assertTrue(engine.nativeEntered(command, 10_001L)); f.post("pending-run")
        val before = f.store.snapshot(); val alarmCount = alarms
        assertFalse(before.pending!!.authorityEnded); assertNotNull(before.pending!!.offEvidence!!.enteredAt)
        assertTrue(f.store.dismiss("pending-run"))
        assertEquals(listOf("pending-run" to 103), f.cancellations); assertTrue(f.active.isEmpty())
        assertEquals(before.pending, f.store.snapshot().pending); assertEquals(alarmCount, alarms)
        assertEquals(before.copy(history = before.history.dismiss("pending-run")), f.store.snapshot())
        assertFalse(f.store.newNativeAllowed()); assertEquals(0L, f.store.snapshot().history.total)
        val posts = f.posts.size; f.post("pending-run"); assertEquals(posts, f.posts.size)
    }
    @Test fun `confirmed pending dismissal survives cancellation failure and cold retries exact identity without repost`() {
        val f = HistoryDispatch(); f.accept("pending-run"); f.post("pending-run"); f.cancelWorks = false
        val pending = f.store.snapshot().pending
        assertTrue(f.store.dismiss("pending-run"))
        assertTrue(f.store.snapshot().history.rows.single().dismissed); assertFalse(f.store.snapshot().storageFailed)
        assertEquals(listOf("pending-run" to 103), f.cancellationAttempts); assertEquals(1, f.active.size)
        val failedRetry = CheckedResetStore(f.storage, f::reconcile)
        assertEquals(pending, failedRetry.snapshot().pending)
        assertEquals(listOf("pending-run" to 103, "pending-run" to 103), f.cancellationAttempts)
        f.cancelWorks = true
        val cold = CheckedResetStore(f.storage, f::reconcile)
        assertEquals(listOf("pending-run" to 103), f.cancellations); assertTrue(f.active.isEmpty())
        assertEquals(pending, cold.snapshot().pending); assertTrue(cold.snapshot().history.rows.single().dismissed)
        assertEquals(1, f.posts.size); assertEquals(0L, cold.snapshot().history.total)
    }
    @Test fun `cold process cancels reposted pending card after confirmed dismissal commit before cancellation`() {
        val f = HistoryDispatch(); f.accept("pending-run"); f.post("pending-run")
        val disconnectedStore = CheckedResetStore(f.storage) // simulate dying after commit with no cancellation delivery
        val before = disconnectedStore.snapshot()
        assertTrue(disconnectedStore.dismiss("pending-run")); assertEquals(1, f.active.size)
        val cold = CheckedResetStore(f.storage, f::reconcile)
        assertEquals(listOf("pending-run" to 103), f.cancellations); assertTrue(f.active.isEmpty())
        assertEquals(before.copy(history = before.history.dismiss("pending-run")), cold.snapshot())
        assertEquals(1, f.posts.size)
    }
    @Test fun `failed eager dismissal commit never dispatches unconfirmed cancellation even after runtime repost`() {
        val f = HistoryDispatch(); f.accept("pending-run"); f.post("pending-run"); f.swipe("pending-run"); f.post("pending-run")
        val before = f.store.snapshot()
        f.storage.works = false; f.storage.eagerFailure = true
        assertFalse(f.store.dismiss("pending-run")); f.reconcile(f.store.snapshot())
        assertTrue(f.cancellationAttempts.isEmpty()); assertTrue(f.cancellations.isEmpty()); assertEquals(1, f.active.size)
        assertEquals(before.pending, f.store.snapshot().pending); assertEquals(before.history, f.store.snapshot().history)
        assertTrue(f.store.snapshot().storageFailed); assertFalse(f.store.snapshot().history.rows.single().dismissed)
        assertEquals(0L, f.store.snapshot().history.total)
    }
    @Test fun `confirmed run101 pruning dispatches only exact evicted active history card cancellation without reposting`() {
        val active = mutableListOf(PostedHistoryCard("run-1", 103, HistoryNotificationRetirement.CHANNEL),
            PostedHistoryCard("run-100", 103, HistoryNotificationRetirement.CHANNEL))
        val unrelated = (100..104).filter { it != 103 }.map { PostedHistoryCard("run-1", it, HistoryNotificationRetirement.CHANNEL) } +
            PostedHistoryCard("other-card", 103, "other-channel") + PostedHistoryCard(null, 103, HistoryNotificationRetirement.CHANNEL)
        active.addAll(unrelated)
        val cancellations = mutableListOf<Pair<String, Int>>()
        val store = CheckedResetStore(MemoryResetStorage(encoded(hundredCompleted()))) { snapshot ->
            HistoryNotificationRetirement.reconcile(snapshot, { active.toList() }) { tag, id ->
                cancellations.add(tag to id); active.removeAll { it.tag == tag && it.id == id }
            }
        }
        val pending = ResetRecord("run-101", "command")
        assertTrue(store.accept(AcceptedResetMatch("run-101", null, null, null, "matched", 1L), pending))
        assertTrue(cancellations.isEmpty()) // 100 terminal rows + protected current pending
        active.add(PostedHistoryCard("run-101", 103, HistoryNotificationRetirement.CHANNEL)) // posted only after confirmed accept
        assertTrue(store.terminal(pending, pending.copy(version = 1), ResetDisposition.FAILED))
        assertEquals(listOf("run-1" to 103), cancellations)
        assertTrue(active.any { it.tag == "run-100" && it.id == 103 })
        assertTrue(active.any { it.tag == "run-101" && it.id == 103 })
        assertTrue(unrelated.filter { it.id != 103 || it.tag == null }.all { it in active })
        assertNull(store.snapshot().pending); assertEquals(0L, store.snapshot().history.total)
        assertEquals(100, store.snapshot().history.rows.size)
    }
    @Test fun `failed eager pruning transaction cannot cancel a card from unconfirmed eviction`() {
        val storage = MemoryResetStorage(encoded(hundredCompleted()))
        val cancellations = mutableListOf<Pair<String, Int>>()
        val active = listOf(PostedHistoryCard("run-1", 103, HistoryNotificationRetirement.CHANNEL))
        val store = CheckedResetStore(storage) { snapshot -> HistoryNotificationRetirement.reconcile(snapshot, { active }) { tag, id -> cancellations.add(tag to id) } }
        val pending = ResetRecord("run-101", "command")
        assertTrue(store.accept(AcceptedResetMatch("run-101", null, null, null, "matched", 1L), pending))
        storage.works = false; storage.eagerFailure = true
        assertFalse(store.terminal(pending, pending.copy(version = 1), ResetDisposition.FAILED))
        assertEquals(0, HistoryNotificationRetirement.reconcile(store.snapshot(), { active }) { tag, id -> cancellations.add(tag to id) })
        assertTrue(cancellations.isEmpty()); assertNotNull(HistoryNotificationPlan.visible(store.snapshot(), "run-1"))
        assertEquals(pending, store.snapshot().pending); assertTrue(store.snapshot().storageFailed)
    }
    @Test fun `cold reconciliation retires own active orphan after crash between confirmed commit and notification cancel`() {
        val storage = MemoryResetStorage(encoded(hundredCompleted()))
        val first = CheckedResetStore(storage) // simulate process dying after commit, before cancellation
        val pending = ResetRecord("run-101", "command")
        assertTrue(first.accept(AcceptedResetMatch("run-101", null, null, null, "matched", 1L), pending))
        assertTrue(first.terminal(pending, pending.copy(version = 1), ResetDisposition.FAILED))
        val active = listOf(PostedHistoryCard("run-1", 103, HistoryNotificationRetirement.CHANNEL),
            PostedHistoryCard("run-100", 103, HistoryNotificationRetirement.CHANNEL),
            PostedHistoryCard("orphan", 103, "other-channel"))
        val cancellations = mutableListOf<Pair<String, Int>>()
        CheckedResetStore(storage) { snapshot -> HistoryNotificationRetirement.reconcile(snapshot, { active }) { tag, id -> cancellations.add(tag to id) } }
        assertEquals(listOf("run-1" to 103), cancellations)
    }
    @Test fun `notification cancellation failure cannot undo confirmed eviction and next snapshot retries exact active identity`() {
        var works = false
        val active = mutableListOf(PostedHistoryCard("run-1", 103, HistoryNotificationRetirement.CHANNEL))
        val cancellations = mutableListOf<Pair<String, Int>>()
        val store = CheckedResetStore(MemoryResetStorage(encoded(hundredCompleted()))) { snapshot ->
            HistoryNotificationRetirement.reconcile(snapshot, { active.toList() }) { tag, id ->
                if (!works) throw IllegalStateException("platform cancel unavailable")
                cancellations.add(tag to id); active.removeAll { it.tag == tag && it.id == id }
            }
        }
        val pending = ResetRecord("run-101", "command")
        assertTrue(store.accept(AcceptedResetMatch("run-101", null, null, null, "matched", 1L), pending))
        assertTrue(store.terminal(pending, pending.copy(version = 1), ResetDisposition.FAILED))
        assertFalse(store.snapshot().storageFailed); assertNull(store.snapshot().pending)
        assertNull(HistoryNotificationPlan.visible(store.snapshot(), "run-1")); assertEquals(1, active.size)
        works = true; assertTrue(store.dismiss("run-100"))
        assertEquals(listOf("run-1" to 103), cancellations); assertTrue(active.isEmpty())
    }
    @Test fun `unreadable ledger cannot retire active cards and pending identity remains protected even without row`() {
        val active = listOf(PostedHistoryCard("pending", 103, HistoryNotificationRetirement.CHANNEL))
        val cancellations = mutableListOf<Pair<String, Int>>()
        fun reconcile(snapshot: ResetStoreSnapshot) = HistoryNotificationRetirement.reconcile(snapshot, { active }) { tag, id -> cancellations.add(tag to id) }
        assertEquals(0, reconcile(ResetStoreSnapshot(integrityBlocked = true)))
        assertEquals(0, reconcile(ResetStoreSnapshot(storageFailed = true)))
        assertEquals(0, reconcile(ResetStoreSnapshot(pending = ResetRecord("pending", "command"))))
        assertTrue(cancellations.isEmpty())
    }
    private class RuntimeLedger {
        val storage = MemoryResetStorage()
        val store = CheckedResetStore(storage)
        var now = 100L
        var serial = 0
        val engine = DurableResetEngine(store, { true }, { now }, { "command-${++serial}" })
        fun readyOn(run: String): ResetRecord {
            val ready = engine.armAccepted(AcceptedResetMatch(run, "target-$run", "address-$run", null, "own advertisement", now++), false)!!
            val off = engine.dispatch(ready, CommandLease.foreground(), false)!!
            engine.nativeEntered(off, now++)
            engine.complete(off, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL).copy(returnedAt = now++, submittedAt = now))
            engine.observe(AdapterState.OFF, now++)
            val on = engine.dispatch(engine.read()!!, CommandLease.foreground(), true)!!
            engine.nativeEntered(on, now++)
            engine.complete(on, SwitchOutcome.submitted(SwitchMode.ORDINARY).copy(returnedAt = now++, submittedAt = now))
            return engine.read()!!
        }
        fun complete(run: String) { readyOn(run); assertEquals(RecoveryStep.RESTORED, engine.observe(AdapterState.ON, now++)) }
    }
    @Test fun `successful terminal records engine decision time atomically rather than state sample or native return time`() {
        val f = RuntimeLedger(); f.readyOn("run")
        assertNull(f.store.snapshot().history.rows.single().terminalRecordedAt)
        f.now = 20_000L
        assertEquals(RecoveryStep.RESTORED, f.engine.observe(AdapterState.ON, 19_000L))
        val snapshot = f.store.snapshot(); val row = snapshot.history.rows.single()
        assertEquals(20_000L, row.terminalRecordedAt)
        assertEquals(19_000L, row.firstOnObservedAt)
        assertNotEquals(row.onEvidence!!.endedAt, row.terminalRecordedAt)
        assertTrue(row.counted); assertEquals(1L, snapshot.history.total); assertNull(snapshot.pending)
        assertEquals(row, CheckedResetStore(f.storage).snapshot().history.rows.single())
        assertTrue(DetailsText.history(row, snapshot).contains("终态决策/记录时间（非硬件切换）：${DetailsText.time(20_000L)}"))
    }
    @Test fun `accepted rejected off then actual on ends FAILED with persisted terminal decision time and zero count`() {
        val f = RuntimeLedger()
        val ready = f.engine.armAccepted(AcceptedResetMatch("failed", "target", "address", null, "matched", f.now), false)!!
        val off = f.engine.dispatch(ready, CommandLease.foreground(), false)!!
        assertTrue(f.engine.nativeEntered(off, 101L))
        assertNotNull(f.engine.complete(off, SwitchOutcome.rejected(SwitchMode.ORDINARY, "OFF refused").copy(returnedAt = 200L)))
        f.now = 1_000L
        assertEquals(RecoveryStep.FAILED, f.engine.observe(AdapterState.ON, 500L))
        val snapshot = f.store.snapshot(); val row = snapshot.history.rows.single()
        assertEquals(ResetDisposition.FAILED, row.finalDisposition); assertEquals(1_000L, row.terminalRecordedAt)
        assertFalse(row.counted); assertEquals(0L, snapshot.history.total); assertNull(snapshot.pending)
        assertNull(row.firstOffObservedAt); assertNull(row.onEvidence)
        assertEquals(row, CheckedResetStore(f.storage).snapshot().history.rows.single())
    }
    @Test fun `failed eager terminal commit cannot confirm or render new terminal time count or removal`() {
        val f = RuntimeLedger(); f.readyOn("run")
        val confirmed = f.store.snapshot(); f.now = 20_000L
        f.storage.works = false; f.storage.eagerFailure = true
        assertEquals(RecoveryStep.STORAGE_FAILED, f.engine.observe(AdapterState.ON, 19_000L))
        val snapshot = f.store.snapshot(); val row = snapshot.history.rows.single()
        assertEquals(confirmed.pending, snapshot.pending); assertEquals(confirmed.history, snapshot.history)
        assertNull(row.terminalRecordedAt); assertFalse(row.counted); assertEquals(0L, snapshot.history.total)
        assertTrue(DetailsText.history(row, snapshot).contains("终态决策/记录时间（非硬件切换）：未知/未记录"))
        assertFalse(DetailsText.history(row, snapshot).contains(DetailsText.time(20_000L)))
        assertTrue(snapshot.storageFailed)
    }
    @Test fun `four engine proven runs expose four independent confirmed cards with own as of totals`() {
        val f = RuntimeLedger()
        val cards = linkedMapOf<HistoryCardIdentity, ResetHistoryRow>()
        repeat(4) { index ->
            val run = "full-uuid-run-$index"
            f.complete(run)
            val snapshot = f.store.snapshot()
            val row = HistoryNotificationPlan.visible(snapshot, run)!!
            cards[HistoryNotificationPlan.identity("pkg", run)] = row
            assertTrue(row.counted); assertEquals(index + 1L, row.asOfTotal)
            assertTrue(DetailsText.history(row, snapshot).contains("真实硬件切换瞬间未知"))
        }
        assertEquals(4, cards.size)
        assertEquals(4L, f.store.snapshot().history.total)
        val first = cards.values.first()
        val selected = DetailsSnapshot(f.store.snapshot(), "current runtime", "authorization", null, emptyMap()).selected(first.runId)!!
        assertEquals(1L, selected.asOfTotal); assertEquals(4L, f.store.snapshot().history.total)
        assertTrue(f.store.dismiss(first.runId))
        assertNull(HistoryNotificationPlan.visible(f.store.snapshot(), first.runId))
        assertEquals(4L, f.store.snapshot().history.total)
    }
    @Test fun `terminal commit crash boundary keeps card uncounted before confirmation and counted after cold reopen`() {
        val f = RuntimeLedger(); f.readyOn("run")
        val before = f.store.snapshot()
        assertFalse(HistoryNotificationPlan.visible(before, "run")!!.counted)
        f.storage.works = false
        assertEquals(RecoveryStep.STORAGE_FAILED, f.engine.observe(AdapterState.ON, f.now++))
        assertEquals(before.pending, f.store.snapshot().pending)
        assertFalse(HistoryNotificationPlan.visible(f.store.snapshot(), "run")!!.counted)
        val cold = CheckedResetStore(f.storage)
        assertEquals(before.pending, cold.snapshot().pending)
        assertEquals(0L, cold.snapshot().history.total)
        f.storage.works = true
        val resumed = DurableResetEngine(cold, { true }, { f.now }, { "unused" })
        assertEquals(RecoveryStep.RESTORED, resumed.observe(AdapterState.ON, f.now++))
        val afterCrash = CheckedResetStore(f.storage).snapshot()
        assertNull(afterCrash.pending); assertEquals(1L, afterCrash.history.total)
        assertTrue(HistoryNotificationPlan.visible(afterCrash, "run")!!.counted)
    }
    @Test fun `history notification plan cannot resurrect trimmed completed rows while current pending stays protected`() {
        val f = RuntimeLedger()
        repeat(104) { f.complete("run-$it") }
        val pending = f.engine.armAccepted(AcceptedResetMatch("active", null, null, null, "actual target", f.now), false)!!
        val snapshot = f.store.snapshot()
        assertEquals(101, snapshot.history.rows.size)
        assertEquals(104L, snapshot.history.total)
        assertNotNull(HistoryNotificationPlan.visible(snapshot, "active"))
        assertNull(HistoryNotificationPlan.visible(snapshot, "run-0"))
        assertNull(f.engine.complete(pending.copy(runId = "run-0"), SwitchOutcome.submitted(SwitchMode.ORDINARY)))
        assertNull(HistoryNotificationPlan.visible(f.store.snapshot(), "run-0"))
        assertEquals(pending, f.store.snapshot().pending)
    }
    @Test fun `four full run ids make four independent cards and direct taps and deletes have distinct identity`() {
        val runs = listOf("12345678-1111-1111-1111-111111111111", "12345678-1111-1111-1111-111111111112", "Aa", "BB")
        val cards = runs.map { HistoryNotificationPlan.identity("com.blreay.bluetoothmonitor.nogui", it) }
        assertEquals(4, cards.map { it.tag to it.id }.distinct().size)
        assertEquals(runs, cards.map { it.tag })
        assertEquals(1, cards.map { it.id }.distinct().size)
        assertNotEquals(100, cards.first().id); assertNotEquals(102, cards.first().id)
        assertEquals(4, cards.map { it.contentAction to it.contentData }.distinct().size)
        assertEquals(4, cards.map { it.deleteAction to it.deleteData }.distinct().size)
        runs.zip(cards).forEach { (run, card) ->
            assertTrue(card.contentData.contains(run)); assertTrue(card.deleteData.contains(run))
            assertNotEquals(card.contentAction, card.deleteAction)
        }
        assertEquals(cards.first(), HistoryNotificationPlan.identity("com.blreay.bluetoothmonitor.nogui", runs.first()))
    }
    @Test fun `confirmed dismissal never resurrects and changes neither total nor pending recovery`() {
        val storage = MemoryResetStorage(); val store = CheckedResetStore(storage)
        val match = AcceptedResetMatch("run", "target", "address", null, "matched", 1L)
        val pending = ResetRecord("run", "off")
        assertTrue(store.accept(match, pending))
        val before = store.snapshot()
        assertNotNull(HistoryNotificationPlan.visible(before, "run"))
        assertTrue(store.dismiss("run"))
        assertNull(HistoryNotificationPlan.visible(store.snapshot(), "run"))
        assertEquals(before.pending, store.snapshot().pending); assertEquals(before.history.total, store.snapshot().history.total)
        assertTrue(store.commit(pending, pending.copy(version = 1, reason = "later event")))
        assertNull(HistoryNotificationPlan.visible(store.snapshot(), "run"))
        assertNull(HistoryNotificationPlan.visible(store.snapshot(), "trimmed-run"))
    }
    @Test fun `failed dismissal retains confirmed visibility and discloses storage failure not pretend deletion`() {
        val storage = MemoryResetStorage(); val store = CheckedResetStore(storage)
        assertTrue(store.accept(AcceptedResetMatch("run", null, null, null, "match", 1L), ResetRecord("run", "off")))
        storage.works = false; storage.eagerFailure = true
        assertFalse(store.dismiss("run"))
        assertFalse(store.snapshot().history.rows.single().dismissed)
        assertTrue(store.snapshot().storageFailed)
        assertNotNull(HistoryNotificationPlan.visible(store.snapshot(), "run"))
        assertNotNull(store.snapshot().pending)
        assertEquals(0L, store.snapshot().history.total)
    }
}
