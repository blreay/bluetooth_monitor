package com.blreay.bluetoothmonitor

enum class MonitorAlarmOrigin { EXACT, INEXACT }
data class MonitorAlarmIdentity(val ticket: MonitorAlarmTicket, val origin: MonitorAlarmOrigin)
data class MonitorTransportSnapshot(val origin: MonitorAlarmOrigin? = null, val failure: String? = null)

/** The origin is part of the immutable PendingIntent identity, never an UPDATE_CURRENT extra. */
object MonitorAlarmEncoding {
    const val RECEIVER = "com.blreay.bluetoothmonitor.MonitorAlarmReceiver"
    fun uri(identity: MonitorAlarmIdentity): String = "btmonitor://periodic/${identity.ticket.settingsRevision}/${identity.ticket.generation}/" +
        if (identity.origin == MonitorAlarmOrigin.EXACT) "exact" else "inexact"
    fun decode(action: String?, component: String?, uri: String?, packageName: String): MonitorAlarmIdentity? {
        if (action != "$packageName.action.MONITOR_TICK" || component != RECEIVER || uri == null) return null
        val match = Regex("btmonitor://periodic/(0|[1-9][0-9]*)/(0|[1-9][0-9]*)/(exact|inexact)").matchEntire(uri) ?: return null
        val revision = match.groupValues[1].toLongOrNull() ?: return null
        val generation = match.groupValues[2].toLongOrNull() ?: return null
        if (revision <= 0L || generation < revision) return null
        return MonitorAlarmIdentity(MonitorAlarmTicket(revision, generation),
            if (match.groupValues[3] == "exact") MonitorAlarmOrigin.EXACT else MonitorAlarmOrigin.INEXACT)
    }
}
interface MonitorPeriodicBackend {
    fun canScheduleExact(): Boolean
    fun arm(identity: MonitorAlarmIdentity, elapsedAt: Long)
    fun cancel(identity: MonitorAlarmIdentity)
}

/** Periodic transport only; it cannot touch requestCode 10 or original OFF→ON recovery. */
class MonitorPeriodicTransport(private val backend: MonitorPeriodicBackend) : MonitorAlarmPort {
    private var state = MonitorTransportSnapshot()
    override fun arm(ticket: MonitorAlarmTicket, elapsedAt: Long): Boolean {
        val exact = try { backend.canScheduleExact() } catch (_: Exception) { false }
        var origin = if (exact) MonitorAlarmOrigin.EXACT else MonitorAlarmOrigin.INEXACT
        try {
            backend.arm(MonitorAlarmIdentity(ticket, origin), elapsedAt)
        } catch (_: SecurityException) {
            if (origin != MonitorAlarmOrigin.EXACT) { state = state.copy(failure = "非精确闹钟安排被拒绝"); return false }
            // A permission refusal permits a distinct safe fallback; arbitrary method failure does not.
            origin = MonitorAlarmOrigin.INEXACT
            try { backend.arm(MonitorAlarmIdentity(ticket, origin), elapsedAt) }
            catch (_: Exception) { state = state.copy(failure = "精确及非精确闹钟安排失败"); return false }
        } catch (_: Exception) { state = state.copy(failure = "闹钟安排失败；旧投递来源保持不变"); return false }
        state = MonitorTransportSnapshot(origin)
        val opposite = if (origin == MonitorAlarmOrigin.EXACT) MonitorAlarmOrigin.INEXACT else MonitorAlarmOrigin.EXACT
        return try {
            backend.cancel(MonitorAlarmIdentity(ticket, opposite))
            true
        } catch (_: Exception) { state = state.copy(failure = "新闹钟已安排；旧周期子身份取消失败"); false }
    }
    override fun cancel(ticket: MonitorAlarmTicket) {
        var failed = false
        for (origin in MonitorAlarmOrigin.values()) {
            try { backend.cancel(MonitorAlarmIdentity(ticket, origin)) } catch (_: Exception) { failed = true }
        }
        if (failed) {
            state = state.copy(failure = "周期闹钟取消失败")
            throw IllegalStateException("periodic cancellation failed")
        }
    }
    fun snapshot() = state
}
