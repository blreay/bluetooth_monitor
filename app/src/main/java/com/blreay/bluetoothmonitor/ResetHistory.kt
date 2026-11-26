package com.blreay.bluetoothmonitor

/** A claimed automatic advertisement match. Creation is never a late callback/upsert. */
data class AcceptedResetMatch(
    val runId: String,
    val advertisementName: String?,
    val address: String?,
    val scanFacts: RunReport.ScanFacts?,
    val scanSummary: String,
    val acceptedAt: Long?,
    val scanStartedAt: Long? = null,
    val scanEndedAt: Long? = null,
    val scanStartedElapsed: Long? = null, // process-local duration origin; never persisted as cross-process proof
)

data class ResetCommandEvidence(
    val runId: String,
    val commandId: String,
    val enteredAt: Long? = null,
    val submitted: Boolean = false,
    val authorityEnded: Boolean = false,
    val observedAt: Long? = null,
    val requestedAt: Long? = null,
    val submittedAt: Long? = null,
    val endedAt: Long? = null,
)

enum class ResetDisposition { RESTORED, FAILED, ARM_FAILED, EXHAUSTED, QUARANTINED }

data class ResetHistoryRow(
    val runId: String,
    val sequence: Long,
    val advertisementName: String? = null,
    val address: String? = null,
    val scanFacts: RunReport.ScanFacts? = null,
    val scanSummary: String = "",
    val acceptedAt: Long? = null,
    val scanStartedAt: Long? = null,
    val scanEndedAt: Long? = null,
    val firstOffObservedAt: Long? = null,
    val firstOnObservedAt: Long? = null,
    val offEvidence: ResetCommandEvidence? = null,
    val onEvidence: ResetCommandEvidence? = null,
    val onEnteredAfterOffObservation: Boolean = false,
    val modeBits: Int = 0,
    val backendHistory: List<BackendEntry> = emptyList(),
    val finalDisposition: ResetDisposition? = null,
    val counted: Boolean = false,
    val asOfTotal: Long? = null,
    val dismissed: Boolean = false,
    val durationMs: Long? = null,
    val finalReason: String = "",
    val terminalRecordedAt: Long? = null,
)

/** Durable verified-count total since 1.1 is independent of bounded cards. Null means unknown. */
data class ResetHistory(
    val rows: List<ResetHistoryRow> = emptyList(),
    val total: Long? = 0L,
    val nextSequence: Long = 1L, // 0 denotes an unknown ordinal after a missing/corrupt ledger.
) {
    fun accept(match: AcceptedResetMatch, protected: Set<String> = emptySet()): ResetHistory {
        if (rows.any { it.runId == match.runId } || nextSequence <= 0L || nextSequence == Long.MAX_VALUE) return this
        val row = ResetHistoryRow(match.runId, nextSequence,
            advertisementName = match.advertisementName?.take(256), address = match.address?.take(64),
            scanFacts = match.scanFacts, scanSummary = match.scanSummary.take(1024), acceptedAt = match.acceptedAt,
            scanStartedAt = match.scanStartedAt, scanEndedAt = match.scanEndedAt, asOfTotal = total)
        return copy(rows = rows + row, nextSequence = nextSequence + 1).prune(protected + match.runId)
    }

    /** Existing rows only. In particular, a late callback cannot recreate an evicted card. */
    fun updateExisting(record: ResetRecord): ResetHistory = copy(rows = rows.map { row ->
        if (row.runId != record.runId || row.counted) row else row.withRecord(record)
    })

    fun terminal(record: ResetRecord, disposition: ResetDisposition, protected: Set<String> = emptySet()): ResetHistory {
        val row = rows.find { it.runId == record.runId } ?: return this
        if (row.counted) return this
        val eligible = disposition == ResetDisposition.RESTORED && record.countable(row.runId)
        val canCount = eligible && total != null && total < Long.MAX_VALUE
        val nextTotal = when {
            eligible && total == Long.MAX_VALUE -> null
            canCount -> requireNotNull(total) + 1L
            else -> total
        }
        val terminal = row.withRecord(record).copy(finalDisposition = disposition,
            counted = canCount, asOfTotal = nextTotal, terminalRecordedAt = record.terminalRecordedAt)
        return copy(rows = rows.map { if (it.runId == row.runId) terminal else it }, total = nextTotal).prune(protected)
    }

    fun dismiss(runId: String): ResetHistory = copy(rows = rows.map {
        if (it.runId == runId) it.copy(dismissed = true) else it
    })

    private fun prune(protected: Set<String>): ResetHistory {
        val evicted = rows.filter { it.finalDisposition != null && it.runId !in protected }
            .sortedBy { it.sequence }.dropLast(100).map { it.runId }.toSet()
        return if (evicted.isEmpty()) this else copy(rows = rows.filter { it.runId !in evicted })
    }
}

private fun ResetHistoryRow.withRecord(record: ResetRecord): ResetHistoryRow = copy(
    firstOffObservedAt = record.firstOffObservedAt, firstOnObservedAt = record.firstOnObservedAt,
    offEvidence = record.offEvidence, onEvidence = record.onEvidence,
    onEnteredAfterOffObservation = record.onEnteredAfterOffObservation, modeBits = record.modeBits,
    durationMs = record.durationMs, finalReason = record.reason.take(1024),
    backendHistory = record.history.takeLast(12).map { it.copy(reason = it.reason.take(512)) },
    finalDisposition = when (record.stage) {
        ResetStage.ARM_FAILED -> ResetDisposition.ARM_FAILED
        ResetStage.EXHAUSTED -> ResetDisposition.EXHAUSTED
        ResetStage.QUARANTINED -> ResetDisposition.QUARANTINED
        else -> null
    },
)

/** Evidence is informational; only native completion can end authority and release pending. */
private fun ResetRecord.countable(acceptedRun: String): Boolean {
    val off = offEvidence ?: return false
    val on = onEvidence ?: return false
    val offEntry = off.enteredAt ?: return false
    val onEntry = on.enteredAt ?: return false
    val offState = off.observedAt ?: return false
    val onState = on.observedAt ?: return false
    return stage == ResetStage.ON_SETTLED && phase == ReEnablePolicy.Phase.WAITING_ON &&
        runId == acceptedRun && off.runId == runId && on.runId == runId &&
        on.commandId == commandId && off.commandId != on.commandId &&
        off.submitted && on.submitted && off.authorityEnded && on.authorityEnded && authorityEnded &&
        firstOffObservedAt != null && firstOnObservedAt != null && onEnteredAfterOffObservation &&
        offState >= offEntry && onEntry >= offState && onState >= onEntry

}
