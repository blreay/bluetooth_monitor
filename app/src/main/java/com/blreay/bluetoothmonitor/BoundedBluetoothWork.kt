package com.blreay.bluetoothmonitor

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Fixed process-wide admission. A stuck Binder consumes one lane, never spawns replacement threads. */
internal object BoundedBluetoothWork {
    enum class Lane { STATUS, RPC, ADAPTER, DIAGNOSTIC }
    private val handler = Handler(Looper.getMainLooper())
    private val pools = Lane.values().associateWith { lane ->
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "bt-${lane.name.lowercase()}").apply { isDaemon = true } })
    }
    // Exactly one registration owns this independent fixed cleanup lane. No replacement workers.
    private val retirementPool = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "bt-scan-retirement").apply { isDaemon = true } })

    fun <T> submit(
        lane: Lane,
        lease: CommandLease,
        timeoutMs: Long,
        notInvoked: () -> T,
        uncertain: () -> T,
        task: () -> T,
        result: (T, Boolean) -> Unit,
    ) {
        // 0 queued; 1 entered native-capable work; 2 expired-before-entry; 3 returned.
        val authority = NativeWorkAuthority()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var timedOut = false // main only
        val timer = Runnable {
            if (authority.hasReturned()) return@Runnable
            timedOut = true
            result(if (authority.expireBeforeEntry()) notInvoked() else uncertain(), false)
        }
        handler.postDelayed(timer, timeoutMs)
        val work = Runnable {
            if (!authority.enter(lease.valid() && SystemClock.elapsedRealtime() < deadline)) {
                if (authority.expireBeforeEntry()) handler.post {
                    handler.removeCallbacks(timer)
                    if (!timedOut) result(notInvoked(), false)
                }
                return@Runnable
            }
            val value = try { task() } catch (_: Exception) { uncertain() }
            authority.returned()
            handler.post {
                handler.removeCallbacks(timer)
                // A returned RPC may reconcile matching durable authority after the receiver has finished.
                result(value, timedOut)
            }
        }
        try { pools.getValue(lane).execute(work) }
        catch (_: RejectedExecutionException) {
            authority.expireBeforeEntry()
            handler.removeCallbacks(timer)
            result(notInvoked(), false)
        }
    }

    /** Retiring existing native registration is not new observer work and cannot silently expire. */
    fun retireExact(task: () -> Unit): Boolean = try {
        retirementPool.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false // caller retains exact registration and blocks replacement
    }
}
