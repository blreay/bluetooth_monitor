package com.blreay.bluetoothmonitor

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Process-local references, never reconstructed from a service Intent or an alarm. */
class MonitorHostStart internal constructor(val token: String)
class MonitorHostSession internal constructor(val start: MonitorHostStart)
class MonitorChildTicket(val operation: ForegroundAdmission.Ticket, val hostSession: MonitorHostSession, val childToken: String)
interface MonitorHostPort {
    fun acquireChild(operation: ForegroundAdmission.Ticket): MonitorChildTicket?
    fun ownsChild(child: MonitorChildTicket): Boolean
    fun releaseChild(child: MonitorChildTicket): Boolean
}

/** Exact-child, scan-only handoff to the worker. Entry reservation is not native return/retirement evidence. */
class MonitorScanEntryPermit internal constructor(private val child: MonitorChildTicket) {
    private enum class State { OPEN, ENTERED, REVOKED }
    private val state = AtomicReference(State.OPEN)
    fun eligible(actual: MonitorChildTicket): Boolean = actual === child && state.get() == State.OPEN
    fun tryEnter(): Boolean = state.compareAndSet(State.OPEN, State.ENTERED)
    fun revoke(actual: MonitorChildTicket) {
        if (actual === child) state.compareAndSet(State.OPEN, State.REVOKED)
        // ENTERED remains entered: drain does not cancel observation, recovery, or imply authority ended.
    }
}

/** Same settings persistence, with a scan-only handoff; validation/confirmation remain in the checked store. */
internal class MonitorSettingsWriteHandoff(private val persistence: AutomaticSettingsPersistence,
    private val revokeNewScan: () -> Unit) : AutomaticSettingsPersistence {
    override fun read(): AutomaticSettingsRead = persistence.read()
    override fun commit(record: AutomaticMonitorRecord): Boolean {
        // Only a checked, validated write reaches here. Fence OFF before the durable write, not after
        // scheduler cancel/Android Binder work. INVALID/CONFLICT/UNCHANGED never reach this handoff.
        if (!record.settings.enabled) revokeNewScan()
        return try {
            persistence.commit(record).also { if (!it) revokeNewScan() }
        } catch (e: Exception) {
            revokeNewScan()
            throw e // the existing checked store owns WRITE_FAILED / last-confirmed truth
        }
    }
}

/** Main-confined actual foreground host proof; idle host is not an operation admission/owner. */
class MonitorHostLifetime(private val clock: () -> Long) : MonitorHostPort {
    private var starting: MonitorHostStart? = null
    private var startAt = 0L
    private var live: MonitorHostSession? = null
    private var child: MonitorChildTicket? = null
    private var scanEntry: MonitorScanEntryPermit? = null
    private var stop: (() -> Unit)? = null
    private var retained = false
    fun beginStart(): MonitorHostStart? {
        if (live != null || starting != null) return null
        val now = try { clock() } catch (_: Exception) { return null }
        if (now < 0L || now > Long.MAX_VALUE - START_WINDOW_MS) return null
        startAt = now
        return MonitorHostStart(UUID.randomUUID().toString()).also { starting = it }
    }
    fun pending(token: String): MonitorHostStart? = starting?.takeIf { it.token == token && startCurrent() }
    fun pendingStart(): MonitorHostStart? = starting
    fun started(start: MonitorHostStart, stop: () -> Unit): MonitorHostSession? {
        if (starting !== start || live != null || !startCurrent()) return null
        starting = null
        this.stop = stop
        return MonitorHostSession(start).also { live = it }
    }
    fun failStart(start: MonitorHostStart): Boolean {
        if (starting !== start) return false
        starting = null
        return true
    }
    fun session(): MonitorHostSession? = live
    fun hasChild(): Boolean = child != null
    internal fun scanPermit(actual: MonitorChildTicket): MonitorScanEntryPermit? = scanEntry?.takeIf { ownsChild(actual) }
    fun newScanEligible(actual: MonitorChildTicket): Boolean = scanPermit(actual)?.eligible(actual) == true
    fun revokeNewScan() { child?.let { scanEntry?.revoke(it) } }
    fun keepAlive(value: Boolean) { retained = value; if (!value) revokeNewScan(); stopIfIdle() }
    fun destroy(session: MonitorHostSession): MonitorChildTicket? {
        if (live !== session) return null
        val previous = child
        revokeNewScan()
        child = null; scanEntry = null; live = null; stop = null
        return previous
    }
    override fun acquireChild(operation: ForegroundAdmission.Ticket): MonitorChildTicket? {
        val session = live ?: return null
        if (child != null || operation.owner != ForegroundAdmission.Owner.AUTOMATIC) return null
        return MonitorChildTicket(operation, session, UUID.randomUUID().toString()).also {
            child = it; scanEntry = MonitorScanEntryPermit(it)
            if (!retained) revokeNewScan()
        }
    }
    override fun ownsChild(child: MonitorChildTicket): Boolean = this.child === child && live === child.hostSession
    override fun releaseChild(child: MonitorChildTicket): Boolean {
        if (!ownsChild(child)) return false
        revokeNewScan()
        this.child = null; scanEntry = null
        stopIfIdle()
        return true
    }
    private fun startCurrent(): Boolean {
        val now = try { clock() } catch (_: Exception) { return false }
        return now >= startAt && now - startAt < START_WINDOW_MS
    }
    private fun stopIfIdle() {
        if (retained || child != null || live == null) return
        val callback = stop
        live = null; stop = null // a stopping host can never lend a capability
        callback?.invoke()
    }
    companion object { const val START_WINDOW_MS = 4_000L }
}

