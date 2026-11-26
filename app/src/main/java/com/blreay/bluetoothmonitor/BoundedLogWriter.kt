package com.blreay.bluetoothmonitor

import java.util.ArrayDeque

fun interface LogScheduler { fun schedule(task: () -> Unit) }

enum class ExternalLogState { UNCHECKED, PERMISSION_REQUIRED, READY, WRITE_FAILED, ACCESS_CHECK_FAILED }
enum class PrivateLogState { UNCHECKED, EMPTY, PENDING, READ_FAILED, WRITE_FAILED, ACK_FAILED }
data class LogStorageStatus(
    val external: ExternalLogState = ExternalLogState.UNCHECKED,
    val private: PrivateLogState = PrivateLogState.UNCHECKED,
    val externalFailureType: String? = null,
    val privateFailureType: String? = null,
    val pendingBytes: Long? = null,
    val externalWriteUncertain: Boolean = false,
    val privateWriteUncertain: Boolean = false,
    val discardedPrivateBytes: Long = 0,
)
data class LogStatus(val initialized: Boolean = false, val storage: LogStorageStatus = LogStorageStatus(),
                     val queuedEvents: Int = 0, val droppedEvents: Long = 0,
                     val schedulerFailureType: String? = null, val initializationFailureType: String? = null)

/** One memory-only finite admission queue and one serialized drain, never one task per entry. */
class BoundedLogWriter(
    private val audit: AuditLog,
    private val scheduler: LogScheduler,
    private val storage: LogStorageBoundary,
    private val capacity: Int = 64,
    private val batchSize: Int = 16,
    private val onStatus: (LogStatus) -> Unit = {},
) : AuditSink {
    init { require(capacity in 1..512); require(batchSize in 1..64) }
    private val lock = Any()
    private val queue = ArrayDeque<String>()
    private var scheduled = false
    private var refresh = false
    private var droppedTotal = 0L
    private var schedulerFailure: String? = null

    override fun record(entry: CompactLogEntry) {
        val launch = synchronized(lock) {
            val line = audit.capture(entry)
            if (queue.size == capacity) { queue.removeFirst(); droppedTotal++ }
            queue.addLast(line)
            requestDrain()
        }
        if (launch) schedule()
    }
    fun refreshAccess() {
        val launch = synchronized(lock) { refresh = true; requestDrain() }
        if (launch) schedule()
    }
    fun status(): LogStatus = synchronized(lock) {
        LogStatus(true, storage.status(), queue.size, droppedTotal, schedulerFailure)
    }
    private fun requestDrain(): Boolean {
        if (scheduled) return false
        scheduled = true
        return true
    }
    private fun schedule() {
        try {
            scheduler.schedule { drain() }
            synchronized(lock) { schedulerFailure = null }
        } catch (failure: Exception) {
            synchronized(lock) { scheduled = false; schedulerFailure = failure.javaClass.simpleName }
            // Keep the finite queue. A later admission/refresh retries, never a busy loop.
        }
    }
    private fun drain() {
        while (true) {
            val lines = synchronized(lock) {
                if (queue.isEmpty() && !refresh) { scheduled = false; return }
                val batch = ArrayList<String>(batchSize)
                repeat(minOf(batchSize, queue.size)) { batch.add(queue.removeFirst()) }
                refresh = false
                batch
            }
            val persisted = try { storage.persist(lines) } catch (_: Exception) { false }
            if (!persisted) synchronized(lock) { droppedTotal += lines.size }
            try { onStatus(status()) } catch (_: Exception) { }
        }
    }
}
