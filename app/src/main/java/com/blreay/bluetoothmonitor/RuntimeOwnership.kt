package com.blreay.bluetoothmonitor

import java.util.concurrent.atomic.AtomicBoolean

/** Persisted at the actual request boundary, not when a Binder-less icon launch occurs. */
class AutomaticAuthorization(
    private val wasAttempted: () -> Boolean,
    private val commitAttempt: () -> Boolean,
    private val request: () -> Unit,
) {
    enum class Decision { NO_BINDER, READY, REQUESTED, PREVIOUS_ATTEMPT, STORE_FAILED }
    fun authorize(attached: Boolean, granted: Boolean, explicit: Boolean = false): Decision {
        if (!attached) return Decision.NO_BINDER
        if (granted) return Decision.READY
        if (!explicit && wasAttempted()) return Decision.PREVIOUS_ATTEMPT
        if (!commitAttempt()) return Decision.STORE_FAILED
        request()
        return Decision.REQUESTED
    }
}

class CompletionGate {
    private val completed = AtomicBoolean(false)
    fun finish(): Boolean = completed.compareAndSet(false, true)
}

/** Main-looper owner. Claim a target match BEFORE stopScan can deliver queued callbacks. */
class RunOwnership {
    private var run: String? = null
    private var matched = false
    fun acquire(id: String): Boolean {
        if (run != null) return false
        run = id
        matched = false
        return true
    }
    fun current(id: String) = run == id
    fun claimMatch(id: String): Boolean {
        if (!current(id) || matched) return false
        matched = true
        return true
    }
    fun release(id: String) { if (current(id)) { run = null; matched = false } }
}

/** Expiry revokes NEW queued work only; it never proves an already entered native call stopped. */
class CommandLease(private val clock: () -> Long, private val deadline: Long) {
    private val cancelled = AtomicBoolean(false)
    fun valid() = !cancelled.get() && clock() < deadline
    fun cancel() { cancelled.set(true) }
    companion object {
        fun foreground() = CommandLease({ System.nanoTime() / 1_000_000 }, Long.MAX_VALUE)
    }
}

/** Main-looper startup initiation and immediate launcher routing; startup may invoke readiness inline. */
object StartupLaunchSequence {
    fun <T : Any> dispatch(startup: ((T) -> Unit) -> Unit, decide: () -> Unit, ready: (T) -> Unit) {
        var decisionCommitted = false
        var inlineReady: T? = null
        val delivery = CompletionGate()
        // Initiate authorization FIRST, but do not let reentrant readiness choose a missing admission.
        startup delivered@{ value ->
            if (!delivery.finish()) return@delivered
            if (decisionCommitted) ready(value) else inlineReady = value
        }
        decide() // synchronous setup/permission/FGS decision; a failed route never flushes readiness
        decisionCommitted = true
        inlineReady?.let { value -> inlineReady = null; ready(value) }
    }
}

/** Exact service-start ticket; pending and admitted callbacks never belong to a newer attempt. */
class ForegroundAdmission(
    private val clock: () -> Long,
    private val timeoutMs: Long,
    private val retireRun: (String) -> Unit,
) {
    enum class Owner { AUTOMATIC, MANUAL }
    data class Ticket(val runId: String, val token: String, val owner: Owner = Owner.AUTOMATIC)
    private var pending: Ticket? = null
    private var accepted: Ticket? = null
    private var deadline = 0L
    fun begin(runId: String, owner: Owner = Owner.AUTOMATIC): Ticket? {
        pending?.let { return if (it.runId == runId && it.owner == owner) it else null }
        if (accepted != null) return null
        return Ticket(runId, java.util.UUID.randomUUID().toString(), owner).also { pending = it; deadline = clock() + timeoutMs }
    }
    fun busy() = pending != null || accepted != null
    fun waiting(runId: String) = pending?.runId == runId
    fun pendingTicket(runId: String) = pending?.takeIf { it.runId == runId }
    fun current(ticket: Ticket) = accepted == ticket || (pending == ticket && clock() < deadline)
    fun admitted(ticket: Ticket) = accepted == ticket
    fun accept(ticket: Ticket): Boolean {
        if (pending != ticket || clock() >= deadline) return false
        pending = null
        accepted = ticket
        return true
    }
    fun reject(ticket: Ticket): Boolean {
        if (pending != ticket) return false
        pending = null
        retireRun(ticket.runId)
        return true
    }
    fun expire(ticket: Ticket): Boolean = pending == ticket && clock() >= deadline && reject(ticket)
    fun end(ticket: Ticket): Boolean {
        if (accepted != ticket) return false
        accepted = null
        retireRun(ticket.runId)
        return true
    }
    fun scanWindow(runId: String, foregroundMs: Long, boundedMs: Long): Long? = when {
        waiting(runId) -> null
        accepted?.runId == runId -> foregroundMs
        else -> boundedMs
    }
}

