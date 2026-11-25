package com.blreay.bluetoothmonitor

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Headless integration boundaries complement executable policy/ownership tests, not device tests. */
class ManualSourceBoundaryTest {
    private fun source(name: String): String {
        val file = File("src/main/java/com/blreay/bluetoothmonitor/$name.kt")
        assertTrue("Missing $name", file.isFile)
        return file.readText()
    }

    @Test fun `launcher dispatches and finishes without a placeholder page`() {
        val activity = source("MainActivity")
        assertTrue(activity.contains("coordinator.launch("))
        assertTrue(activity.contains("finish()"))
        assertFalse(activity.contains("setContentView"))
        assertFalse(activity.contains("ShizukuBluetoothClient("))
    }

    @Test fun `Android scanner waits exact foreground admission and failed admission cancels exact controller`() {
        val coordinator = source("ResetCoordinator")
        assertTrue(coordinator.contains("ForegroundAdmission("))
        assertTrue(coordinator.contains("StartupLaunchSequence.dispatch"))
        assertTrue(coordinator.contains("startup = { ready -> client.startup(callback = ready) }"))
        assertTrue(coordinator.contains("admission.scanWindow(id,"))
        assertTrue(coordinator.contains("admission.expire(ticket)"))
        assertTrue(coordinator.contains("cancelScanFor("))
        val service = source("ResetService")
        assertTrue(service.contains("EXTRA_ADMISSION"))
        assertTrue(service.contains("serviceAdmissionCurrent(ticket)"))
        assertTrue(service.contains("serviceStarted(ticket)"))
        assertTrue(service.contains("serviceGone(it)"))
    }

    @Test fun `Android scan retirement is exact independent coalesced and fences replacement`() {
        val coordinator = source("ResetCoordinator")
        assertTrue(coordinator.contains("ScanRegistrationRetirement("))
        assertTrue(coordinator.contains("retirement.blocked()"))
        assertTrue(coordinator.contains("retirement.retryRetirement()"))
        val scanner = source("BandResetController")
        assertTrue(scanner.contains("retirement.start(ticket)"))
        assertTrue(scanner.contains("retirement.retire(it)"))
        assertEquals(1, Regex("scanner\\.stopScan\\(").findAll(scanner).count())
        assertFalse(scanner.contains("CommandLease.foreground()"))
        val work = source("BoundedBluetoothWork")
        assertTrue(work.contains("retirementPool.execute(task)"))
        val cleanup = work.substringAfter("fun retireExact(")
        assertFalse(cleanup.contains("NativeWorkAuthority"))
        assertFalse(cleanup.contains("deadline"))
        assertFalse(cleanup.contains("CommandLease"))
    }

    @Test fun `both automatic switch directions use one driver no hidden ordinary calls`() {
        val coordinator = source("ResetCoordinator")
        assertTrue(coordinator.contains("driver.execute("))
        assertTrue(coordinator.contains("engine.completeAndRearm(command, outcome)"))
        assertFalse(coordinator.contains("if (lease.valid() && serviceDone != null) scheduleServiceTick()"))
        assertTrue(coordinator.contains("engine.retryRecovery(state)"))
        assertTrue(coordinator.contains("engine.rearmContinuation(pending)"))
        assertFalse(source("BandResetController").contains("BluetoothSwitch.disable("))
        assertFalse(source("ReEnableReceiver").contains("BluetoothSwitch.enable("))
        for (name in listOf("MainActivity", "PermissionActivity", "ShizukuBluetoothClient", "ResetCoordinator")) {
            assertFalse(source(name).contains("adapter.enable("))
            assertFalse(source(name).contains("adapter.disable("))
        }
    }