/** Adapter for Handler's Boolean contract; logical timeout ownership cannot rely on physical lock expiry. */
internal object MonitorTimeoutScheduling {
    fun post(delay: Long, task: () -> Unit, enqueue: (Long, () -> Unit) -> Boolean) {
        check(enqueue(delay, task)) { "monitor timeout enqueue refused" }
    }
}

interface MonitorTaskLock {
    fun acquire(timeoutMs: Long)
    fun release()
}
class MonitorWorkSlice internal constructor(val child: MonitorChildTicket, val lease: CommandLease)

/** Hosting adapter reads the original engine's lifecycle, never advances or clears it. */
internal object MonitorRecoveryAdmission {
    fun permits(child: MonitorChildTicket, pending: ResetRecord?): Boolean =
        pending != null && pending.runId == child.operation.runId && pending.stage in listOf(
            ResetStage.OFF_READY, ResetStage.OFF_DISPATCHED, ResetStage.OFF_SETTLED,
            ResetStage.ON_READY, ResetStage.ON_DISPATCHED, ResetStage.ON_SETTLED,
        ) // exact nonterminal lifecycle only; unknown authority still belongs to the unchanged engine
}

/** Main-confined, lock-free blocked handoff lifetime; never a native recovery permission/proof. */
internal class MonitorRecoveryHandoff(private val foreground: OperationForegroundOwner, private val clock: () -> Long,
    private val later: (Long, () -> Unit) -> Unit, private val expired: (MonitorChildTicket) -> Unit) {
    private class Wait(val child: MonitorChildTicket, val deadline: Long, var blocked: Boolean = true)
    private var wait: Wait? = null
    private var highWater: Long? = null
    fun blocked(child: MonitorChildTicket): Boolean {
        if (!foreground.childCurrent(child)) return false
        val now = now() ?: return end(child)
        wait?.takeIf { it.child === child }?.let { slot ->
            slot.blocked = true
            return if (now >= slot.deadline) end(child) else true // never renew on tick, retry or later reblock
        }
        val budget = MonitorHostedWork.RECOVERY_WINDOW_MS
        if (now > Long.MAX_VALUE - budget) return end(child)
        val slot = Wait(child, now + budget)
        wait = slot
        try {
            later(budget) {
                if (wait === slot && slot.blocked && foreground.childCurrent(child)) end(child)
                // A recovered handoff retains its deadline, but cannot truncate ongoing safe OFF-to-ON work.
            }
        } catch (_: Exception) {
            if (wait === slot) return end(child) // includes Handler Boolean false through the checked adapter
        }
        return wait === slot && foreground.childCurrent(child)
    }
    fun ready(child: MonitorChildTicket): Boolean {
        if (!foreground.childCurrent(child)) return false
        val now = now() ?: return end(child)
        wait?.takeIf { it.child === child }?.let { slot ->
            if (slot.blocked && now >= slot.deadline) return end(child)
            slot.blocked = false
        }
        return true
    }
    fun cancel(child: MonitorChildTicket) { if (wait?.child === child) wait = null }
    private fun end(child: MonitorChildTicket): Boolean {
        cancel(child)
        if (foreground.childCurrent(child)) expired(child)
        return false
    }
    private fun now(): Long? {
        val value = try { clock() } catch (_: Exception) { return null }
        if (value < 0L || highWater?.let { value < it } == true) return null
        highWater = value
        return value
    }
}

