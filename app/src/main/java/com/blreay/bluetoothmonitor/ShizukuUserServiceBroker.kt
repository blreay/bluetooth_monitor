package com.blreay.bluetoothmonitor

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import android.util.Log

/**
 * Public-API-only bridge for SDK 13.1.5's unsynchronized callback set/local cache.
 *
 * Each unique-tag slot is bound exactly once. Its off-main bind can add to a new, empty SDK entry;
 * remove(true) makes no local collection mutation. No other entry is admitted until the old SDK
 * main death callback has completed its clear/cache removal AND all old management calls returned.
 * Never-connected ambiguous failures quarantine one detached slot instead of racing false unbind,
 * reusing a dead SDK entry, or blocking the main looper on a management Binder transaction.
 */
internal object ShizukuUserServiceBroker {
    const val UNAVAILABLE = "前次高权限服务的退出尚未确认；自动尝试普通方式。详情可检查状态；必要时在系统设置中强制停止本应用后重新打开（这会中止自动恢复，先检查系统蓝牙），无需卸载"
    private val handler = Handler(Looper.getMainLooper())
    private val management = Executors.newSingleThreadExecutor { task ->
        Thread(task, "bt-shizuku-management").apply { isDaemon = true }
    }
    // Independent endpoint destruction still runs when the management Binder is stalled.
    private val cleanup = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "bt-shizuku-destroy").apply { isDaemon = true } })
    private val slots = ShizukuRegistrationBroker<IBinder>(
        { barrier -> handler.post { barrier() } },
        { endpoint ->
            try { cleanup.execute {
                try { IPrivilegedBluetoothService.Stub.asInterface(endpoint)?.destroy() } catch (_: Exception) {}
            } } catch (_: RejectedExecutionException) {
                Log.e("BtReset.Broker", "exact endpoint cleanup lane occupied; slot remains unavailable")
            }
        },
    )

    class Registration internal constructor(
        internal val slot: ShizukuRegistrationBroker.Slot<IBinder>,
        internal val args: Shizuku.UserServiceArgs,
    ) {
        @Volatile internal var retired = false
        internal val dispatchDeadline = SystemClock.elapsedRealtime() + 5_000L
        internal val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                requireMain()
                slots.connected(slot, service)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                requireMain()
                slots.disconnected(slot)
            }
        }
    }

    val quarantined: Boolean get() { requireMain(); return slots.quarantined }

    fun bind(context: Context, owner: ShizukuRegistrationBroker.Owner<IBinder>): Registration? {
        requireMain()
        val slot = slots.acquire(owner) ?: return null
        val args = Shizuku.UserServiceArgs(ComponentName(context, PrivilegedBluetoothService::class.java))
            .daemon(false).processNameSuffix("bt_privileged").version(1)
            .tag("bt_manual_${UUID.randomUUID()}")
        val registration = Registration(slot, args)
        management.execute {
            if (registration.retired || SystemClock.elapsedRealtime() >= registration.dispatchDeadline) {
                handler.post { slots.managementFinished(slot, "高权限注册已退出/排队窗口过期；未开始新的绑定") }
                return@execute
            }
            val failure = try {
                Shizuku.bindUserService(registration.args, registration.connection)
                null
            } catch (e: Exception) { "高权限服务连接失败：${e.message}" }
            handler.post { slots.managementFinished(slot, failure) }
        }
        return registration
    }

    fun canReconfirm(registration: Registration, endpoint: IBinder): Boolean {
        requireMain()
        return slots.canReconfirm(registration.slot, endpoint)
    }

    fun retire(registration: Registration) {
        requireMain()
        if (!slots.retire(registration.slot)) return
        registration.retired = true
        management.execute {
            try { Shizuku.unbindUserService(registration.args, registration.connection, true) }
            catch (_: Exception) { /* An ambiguous failure cannot release this slot. */ }
            finally { handler.post { slots.managementFinished(registration.slot) } }
        }
    }

    private fun requireMain() { check(Looper.myLooper() === handler.looper) }
}