/** ResetService uses this exact ticket + original Android startId lifetime, not extras/runId alone. */
class ExactServiceLifetime {
    data class Owned(val ticket: ForegroundAdmission.Ticket, val startId: Int)
    var owned: Owned? = null
        private set
    fun adopt(ticket: ForegroundAdmission.Ticket, startId: Int): Boolean {
        if (owned != null || startId <= 0) return false
        owned = Owned(ticket, startId)
        return true
    }
    fun finish(ticket: ForegroundAdmission.Ticket, startId: Int): Boolean {
        if (owned != Owned(ticket, startId)) return false
        owned = null
        return true
    }
}

/** One exact scanner/callback slot. Native start/stop never run while holding the short state lock. */
class ScanRegistrationRetirement(
    private val clock: () -> Long,
    private val enqueue: (() -> Unit) -> Boolean,
    private val onRetired: (Ticket) -> Unit = {},
    private val onFailure: (Ticket, String) -> Unit,
) {
    data class Ticket(val runId: String, val serial: Long)
    private data class Slot(
        val ticket: Ticket,
        val stop: () -> Boolean,
        var started: Boolean = false,
        var startReturned: Boolean = false,
        var retiring: Boolean = false,
        var retiredAt: Long = 0,
        var queued: Boolean = false,
        var running: Boolean = false,
        var failed: Boolean = false,
    )
    private var serial = 0L
    private var slot: Slot? = null
    @Synchronized fun claim(runId: String, stop: () -> Boolean): Ticket? {
        if (slot != null) return null
        return Ticket(runId, ++serial).also { slot = Slot(it, stop) }
    }
    @Synchronized fun blocked() = slot != null
    @Synchronized fun unresolved(ticket: Ticket) = slot?.ticket == ticket
    @Synchronized fun retirementAge(ticket: Ticket): Long? = slot?.takeIf {
        it.ticket == ticket && it.retiring
    }?.let { (clock() - it.retiredAt).coerceAtLeast(0L) }

    fun start(ticket: Ticket, operation: () -> Boolean): Boolean {
        synchronized(this) {
            val current = slot?.takeIf { it.ticket == ticket } ?: return false
            if (current.started || current.retiring) return false
            current.started = true // atomic against retire-before-entry; prevents late queued native start
        }
        val started = try { operation() } catch (_: Exception) { false }
        synchronized(this) {
            val current = slot?.takeIf { it.ticket == ticket } ?: return started
            current.startReturned = true
            if (!started && !current.retiring) {
                current.retiring = true
                current.retiredAt = clock()
            }
        }
        schedule(ticket) // a late entered start still belongs to this exact retirement
        return started
    }

    fun retire(ticket: Ticket): Boolean {
        val neverEntered = synchronized(this) {
            val current = slot?.takeIf { it.ticket == ticket } ?: return false
            if (!current.retiring) { current.retiring = true; current.retiredAt = clock() }
            if (!current.started) {
                slot = null // start's atomic entry check now rejects this retired ticket
                true
            } else false
        }
        if (neverEntered) onRetired(ticket) else schedule(ticket)
        return true
    }

    /** Explicit retry only for a failed retirement, never for a live/new scan or a hung cleanup. */
    fun retryRetirement(): Boolean {
        val ticket = synchronized(this) {
            val current = slot ?: return false
            if (!current.retiring || !current.startReturned || !current.failed || current.queued || current.running) return false
            current.failed = false
            current.ticket
        }
        schedule(ticket)
        return true
    }

    private fun schedule(ticket: Ticket) {
        synchronized(this) {
            val current = slot?.takeIf { it.ticket == ticket } ?: return
            if (!current.retiring || !current.startReturned || current.queued || current.running || current.failed) return
            current.queued = true
        }
        val accepted = try { enqueue { cleanup(ticket) } } catch (_: Exception) { false }
        if (!accepted) {
            synchronized(this) {
                val current = slot?.takeIf { it.ticket == ticket } ?: return
                current.queued = false
                current.failed = true
            }
            onFailure(ticket, "扫描清理队列拒绝；旧注册结束未确认，不允许叠加新扫描")
        }
    }

    private fun cleanup(ticket: Ticket) {
        val stop = synchronized(this) {
            val current = slot?.takeIf { it.ticket == ticket && it.queued } ?: return
            current.queued = false
            current.running = true
            current.stop
        }
        val stopped = try { stop() } catch (_: Exception) { false }
        synchronized(this) {
            val current = slot?.takeIf { it.ticket == ticket } ?: return
            current.running = false
            if (stopped) slot = null else current.failed = true
        }
        if (stopped) onRetired(ticket)
        else onFailure(ticket, "stopScan 清理失败/权限变化；旧注册结束未确认，恢复权限后重新启动图标只重试清理")
    }
}

