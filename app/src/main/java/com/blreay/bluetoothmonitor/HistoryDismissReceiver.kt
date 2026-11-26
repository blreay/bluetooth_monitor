package com.blreay.bluetoothmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notification deletion only marks the existing checked row; no UI, Bluetooth, pending/alarm or count effects. */
class HistoryDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getStringExtra(EXTRA_RUN)?.takeIf { it.isNotBlank() } ?: return
        val identity = HistoryNotificationPlan.identity(context.packageName, runId)
        if (intent.action != identity.deleteAction || intent.data?.toString() != identity.deleteData) return
        ResetCoordinator.get(context).dismissHistory(runId)
    }
    companion object { const val EXTRA_RUN = "dismiss_history_run" }
}
