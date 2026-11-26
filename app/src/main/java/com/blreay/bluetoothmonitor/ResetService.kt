package com.blreay.bluetoothmonitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/** Exact short automatic/manual foreground owner. Cold/stale intents never become automatic scan/recovery. */
class ResetService : Service() {
    private val lifetime = ExactServiceLifetime()
    private var latestAndroidStartId = 0
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestAndroidStartId = startId // only used to stop this Android service after its actual owner finishes
        val id = intent?.getStringExtra(EXTRA_RUN)
        val token = intent?.getStringExtra(EXTRA_ADMISSION)
        val owner = try { intent?.getStringExtra(EXTRA_OWNER)?.let { ForegroundAdmission.Owner.valueOf(it) } } catch (_: Exception) { null }
        val ticket = if (id != null && !token.isNullOrEmpty() && owner != null) ForegroundAdmission.Ticket(id, token, owner) else null
        val coordinator = ResetCoordinator.get(this)
        if (lifetime.owned != null) return START_NOT_STICKY // duplicate/stale start cannot replace exact owner/startId
        if (ticket == null || !coordinator.serviceAdmissionCurrent(ticket)) {
            stopSelfResult(startId); return START_NOT_STICKY
        }
        if (!BtPermissions.allGranted(this)) {
            coordinator.serviceAdmissionFailed(ticket, "附近设备 SCAN/CONNECT 权限未满足")
            stopSelfResult(startId); return START_NOT_STICKY
        }
        try {
            startForeground(RunNotification.ONGOING_ID, RunNotification.ongoing(this, ticket.owner), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            Log.e("BtReset.Svc", "foreground admission failed", e)
            coordinator.serviceAdmissionFailed(ticket, e.javaClass.simpleName)
            stopSelfResult(startId); return START_NOT_STICKY
        }
        if (!lifetime.adopt(ticket, startId)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId); return START_NOT_STICKY }
        coordinator.serviceStarted(ticket, startId) {
            if (lifetime.finish(ticket, startId)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(latestAndroidStartId)
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        lifetime.owned?.let { owned ->
            ResetCoordinator.get(this).serviceGone(owned.ticket, owned.startId)
            lifetime.finish(owned.ticket, owned.startId)
        }
        super.onDestroy()
    }
    companion object {
        const val SCAN_TIMEOUT_MS = 8_000L
        private const val EXTRA_RUN = "run_owner"
        private const val EXTRA_ADMISSION = "foreground_admission"
        private const val EXTRA_OWNER = "foreground_owner_kind"
        fun tryStart(context: Context, ticket: ForegroundAdmission.Ticket): Boolean {
            if (!BtPermissions.allGranted(context)) return false
            return try {
                context.startForegroundService(Intent(context, ResetService::class.java)
                    .setAction("${context.packageName}.action.WORK.${ticket.owner}")
                    .putExtra(EXTRA_RUN, ticket.runId).putExtra(EXTRA_ADMISSION, ticket.token).putExtra(EXTRA_OWNER, ticket.owner.name))
                true
            } catch (e: Exception) {
                Log.w("BtReset.Svc", "legal FGS handoff refused; no UI/background workaround", e)
                false
            }
        }
    }
}
