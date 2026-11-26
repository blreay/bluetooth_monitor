package com.blreay.bluetoothmonitor

import org.json.JSONArray
import org.json.JSONObject

/** Only CONFIRMED writes advance this snapshot. Storage failure is a process-lifetime latch. */
data class ResetStoreSnapshot(
    val pending: ResetRecord? = null,
    val history: ResetHistory = ResetHistory(),
    val manualFence: ManualOperation? = null,
    val manualResults: Map<ManualAction, ManualResult> = emptyMap(),
    val storageFailed: Boolean = false,
    val integrityBlocked: Boolean = false,
    val disclosure: String? = null,
    val legacyHistoryUnrecorded: Boolean = false,
)

interface ResetStorageAdapter {
    fun read(): Map<String, String>
    /** A false result may already have mutated the platform's in-memory cache. */
    fun commit(changes: Map<String, String?>): Boolean
}

/** The actual Android JSON codec. Missing facts remain missing; decoding never allocates ids or times. */
object ResetStoreCodec {
    const val HISTORY_KEY = "reset_history"
    const val TOTAL_KEY = "reset_total"
    const val MANUAL_KEY = "manual_fence"
    const val RESULTS_KEY = "manual_results"
    const val LEGACY_KEY = "history_legacy_unrecorded"
    val keys = setOf("record", "armed", HISTORY_KEY, TOTAL_KEY, MANUAL_KEY, RESULTS_KEY, LEGACY_KEY)
    private const val UNKNOWN_TOTAL = "1.1 以来累计自动重置次数未知；计数不可验证"
    private const val LEGACY_HISTORY = "旧版历史未记录；次数仅统计 1.1 以来已验证的自动重置"

    fun decode(values: Map<String, String>): ResetStoreSnapshot {
        val disclosures = mutableListOf<String>()
        var blocked = false
        val pending = if (values["record"] == null && (values["armed"] == null || values["armed"] == "false")) null else try {
            decodeRecord(JSONObject(requireNotNull(values["record"])))
        } catch (_: Exception) {
            disclosures.add("旧/损坏的待恢复记录无法判定执行结束；保留安全隔离，请检查系统蓝牙状态")
            quarantinePending(disclosures.last())
        }
        val hasEpoch = listOf(HISTORY_KEY, TOTAL_KEY, MANUAL_KEY, RESULTS_KEY, LEGACY_KEY).any { it in values }
        // A fresh install and a completed v1 (which cleared its prefs) are indistinguishable.
        val legacy = values[LEGACY_KEY] == "true" || !hasEpoch
        if (legacy) disclosures.add(LEGACY_HISTORY)
        val total = when (val raw = values[TOTAL_KEY]) {
            null -> if (!hasEpoch) 0L else {
                blocked = true
                disclosures.add("累计次数存储缺失；未知且禁止新执行")
                null
            }
            "null" -> {
                blocked = true
                disclosures.add("累计次数未知；禁止新执行，不能重置为零")
                null
            }
            else -> raw.toLongOrNull()?.takeIf { it >= 0L }.also {
                if (it == null) { blocked = true; disclosures.add("累计次数存储损坏；未知且禁止新执行") }
            }
        }
        if (total == null) disclosures.add(UNKNOWN_TOTAL)
        val history = try {
            values[HISTORY_KEY]?.let { decodeHistory(JSONObject(it), total) } ?: if (hasEpoch) {
                blocked = true
                disclosures.add("自动历史存储缺失；历史和序号未知，禁止新执行")
                ResetHistory(total = total, nextSequence = 0L)
            } else ResetHistory(total = total)
        } catch (_: Exception) {
            blocked = true
            disclosures.add("自动历史存储损坏；不能确认历史完整性，禁止新执行")
            ResetHistory(total = total, nextSequence = 0L)
        }
        val manual = try {
            values[MANUAL_KEY]?.let { decodeManual(JSONObject(it)) }
        } catch (_: Exception) {
            disclosures.add("手动执行记录损坏；旧执行结束未确认，保留安全隔离")
            ManualOperation("unreadable-manual", "unknown-command", action = ManualAction.OFF,
                stage = ManualStage.QUARANTINED, nativeEntered = true, authorityEnded = false,
                reason = disclosures.last())
        }
        val results = try {
            values[RESULTS_KEY]?.let { decodeResults(JSONArray(it)) } ?: emptyMap()
        } catch (_: Exception) {
            disclosures.add("手动上次结果存储损坏；结果未知，执行隔离记录独立保留")
            emptyMap()
        }
        return ResetStoreSnapshot(pending, history, manual, results,
            integrityBlocked = blocked, disclosure = disclosures.takeIf { it.isNotEmpty() }?.joinToString("；"),
            legacyHistoryUnrecorded = legacy)
    }

