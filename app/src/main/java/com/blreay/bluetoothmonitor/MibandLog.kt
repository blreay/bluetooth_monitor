package com.blreay.bluetoothmonitor

import android.app.Application
import android.content.Context
import android.os.Environment
import android.os.Process
import java.io.File
import java.util.concurrent.Executors

/**
 * Permission, storage resolution, and all reads/writes belong to the fixed logger worker.
 * Replay preserves the original serialized rows. Root success followed by a kill or failed private
 * acknowledgement can repeat their original lines: this is deliberately not an exactly-once store.
 * This storage is entirely independent of Bluetooth safety persistence and never authorizes work.
 */
class LogStorageBoundary(
    private val hasAccess: () -> Boolean,
    private val external: () -> LogStorage,
    private val pending: () -> LogStorage,
) {
    @Volatile private var current = LogStorageStatus()
    fun status(): LogStorageStatus = current

    /** True means incoming rows were acknowledged by at least one file; it is not kill-proof. */
    fun persist(lines: List<String>): Boolean {
        val privatePort by lazy { pending() }
        var readAttempted = false
        var snapshot: LogReadResult? = null
        fun readPrivate() {
            readAttempted = true
            try {
                snapshot = privatePort.readComplete()
                current = current.copy(private = if (snapshot!!.bytes > 0) PrivateLogState.PENDING else PrivateLogState.EMPTY,
                    privateFailureType = null, pendingBytes = snapshot!!.bytes,
                    privateWriteUncertain = false, discardedPrivateBytes = snapshot!!.discardedBytes)
            } catch (failure: Exception) {
                current = current.copy(private = PrivateLogState.READ_FAILED,
                    privateFailureType = failure.javaClass.simpleName, pendingBytes = null,
                    discardedPrivateBytes = 0)
            }
        }
        fun retain(): Boolean {
            if (lines.isEmpty()) {
                if (!readAttempted) readPrivate()
                return true
            }
            val result = safeWrite { privatePort.append(lines) }
            readPrivate()
            if (!result.success) current = current.copy(private = PrivateLogState.WRITE_FAILED,
                privateFailureType = result.failureType, privateWriteUncertain = result.mayHaveWritten)
            return result.success
        }
        val granted = try { hasAccess() } catch (failure: Exception) {
            current = current.copy(external = ExternalLogState.ACCESS_CHECK_FAILED,
                externalFailureType = failure.javaClass.simpleName, externalWriteUncertain = false)
            return retain()
        }
        if (!granted) {
            current = current.copy(external = ExternalLogState.PERMISSION_REQUIRED, externalFailureType = null, externalWriteUncertain = false)
            return retain()
        }
        readPrivate()
        val rootPort by lazy { external() }
        val oldRows = snapshot?.lines.orEmpty()
        if (oldRows.isNotEmpty()) {
            val replay = safeWrite { rootPort.append(oldRows) }
            if (!replay.success) {
                current = current.copy(external = ExternalLogState.WRITE_FAILED, externalFailureType = replay.failureType,
                    externalWriteUncertain = replay.mayHaveWritten)
                return retain()
            }
            // Only a successful, synced root append permits acknowledgement; never clear in-place.
            val ack = safeWrite { privatePort.clear() }
            current = if (ack.success) current.copy(private = PrivateLogState.EMPTY, privateFailureType = null, pendingBytes = 0,
                privateWriteUncertain = false, discardedPrivateBytes = 0)
            else current.copy(private = PrivateLogState.ACK_FAILED, privateFailureType = ack.failureType, privateWriteUncertain = ack.mayHaveWritten)
        }
        // Empty refresh checks root writability on the worker, not on an Activity/Bluetooth thread.
        val written = safeWrite { rootPort.append(lines) }
        current = current.copy(external = if (written.success) ExternalLogState.READY else ExternalLogState.WRITE_FAILED,
            externalFailureType = written.failureType, externalWriteUncertain = !written.success && written.mayHaveWritten)
        return if (written.success) true else retain()
    }

    private fun safeWrite(write: () -> LogWriteResult): LogWriteResult =
        try { write() } catch (failure: Exception) { LogWriteResult(false, failure.javaClass.simpleName) }
}

/** Holds only the main app's application context. Storage is lazy and worker-confined. */
object MibandLog : AuditSink {
    @Volatile private var writer: BoundedLogWriter? = null
    @Volatile private var initializationFailure: String? = null

    @Synchronized
    fun initialize(context: Context) {
        if (writer != null) return
        try {
            val app = context.applicationContext
            if (Process.myUid() != app.applicationInfo.uid || Application.getProcessName() != app.packageName) return
            val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "miband-log-writer") }
            val notifications = LogStatusNotification(app)
            val storage = LogStorageBoundary(
                hasAccess = { Environment.isExternalStorageManager() },
                external = { RollingLogFile(File(Environment.getExternalStorageDirectory(), "miband.log")) },
                pending = { RollingLogFile(File(app.filesDir, "miband-log-pending.log")) },
            )
            writer = BoundedLogWriter(AuditLog(AuditClock { System.currentTimeMillis() }),
                LogScheduler { task -> executor.execute { task() } }, storage, onStatus = { notifications.update(it) })
            initializationFailure = null
            writer?.refreshAccess()
        } catch (failure: Exception) {
            initializationFailure = failure.javaClass.simpleName
        }
    }

    override fun record(entry: CompactLogEntry) { writer?.record(entry) }
    fun status(): LogStatus = writer?.status() ?: LogStatus(initializationFailureType = initializationFailure)
    fun refreshAccess() { writer?.refreshAccess() }
}
