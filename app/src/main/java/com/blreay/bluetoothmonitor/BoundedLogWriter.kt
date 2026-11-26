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
/** storageCount means unacknowledged by both files; a failed sync can still have written bytes. */
data class LogDropRange(val count: Long, val firstOccurredAt: Long, val lastOccurredAt: Long,
                        val firstReceivedAt: Long, val lastReceivedAt: Long,
                        val queueCount: Long = 0, val storageCount: Long = 0)
data class LogStatus(val initialized: Boolean = false, val storage: LogStorageStatus = LogStorageStatus(),
                     val queuedEvents: Int = 0, val droppedEvents: Long = 0,
                     val unreportedDrops: LogDropRange? = null, val schedulerFailureType: String? = null,
                     val initializationFailureType: String? = null)

/**
 * One scheduled drain, at most 512 queued finite rows plus one batch in flight. Admission holds a
 * short memory-only lock and never waits for disk or the worker. Overflow keeps the newest events.
 * Enqueue, disk-full, and asynchronous process kill cannot guarantee persistence of every event.
 */
class BoundedLogWriter(
    private val audit: AuditLog,
    private val scheduler: LogScheduler,
    private val storage: LogStorageBoundary,
    private val capacity: Int = 512,
    private val batchSize: Int = 64,
    private val onStatus: (LogStatus) -> Unit = {},
) : AuditSink {
    init { require(capacity in 1..512); require(batchSize in 1..64) }
    private val lock = Any()
    private val queue = ArrayDeque<AuditRow>()
    private var scheduled = false
    private var refresh = false
    private var droppedTotal = 0L
    private var drops: LogDropRange? = null
    private var schedulerFailure: String? = null

    override fun record(name: String, fields: Map<String, Any?>, occurredAt: Long?) {
        val launch = synchronized(lock) {
            val row = audit.capture(name, fields, occurredAt)
            if (queue.size == capacity) lost(queue.removeFirst(), queueOverflow = true)
            queue.addLast(row)
            requestDrain()
        }
        if (launch) schedule()
    }
    fun refreshAccess() {
        val launch = synchronized(lock) { refresh = true; requestDrain() }
        if (launch) schedule()
    }
    fun status(): LogStatus = synchronized(lock) {
        LogStatus(true, storage.status(), queue.size, droppedTotal, drops, schedulerFailure)
    }

    /** Called only under lock. The executor never receives one task per event. */
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
            synchronized(lock) {
                scheduled = false
                schedulerFailure = failure.javaClass.simpleName
            }
            // Keep the finite queue. A later admission/refresh retries; never busy-loop here.
        }
    }
    private data class Batch(val rows: List<AuditRow>, val drops: LogDropRange?)
    private fun drain() {
        while (true) {
            val batch = synchronized(lock) {
                if (queue.isEmpty() && !refresh) { scheduled = false; return }
                val rows = ArrayList<AuditRow>(batchSize)
                repeat(minOf(batchSize, queue.size)) { rows.add(queue.removeFirst()) }
                refresh = false
                Batch(rows, drops).also { drops = null }
            }
            var diagnostic: AuditRow? = null
            try {
                batch.drops?.let { range ->
                    diagnostic = audit.capture("log.dropped", mapOf(
                        "count" to range.count, "queueCount" to range.queueCount, "storageCount" to range.storageCount,
                        "firstOccurredAtMs" to range.firstOccurredAt, "lastOccurredAtMs" to range.lastOccurredAt,
                        "firstReceivedAtMs" to range.firstReceivedAt, "lastReceivedAtMs" to range.lastReceivedAt,
                        "reason" to "queue eviction or unacknowledged file write; sync failure may have written rows",
                    ))
                }
            } catch (_: Exception) { }
            val lines = listOfNotNull(diagnostic?.line) + batch.rows.map { it.line }
            val persisted = try { storage.persist(lines) } catch (_: Exception) { false }
            synchronized(lock) {
                if (!persisted || (batch.drops != null && diagnostic == null)) drops = merge(drops, batch.drops)
                if (!persisted) batch.rows.forEach { lost(it, queueOverflow = false) }
            }
            try { onStatus(status()) } catch (_: Exception) { }
        }
    }
    private fun lost(row: AuditRow, queueOverflow: Boolean) {
        droppedTotal++
        drops = merge(drops, LogDropRange(1, row.occurredAt, row.occurredAt, row.receivedAt, row.receivedAt,
            queueCount = if (queueOverflow) 1 else 0, storageCount = if (queueOverflow) 0 else 1))
    }
    private fun merge(a: LogDropRange?, b: LogDropRange?): LogDropRange? {
        if (a == null) return b
        if (b == null) return a
        return LogDropRange(a.count + b.count, minOf(a.firstOccurredAt, b.firstOccurredAt), maxOf(a.lastOccurredAt, b.lastOccurredAt),
            minOf(a.firstReceivedAt, b.firstReceivedAt), maxOf(a.lastReceivedAt, b.lastReceivedAt),
            a.queueCount + b.queueCount, a.storageCount + b.storageCount)
    }
}
