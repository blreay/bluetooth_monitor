package com.blreay.bluetoothmonitor

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Independent idle connectedDevice host. It never owns a native operation or an idle WakeLock. */
class MonitorService : Service() {
    private var session: MonitorHostSession? = null
    private var latestAndroidStartId = 0
    private var screenReceiver: BroadcastReceiver? = null
    private var selectedEdges: Pair<Boolean, Boolean>? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestAndroidStartId = startId
        if (session != null) return START_NOT_STICKY
        val coordinator = ResetCoordinator.get(this)
        val token = intent?.getStringExtra(EXTRA_START)
        val start = token?.takeIf { intent?.action == "$packageName.action.MONITOR_HOST" }
            ?.let { coordinator.automatic.host.pending(it) }
        if (start == null) { stopSelfResult(startId); return START_NOT_STICKY }
        if (!BtPermissions.allGranted(this)) {
            coordinator.automatic.hostFailed(start, "附近设备 SCAN/CONNECT 权限未满足")
            stopSelfResult(startId); return START_NOT_STICKY
        }
        try {
            startForeground(RunNotification.MONITOR_ID, RunNotification.monitor(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (failure: Exception) {
            coordinator.automatic.hostFailed(start, failure.javaClass.simpleName)
            stopSelfResult(startId); return START_NOT_STICKY
        }
        session = coordinator.automatic.hostStarted(start, {
            unregisterScreens()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(latestAndroidStartId)
        }, ::selectScreens)
        if (session == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId) }
        return START_NOT_STICKY
    }
    private fun selectScreens(settings: AutomaticMonitorSettings?) {
        val edges = settings?.let { it.screenOnEnabled to it.screenOffEnabled }
        if (edges == selectedEdges) return
        unregisterScreens()
        selectedEdges = edges
        val coordinator = ResetCoordinator.get(this)
        val actual = coordinator.automatic.host.session() ?: return
        if (edges == null || (!edges.first && !edges.second)) {
            coordinator.automatic.screenRegistration(actual, null)
            return
        }
        val filter = IntentFilter().apply {
            if (edges.first) addAction(Intent.ACTION_SCREEN_ON)
            if (edges.second) addAction(Intent.ACTION_SCREEN_OFF)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val actual = session ?: return
                val coordinator = ResetCoordinator.get(this@MonitorService)
                if (coordinator.automatic.host.session() !== actual) return
                val source = when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> AutomaticTrigger.SCREEN_ON
                    Intent.ACTION_SCREEN_OFF -> AutomaticTrigger.SCREEN_OFF
                    else -> return
                }
                coordinator.automatic.screen(source) // borrow synchronously, NEVER request another FGS
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(receiver, filter)
            screenReceiver = receiver
            coordinator.automatic.screenRegistration(actual, null)
        } catch (failure: Exception) {
            selectedEdges = null // truthful next refresh may retry; failed registration is not a source capability
            coordinator.automatic.screenRegistration(actual, "所选亮/灭屏来源注册失败：${failure.javaClass.simpleName}；定时状态单独显示")
        }
    }
    private fun unregisterScreens() {
        val receiver = screenReceiver
        screenReceiver = null; selectedEdges = null
        if (receiver != null) try { unregisterReceiver(receiver) } catch (_: Exception) { }
    }
    override fun onDestroy() {
        try {
            unregisterScreens()
            session?.let { ResetCoordinator.get(this).automatic.hostGone(it) }
            session = null
        } finally { super.onDestroy() }
    }
    companion object {
        private const val EXTRA_START = "monitor_host_start"
        fun tryStart(context: Context, start: MonitorHostStart): Boolean {
            if (!BtPermissions.allGranted(context)) return false
            return try {
                context.startForegroundService(Intent(context, MonitorService::class.java)
                    .setAction("${context.packageName}.action.MONITOR_HOST").putExtra(EXTRA_START, start.token))
                true
            } catch (_: Exception) { false }
        }
    }
}
