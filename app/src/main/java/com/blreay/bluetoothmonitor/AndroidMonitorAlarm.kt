package com.blreay.bluetoothmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Explicit nonexported periodic transport. No original recovery alarm PendingIntent is reachable here. */
class AndroidMonitorAlarm(private val context: Context) : MonitorAlarmPort {
    private val manager get() = context.getSystemService(AlarmManager::class.java)
    private val transport = MonitorPeriodicTransport(object : MonitorPeriodicBackend {
        override fun canScheduleExact(): Boolean = manager?.canScheduleExactAlarms() == true
        override fun arm(identity: MonitorAlarmIdentity, elapsedAt: Long) {
            val alarms = manager ?: throw IllegalStateException("AlarmManager unavailable")
            if (identity.origin == MonitorAlarmOrigin.EXACT) {
                if (!alarms.canScheduleExactAlarms()) throw SecurityException("exact access unavailable")
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedAt, operation(identity, false)!!)
            } else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedAt, operation(identity, false)!!)
        }
        override fun cancel(identity: MonitorAlarmIdentity) {
            val pending = operation(identity, true) ?: return
            val alarms = manager ?: throw IllegalStateException("AlarmManager unavailable")
            alarms.cancel(pending)
            pending.cancel()
        }
    })
    override fun arm(ticket: MonitorAlarmTicket, elapsedAt: Long) = transport.arm(ticket, elapsedAt)
    override fun cancel(ticket: MonitorAlarmTicket) = transport.cancel(ticket)
    fun snapshot() = transport.snapshot()
    fun exactAccess() = manager?.canScheduleExactAlarms() == true
    private fun operation(identity: MonitorAlarmIdentity, existingOnly: Boolean): PendingIntent? = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, MonitorAlarmReceiver::class.java)
            .setPackage(context.packageName).setAction("${context.packageName}.action.MONITOR_TICK")
            .setData(Uri.parse(MonitorAlarmEncoding.uri(identity))),
        (if (existingOnly) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT) or PendingIntent.FLAG_IMMUTABLE)
    companion object { const val REQUEST_CODE = 106 }
}