    /** Null values remove only owned keys. No whole-file clear, so unrelated prefs survive. */
    fun encode(snapshot: ResetStoreSnapshot): Map<String, String?> = mapOf(
        "record" to snapshot.pending?.let { encodeRecord(it).toString() }, "armed" to null,
        LEGACY_KEY to if (snapshot.legacyHistoryUnrecorded) "true" else null,
        HISTORY_KEY to encodeHistory(snapshot.history).toString(), TOTAL_KEY to (snapshot.history.total?.toString() ?: "null"),
        MANUAL_KEY to snapshot.manualFence?.let { encodeManual(it).toString() },
        RESULTS_KEY to JSONArray().apply {
            ManualAction.values().forEach { action -> snapshot.manualResults[action]?.let { put(encodeResult(it)) } }
        }.toString(),
    )

    internal fun quarantinePending(reason: String) = ResetRecord("unreadable-pending", "unknown-command",
        stage = ResetStage.QUARANTINED, authorityEnded = false, reason = reason)

    private fun decodeRecord(json: JSONObject) = ResetRecord(
        runId = json.getString("run"), commandId = json.getString("command"),
        version = json.requiredLong("version"), stage = ResetStage.valueOf(json.getString("stage")),
        phase = ReEnablePolicy.Phase.valueOf(json.getString("phase")), attempts = json.requiredInt("attempts"),
        extraObservations = json.optionalInt("extra"), wantsToast = json.optionalBoolean("toast"),
        offDispatchedAt = json.optionalLong("offDispatch"), offCompletedAt = json.optionalLong("offComplete"),
        dispatchedAt = json.optionalLong("dispatch"), commandDeadline = json.optionalLong("deadline"),
        authorityEnded = json.requiredBoolean("ended"), lastStatus = SwitchOutcome.Status.valueOf(json.getString("status")),
        lastMode = SwitchMode.valueOf(json.getString("mode")), ordinaryOnly = json.optionalBoolean("ordinaryOnly"),
        ordinaryOnAttempts = json.optionalInt("ordinaryAttempts"), lastOrdinaryOnAt = json.optionalLong("ordinaryAt"),
        modeBits = json.optionalInt("modeBits"), history = decodeBackend(json.optJSONArray("history")),
        reason = json.optString("reason").take(1024), firstOffObservedAt = json.nullableLong("firstOffObserved"),
        firstOnObservedAt = json.nullableLong("firstOnObserved"), offEvidence = json.optionalObject("offEvidence")?.let { decodeEvidence(it) },
        onEvidence = json.optionalObject("onEvidence")?.let { decodeEvidence(it) },
        onEnteredAfterOffObservation = json.optionalBoolean("onAfterOff"), durationMs = json.nullableLong("duration"),
        terminalRecordedAt = json.nullableLong("terminalRecorded"),
    ).also { record ->
        require(record.runId.isNotBlank() && record.commandId.isNotBlank())
        require(listOf(record.version, record.attempts.toLong(), record.extraObservations.toLong(),
            record.ordinaryOnAttempts.toLong(), record.modeBits.toLong()).all { it >= 0L })
        require(listOf(record.offDispatchedAt, record.offCompletedAt, record.dispatchedAt, record.commandDeadline,
            record.lastOrdinaryOnAt, record.firstOffObservedAt, record.firstOnObservedAt, record.durationMs, record.terminalRecordedAt).all { it == null || it >= 0L })
        val expectedPhase = when (record.stage) {
            ResetStage.OFF_READY, ResetStage.OFF_DISPATCHED, ResetStage.OFF_SETTLED, ResetStage.ARM_FAILED -> ReEnablePolicy.Phase.WAITING_OFF
            ResetStage.ON_READY, ResetStage.ON_DISPATCHED, ResetStage.ON_SETTLED -> ReEnablePolicy.Phase.WAITING_ON
            ResetStage.EXHAUSTED, ResetStage.QUARANTINED -> null
        }
        require(expectedPhase == null || record.phase == expectedPhase)
        val unresolved = record.stage in listOf(ResetStage.OFF_DISPATCHED, ResetStage.ON_DISPATCHED, ResetStage.QUARANTINED)
        require(record.authorityEnded != unresolved)
    }

