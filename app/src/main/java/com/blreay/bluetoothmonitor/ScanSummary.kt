package com.blreay.bluetoothmonitor

/** Presentation annotation, deliberately not a match claim, reset policy or execution proof. */
data class ScanSummary(val count: Int?, val overflow: Boolean, val targetSeen: Boolean?, val result: String) {
    fun entry() = CompactLogEntry.ScanEnded(count, overflow, targetSeen, result)
}

/** Main-confined sticky annotation; the scanner still owns its existing device count/window. */
class ScanSummaryObservation {
    private var targetSeen = false
    private var closed = false
    fun admitted(address: String?, name: String?) {
        if (!closed && BandMatcher.isTarget(address, name)) targetSeen = true
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
        return ScanSummary(if (executed) count else null, executed && overflow, if (executed) targetSeen else null, phrase)
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
