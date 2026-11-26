package com.blreay.bluetoothmonitor

import android.app.Application
import android.os.Process

/** The Shizuku UserService loads application classes as UID 0/2000; it must not initialize app runtime. */
class MonitorApplication : Application() {
    lateinit var coordinator: ResetCoordinator
        private set
    override fun onCreate() {
        super.onCreate()
        if (Process.myUid() == 0 || Process.myUid() == 2000 || Process.myUid() != applicationInfo.uid || getProcessName() != packageName) return
        MibandLog.initialize(this)
        MibandLog.event(CompactLogEntry.ProcessStarted)
        coordinator = ResetCoordinator(this)
    }
}
