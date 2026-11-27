package com.blreay.bluetoothmonitor

import java.util.UUID

/** Identity is process-local and one-shot; a copied value is not a readiness capability. */
data class TriggerAttempt(val settingsRevision: Long, val attemptId: String)
enum class AutomaticScheduleStatus { NOT_SCHEDULED, SCHEDULED, BLOCKED_SETTINGS, WRITE_FAILED, ARM_FAILED, CANCEL_FAILED, INVALID_CLOCK }
data class AutomaticScheduleSnapshot(
    val status: AutomaticScheduleStatus,
    val ticket: MonitorAlarmTicket? = null,
    val elapsedAt: Long? = null,
)

/**
 * Main-coordinator-lane policy, not Android host/gate authority. Only periodic alarms belong here.
 * Runtime supplies actual busy state and dispatch acceptance after proving host and exact child admission.
 */
class AutomaticMonitorScheduler(
    private val store: CheckedAutomaticSettingsStore,
    private val alarms: MonitorAlarmPort,
    private val elapsedNow: () -> Long,
) {
    private data class Provisional(val attempt: TriggerAttempt, val source: AutomaticTrigger, val begunAt: Long, val expiresAt: Long)
    private var schedule = AutomaticScheduleSnapshot(AutomaticScheduleStatus.NOT_SCHEDULED)
    private var ownedTicket: MonitorAlarmTicket? = null
    private var provisional: Provisional? = null
    private var lastAcceptedAt: Long? = null
    private var clockHighWater: Long? = null
    private var acceptanceClockUntrusted = false
    private var admitting = false

    fun snapshot(): AutomaticScheduleSnapshot {
        val config = store.snapshot()
        return if (config.blocked) AutomaticScheduleSnapshot(
            if (config.fault == AutomaticSettingsFault.WRITE_FAILED) AutomaticScheduleStatus.WRITE_FAILED
            else AutomaticScheduleStatus.BLOCKED_SETTINGS)
            else if (acceptanceClockUntrusted) AutomaticScheduleSnapshot(AutomaticScheduleStatus.INVALID_CLOCK)
            else schedule
    }

    /** SAVED describes the transaction, not host liveness or arm success; inspect snapshot separately. */
    fun configure(expectedRevision: Long?, draft: AutomaticMonitorSettings): AutomaticSettingsSave {
        val old = store.snapshot().record?.pendingAlarm
        val result = store.save(expectedRevision, draft)
        if (result == AutomaticSettingsSave.SAVED) {
            provisional = null
            if (ownedTicket == null) ownedTicket = old
            resume()
        } else if (store.snapshot().blocked) {
            provisional = null
        }
        return result
    }

    /** Cold restoration schedules now + interval, with no immediate scan and no catchup. */
    fun resume(): AutomaticScheduleSnapshot {
        val config = store.snapshot()
        if (config.blocked) { provisional = null; return snapshot() }
        val record = requireNotNull(config.record)
        val ticket = record.pendingAlarm
        val old = ownedTicket
        if (old != null && old != ticket && !cancel(old)) return snapshot()
        if (ticket == null) {
            schedule = AutomaticScheduleSnapshot(AutomaticScheduleStatus.NOT_SCHEDULED)
            return snapshot()
        }
        if (schedule.status == AutomaticScheduleStatus.SCHEDULED && schedule.ticket == ticket) return snapshot()
        val now = now() ?: return snapshot()
        val at = nextAt(now, record.settings) ?: return snapshot()
        arm(ticket, at)
        return snapshot()
    }

    /** Commit consume/rotate -> arm next -> optional provisional offer, even when work is busy/cooling down. */
    fun onTimer(ticket: MonitorAlarmTicket, busy: Boolean = false): TriggerAttempt? {
        val config = store.snapshot()
        if (config.blocked) { provisional = null; return null }
        val record = requireNotNull(config.record)
        if (record.pendingAlarm != ticket) return null
        val now = now() ?: return null
        val at = nextAt(now, record.settings) ?: return null
        val next = store.consumeAlarm(ticket)
        if (next == null) { provisional = null; return null }
        // The old one-shot was consumed. Never cancel an unrelated recovery alarm or reset its identity.
        if (!arm(next, at)) { provisional = null; return null }
        return offer(AutomaticTrigger.TIMER, ticket.settingsRevision, now, busy)
    }

    /** Real screen edges only; this path never writes settings, rotates tickets or touches the timer. */
    fun onScreen(source: AutomaticTrigger, busy: Boolean = false): TriggerAttempt? {
        if (admitting || source == AutomaticTrigger.TIMER) return null
        val config = store.snapshot()
        if (config.blocked) { provisional = null; return null }
        val settings = requireNotNull(config.record).settings
        if (!selected(settings, source) || busy) return null
        return offer(source, settings.revision, now() ?: return null, busy)
    }

    /**
     * Call at delayed host readiness. The config revision/attempt, NOT the consumed alarm, is checked.
     * dispatch must return true only for a real accepted operation; false/throw consumes the attempt
     * without cooldown. Successful cooldown starts at post-dispatch confirmation, not callback entry.
     * An untrusted confirmation clock blocks future offers in this instance, never retracts accepted
     * child truth or clears safety authority. Accepted work keeps its frozen target across config changes.
     */
    fun accept(attempt: TriggerAttempt, busy: Boolean = false, dispatch: (TargetSnapshot) -> Boolean): Boolean {
        val pending = provisional ?: return false
        if (pending.attempt !== attempt) return false
        provisional = null // no retries or post-idle queue for this event
        val config = store.snapshot()
        val settings = config.record?.settings ?: return false
        if (config.blocked || settings.revision != attempt.settingsRevision || !selected(settings, pending.source) || busy)
            return false
        val now = now() ?: return false
        if (now < pending.begunAt || now >= pending.expiresAt || !cooledDown(now)) return false
        admitting = true
        return try {
            val accepted = try { dispatch(TargetSnapshot(settings.revision, settings.targetName)) }
                catch (_: Exception) { false }
            if (accepted) {
                val confirmedAt = now()
                if (confirmedAt == null || confirmedAt > Long.MAX_VALUE - AUTO_MIN_INTERVAL_MS) {
                    // The child was accepted. Unknown timing cannot justify another automatic admission.
                    acceptanceClockUntrusted = true
                    schedule = AutomaticScheduleSnapshot(AutomaticScheduleStatus.INVALID_CLOCK)
                } else lastAcceptedAt = confirmedAt
            }
            accepted
        } finally { admitting = false }
    }

    fun discard(attempt: TriggerAttempt): Boolean {
        if (provisional?.attempt !== attempt) return false
        provisional = null
        return true
    }

    private fun offer(source: AutomaticTrigger, revision: Long, now: Long, busy: Boolean): TriggerAttempt? {
        if (admitting) return null
        val config = store.snapshot()
        val settings = config.record?.settings ?: return null
        if (config.blocked || settings.revision != revision || !selected(settings, source) || busy || !cooledDown(now)) return null
        val old = provisional
        if (old != null && (old.attempt.settingsRevision != revision || now >= old.expiresAt || now < old.begunAt)) provisional = null
        if (provisional != null) return null // merge nearby edges; never a list of deferred work
        if (now > Long.MAX_VALUE - ATTEMPT_WINDOW_MS) {
            schedule = AutomaticScheduleSnapshot(AutomaticScheduleStatus.INVALID_CLOCK)
            return null
        }
        val attempt = TriggerAttempt(revision, UUID.randomUUID().toString())
        provisional = Provisional(attempt, source, now, now + ATTEMPT_WINDOW_MS)
        return attempt
    }

    private fun selected(settings: AutomaticMonitorSettings, source: AutomaticTrigger): Boolean = settings.enabled && when (source) {
        AutomaticTrigger.TIMER -> settings.timerEnabled
        AutomaticTrigger.SCREEN_ON -> settings.screenOnEnabled
        AutomaticTrigger.SCREEN_OFF -> settings.screenOffEnabled
    }

    private fun cooledDown(now: Long): Boolean = !acceptanceClockUntrusted &&
        (lastAcceptedAt?.let { now >= it && now - it >= AUTO_MIN_INTERVAL_MS } ?: true)

    private fun now(): Long? {
        val value = try { elapsedNow() } catch (_: Exception) { -1L }
        val highWater = clockHighWater
        if (value < 0 || (highWater != null && value < highWater)) {
            schedule = AutomaticScheduleSnapshot(AutomaticScheduleStatus.INVALID_CLOCK)
            provisional = null
            return null
        }
        // Keep this across discard/configuration/faults; a lower sample cannot create a replacement.
        clockHighWater = value
        return value
    }

    private fun nextAt(now: Long, settings: AutomaticMonitorSettings): Long? {
        val delay = settings.intervalMinutes * 60_000L
        if (now > Long.MAX_VALUE - delay) {
            schedule = AutomaticScheduleSnapshot(AutomaticScheduleStatus.INVALID_CLOCK)
            provisional = null
            return null
        }
        return now + delay
    }

    private fun cancel(ticket: MonitorAlarmTicket): Boolean {
        return try {
            alarms.cancel(ticket)
            ownedTicket = null
            true
        } catch (_: Exception) {
            schedule = AutomaticScheduleSnapshot(AutomaticScheduleStatus.CANCEL_FAILED, ticket)
            false
        }
    }

    private fun arm(ticket: MonitorAlarmTicket, at: Long): Boolean {
        // A false port result may still have scheduled the identity. Retain it for exact cancellation.
        ownedTicket = ticket
        val armed = try { alarms.arm(ticket, at) } catch (_: Exception) { false }
        schedule = if (armed) AutomaticScheduleSnapshot(AutomaticScheduleStatus.SCHEDULED, ticket, at)
            else AutomaticScheduleSnapshot(AutomaticScheduleStatus.ARM_FAILED, ticket)
        return armed
    }

    private companion object {
        const val AUTO_MIN_INTERVAL_MS = 30_000L
        const val ATTEMPT_WINDOW_MS = 30_000L
    }
}
