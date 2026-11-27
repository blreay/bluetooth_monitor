package com.blreay.bluetoothmonitor

/** Presentation annotation, deliberately not a match claim, reset policy or execution proof. */
data class ScanSummary(val count: Int?, val overflow: Boolean, val targetSeen: Boolean?, val result: String) {
    fun entry() = CompactLogEntry.ScanEnded(count, overflow, targetSeen, result)
}

/** Main-confined sticky annotation; the scanner still owns its existing device count/window. */
class ScanSummaryObservation(private val target: TargetSnapshot? = null) {
    private var targetSeen = false
    private var closed = false
    fun admitted(address: String?, name: String?) {
        if (!closed && target?.matches(name) == true) targetSeen = true
    }
    internal fun closeManual(count: Int, overflow: Boolean, entryCaptured: Boolean, ticketAllocated: Boolean,
                    result: String, start: ManualScanStartedDelivery.Started? = null): ScanSummary? {
        val executed = entryCaptured || (start?.ended == true && start.returnedAt != null)
        val phrase = when {
            executed -> result
            start?.ended == true -> "未执行" // ended + no registration-entry source, even with a claimed ticket
            start?.ended == false -> "状态未确认" // timeout is not proof that queued/entered work never ran
            !ticketAllocated -> "未执行"
            else -> "状态未确认"
        }
        return close(count, overflow, executed, phrase)
    }
    fun close(count: Int, overflow: Boolean, executed: Boolean, result: String): ScanSummary? {
        if (closed) return null
        closed = true
        val phrase = if (executed || result == "状态未确认") result else "未执行"
        return ScanSummary(if (executed) count else null, executed && overflow, if (executed && target != null) targetSeen else null, phrase)
    }
}

/** Automatic observation only; its six/eight second budget is supplied by the original caller. */
internal class AutomaticPacketWindow(timeoutMs: Long) {
    private val duration = timeoutMs.takeIf { it > 0L && it <= Long.MAX_VALUE / 1_000_000L }?.times(1_000_000L)
    @Volatile private var entry: Long? = null
    private var expired = false
    private var highWater: Long? = null
    @Synchronized fun captureEntry(elapsedNanos: Long) {
        if (entry == null && elapsedNanos >= 0L) entry = elapsedNanos
    }
    fun admit(sampled: Long, received: Long, delivered: Long): Boolean {
        if (remainingMs(delivered) == null) return false
        return sampled > 0L && sampled >= entry!! && sampled <= received && received <= delivered
    }
    fun remainingMs(delivered: Long): Long? {
        if (expired) return null
        val start = entry; val budget = duration
        if (start == null || budget == null || delivered < start ||
            highWater?.let { delivered < it } == true || delivered - start >= budget) {
            expired = true
            return null
        }
        highWater = delivered
        val remaining = budget - (delivered - start)
        return remaining / 1_000_000L + if (remaining % 1_000_000L == 0L) 0L else 1L
    }
}

/** Coordinator callback ordering only; no scanner, lease, or native authority policy. */
internal object AutomaticScanCompletionDelivery {
    fun finish(observe: () -> Unit, done: () -> Unit, settle: () -> Unit) {
        try { observe() } catch (_: Exception) { } // producer/notifier failures are presentation only
        try { done() } finally { settle() }
    }
}

/** Presentation delivery is separate from exact scanner/native authority evidence. */
internal object ManualScanPresentation {
    fun finalSnapshot(devices: () -> Unit, summary: () -> Unit) {
        try { devices() } catch (_: Exception) { }
        try { summary() } catch (_: Exception) { }
    }
    fun finish(observe: () -> Unit, terminal: () -> Unit, retire: () -> Unit) {
        try { try { observe() } finally { terminal() } } finally { retire() }
    }
}

/** Synchronous delivery of the scanner's existing start result; no worker or authority policy. */
internal object ManualScanStartedDelivery {
    data class Started(val ok: Boolean, val returnedAt: Long?, val ended: Boolean)

    fun deliver(start: Started, late: Boolean, finished: () -> Boolean, leaseValid: () -> Boolean,
                returned: (Long?, Boolean) -> Unit, failure: (Started) -> Unit,
                retire: () -> Unit, observing: () -> Unit) {
        val failed = !start.ok || late || finished() || !leaseValid()
        if (failed) {
            try {
                failure(start) // final snapshot before any returned callback can reenter retirement/terminal-clear
            } finally {
                try {
                    if (start.ended) returned(start.returnedAt, start.ok) // exact authority evidence, including late results
                } finally { retire() } // an earlier observer failure must not suppress either existing callback
            }
        } else {
            if (start.ended) returned(start.returnedAt, start.ok)
            observing()
        }
    }
}
