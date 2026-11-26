package com.blreay.bluetoothmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Worker-confined state deduplication ignores pending-byte/queue/advertisement count changes. */
class LogStatusChanges(private val post: (LogStorageStatus) -> Unit) {
    private var previous: LogStorageStatus? = null
    fun update(status: LogStorageStatus) {
        val state = status.copy(pendingBytes = null, discardedPrivateBytes = 0)
        if (state == previous) return
        previous = state
        // A failed post neither changes logging/storage nor retries for every later advertisement.
        try { post(status) } catch (_: Exception) { }
    }
}

/** Silent, best-effort observational output. A tap is a direct immutable details PendingIntent. */
@Suppress("DEPRECATION") // Also explicitly silence legacy Builder defaults on target 32.
class LogStatusNotification(context: Context) {
    companion object { const val ID = 105; const val CHANNEL = "bt_log_status" }
    private val app = context.applicationContext
    private val changes = LogStatusChanges { status -> post(status) }
    fun update(status: LogStatus) { if (status.initialized) changes.update(status.storage) }

    private fun post(status: LogStorageStatus) {
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "miband.log 日志状态", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        })
        val details = PendingIntent.getActivity(
            app, 25, Intent(app, PermissionActivity::class.java).setAction("${app.packageName}.action.LOG_DETAILS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = buildString {
            append(when (status.external) {
                ExternalLogState.UNCHECKED -> "共享日志访问尚未检查"
                ExternalLogState.PERMISSION_REQUIRED -> "需要所有文件访问授权；点击详情显式申请，授权前仅保留有界私有日志"
                ExternalLogState.READY -> "已完成共享存储根目录 miband.log 写入/同步检查"
                ExternalLogState.WRITE_FAILED -> "共享日志写入/同步失败（${status.externalFailureType ?: "未知类型"}）；尝试保留私有日志"
                ExternalLogState.ACCESS_CHECK_FAILED -> "共享日志授权检查失败（${status.externalFailureType ?: "未知类型"}）；尝试保留私有日志"
            })
            append("\n")
            append(when (status.private) {
                PrivateLogState.UNCHECKED -> "私有日志尚未检查"
                PrivateLogState.EMPTY -> "当前无私有待导出记录"
                PrivateLogState.PENDING -> "有界私有记录待导出；授权恢复后由日志线程重放原始事件"
                PrivateLogState.READ_FAILED -> "私有日志读取失败（${status.privateFailureType ?: "未知类型"}）；旧记录导出未确认"
                PrivateLogState.WRITE_FAILED -> "私有日志写入/同步失败（${status.privateFailureType ?: "未知类型"}）；记录可能丢失或未确认"
                PrivateLogState.ACK_FAILED -> "共享导出成功但私有确认失败（${status.privateFailureType ?: "未知类型"}）；以后可能重复相同事件 ID"
            })
            append("\n日志为观测输出，不改变蓝牙或恢复安全状态；队列溢出/磁盘错误/进程终止可能丢记录，同步失败也可能已写入。")
        }
        manager.notify(ID, Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_stat_bluetooth)
            .setContentTitle("miband.log 日志状态").setContentText(body.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(details)
            .setOngoing(false).setAutoCancel(false).setSound(null).setVibrate(null).setDefaults(0)
            .setOnlyAlertOnce(true).build())
    }
}