/** Capture a batch callback's receipt before main-looper delivery; never timestamp each delayed UI iteration. */
object AdvertisementDelivery {
    fun <T> deliver(values: List<T>, clock: () -> Long, dispatch: (() -> Unit) -> Unit, event: (T, Long) -> Unit) {
        val receivedAt = clock()
        val copy = values.toList()
        dispatch { copy.forEach { event(it, receivedAt) } }
    }
}

/** One actual worker-entry window. Closing observation never proves native start/registration ended. */
class ManualObservationWindow {
    @Volatile private var enteredElapsedNanos: Long? = null
    private var expired = false // delivery/expiry decisions belong to Main, not the native worker
    @Synchronized fun captureEntry(elapsedNanos: Long) {
        if (enteredElapsedNanos == null && elapsedNanos >= 0L) enteredElapsedNanos = elapsedNanos
    }
    fun admit(sampledElapsedNanos: Long, receivedElapsedNanos: Long, deliveredElapsedNanos: Long, expire: () -> Unit): Boolean {
        val entry = enteredElapsedNanos ?: return false
        if (remaining(deliveredElapsedNanos, expire) == null) return false
        // Android sample, callback receipt and Main delivery share elapsedRealtimeNanos. Unknown,
        // pre-entry, reordered or late evidence is rejected, even if an early packet was queued.
        return sampledElapsedNanos > 0L && sampledElapsedNanos >= entry &&
            sampledElapsedNanos <= receivedElapsedNanos && receivedElapsedNanos <= deliveredElapsedNanos
    }
    fun startResult(deliveredElapsedNanos: Long, expire: () -> Unit, schedule: (Long) -> Unit) {
        val remaining = remaining(deliveredElapsedNanos, expire) ?: return // expire inline, never queue a zero-delay timer
        schedule((remaining + 999_999L) / 1_000_000L)
    }
    private fun remaining(deliveredElapsedNanos: Long, expire: () -> Unit): Long? {
        if (expired) return null
        val entry = enteredElapsedNanos
        if (entry == null || deliveredElapsedNanos < entry || deliveredElapsedNanos - entry >= WINDOW_NANOS) {
            expired = true
            expire() // presentation finish requests exact retirement; it is not an authority-end event
            return null
        }
        return WINDOW_NANOS - (deliveredElapsedNanos - entry)
    }
    companion object { private const val WINDOW_NANOS = 8_000_000_000L }
}