    private fun encodeRecord(record: ResetRecord) = JSONObject().apply {
        put("run", record.runId); put("command", record.commandId); put("version", record.version)
        put("stage", record.stage.name); put("phase", record.phase.name); put("attempts", record.attempts)
        put("extra", record.extraObservations); put("toast", record.wantsToast)
        put("offDispatch", record.offDispatchedAt); put("offComplete", record.offCompletedAt)
        put("dispatch", record.dispatchedAt); put("deadline", record.commandDeadline); put("ended", record.authorityEnded)
        put("status", record.lastStatus.name); put("mode", record.lastMode.name); put("ordinaryOnly", record.ordinaryOnly)
        put("ordinaryAttempts", record.ordinaryOnAttempts); put("ordinaryAt", record.lastOrdinaryOnAt)
        put("modeBits", record.modeBits); put("reason", record.reason.take(1024)); put("history", encodeBackend(record.history))
        putNullable("firstOffObserved", record.firstOffObservedAt); putNullable("firstOnObserved", record.firstOnObservedAt)
        record.offEvidence?.let { put("offEvidence", encodeEvidence(it)) }
        record.onEvidence?.let { put("onEvidence", encodeEvidence(it)) }
        put("onAfterOff", record.onEnteredAfterOffObservation); putNullable("duration", record.durationMs)
        putNullable("terminalRecorded", record.terminalRecordedAt)
    }

    private fun decodeEvidence(json: JSONObject) = ResetCommandEvidence(json.getString("run"), json.getString("command"),
        json.nullableLong("entered"), json.optionalBoolean("submitted"), json.optionalBoolean("ended"), json.nullableLong("observed"),
        json.nullableLong("requested"), json.nullableLong("submittedAt"), json.nullableLong("endedAt")).also {
        require(listOf(it.enteredAt, it.observedAt, it.requestedAt, it.submittedAt, it.endedAt).all { time -> time == null || time >= 0L })
    }
    private fun encodeEvidence(evidence: ResetCommandEvidence) = JSONObject().apply {
        put("run", evidence.runId); put("command", evidence.commandId); putNullable("entered", evidence.enteredAt)
        put("submitted", evidence.submitted); put("ended", evidence.authorityEnded); putNullable("observed", evidence.observedAt)
        putNullable("requested", evidence.requestedAt); putNullable("submittedAt", evidence.submittedAt); putNullable("endedAt", evidence.endedAt)
    }

    private fun decodeHistory(json: JSONObject, total: Long?): ResetHistory {
        val array = json.getJSONArray("rows")
        val rows = (0 until array.length()).map { decodeRow(array.getJSONObject(it)) }
        require(rows.map { it.runId }.distinct().size == rows.size && rows.map { it.sequence }.distinct().size == rows.size)
        val maximum = rows.maxOfOrNull { it.sequence } ?: 0L
        val sequence = json.requiredLong("nextSequence")
        require(sequence > maximum && sequence > 0L)
        require(total == null || total >= rows.count { it.counted })
        return ResetHistory(rows, total, sequence)
    }
    private fun encodeHistory(history: ResetHistory) = JSONObject().apply {
        put("nextSequence", history.nextSequence)
        put("rows", JSONArray().apply { history.rows.forEach { put(encodeRow(it)) } })
    }

