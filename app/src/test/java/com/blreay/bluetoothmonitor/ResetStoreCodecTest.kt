package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class ResetStoreCodecTest {
    private val v1 = """{"run":"v1-run-uuid","command":"v1-command-uuid","version":7,"stage":"OFF_DISPATCHED","phase":"WAITING_OFF","attempts":3,"extra":2,"toast":true,"offDispatch":1000,"offComplete":0,"dispatch":1000,"deadline":17000,"ended":false,"status":"NOT_INVOKED","mode":"NONE","ordinaryOnly":false,"ordinaryAttempts":0,"ordinaryAt":0,"modeBits":2,"history":[{"mode":"SHIZUKU_SHELL","reason":"OFF dispatch"}],"reason":"v1 in flight"}"""
    private val minimalV1 = """{"run":"minimal-run","command":"minimal-command","version":9,"stage":"ON_READY","phase":"WAITING_ON","attempts":11,"ended":true,"status":"SUBMITTED","mode":"ORDINARY"}"""
    private fun match(run: String) = AcceptedResetMatch(run, "Xiaomi Band", "AA:BB:CC:DD:EE:FF",
        RunReport.ScanFacts(8_000L, 1_000L, 3, 1, 0, true), "scan", 100L)
    private fun proven(run: String) = ResetRecord(run, "on-$run", version = 8, stage = ResetStage.ON_SETTLED,
        phase = ReEnablePolicy.Phase.WAITING_ON, firstOffObservedAt = 200L, firstOnObservedAt = 400L,
        offEvidence = ResetCommandEvidence(run, "off-$run", 150L, true, true, 200L),
        onEvidence = ResetCommandEvidence(run, "on-$run", 300L, true, true, 400L),
        onEnteredAfterOffObservation = true)

    @Test fun `real v1 record preserves dispatch authority ids and deterministic missing evidence`() {
        val decoded = ResetStoreCodec.decode(mapOf("record" to v1))
        val record = decoded.pending!!
        assertEquals("v1-run-uuid", record.runId)
        assertEquals("v1-command-uuid", record.commandId)
        assertEquals(7L, record.version)
        assertEquals(ResetStage.OFF_DISPATCHED, record.stage)
        assertEquals(ReEnablePolicy.Phase.WAITING_OFF, record.phase)
        assertFalse(record.authorityEnded)
        assertEquals(1_000L, record.offDispatchedAt)
        assertEquals(0L, record.offCompletedAt)
        assertEquals(17_000L, record.commandDeadline)
        assertEquals(2, record.extraObservations)
        assertNull(record.offEvidence)
        assertNull(record.onEvidence)
        assertNull(record.firstOffObservedAt)
        assertTrue(decoded.history.rows.isEmpty())
        assertEquals(0L, decoded.history.total) // new 1.1 verified-count epoch, not a claim about 1.0
        assertTrue(decoded.disclosure!!.contains("旧版历史未记录"))
        assertEquals(decoded, ResetStoreCodec.decode(mapOf("record" to v1)))
        assertEquals(decoded, ResetStoreCodec.decode(ResetStoreCodec.encode(decoded).filterValues { it != null }.mapValues { it.value!! }))
    }

    @Test fun `v1 restoration is uncounted and the next proven new run starts the 1_1 epoch at one`() {
        val storage = MemoryResetStorage(mapOf("record" to minimalV1))
        val store = CheckedResetStore(storage)
        var now = 1_000L
        var serial = 0
        val engine = DurableResetEngine(store, { true }, { now }, { "new-${++serial}" })
        assertEquals(RecoveryStep.RESTORED, engine.observe(AdapterState.ON))
        assertTrue(store.snapshot().history.rows.isEmpty())
        assertEquals(0L, store.snapshot().history.total)
        val ready = requireNotNull(engine.armAccepted(match("new-run"), false))
        val off = requireNotNull(engine.dispatch(ready, CommandLease.foreground(), false))
        now++
        assertTrue(engine.nativeEntered(off))
        now++
        assertNotNull(engine.complete(off, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL)))
        now++
        assertEquals(RecoveryStep.DISPATCH_ON, engine.observe(AdapterState.OFF))
        val on = requireNotNull(engine.dispatch(engine.read()!!, CommandLease.foreground(), true))
        now++
        assertTrue(engine.nativeEntered(on))
        now++
        assertNotNull(engine.complete(on, SwitchOutcome.submitted(SwitchMode.ORDINARY)))
        now++
        assertEquals(RecoveryStep.RESTORED, engine.observe(AdapterState.ON))
        assertEquals(1L, store.snapshot().history.total)
        assertEquals("new-run", store.snapshot().history.rows.single().runId)
        assertTrue(store.snapshot().history.rows.single().counted)
        assertTrue(store.snapshot().disclosure!!.contains("旧版历史未记录"))
        assertEquals(store.snapshot(), CheckedResetStore(storage).snapshot())
    }

    @Test fun `optional v1 fields default without manufactured entry observations or rows`() {
        val decoded = ResetStoreCodec.decode(mapOf("record" to minimalV1))
        val record = decoded.pending!!
        assertEquals(ResetStage.ON_READY, record.stage)
        assertEquals(9L, record.version)
        assertEquals(11, record.attempts)
        assertTrue(record.authorityEnded)
        assertEquals(0L, record.offDispatchedAt)
        assertEquals(0L, record.offCompletedAt)
        assertNull(record.firstOnObservedAt)
        assertFalse(record.onEnteredAfterOffObservation)
        assertTrue(record.history.isEmpty())
        assertTrue(decoded.history.rows.isEmpty())
        assertEquals(0L, decoded.history.total)
    }

    @Test fun `malformed pending and armed legacy never read as empty while ledger remains independently readable`() {
        val ledger = ResetStoreSnapshot(history = ResetHistory().accept(match("history-run")))
        val valid = ResetStoreCodec.encode(ledger).filterValues { it != null }.mapValues { it.value!! }
        listOf("{broken", "{}", v1.replace("OFF_DISPATCHED", "NO_SUCH_STAGE")).forEach { raw ->
            val decoded = ResetStoreCodec.decode(valid + ("record" to raw))
            assertEquals(ResetStage.QUARANTINED, decoded.pending!!.stage)
            assertFalse(decoded.pending!!.authorityEnded)
            assertEquals("history-run", decoded.history.rows.single().runId)
            assertEquals(0L, decoded.history.total)
        }
        val legacy = ResetStoreCodec.decode(mapOf("armed" to "true"))
        assertNotNull(legacy.pending)
        assertFalse(legacy.pending!!.authorityEnded)
        assertEquals(0L, legacy.history.total)
    }

    @Test fun `malformed legacy armed types and present invalid optional pending facts quarantine rather than defaulting`() {
        val rawRecords = listOf(
            v1.replace("\"version\":7", "\"version\":7.5"),
            v1.replace("\"offComplete\":0", "\"offComplete\":\"invalid\""),
            v1.replace("\"ended\":false", "\"ended\":\"true\""),
            v1.replace("\"offDispatch\":1000", "\"offDispatch\":1000.5"),
        )
        rawRecords.forEachIndexed { index, raw ->
            val decoded = ResetStoreCodec.decode(mapOf("record" to raw))
            assertEquals("fixture $index", ResetStage.QUARANTINED, decoded.pending!!.stage)
            assertFalse("fixture $index", decoded.pending!!.authorityEnded)
        }
        val badArmed = ResetStoreCodec.decode(mapOf("armed" to "unreadable-preference-type"))
        assertNotNull(badArmed.pending)
        assertFalse(badArmed.pending!!.authorityEnded)
        assertNull(ResetStoreCodec.decode(mapOf("armed" to "false")).pending)
    }

    @Test fun `dispatched v1 record with ended authority cannot use actual off to prepare on`() {
        val raw = v1.replace("\"ended\":false", "\"ended\":true")
        val store = CheckedResetStore(MemoryResetStorage(mapOf("record" to raw)))
        val engine = DurableResetEngine(store, { true }, { 20_000L }, { "must-not-allocate-on" })
        assertEquals(RecoveryStep.OBSERVE, engine.observe(AdapterState.OFF))
        assertEquals(ResetStage.QUARANTINED, engine.read()!!.stage)
        assertFalse(engine.read()!!.authorityEnded)
        assertFalse(engine.retryRecovery(AdapterState.OFF))
        assertNull(engine.dispatch(engine.read()!!, CommandLease.foreground(), true))
    }

    @Test fun `negative v1 completion time cannot authorize stable wrong Shizuku fallback`() {
        val raw = v1.replace("OFF_DISPATCHED", "OFF_SETTLED")
            .replace("\"ended\":false", "\"ended\":true")
            .replace("NOT_INVOKED", "SUBMITTED").replace("\"mode\":\"NONE\"", "\"mode\":\"SHIZUKU_SHELL\"")
            .replace("\"offComplete\":0", "\"offComplete\":-1")
        val store = CheckedResetStore(MemoryResetStorage(mapOf("record" to raw)))
        val engine = DurableResetEngine(store, { true }, { 20_000L }, { "must-not-allocate-fallback" })
        assertEquals(RecoveryStep.OBSERVE, engine.observe(AdapterState.ON))
        assertEquals(ResetStage.QUARANTINED, engine.read()!!.stage)
        assertFalse(engine.read()!!.authorityEnded)
        assertNull(engine.dispatch(engine.read()!!, CommandLease.foreground(), false))
    }

    @Test fun `impossible v1 stage phase authority combinations quarantine while valid absent evidence survives`() {
        val valid = listOf(
            Triple(ResetStage.OFF_READY, ReEnablePolicy.Phase.WAITING_OFF, true),
            Triple(ResetStage.OFF_DISPATCHED, ReEnablePolicy.Phase.WAITING_OFF, false),
            Triple(ResetStage.OFF_SETTLED, ReEnablePolicy.Phase.WAITING_OFF, true),
            Triple(ResetStage.ON_READY, ReEnablePolicy.Phase.WAITING_ON, true),
            Triple(ResetStage.ON_DISPATCHED, ReEnablePolicy.Phase.WAITING_ON, false),
            Triple(ResetStage.ON_SETTLED, ReEnablePolicy.Phase.WAITING_ON, true),
            Triple(ResetStage.ARM_FAILED, ReEnablePolicy.Phase.WAITING_OFF, true),
            Triple(ResetStage.EXHAUSTED, ReEnablePolicy.Phase.WAITING_OFF, true),
            Triple(ResetStage.EXHAUSTED, ReEnablePolicy.Phase.WAITING_ON, true),
            Triple(ResetStage.QUARANTINED, ReEnablePolicy.Phase.WAITING_OFF, false),
            Triple(ResetStage.QUARANTINED, ReEnablePolicy.Phase.WAITING_ON, false),
        )
        valid.forEach { (stage, phase, ended) ->
            val raw = minimalV1.replace("ON_READY", stage.name).replace("WAITING_ON", phase.name)
                .replace("\"ended\":true", "\"ended\":$ended")
            val record = ResetStoreCodec.decode(mapOf("record" to raw)).pending!!
            assertEquals(stage, record.stage)
            assertEquals(phase, record.phase)
            assertEquals(ended, record.authorityEnded)
            assertEquals("minimal-run", record.runId)
            assertEquals("minimal-command", record.commandId)
            assertEquals(9L, record.version)
            assertNull(record.offEvidence)
            assertNull(record.onEvidence)
            assertEquals(0L, record.offCompletedAt)
            val impossibleAuthority = raw.replace("\"ended\":$ended", "\"ended\":${!ended}")
            val quarantined = ResetStoreCodec.decode(mapOf("record" to impossibleAuthority)).pending!!
            assertEquals("invalid authority for $stage", "unreadable-pending", quarantined.runId)
            assertFalse(quarantined.authorityEnded)
            if (stage != ResetStage.EXHAUSTED && stage != ResetStage.QUARANTINED) {
                val wrongPhase = if (phase == ReEnablePolicy.Phase.WAITING_OFF) ReEnablePolicy.Phase.WAITING_ON else ReEnablePolicy.Phase.WAITING_OFF
                val impossiblePhase = raw.replace(phase.name, wrongPhase.name)
                assertEquals("invalid phase for $stage", "unreadable-pending",
                    ResetStoreCodec.decode(mapOf("record" to impossiblePhase)).pending!!.runId)
            }
        }
    }

    @Test fun `present negative pending counters and times quarantine instead of becoming recovery facts`() {
        val negativeFields = listOf("version", "attempts", "extra", "offDispatch", "offComplete", "dispatch", "deadline",
            "ordinaryAttempts", "ordinaryAt", "modeBits", "firstOffObserved", "firstOnObserved")
        negativeFields.forEach { key ->
            val json = org.json.JSONObject(v1).put(key, -1L)
            val record = ResetStoreCodec.decode(mapOf("record" to json.toString())).pending!!
            assertEquals("negative $key", ResetStage.QUARANTINED, record.stage)
            assertFalse("negative $key", record.authorityEnded)
        }
        for (phase in listOf("offEvidence", "onEvidence")) {
            for (time in listOf("entered", "observed")) {
                val evidence = org.json.JSONObject().put("run", "v1-run-uuid").put("command", "v1-command-uuid").put(time, -1L)
                val json = org.json.JSONObject(v1).put(phase, evidence)
                val record = ResetStoreCodec.decode(mapOf("record" to json.toString())).pending!!
                assertEquals("negative $phase.$time", ResetStage.QUARANTINED, record.stage)
            }
        }
    }

    @Test fun `corrupt ledger and total disclose independently without silently assigning zero`() {
        val initial = ResetStoreCodec.encode(ResetStoreSnapshot(history = ResetHistory().accept(match("run"))))
            .filterValues { it != null }.mapValues { it.value!! }
        val badLedger = ResetStoreCodec.decode(initial + (ResetStoreCodec.HISTORY_KEY to "{bad"))
        assertTrue(badLedger.integrityBlocked)
        assertFalse(badLedger.disclosure.isNullOrBlank())
        assertEquals(0L, badLedger.history.total) // independently valid counter is retained
        val badCount = ResetStoreCodec.decode(initial + (ResetStoreCodec.TOTAL_KEY to "not-a-number"))
        assertTrue(badCount.integrityBlocked)
        assertNull(badCount.history.total)
        assertEquals("run", badCount.history.rows.single().runId)
        assertFalse(badCount.disclosure.isNullOrBlank())
        listOf("-1", "9223372036854775808", "1.5").forEach { value ->
            assertNull(ResetStoreCodec.decode(initial + (ResetStoreCodec.TOTAL_KEY to value)).history.total)
        }
        val both = ResetStoreCodec.decode(initial + mapOf(ResetStoreCodec.HISTORY_KEY to "bad", ResetStoreCodec.TOTAL_KEY to "bad"))
        assertNull(both.history.total)
        assertTrue(both.integrityBlocked)
    }

    @Test fun `empty old storage initializes known 1_1 epoch with persistent unrecorded legacy disclosure`() {
        val decoded = ResetStoreCodec.decode(emptyMap())
        assertEquals(0L, decoded.history.total)
        assertEquals(1L, decoded.history.nextSequence)
        assertTrue(decoded.legacyHistoryUnrecorded)
        assertTrue(decoded.disclosure!!.contains("旧版历史未记录"))
        assertFalse(decoded.integrityBlocked)
        val encoded = ResetStoreCodec.encode(decoded).filterValues { it != null }.mapValues { it.value!! }
        assertEquals(decoded, ResetStoreCodec.decode(encoded))
        val store = CheckedResetStore(MemoryResetStorage())
        assertTrue(store.newNativeAllowed())
        assertTrue(store.accept(match("new"), ResetRecord("new", "off")))
        assertTrue(store.snapshot().legacyHistoryUnrecorded)
        assertTrue(store.snapshot().disclosure!!.contains("旧版历史未记录"))
    }

    @Test fun `standalone valid epoch counter discloses missing ledger and blocks resetting ordinal or admission`() {
        val store = CheckedResetStore(MemoryResetStorage(mapOf(ResetStoreCodec.TOTAL_KEY to "42")))
        val snapshot = store.snapshot()
        assertEquals(42L, snapshot.history.total)
        assertTrue(snapshot.integrityBlocked)
        assertTrue(snapshot.disclosure!!.contains("历史"))
        assertEquals(0L, snapshot.history.nextSequence) // unknown ordinal, never a fabricated new sequence 1
        assertFalse(store.newNativeAllowed())
        assertNotNull(store.read())
        assertFalse(store.accept(match("new"), ResetRecord("new", "off")))
        assertFalse(store.beginManual(ManualOperation("manual", "on", action = ManualAction.ON), OperationAdmission()))
        assertEquals(snapshot.history, snapshot.history.accept(match("new")))
    }

    @Test fun `owned partial epoch markers cannot masquerade as empty legacy storage`() {
        val full = ResetStoreCodec.encode(ResetStoreSnapshot(manualFence = ManualOperation("manual", "off", action = ManualAction.OFF)))
            .filterValues { it != null }.mapValues { it.value!! }
        val partialEpochs = listOf(
            mapOf(ResetStoreCodec.MANUAL_KEY to full.getValue(ResetStoreCodec.MANUAL_KEY)),
            mapOf(ResetStoreCodec.RESULTS_KEY to "[]"),
            mapOf(ResetStoreCodec.LEGACY_KEY to "true"),
        )
        partialEpochs.forEach { values ->
            val store = CheckedResetStore(MemoryResetStorage(values))
            assertTrue("partial ${values.keys}", store.snapshot().integrityBlocked)
            assertNull("partial ${values.keys}", store.snapshot().history.total)
            assertEquals(0L, store.snapshot().history.nextSequence)
            assertFalse(store.snapshot().disclosure.isNullOrBlank())
            assertFalse(store.newNativeAllowed())
            assertFalse(store.commit(null, ResetRecord("new", "off")))
        }
    }

    @Test fun `intact epoch retains ordinal while lost ledger retains independent total and unknown ordinal`() {
        val original = ResetStoreSnapshot(history = ResetHistory(total = 17L, nextSequence = 29L))
        val encoded = ResetStoreCodec.encode(original).filterValues { it != null }.mapValues { it.value!! }
        val intact = ResetStoreCodec.decode(encoded)
        assertEquals(29L, intact.history.nextSequence)
        assertEquals(17L, intact.history.total)
        assertFalse(intact.integrityBlocked)
        val lost = CheckedResetStore(MemoryResetStorage(encoded - ResetStoreCodec.HISTORY_KEY))
        assertEquals(17L, lost.snapshot().history.total)
        assertEquals(0L, lost.snapshot().history.nextSequence)
        assertTrue(lost.snapshot().integrityBlocked)
        assertFalse(lost.newNativeAllowed())
        val missingCount = CheckedResetStore(MemoryResetStorage(encoded - ResetStoreCodec.TOTAL_KEY))
        assertNull(missingCount.snapshot().history.total)
        assertEquals(29L, missingCount.snapshot().history.nextSequence)
        assertFalse(missingCount.newNativeAllowed())
    }

    @Test fun `missing or explicitly unknown existing counter blocks new work without resetting total`() {
        val encoded = ResetStoreCodec.encode(ResetStoreSnapshot(history = ResetHistory().accept(match("run"))))
            .filterValues { it != null }.mapValues { it.value!! }
        for (values in listOf(encoded - ResetStoreCodec.TOTAL_KEY, encoded + (ResetStoreCodec.TOTAL_KEY to "null"))) {
            val store = CheckedResetStore(MemoryResetStorage(values))
            assertNull(store.snapshot().history.total)
            assertTrue(store.snapshot().integrityBlocked)
            assertFalse(store.newNativeAllowed())
            assertNotNull(store.read())
            assertFalse(store.commit(null, ResetRecord("new", "new-off")))
        }
    }

    @Test fun `counter overflow preserves unknown rather than wrapping and blocks following new commands`() {
        val initial = ResetStoreSnapshot(history = ResetHistory(total = Long.MAX_VALUE))
        val storage = MemoryResetStorage(ResetStoreCodec.encode(initial).filterValues { it != null }.mapValues { it.value!! })
        val store = CheckedResetStore(storage)
        val current = proven("run")
        assertTrue(store.accept(match("run"), current))
        assertTrue(store.terminal(current, current.copy(version = 9), ResetDisposition.RESTORED))
        assertNull(store.snapshot().history.total)
        assertFalse(store.snapshot().history.rows.single().counted)
        assertFalse(store.newNativeAllowed())
        assertNotNull(store.read())
        assertFalse(CheckedResetStore(storage).newNativeAllowed())
    }

    @Test fun `pending commits and removal preserve dismissed history manual results and unrelated preference keys`() {
        val storage = MemoryResetStorage(mapOf("unrelated" to "keep"))
        val store = CheckedResetStore(storage)
        val ready = ResetRecord("run", "off")
        assertTrue(store.accept(match("run"), ready))
        assertTrue(store.dismiss("run"))
        assertTrue(store.rejectManual(ManualResult("rejected", ManualAction.SCAN, ManualResultStatus.REJECTED, "pending")))
        val next = ready.copy(version = 1, reason = "updated")
        assertTrue(store.commit(ready, next))
        assertTrue(store.terminal(next, next.copy(version = 2), ResetDisposition.FAILED))
        val cold = CheckedResetStore(storage).snapshot()
        assertNull(cold.pending)
        assertTrue(cold.history.rows.single().dismissed)
        assertEquals(ResetDisposition.FAILED, cold.history.rows.single().finalDisposition)
        assertEquals("rejected", cold.manualResults[ManualAction.SCAN]!!.operationId)
        assertEquals("keep", storage.values["unrelated"])
    }

    @Test fun `failed terminal eagerly mutating cache retains confirmed pending count and history and latches closed`() {
        val storage = MemoryResetStorage()
        val store = CheckedResetStore(storage)
        val terminal = proven("run")
        assertTrue(store.accept(match("run"), terminal))
        val before = store.snapshot()
        storage.works = false
        storage.eagerFailure = true
        assertFalse(store.terminal(terminal, terminal.copy(version = 9), ResetDisposition.RESTORED))
        assertFalse(storage.values.containsKey("record")) // platform cache really changed despite false
        val failed = store.snapshot()
        assertEquals(before.pending, failed.pending)
        assertEquals(before.history, failed.history)
        assertTrue(failed.storageFailed)
        assertTrue(failed.disclosure!!.contains("存储"))
        assertNotNull(store.read())
        assertFalse(store.newNativeAllowed())
        val writes = storage.writes
        storage.works = true
        assertFalse(store.commit(terminal, terminal.copy(version = 9)))
        assertFalse(store.accept(match("new"), ResetRecord("new", "new-off")))
        assertFalse(store.beginManual(ManualOperation("manual", "manual-on", action = ManualAction.ON), OperationAdmission()))
        assertEquals(writes, storage.writes)
        assertEquals(before.pending, store.snapshot().pending)
    }

    @Test fun `failed initial arm with eager cache never admits off on reread or another attempt`() {
        val storage = MemoryResetStorage().apply { works = false; eagerFailure = true }
        val store = CheckedResetStore(storage)
        val engine = DurableResetEngine(store, { true }, { 100L }, { "off-command" })
        assertNull(engine.arm("run", false))
        assertNull(store.snapshot().pending)
        assertNotNull(store.read()) // fail-closed sentinel for existing Android callers
        assertTrue(store.snapshot().storageFailed)
        assertNull(engine.arm("run-2", false))
        assertEquals(1, storage.writes)
    }

    @Test fun `write exceptions retain confirmed state and cannot be cleared by later successful cache reads`() {
        val storage = MemoryResetStorage()
        val store = CheckedResetStore(storage)
        val ready = ResetRecord("run", "off")
        assertTrue(store.commit(null, ready))
        storage.throws = true
        storage.eagerFailure = true
        storage.works = false
        assertFalse(store.commit(ready, ready.copy(version = 1)))
        assertEquals(ready, store.snapshot().pending)
        assertTrue(store.snapshot().storageFailed)
        assertFalse(store.newNativeAllowed())
    }

    @Test fun `exact terminal cas rejects run command revision and stale callbacks without any write`() {
        val storage = MemoryResetStorage()
        val store = CheckedResetStore(storage)
        val current = proven("run")
        assertTrue(store.accept(match("run"), current))
        val writes = storage.writes
        listOf(current.copy(runId = "other"), current.copy(commandId = "other"), current.copy(version = 7)).forEach { stale ->
            assertFalse(store.terminal(stale, stale.copy(version = stale.version + 1), ResetDisposition.RESTORED))
        }
        assertEquals(writes, storage.writes)
        assertTrue(store.terminal(current, current.copy(version = 9), ResetDisposition.RESTORED))
        val done = store.snapshot()
        assertNull(done.pending)
        assertEquals(1L, done.history.total)
        assertTrue(done.history.rows.single().counted)
        assertFalse(store.terminal(current, current.copy(version = 9), ResetDisposition.RESTORED))
        assertEquals(done, store.snapshot())
    }

    @Test fun `crash before and after atomic terminal yields only complete old or complete new snapshot`() {
        val storage = MemoryResetStorage()
        val store = CheckedResetStore(storage)
        val current = proven("run")
        assertTrue(store.accept(match("run"), current))
        val before = CheckedResetStore(storage).snapshot()
        assertEquals(current, before.pending)
        assertFalse(before.history.rows.single().counted)
        assertEquals(0L, before.history.total)
        val writes = storage.writes
        assertTrue(store.terminal(current, current.copy(version = 9), ResetDisposition.RESTORED))
        assertEquals(writes + 1, storage.writes)
        val after = CheckedResetStore(storage).snapshot()
        assertNull(after.pending)
        assertTrue(after.history.rows.single().counted)
        assertEquals(1L, after.history.total)
        assertEquals(ResetDisposition.RESTORED, after.history.rows.single().finalDisposition)
    }

    @Test fun `details metadata roundtrips captured optional times backend evidence and bounded advertisement results`() {
        val devices = DiscoveredDevices(100).apply { repeat(150) { add("address-$it", "advertisement-$it", -it, "BLE") } }
        val result = ManualResult("manual", ManualAction.SCAN, ManualResultStatus.COMPLETED, "display only",
            completedAt = 200L, modeBits = 0, begunAt = 100L, requestedAt = 110L,
            nativeEnteredAt = 120L, authorityEndedAt = 130L, observedAt = 150L,
            scanStartedAt = 120L, scanReturnedAt = 130L, scanEndedAt = 180L,
            devices = devices.snapshot(), overflow = devices.overflow)
        val manual = ManualOperation("manual", "command", action = ManualAction.SCAN,
            stage = ManualStage.RETIRING, nativeEntered = true, authorityEnded = true,
            scannerIdentity = "manual/7", begunAt = 100L)
        val evidence = ResetCommandEvidence("run", "on-run", 140L, true, true, 170L, 110L, 150L, 160L)
        val row = ResetHistory().accept(match("run")).rows.single().copy(onEvidence = evidence,
            durationMs = 900L, finalReason = "captured observation")
        val snapshot = ResetStoreSnapshot(history = ResetHistory(rows = listOf(row), nextSequence = 2L),
            manualFence = manual, manualResults = mapOf(ManualAction.SCAN to result))
        val encoded = ResetStoreCodec.encode(snapshot).filterValues { it != null }.mapValues { it.value!! }
        val decoded = ResetStoreCodec.decode(encoded)
        assertEquals(snapshot, decoded)
        assertEquals(100, decoded.manualResults[ManualAction.SCAN]!!.devices.size)
        assertTrue(decoded.manualResults[ManualAction.SCAN]!!.overflow)
        assertEquals(evidence, decoded.history.rows.single().onEvidence)
        assertEquals(decoded, ResetStoreCodec.decode(encoded))
    }
    @Test fun `missing new metadata stays unknown and never allocates wall time duration or entry evidence`() {
        val result = ManualResult("old-manual", ManualAction.OFF, ManualResultStatus.FAILED, "old result")
        val snapshot = ResetStoreSnapshot(pending = proven("run"), manualResults = mapOf(ManualAction.OFF to result))
        val values = ResetStoreCodec.encode(snapshot).filterValues { it != null }.mapValues { it.value!! }.toMutableMap()
        val json = org.json.JSONArray(values.getValue(ResetStoreCodec.RESULTS_KEY))
        listOf("begun", "requested", "nativeEntered", "submittedAt", "authorityEndedAt", "observedAt", "scanStarted", "scanReturned", "scanEnded", "devices", "overflow").forEach { json.getJSONObject(0).remove(it) }
        values[ResetStoreCodec.RESULTS_KEY] = json.toString()
        val pending = org.json.JSONObject(values.getValue("record")); pending.remove("duration")
        for (key in listOf("offEvidence", "onEvidence")) {
            pending.getJSONObject(key).also { e -> listOf("requested", "submittedAt", "endedAt").forEach { e.remove(it) } }
        }
        values["record"] = pending.toString()
        val decoded = ResetStoreCodec.decode(values)
        assertEquals(result, decoded.manualResults[ManualAction.OFF])
        assertNull(decoded.pending!!.durationMs)
        assertNull(decoded.pending!!.onEvidence!!.requestedAt)
        assertNull(decoded.pending!!.onEvidence!!.submittedAt)
        assertNull(decoded.pending!!.onEvidence!!.endedAt)
        assertEquals(decoded, ResetStoreCodec.decode(values))
    }
    @Test fun `negative new manual timestamps disclose unknown result or preserve quarantined operation`() {
        val snapshot = ResetStoreSnapshot(manualFence = ManualOperation("manual", "command", action = ManualAction.OFF),
            manualResults = mapOf(ManualAction.OFF to ManualResult("manual", ManualAction.OFF, ManualResultStatus.FAILED, "result")))
        val values = ResetStoreCodec.encode(snapshot).filterValues { it != null }.mapValues { it.value!! }
        val badFence = org.json.JSONObject(values.getValue(ResetStoreCodec.MANUAL_KEY)).put("begun", -1)
        val fence = ResetStoreCodec.decode(values + (ResetStoreCodec.MANUAL_KEY to badFence.toString())).manualFence!!
        assertEquals(ManualStage.QUARANTINED, fence.stage); assertFalse(fence.authorityEnded)
        for (key in listOf("begun", "requested", "nativeEntered", "submittedAt", "authorityEndedAt", "observedAt", "scanStarted", "scanReturned", "scanEnded", "completed")) {
            val bad = org.json.JSONArray(values.getValue(ResetStoreCodec.RESULTS_KEY)); bad.getJSONObject(0).put(key, -1)
            val decoded = ResetStoreCodec.decode(values + (ResetStoreCodec.RESULTS_KEY to bad.toString()))
            assertTrue("negative $key must not become an actual time", decoded.manualResults.isEmpty())
            assertEquals(snapshot.manualFence, decoded.manualFence)
            assertTrue(decoded.disclosure!!.contains("结果未知"))
        }
    }
    @Test fun `negative new durations cannot masquerade as verified pending or history timing`() {
        val pending = org.json.JSONObject(minimalV1).put("duration", -1)
        assertEquals(ResetStage.QUARANTINED, ResetStoreCodec.decode(mapOf("record" to pending.toString())).pending!!.stage)
        val values = ResetStoreCodec.encode(ResetStoreSnapshot(history = ResetHistory().accept(match("run"))))
            .filterValues { it != null }.mapValues { it.value!! }
        val history = org.json.JSONObject(values.getValue(ResetStoreCodec.HISTORY_KEY))
        history.getJSONArray("rows").getJSONObject(0).put("duration", -1)
        val decoded = ResetStoreCodec.decode(values + (ResetStoreCodec.HISTORY_KEY to history.toString()))
        assertTrue(decoded.integrityBlocked); assertEquals(0L, decoded.history.total)
    }
    @Test fun `terminal recording metadata roundtrips and nullable legacy defaults never infer sample or return time`() {
        val row = ResetHistory().accept(match("run")).rows.single().copy(finalDisposition = ResetDisposition.FAILED,
            terminalRecordedAt = 2_000L)
        val snapshot = ResetStoreSnapshot(pending = proven("pending").copy(terminalRecordedAt = 3_000L),
            history = ResetHistory(rows = listOf(row), nextSequence = 2L))
        val values = ResetStoreCodec.encode(snapshot).filterValues { it != null }.mapValues { it.value!! }.toMutableMap()
        assertEquals(snapshot, ResetStoreCodec.decode(values))
        val history = org.json.JSONObject(values.getValue(ResetStoreCodec.HISTORY_KEY))
        history.getJSONArray("rows").getJSONObject(0).remove("terminalRecorded")
        values[ResetStoreCodec.HISTORY_KEY] = history.toString()
        val pending = org.json.JSONObject(values.getValue("record")); pending.remove("terminalRecorded"); values["record"] = pending.toString()
        val decoded = ResetStoreCodec.decode(values)
        assertNull(decoded.history.rows.single().terminalRecordedAt); assertNull(decoded.pending!!.terminalRecordedAt)
        assertEquals(decoded, ResetStoreCodec.decode(values))
    }
    @Test fun `present invalid terminal recording metadata blocks history and quarantines pending without fabricating time`() {
        val values = ResetStoreCodec.encode(ResetStoreSnapshot(history = ResetHistory().accept(match("run"))))
            .filterValues { it != null }.mapValues { it.value!! }
        for (bad in listOf<Any>(-1L, 1.5, "1000", true)) {
            val history = org.json.JSONObject(values.getValue(ResetStoreCodec.HISTORY_KEY))
            history.getJSONArray("rows").getJSONObject(0).put("terminalRecorded", bad)
            val decoded = ResetStoreCodec.decode(values + (ResetStoreCodec.HISTORY_KEY to history.toString()))
            assertTrue("invalid history terminal time $bad", decoded.integrityBlocked)
            assertEquals(0L, decoded.history.total)
            val pending = org.json.JSONObject(minimalV1).put("terminalRecorded", bad)
            val record = ResetStoreCodec.decode(mapOf("record" to pending.toString())).pending!!
            assertEquals("invalid pending terminal time $bad", ResetStage.QUARANTINED, record.stage)
            assertNull(record.terminalRecordedAt); assertFalse(record.authorityEnded)
        }
    }
    @Test fun `corrupt manual fence quarantines and blocks both kinds of new operation`() {
        val store = CheckedResetStore(MemoryResetStorage(mapOf(ResetStoreCodec.MANUAL_KEY to "{bad")))
        assertNotNull(store.snapshot().manualFence)
        assertFalse(store.snapshot().manualFence!!.authorityEnded)
        assertFalse(store.newNativeAllowed())
        assertFalse(store.commit(null, ResetRecord("new", "new-off")))
    }
}
