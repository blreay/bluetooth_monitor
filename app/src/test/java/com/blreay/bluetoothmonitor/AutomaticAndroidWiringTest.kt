package com.blreay.bluetoothmonitor

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Secondary Android guards; executable production seams are tested with real policies and fake ports. */
class AutomaticAndroidWiringTest {
    private fun source(name: String): String {
        val file = File("src/main/java/com/blreay/bluetoothmonitor/$name.kt")
        assertTrue("Missing actual Android production path $name", file.isFile)
        return file.readText()
    }
    @Test fun `nonexported connected device host registers capability only after actual foreground success`() {
        val text = source("MonitorService")
        assertTrue(text.indexOf("startForeground(") < text.indexOf(".hostStarted("))
        assertTrue(text.contains("START_NOT_STICKY")); assertTrue(text.contains("FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE"))
        assertTrue(text.contains(".hostGone(")); assertTrue(text.contains("RECEIVER_NOT_EXPORTED"))
        assertTrue(text.contains(".screenRegistration(actual,")); assertTrue(source("PermissionActivity").contains("monitor.screenSourceFailure"))
        assertTrue(text.contains("Intent.ACTION_SCREEN_ON")); assertTrue(text.contains("Intent.ACTION_SCREEN_OFF"))
        assertFalse(text.contains("USER_PRESENT")); assertFalse(text.contains("newWakeLock("))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains(".MonitorService\" android:exported=\"false\" android:foregroundServiceType=\"connectedDevice\""))
        assertTrue(manifest.contains(".MonitorAlarmReceiver\" android:exported=\"false\""))
        assertTrue(manifest.contains("android.permission.WAKE_LOCK"))
        assertFalse(manifest.contains("android.intent.action.SCREEN_ON")); assertFalse(manifest.contains("BOOT_COMPLETED"))
    }
    @Test fun `periodic Android transport has separate immutable origin identities and never recovery cancellation`() {
        val text = source("AndroidMonitorAlarm")
        assertTrue(text.contains("canScheduleExactAlarms()")); assertTrue(text.contains("setExactAndAllowWhileIdle("))
        assertTrue(text.contains("setAndAllowWhileIdle(")); assertTrue(text.contains("MonitorAlarmEncoding.uri(identity)"))
        assertTrue(text.contains("FLAG_IMMUTABLE")); assertTrue(text.contains("FLAG_NO_CREATE"))
        assertTrue(text.contains("REQUEST_CODE = 106")); assertFalse(text.contains("putExtra("))
        assertFalse(text.contains("ReEnableScheduler")); assertFalse(text.contains("requestCode = 10"))
        val receiver = source("MonitorAlarmReceiver")
        assertTrue(receiver.contains("MonitorAlarmEncoding.decode(")); assertTrue(receiver.contains(".automatic.timer(receipt)"))
        assertFalse(receiver.contains(".resume(")); assertFalse(receiver.contains("canScheduleExact")); assertFalse(receiver.contains("startActivity("))
    }
    @Test fun `coordinator uses real child admission bounded task locks and original driver recovery completion`() {
        val text = source("ResetCoordinator")
        assertTrue(text.contains("OperationForegroundOwner()")); assertTrue(text.contains("MonitorChildAdmission.accept("))
        assertTrue(text.contains("MonitorHostedWork(")); assertTrue(text.contains("PowerManager.PARTIAL_WAKE_LOCK"))
        assertTrue(text.contains("wakeLock.acquire(timeoutMs)")); assertTrue(text.contains("wakeLock.release()"))
        assertTrue(text.contains("monitorWork.initial(child)")); assertTrue(text.contains("monitorWork.recovery("))
        assertTrue(text.contains("client.preflight(slice.lease, 5_000L)")); assertTrue(text.contains("MonitorChildAdmission.startAccepted(child,"))
        assertTrue(text.contains("engine.completeAndRearm(command, outcome)")); assertTrue(text.contains("BluetoothSwitchDriver(backend)"))
        assertTrue(text.contains("foreground.originalCurrent(ticket, startId)"))
        assertFalse(text.contains("serviceStarted(child.operation"))
        assertFalse(source("AutomaticMonitorController").contains("CommandLease.foreground"))
    }
    @Test fun `both scanner paths retain actual entry timing and isolate presentation from retirement`() {
        val automatic = source("BandResetController")
        assertTrue(automatic.contains("AutomaticPacketWindow(timeout)"))
        assertTrue(automatic.contains("packetWindow?.captureEntry(SystemClock.elapsedRealtimeNanos())"))
        assertTrue(automatic.contains("packetWindow?.admit(result.timestampNanos, receivedElapsedNanos, SystemClock.elapsedRealtimeNanos())"))
        assertTrue(automatic.contains("packetWindow?.remainingMs(SystemClock.elapsedRealtimeNanos())"))
        assertTrue(automatic.contains("try { audit.event(value.entry()) } catch (_: Exception)"))
        val manual = source("ManualBleScanner")
        assertTrue(manual.contains("ManualScanPresentation.finalSnapshot("))
        assertTrue(manual.contains("ManualScanPresentation.finish("))
        assertTrue(manual.contains("finally { ticket?.let { retirement.retire(it) } }"))
        assertTrue(manual.contains("ManualScanStartedDelivery.deliver("))
    }
    @Test fun `automatic scan filter and current packet both use accepted frozen raw full target`() {
        val text = source("BandResetController")
        assertTrue(text.contains("private val target: TargetSnapshot"))
        assertTrue(text.contains("ScanFilter.Builder().setDeviceName(target.advertisementName).build()"))
        assertTrue(text.contains("scanner.startScan(filters,")); assertFalse(text.contains("scanner.startScan(null,"))
        assertTrue(text.contains("target.matches(name)")); assertFalse(text.contains("BandMatcher"))
        assertTrue(text.indexOf("ownership.claimMatch(runId)") < text.indexOf("teardown()\n                cycleBluetooth()"))
        assertFalse(text.contains("DiscoveredDevices(100)"))
        assertTrue(source("ManualBleScanner").contains("ScanSummaryObservation(target)"))
        assertFalse(source("ScanSummary").contains("BandMatcher"))
        assertFalse(source("RunNotification").contains("BandMatcher"))
    }
    @Test fun `accepted manual scanner candidates publish final then close before live clear without old result hydration`() {
        val controller = source("ManualOperationController")
        assertTrue(controller.contains("candidates.begin(operation)"))
        assertTrue(controller.contains("candidates.update(")); assertTrue(controller.contains("candidates.close("))
        assertTrue(source("ResetCoordinator").contains("ManualScanCandidates { publishDetails() }"))
        val activity = source("PermissionActivity")
        assertTrue(activity.contains("AutomaticSettingsEditor(")); assertTrue(activity.contains("snapshot.scanCandidates"))
        assertFalse(activity.contains("snapshot.manual(ManualAction.SCAN)"))
        assertTrue(activity.contains("override fun replaceText(")); assertTrue(activity.contains("editor.choose(choice)"))
    }
    @Test fun `spec fix seams guard actual Android worker timeout lifecycle and publication boundaries`() {
        val coordinator = source("ResetCoordinator")
        assertTrue(coordinator.contains("MonitorTimeoutScheduling.post(delay, task) { ms, callback -> handler.postDelayed(callback, ms) }"))
        assertTrue(coordinator.contains("override fun later(delay: Long, task: () -> Unit) { monitorLater(delay, task) }"))
        assertTrue(coordinator.contains("}, monitorLater, { slice ->"))
        assertTrue(coordinator.contains("MonitorSettingsWriteHandoff(AutomaticSettingsPreferences(app),"))
        assertTrue(coordinator.contains("!monitorHost.newScanEligible(actual)"))
        assertTrue(coordinator.contains("!scanPermit.eligible(monitorBinding.child)"))
        assertTrue(coordinator.contains("retirement, target, audit, scanPermit)"))
        assertTrue(coordinator.indexOf("MonitorRecoveryAdmission.permits(binding.child, pending)") < coordinator.indexOf("monitorWork.recovery(binding.child,"))
        assertTrue(coordinator.contains("AutomaticScanCompletionDelivery.finish("))
        assertTrue(source("RuntimeOwnership").contains("try { snapshot() } catch (_: Exception) { return }"))
        val scanner = source("BandResetController")
        val finalEntry = scanner.substring(scanner.indexOf("else retirement.start(ticket) registration@"), scanner.indexOf("scanner.startScan(filters,"))
        assertTrue(finalEntry.contains("scanEntryPermit?.tryEnter() == false"))
        assertFalse(finalEntry.contains("monitorHost")); assertFalse(finalEntry.contains("store.snapshot")); assertFalse(finalEntry.contains("gate.automaticContinuation"))
    }
    @Test fun `details adds explicit checked settings and system entrances while preserving original button rows`() {
        val text = source("PermissionActivity")
        for (label in listOf("自动监控", "定时", "亮屏", "灭屏", "保存自动设置")) assertTrue(text.contains(label))
        assertTrue(text.contains("coordinator.saveAutomatic(")); assertTrue(text.contains("editor.confirmSaved("))
        assertTrue(text.contains("ACTION_REQUEST_SCHEDULE_EXACT_ALARM")); assertTrue(text.contains("ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS"))
        assertFalse(text.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertFalse(text.contains("CommandLease(")); assertFalse(text.contains("driver.execute("))
        assertFalse(text.contains("manualBodies")); assertFalse(text.contains("日志说明"))
        val notification = source("RunNotification")
        assertTrue(notification.contains("MONITOR_ID = 106")); assertTrue(notification.contains("bt_monitor_automatic"))
        assertTrue(notification.contains("setSound(null, null)")); assertFalse(notification.contains("无定时/开机监控"))
    }
}