    @Test fun `provider remains protected and launcher is genuinely no display`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("rikka.shizuku.ShizukuProvider"))
        assertTrue(manifest.contains("android.permission.INTERACT_ACROSS_USERS_FULL"))
        assertTrue(manifest.contains(".PermissionActivity"))
        assertTrue(manifest.contains("Theme.NoDisplay"))
        assertTrue(manifest.contains("excludeFromRecents=\"true\""))
        assertTrue(manifest.contains("FOREGROUND_SERVICE"))
        assertTrue(manifest.contains("connectedDevice"))
        assertTrue(manifest.contains("${'$'}{applicationId}.action.RUN"))
        assertFalse(manifest.contains("android.intent.action.BOOT_COMPLETED"))
    }

    @Test fun `notification tap opens detail activity directly and is immutable`() {
        val text = source("RunNotification")
        assertTrue(text.contains("PendingIntent.getActivity("))
        assertTrue(text.contains("PermissionActivity::class.java"))
        assertTrue(text.contains("FLAG_IMMUTABLE"))
        assertFalse(text.contains("PendingIntent.getBroadcast("))
        assertFalse(text.contains("startActivity("))
    }

    @Test fun `startup requests automatically with application owned lifetime`() {
        val text = source("ShizukuBluetoothClient")
        assertTrue(text.contains("AutomaticAuthorization"))
        assertTrue(text.contains("addBinderReceivedListenerSticky"))
        assertTrue(text.contains("Shizuku.isPreV11()"))
        assertTrue(text.contains("Shizuku.getUid()"))
        assertFalse(text.contains("newCachedThreadPool"))
        assertTrue(source("MonitorApplication").contains("Process.myUid()"))
        assertFalse(source("MainActivity").contains("client.stop()"))
    }

    @Test fun `user service keeps official binding dedicated process and guarded fixed commands`() {
        val text = source("ShizukuUserServiceBroker")
        assertTrue(text.contains("Shizuku.bindUserService("))
        assertTrue(text.contains("Shizuku.unbindUserService("))
        assertTrue(text.contains(".daemon(false)"))
        assertTrue(text.contains(".processNameSuffix(\"bt_privileged\")"))
        assertTrue(text.contains(".version(1)"))
        val service = source("PrivilegedBluetoothService")
        assertTrue(service.contains("IPrivilegedBluetoothService.Stub()"))
        assertFalse(service.contains("android.app.Service"))
        assertTrue(service.contains("Process.myUid()"))
        assertFalse(service.contains("sh -c"))
        assertTrue(source("PrivilegedBluetoothCommand").contains("listOf(\"/system/bin/svc\", \"bluetooth\""))
        val aidl = File("src/main/aidl/com/blreay/bluetoothmonitor/IPrivilegedBluetoothService.aidl").readText()
        assertTrue(aidl.contains("= 1"))
        assertTrue(aidl.contains("= 16777114"))
    }

    @Test fun `SDK registrations keep one slot exact cleanup and posted death barrier`() {
        val text = source("ShizukuUserServiceBroker")
        assertTrue(text.contains("ShizukuRegistrationBroker<IBinder>"))
        assertTrue(text.contains("management.execute"))
        assertTrue(text.contains("handler.post { barrier() }"))
        assertTrue(text.contains("Shizuku.unbindUserService(registration.args, registration.connection, true)"))
        assertFalse(text.contains("registration.connection, false"))
        assertTrue(text.contains("UUID.randomUUID()"))
        assertTrue(text.contains("强制停止本应用"))
        assertFalse(text.contains("点击申请shizuku权限"))
        val client = source("ShizukuBluetoothClient")
        assertFalse(client.contains("Shizuku.bindUserService("))
        assertFalse(client.contains("Shizuku.unbindUserService("))
        assertTrue(client.contains("ShizukuUserServiceBroker.retire("))
        assertTrue(client.contains("ShizukuUserServiceBroker.canReconfirm("))
    }

    @Test fun `classic diagnostic discovery still belongs to announced session`() {
        val text = source("BluetoothDiscovery")
        assertTrue(text.contains("BluetoothAdapter.ACTION_DISCOVERY_STARTED"))
        assertTrue(text.contains("classicAnnounced"))
    }

    @Test fun `runner destroy is terminal before short spawn lock and never shells caller input`() {
        val text = source("BluetoothCommandRunner")
        assertTrue(text.contains("AtomicBoolean(false)"))
        val destroy = text.substringAfter("fun destroyChild() {").substringBefore("private fun stoppedResult")
        assertTrue(destroy.indexOf("terminal.set(true)") >= 0)
        assertTrue(destroy.indexOf("terminal.set(true)") < destroy.indexOf("synchronized(spawnLock) { child.getAndSet"))
        assertTrue(text.contains("ProcessBuilder(it)"))
        assertFalse(text.contains("sh -c"))
        for (name in listOf("ShizukuBluetoothClient", "PrivilegedBluetoothService")) {
            assertFalse(source(name).contains("newProcess("))
            assertFalse(source(name).contains("requestBinderForNonProviderProcess"))
        }
    }
}