    private fun decodeRow(json: JSONObject) = ResetHistoryRow(
        runId = json.getString("run"), sequence = json.requiredLong("sequence"),
        advertisementName = json.nullableString("advertisement")?.take(256), address = json.nullableString("address")?.take(64),
        scanFacts = json.optionalObject("facts")?.let { decodeFacts(it) }, scanSummary = json.optString("summary").take(1024),
        acceptedAt = json.nullableLong("accepted"), scanStartedAt = json.nullableLong("scanStart"), scanEndedAt = json.nullableLong("scanEnd"),
        firstOffObservedAt = json.nullableLong("firstOffObserved"), firstOnObservedAt = json.nullableLong("firstOnObserved"),
        offEvidence = json.optionalObject("offEvidence")?.let { decodeEvidence(it) },
        onEvidence = json.optionalObject("onEvidence")?.let { decodeEvidence(it) },
        onEnteredAfterOffObservation = json.optionalBoolean("onAfterOff"), modeBits = json.optionalInt("modeBits"),
        backendHistory = decodeBackend(json.optJSONArray("history")),
        finalDisposition = json.nullableString("disposition")?.let { ResetDisposition.valueOf(it) },
        counted = json.optionalBoolean("counted"), asOfTotal = json.nullableLong("asOfTotal"), dismissed = json.optionalBoolean("dismissed"),
        durationMs = json.nullableLong("duration"), finalReason = json.optString("finalReason").take(1024),
        terminalRecordedAt = json.nullableLong("terminalRecorded"),
    ).also {
        require(it.runId.isNotBlank() && it.sequence > 0L && (it.asOfTotal == null || it.asOfTotal >= 0L))
        require(it.durationMs == null || it.durationMs >= 0L)
        require(it.terminalRecordedAt == null || it.terminalRecordedAt >= 0L)
    }

    private fun encodeRow(row: ResetHistoryRow) = JSONObject().apply {
        put("run", row.runId); put("sequence", row.sequence); putNullable("advertisement", row.advertisementName?.take(256))
        putNullable("address", row.address?.take(64)); row.scanFacts?.let { put("facts", encodeFacts(it)) }
        put("summary", row.scanSummary.take(1024)); putNullable("accepted", row.acceptedAt)
        putNullable("scanStart", row.scanStartedAt); putNullable("scanEnd", row.scanEndedAt)
        putNullable("firstOffObserved", row.firstOffObservedAt); putNullable("firstOnObserved", row.firstOnObservedAt)
        row.offEvidence?.let { put("offEvidence", encodeEvidence(it)) }; row.onEvidence?.let { put("onEvidence", encodeEvidence(it)) }
        put("onAfterOff", row.onEnteredAfterOffObservation); put("modeBits", row.modeBits); put("history", encodeBackend(row.backendHistory))
        putNullable("disposition", row.finalDisposition?.name); put("counted", row.counted)
        putNullable("asOfTotal", row.asOfTotal); put("dismissed", row.dismissed)
        putNullable("duration", row.durationMs); put("finalReason", row.finalReason.take(1024))
        putNullable("terminalRecorded", row.terminalRecordedAt)
    }

    private fun decodeFacts(json: JSONObject) = RunReport.ScanFacts(json.requiredLong("window"), json.requiredLong("elapsed"),
        json.requiredInt("devices"), json.requiredInt("named"), json.requiredInt("near"), json.requiredBoolean("bonded"),
        if (json.has("failure") && !json.isNull("failure")) json.requiredInt("failure") else null)
    private fun encodeFacts(facts: RunReport.ScanFacts) = JSONObject().apply {
        put("window", facts.windowMs); put("elapsed", facts.elapsedMs); put("devices", facts.devicesSeen)
        put("named", facts.namedSeen); put("near", facts.nearMisses); put("bonded", facts.targetBonded); putNullable("failure", facts.scanFailedCode)
    }

