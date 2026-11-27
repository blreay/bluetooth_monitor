package com.blreay.bluetoothmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Bounded synchronous receipt/FGS handshake only. Native work belongs to a later real admitted host child. */
class MonitorAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val receipt = MonitorAlarmEncoding.decode(intent.action, intent.component?.className, intent.data?.toString(), context.packageName) ?: return
        ResetCoordinator.get(context).automatic.timer(receipt)
    }
}