/** One exact child-owned finite task slice. Expiry is NOT a native/endpoint authority-end event. */
class MonitorHostedWork(private val host: MonitorHostPort, private val clock: () -> Long,
                        private val lock: () -> MonitorTaskLock, private val later: (Long, () -> Unit) -> Unit,
                        private val expired: (MonitorWorkSlice) -> Unit) {
    private data class Active(val slice: MonitorWorkSlice, val lock: MonitorTaskLock, val release: CompletionGate)
    private var active: Active? = null
    private var initialized: MonitorChildTicket? = null
    private var highWater: Long? = null
    fun initial(child: MonitorChildTicket): MonitorWorkSlice? {
        if (initialized === child || !host.ownsChild(child) || active != null) return null
        initialized = child
        return begin(child, INITIAL_WINDOW_MS)
    }
    fun recovery(child: MonitorChildTicket, eligible: Boolean): MonitorWorkSlice? {
        if (!eligible || initialized !== child || !host.ownsChild(child) || active != null) return null
        return begin(child, RECOVERY_WINDOW_MS)
    }
    fun finish(slice: MonitorWorkSlice): Boolean {
        val slot = active?.takeIf { it.slice === slice } ?: return false
        active = null
        release(slot)
        return true
    }
    fun cancel(child: MonitorChildTicket) {
        active?.takeIf { it.slice.child === child }?.let { finish(it.slice) }
    }
    fun activeSlice(): MonitorWorkSlice? = active?.slice
    private fun begin(child: MonitorChildTicket, budget: Long): MonitorWorkSlice? {
        val now = now() ?: return null
        if (now > Long.MAX_VALUE - budget) return null
        val lease = CommandLease({ now() ?: Long.MAX_VALUE }, now + budget)
        val taskLock = try { lock() } catch (_: Exception) { lease.cancel(); return null }
        val slice = MonitorWorkSlice(child, lease)
        val slot = Active(slice, taskLock, CompletionGate())
        try {
            taskLock.acquire(budget) // Android also enforces this finite timeout
            active = slot
            later(budget) {
                if (active?.slice !== slice) return@later
                if (finish(slice)) expired(slice)
            }
        } catch (_: Exception) {
            if (active === slot) active = null
            release(slot)
            return null
        }
        return slice
    }
    private fun release(slot: Active) {
        slot.slice.lease.cancel()
        if (slot.release.finish()) try { slot.lock.release() } catch (_: Exception) { }
    }
    private fun now(): Long? {
        val value = try { clock() } catch (_: Exception) { return null }
        if (value < 0L || highWater?.let { value < it } == true) return null
        highWater = value
        return value
    }
    companion object { const val INITIAL_WINDOW_MS = 30_000L; const val RECOVERY_WINDOW_MS = 15_000L }
}

class OperationForegroundOwner {
    var binding: OperationForegroundBinding? = null
        private set
    fun installOriginal(ticket: ForegroundAdmission.Ticket, startId: Int, done: () -> Unit): Boolean {
        if (binding != null || startId <= 0) return false
        binding = OperationForegroundBinding.ResetServiceOwner(ticket, startId, done)
        return true
    }
    fun installMonitor(child: MonitorChildTicket, target: TargetSnapshot): Boolean {
        if (binding != null || child.operation.owner != ForegroundAdmission.Owner.AUTOMATIC) return false
        binding = OperationForegroundBinding.MonitorChild(child, target)
        return true
    }
    fun originalCurrent(ticket: ForegroundAdmission.Ticket, startId: Int): Boolean =
        (binding as? OperationForegroundBinding.ResetServiceOwner)?.let { it.operation == ticket && it.originalStartId == startId } == true
    fun childCurrent(child: MonitorChildTicket): Boolean = (binding as? OperationForegroundBinding.MonitorChild)?.child === child
    fun clear(expected: OperationForegroundBinding): OperationForegroundBinding? {
        if (binding !== expected) return null
        binding = null
        return expected
    }
}
object MonitorChildAdmission {
    /** Readiness starts AFTER real admission. A startup exception cannot change that accepted truth. */
    fun startAccepted(child: MonitorChildTicket, start: (MonitorChildTicket) -> Unit, failed: (MonitorChildTicket) -> Unit) {
        try { start(child) } catch (_: Exception) { failed(child) }
    }
    fun accept(admission: ForegroundAdmission, host: MonitorHostPort, target: TargetSnapshot, runId: String,
        gate: () -> Boolean, claim: () -> Boolean, release: () -> Unit,
        revalidate: (ForegroundAdmission.Ticket) -> Boolean,
        install: (MonitorChildTicket, TargetSnapshot) -> Boolean): MonitorChildTicket? {
        if (!gate() || !claim()) return null
        val ticket = admission.begin(runId)
        if (ticket == null) { release(); return null }
        var child: MonitorChildTicket? = null
        var installed = false
        try {
            val exact = host.acquireChild(ticket) ?: return null
            child = exact
            if (!revalidate(ticket) || !host.ownsChild(exact) || !admission.current(ticket) || !admission.accept(ticket)) return null
            installed = install(exact, target)
            return exact.takeIf { installed }
        } finally {
            if (!installed) {
                try { child?.let { host.releaseChild(it) } }
                finally { try { if (!admission.reject(ticket)) admission.end(ticket) } finally { release() } }
            }
        }
    }
}

/** The original ResetService startId callback and a monitor child are disjoint exact bindings. */
sealed interface OperationForegroundBinding {
    val operation: ForegroundAdmission.Ticket
    data class ResetServiceOwner(override val operation: ForegroundAdmission.Ticket, val originalStartId: Int,
                                 val done: () -> Unit) : OperationForegroundBinding
    class MonitorChild(val child: MonitorChildTicket, val target: TargetSnapshot) : OperationForegroundBinding {
        override val operation get() = child.operation
    }
}