    private fun decodeManual(json: JSONObject) = ManualOperation(json.getString("operation"), json.getString("command"),
        json.requiredLong("revision"), ManualAction.valueOf(json.getString("action")), ManualStage.valueOf(json.getString("stage")),
        json.requiredBoolean("entered"), json.requiredBoolean("ended"), json.nullableString("scanner"), json.optionalBoolean("retired"),
        json.optString("reason").take(1024), json.nullableLong("begun")).also {
        require(it.operationId.isNotBlank() && it.commandId.isNotBlank() && it.revision >= 0L)
        require(it.begunAt == null || it.begunAt >= 0L)
    }
    private fun encodeManual(operation: ManualOperation) = JSONObject().apply {
        put("operation", operation.operationId); put("command", operation.commandId); put("revision", operation.revision)
        put("action", operation.action.name); put("stage", operation.stage.name); put("entered", operation.nativeEntered)
        put("ended", operation.authorityEnded); putNullable("scanner", operation.scannerIdentity); put("retired", operation.scannerRetired)
        put("reason", operation.reason.take(1024)); putNullable("begun", operation.begunAt)
    }
    private fun decodeResults(array: JSONArray): Map<ManualAction, ManualResult> {
        require(array.length() <= ManualAction.values().size)
        val results = (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            ManualResult(json.getString("operation"), ManualAction.valueOf(json.getString("action")),
                ManualResultStatus.valueOf(json.getString("status")), json.getString("reason").take(1024),
                json.nullableString("state")?.let { AdapterState.valueOf(it) }, json.nullableLong("completed"),
                json.optionalInt("modeBits"), decodeBackend(json.optJSONArray("history")),
                begunAt = json.nullableLong("begun"), requestedAt = json.nullableLong("requested"),
                nativeEnteredAt = json.nullableLong("nativeEntered"), submittedAt = json.nullableLong("submittedAt"),
                authorityEndedAt = json.nullableLong("authorityEndedAt"), observedAt = json.nullableLong("observedAt"),
                scanStartedAt = json.nullableLong("scanStarted"), scanReturnedAt = json.nullableLong("scanReturned"),
                scanEndedAt = json.nullableLong("scanEnded"), devices = decodeDevices(json.optJSONArray("devices")),
                overflow = json.optionalBoolean("overflow")).also {
                require(listOf(it.begunAt, it.requestedAt, it.nativeEnteredAt, it.submittedAt, it.authorityEndedAt,
                    it.observedAt, it.scanStartedAt, it.scanReturnedAt, it.scanEndedAt, it.completedAt).all { time -> time == null || time >= 0L })
            }
        }
        require(results.map { it.action }.distinct().size == results.size)
        return results.associateBy { it.action }
    }
    private fun encodeResult(result: ManualResult) = JSONObject().apply {
        put("operation", result.operationId); put("action", result.action.name); put("status", result.status.name)
        put("reason", result.reason.take(1024)); putNullable("state", result.observedState?.name); putNullable("completed", result.completedAt)
        put("modeBits", result.modeBits); put("history", encodeBackend(result.backendHistory))
        putNullable("begun", result.begunAt); putNullable("requested", result.requestedAt)
        putNullable("nativeEntered", result.nativeEnteredAt); putNullable("submittedAt", result.submittedAt)
        putNullable("authorityEndedAt", result.authorityEndedAt); putNullable("observedAt", result.observedAt)
        putNullable("scanStarted", result.scanStartedAt); putNullable("scanReturned", result.scanReturnedAt)
        putNullable("scanEnded", result.scanEndedAt); put("overflow", result.overflow)
        put("devices", JSONArray().apply { result.devices.take(100).forEach { device ->
            put(JSONObject().apply {
                put("mac", device.mac.take(64)); put("name", device.name.take(256)); putNullable("rssi", device.rssi)
                putNullable("advertisementName", device.advertisementName?.take(256))
                put("types", JSONArray(device.types.take(4)))
            })
        } })
    }
    private fun decodeDevices(array: JSONArray?): List<DiscoveredDevices.Device> = if (array == null) emptyList() else
        (0 until minOf(array.length(), 100)).map { index ->
            val json = array.getJSONObject(index)
            val types = json.getJSONArray("types")
            DiscoveredDevices.Device(json.getString("mac").take(64), json.getString("name").take(256),
                if (json.isNull("rssi")) null else json.requiredInt("rssi"),
                (0 until minOf(types.length(), 4)).map { types.getString(it).take(32) }.toSet(),
                json.nullableString("advertisementName")?.take(256))
        }
    private fun decodeBackend(array: JSONArray?): List<BackendEntry> = if (array == null) emptyList() else
        (maxOf(0, array.length() - 12) until array.length()).map { index ->
            val json = array.getJSONObject(index)
            BackendEntry(SwitchMode.valueOf(json.getString("mode")), json.getString("reason").take(512))
        }
    private fun encodeBackend(entries: List<BackendEntry>) = JSONArray().apply {
        entries.takeLast(12).forEach { put(JSONObject().put("mode", it.mode.name).put("reason", it.reason.take(512))) }
    }
    private fun JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.requiredLong(key: String): Long {
        val value = get(key)
        require(value is Number)
        return requireNotNull(value.toString().toLongOrNull())
    }
    private fun JSONObject.requiredInt(key: String): Int = requiredLong(key).also {
        require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    }.toInt()
    private fun JSONObject.requiredBoolean(key: String): Boolean {
        val value = get(key)
        require(value is Boolean)
        return value
    }
    private fun JSONObject.optionalLong(key: String): Long = nullableLong(key) ?: 0L
    private fun JSONObject.optionalInt(key: String): Int = if (!has(key) || isNull(key)) 0 else requiredInt(key)
    private fun JSONObject.nullableLong(key: String): Long? = if (!has(key) || isNull(key)) null else requiredLong(key)
    private fun JSONObject.nullableString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    private fun JSONObject.optionalObject(key: String): JSONObject? = if (!has(key) || isNull(key)) null else getJSONObject(key)
    private fun JSONObject.optionalBoolean(key: String): Boolean = if (!has(key) || isNull(key)) false else requiredBoolean(key)
}