/** Main-looper exact routing for manual scanner cleanup; native ticket success is not a global event. */
class ExactScannerOwner<T : Any>(private val ticketOf: (T) -> ScanRegistrationRetirement.Ticket?) {
    private var operationId: String? = null
    var current: T? = null
        private set
    fun install(id: String, scanner: T) { operationId = id; current = scanner }
    fun retire(id: String, action: (T) -> Unit): Boolean {
        val scanner = current?.takeIf { operationId == id } ?: return false
        action(scanner)
        return true
    }
    fun retired(ticket: ScanRegistrationRetirement.Ticket, action: (T) -> Unit): Boolean {
        val scanner = current?.takeIf { operationId == ticket.runId && ticketOf(it) == ticket } ?: return false
        action(scanner)
        if (current === scanner) { current = null; operationId = null }
        return true
    }
}

/** The coordinator calls this same gate at every new-work/continuation entrance. */
class RuntimeOperationGate(private val state: () -> OperationAdmission) {
    fun newRequest(): Boolean = state().allowsNewOperation()
    fun automaticContinuation(runId: String, ownerId: String?, setup: Boolean = false): Boolean {
        val s = state()
        return ownerId == runId && s.manualFence == null && !s.storageFailed && !s.integrityBlocked &&
            (s.pending == null || s.pending.runId == runId) && (setup || !s.setupWaiting)
    }
    fun recovery(runId: String): Boolean {
        val s = state()
        return s.pending?.runId == runId && s.manualFence == null && !s.storageFailed && !s.integrityBlocked &&
            !s.activeScan && !s.scanRetirement && !s.setupWaiting
    }
}

data class DetailsSnapshot(
    val store: ResetStoreSnapshot,
    val automaticStatus: String,
    val authorization: String,
    val activeManual: ManualResult?,
    val rejectionNotices: Map<ManualAction, String>,
    val runtimeNotice: String? = null,
) {
    fun manual(action: ManualAction): ManualResult? = activeManual?.takeIf { it.action == action } ?: store.manualResults[action]
    fun selected(runId: String?): ResetHistoryRow? = store.history.rows.find { it.runId == runId }
}

/** Detach is presentation only: no command/lease/scanner cancellation callback. */
class VisibleDetailsSubscription(private val snapshot: () -> DetailsSnapshot) {
    private var serial = 0L
    private val observers = linkedMapOf<Long, (DetailsSnapshot) -> Unit>()
    fun attach(observer: (DetailsSnapshot) -> Unit): Long {
        val token = ++serial
        observers[token] = observer
        observer(snapshot())
        return token
    }
    fun detach(token: Long) { observers.remove(token) }
    fun publish() {
        if (observers.isEmpty()) return
        val value = snapshot()
        observers.toMap().forEach { (token, callback) -> if (observers[token] === callback) callback(value) }
    }
}

enum class SwitchMode(val label: String) {
    NONE("尚未切换"), SHIZUKU_SHELL("Shizuku shell"), SHIZUKU_ROOT("Shizuku root"), ORDINARY("普通方式")
}

data class BackendEntry(val mode: SwitchMode, val reason: String)

/** Mode bits survive history truncation: an OFF on Shizuku and ON ordinary is still mixed. */
class BackendHistory(initial: List<BackendEntry> = emptyList(), initialBits: Int = 0) {
    var bits = initialBits
        private set
    private val rows = initial.takeLast(12).toMutableList()
    val entries: List<BackendEntry> get() = rows.toList()
    init { initial.forEach { bits = bits or (1 shl it.mode.ordinal) } }
    fun add(mode: SwitchMode, reason: String) {
        if (mode != SwitchMode.NONE) bits = bits or (1 shl mode.ordinal)
        rows.add(BackendEntry(mode, reason.take(512)))
        if (rows.size > 12) rows.removeAt(0)
    }
    fun label(): String {
        val modes = SwitchMode.values().filter { it != SwitchMode.NONE && bits and (1 shl it.ordinal) != 0 }
        return when (modes.size) {
            0 -> SwitchMode.NONE.label
            1 -> modes.single().label
            else -> "混合（${modes.joinToString(" + ") { it.label }}）"
        }
    }
}
