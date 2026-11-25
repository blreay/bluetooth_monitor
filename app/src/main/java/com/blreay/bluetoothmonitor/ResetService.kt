package com.blreay.bluetoothmonitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/** Short foreground scan/recovery owner, not sticky and never a periodic/24-hour monitor. */
class ResetService : Service() {
    private var ownedTicket: ForegroundAdmission.Ticket? = null
    private var ownedStartId = 0
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_RUN)
        val token = intent?.getStringExtra(EXTRA_ADMISSION)
        val ticket = if (id != null && !token.isNullOrEmpty()) ForegroundAdmission.Ticket(id, token) else null
        val coordinator = ResetCoordinator.get(this)
        if (ownedTicket != null) {
            // A stale/duplicate startId must neither demote nor stop the current exact owner.
            ownedStartId = startId
            return START_NOT_STICKY
        }
        if (ticket == null || !coordinator.serviceAdmissionCurrent(ticket)) {
            // Process recreation or an expired admission cannot create a scan/FGS owner.
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!BtPermissions.allGranted(this)) {
            coordinator.serviceAdmissionFailed(ticket, "附近设备 SCAN/CONNECT 权限未满足")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        try {
            startForeground(RunNotification.ONGOING_ID, RunNotification.ongoing(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            Log.e("BtReset.Svc", "foreground admission failed", e)
            coordinator.serviceAdmissionFailed(ticket, e.javaClass.simpleName)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        ownedTicket = ticket
        ownedStartId = startId
        coordinator.serviceStarted(ticket) {
            if (ownedTicket == ticket) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                ownedTicket = null
                stopSelfResult(ownedStartId)
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        ownedTicket?.let { ResetCoordinator.get(this).serviceGone(it) }
        ownedTicket = null
        super.onDestroy()
    }
    companion object {
        const val SCAN_TIMEOUT_MS = 8_000L
        private const val EXTRA_RUN = "run_owner"
        private const val EXTRA_ADMISSION = "foreground_admission"
        fun tryStart(context: Context, ticket: ForegroundAdmission.Ticket): Boolean {
            if (!BtPermissions.allGranted(context)) return false
            return try {
                context.startForegroundService(Intent(context, ResetService::class.java)
                    .setAction("${context.packageName}.action.WORK")
                    .putExtra(EXTRA_RUN, ticket.runId).putExtra(EXTRA_ADMISSION, ticket.token))
                true
            } catch (e: Exception) {
                // Includes background-start and connectedDevice prerequisite/security restrictions.
                Log.w("BtReset.Svc", "legal FGS handoff refused; bounded receiver/app path only", e)
                false
            }
        }
    }
}