/** One synchronized, process-cached checked store; never re-read eager SharedPreferences cache after false. */
class CheckedResetStore(private val storage: ResetStorageAdapter,
                        private val onConfirmedSnapshot: (ResetStoreSnapshot) -> Unit = {}) : ResetPersistence {
    private var confirmed = try { ResetStoreCodec.decode(storage.read()) } catch (_: Exception) {
        ResetStoreSnapshot(pending = ResetStoreCodec.quarantinePending("存储读取失败；禁止新执行，保留安全隔离"),
            history = ResetHistory(total = null), storageFailed = true, integrityBlocked = true,
            disclosure = "存储读取失败；禁止新执行，累计次数未知")
    }
    init { publishConfirmed() } // cold-process reconciliation of commit-before-notification-cancel crashes
    private fun publishConfirmed() {
        // Presentation is best effort and cannot change a successful durable transaction into failure.
        try { onConfirmedSnapshot(confirmed) } catch (_: Exception) { }
    }
    @Synchronized fun snapshot(): ResetStoreSnapshot = confirmed
    @Synchronized override fun read(): ResetRecord? = when {
        confirmed.storageFailed || confirmed.integrityBlocked -> (confirmed.pending ?: ResetStoreCodec.quarantinePending("存储不可确认；禁止新执行"))
            .copy(reason = "${confirmed.pending?.reason.orEmpty().take(700)}；${confirmed.disclosure ?: "存储失败；禁止新执行"}")
        else -> confirmed.pending
    }
    @Synchronized override fun commit(expected: ResetRecord?, next: ResetRecord?): Boolean {
        if (!writable() || confirmed.pending != expected || (expected == null && !newNativeAllowed())) return false
        // Terminal removal must atomically annotate the ledger through terminal(), never this seam.
        if (expected != null && next == null) return false
        val history = next?.let { confirmed.history.updateExisting(it) } ?: confirmed.history
        return write(confirmed.copy(pending = next, history = history))
    }
    @Synchronized override fun terminal(expected: ResetRecord, terminal: ResetRecord, disposition: ResetDisposition): Boolean {
        if (!writable() || confirmed.pending != expected || terminal.runId != expected.runId ||
            terminal.commandId != expected.commandId || terminal.version != expected.version + 1 || !terminal.authorityEnded) return false
        val history = confirmed.history.terminal(terminal, disposition)
        val disclosure = if (history.total == null) listOfNotNull(confirmed.disclosure,
            "1.1 以来累计自动重置次数未知；计数不可验证").distinct().joinToString("；") else confirmed.disclosure
        return write(confirmed.copy(pending = null, history = history, disclosure = disclosure,
            integrityBlocked = history.total == null))
    }
    @Synchronized override fun accept(match: AcceptedResetMatch, pending: ResetRecord): Boolean {
        if (!newNativeAllowed() || pending.runId != match.runId || confirmed.history.rows.any { it.runId == match.runId }) return false
        val history = confirmed.history.accept(match, setOf(pending.runId))
        if (history.rows.none { it.runId == match.runId }) return false
        return write(confirmed.copy(pending = pending, history = history.updateExisting(pending)))
    }
    @Synchronized fun dismiss(runId: String): Boolean {
        if (!writable() || confirmed.history.rows.none { it.runId == runId }) return false
        return write(confirmed.copy(history = confirmed.history.dismiss(runId)))
    }
    @Synchronized fun newNativeAllowed(): Boolean = writable() && confirmed.pending == null && confirmed.manualFence == null
    @Synchronized fun beginManual(operation: ManualOperation, admission: OperationAdmission): Boolean {
        if (!newNativeAllowed() || !admission.allowsNewOperation() || operation.revision != 0L ||
            operation.stage != ManualStage.READY || operation.nativeEntered || !operation.authorityEnded) return false
        return write(confirmed.copy(manualFence = operation))
    }
    @Synchronized fun updateManual(expected: ManualOperation, next: ManualOperation): Boolean {
        if (!writable() || confirmed.manualFence != expected || next.operationId != expected.operationId ||
            next.commandId != expected.commandId || next.action != expected.action || next.revision != expected.revision + 1) return false
        return write(confirmed.copy(manualFence = next))
    }
    /** Same-operation physical fallback only after proven prior authority end; never clear/reacquire. */
    @Synchronized fun nextManualCommand(expected: ManualOperation, commandId: String): ManualOperation? {
        if (!writable() || confirmed.manualFence != expected || !expected.authorityEnded ||
            expected.action == ManualAction.SCAN || expected.stage != ManualStage.SETTLED ||
            commandId.isBlank() || commandId == expected.commandId) return null
        val next = expected.copy(commandId = commandId, revision = expected.revision + 1,
            stage = ManualStage.READY, nativeEntered = false, authorityEnded = true,
            reason = "前次执行结束已确认；同目标新命令准备，不释放手动隔离")
        return if (write(confirmed.copy(manualFence = next))) next else null
    }
    @Synchronized fun finishManual(expected: ManualOperation, result: ManualResult): Boolean {
        if (!writable() || confirmed.manualFence != expected || !expected.canFinish() ||
            result.operationId != expected.operationId || result.action != expected.action) return false
        return write(confirmed.copy(manualFence = null, manualResults = confirmed.manualResults + (result.action to result)))
    }
    /** A rejected new request is a result, never a replacement for the still-active operation. */
    @Synchronized fun rejectManual(result: ManualResult): Boolean {
        if (!writable() || result.status != ManualResultStatus.REJECTED) return false
        return write(confirmed.copy(manualResults = confirmed.manualResults + (result.action to result)))
    }
    private fun writable(): Boolean = !confirmed.storageFailed && !confirmed.integrityBlocked
    private fun write(next: ResetStoreSnapshot): Boolean {
        val accepted = try { storage.commit(ResetStoreCodec.encode(next)) } catch (_: Exception) { false }
        if (accepted) { confirmed = next; publishConfirmed() } else confirmed = confirmed.copy(storageFailed = true,
            disclosure = listOfNotNull(confirmed.disclosure, "存储提交失败；保留最后已确认记录，禁止新执行").joinToString("；"))
        return accepted
    }
}
