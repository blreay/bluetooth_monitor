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
        assertTrue(service.contains("serviceStarted(ticket, startId)"))
        assertTrue(service.contains("serviceGone(owned.ticket, owned.startId)"))
        assertTrue(service.contains("ExactServiceLifetime()"))
        assertTrue(service.contains("EXTRA_OWNER"))
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
        assertTrue(coordinator.contains("engine.retryRecovery(state, sample.sampledAt)"))
        assertTrue(coordinator.contains("engine.rearmContinuation(current)"))
        assertTrue(coordinator.contains("engine.nativeEntered(command, at)"))
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
        assertEquals("Only deletion may use a receiver PendingIntent", 1, Regex("PendingIntent\\.getBroadcast\\(").findAll(text).count())
        val delete = text.substringAfter("val delete = PendingIntent.getBroadcast(").substringBefore("ensureChannels(context)")
        assertTrue(delete.contains("HistoryDismissReceiver::class.java"))
        assertTrue(delete.contains("identity.deleteAction")); assertTrue(delete.contains("identity.deleteData"))
        assertTrue(delete.contains("FLAG_IMMUTABLE"))
        assertFalse(text.contains("startActivity("))
        val receiver = source("HistoryDismissReceiver")
        for (forbidden in listOf("startActivity(", "BluetoothSwitch", "ReEnableScheduler", "clear(", "cancel(")) assertFalse(receiver.contains(forbidden))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains(".HistoryDismissReceiver\" android:exported=\"false\""))
    }

    @Test fun `tested runtime manual gate scanner snapshot and history seams are used by Android`() {
        val coordinator = source("ResetCoordinator")
        assertTrue(coordinator.contains("ManualOperationController(store"))
        assertTrue(coordinator.contains("RuntimeOperationGate { operationAdmission() }"))
        assertTrue(coordinator.contains("manual.serviceStarted(ticket.runId, serviceLease!!"))
        assertTrue(coordinator.contains("manual.serviceEnded(ticket.runId)"))
        assertTrue(coordinator.contains("manualScanners.install(operation.operationId, scanner)")); assertTrue(coordinator.contains("scanner.start()"))
        assertTrue(coordinator.contains("ExactScannerOwner<ManualBleScanner> { it.registrationTicket() }"))
        assertTrue(coordinator.contains("manualScanners.retired(ticket) { it.retirementConfirmed(ticket) }"))
        assertTrue(coordinator.contains("manualScanners.retire(operationId) { it.retire() }"))
        assertTrue(coordinator.contains("engine.armAccepted(match, wantsToast)"))
        assertFalse(coordinator.contains("engine.arm(id,"))
        val scanner = source("BandResetController")
        assertTrue(scanner.indexOf("ownership.claimMatch(runId)") < scanner.indexOf("matchedName = name; matchedAddress = mac"))
        assertTrue(scanner.contains("onMatch(AcceptedResetMatch("))
        assertTrue(scanner.contains("AdvertisementDelivery.deliver(results"))
        assertFalse(scanner.contains("RunNotification.fastPhase("))
        assertTrue(source("ResetService").contains("RunNotification.ongoing(this, ticket.owner)"))
        assertTrue(source("RunNotification").contains("DetailsText.foreground(owner)"))
        val manualScanner = source("ManualBleScanner")
        for (forbidden in listOf("BandMatcher", "onMatch", "bondedDevices", "startDiscovery(", "BluetoothSwitch.enable(", "BluetoothSwitch.disable(", ".connectGatt(", ".createBond(")) assertFalse(manualScanner.contains(forbidden))
        assertTrue(manualScanner.contains("retirement.start(exact)")); assertTrue(manualScanner.contains("retirement.retire(it)"))
        assertTrue(manualScanner.contains("DiscoveredDevices(100)")); assertTrue(manualScanner.contains("result.scanRecord?.deviceName"))
        assertTrue(manualScanner.contains("ManualObservationWindow()"))
        assertTrue(manualScanner.contains("AdvertisementDelivery.deliver(listOf(result)"))
        assertTrue(manualScanner.contains("AdvertisementDelivery.deliver(results"))
        assertTrue(manualScanner.contains("observationWindow.admit(result.timestampNanos, receivedElapsedNanos, SystemClock.elapsedRealtimeNanos()"))
        assertTrue(manualScanner.contains("observationWindow.startResult(SystemClock.elapsedRealtimeNanos(), { timeout.run() })"))
        assertTrue(manualScanner.indexOf("observationWindow.captureEntry(SystemClock.elapsedRealtimeNanos())") < manualScanner.indexOf("scanner.startScan("))
        assertFalse(manualScanner.contains("coerceAtLeast(0L)")); assertFalse(manualScanner.contains("startedElapsed ?:"))
        val activity = source("PermissionActivity")
        for (label in listOf("打开蓝牙", "关闭蓝牙", "扫描蓝牙")) assertTrue(activity.contains("button(\"$label\")"))
        assertTrue(activity.contains("coordinator.subscribeDetails { render(it) }"))
        assertTrue(activity.contains("coordinator.unsubscribeDetails(it)"))
        assertTrue(activity.contains("override fun onStop()")); assertTrue(activity.contains("selected_run"))
        assertFalse(activity.contains("coordinator.retryRecovery {")); assertFalse(activity.contains("coordinator.retryAuthorization {"))
        assertFalse(activity.contains("CommandLease(")); assertFalse(activity.contains("driver.execute("))
        val notification = source("RunNotification")
        assertTrue(notification.contains("HistoryNotificationPlan.visible(snapshot, runId)"))
        assertTrue(notification.contains("manager.notify(identity.tag, identity.id"))
        assertTrue(notification.contains("manager.activeNotifications.filter { it.packageName == context.packageName }"))
        assertTrue(notification.contains("HistoryNotificationRetirement.reconcile(snapshot"))
        assertTrue(notification.contains("manager.cancel(tag, id)"))
        assertTrue(source("PendingResetStore").contains("onConfirmedSnapshot = { snapshot -> RunNotification.reconcileHistory"))
        assertTrue(notification.contains("setAutoCancel(false)")); assertTrue(notification.contains("identity.contentData"))
        assertFalse(notification.contains("runId.hashCode()"))
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
