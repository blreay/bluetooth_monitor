package com.blreay.bluetoothmonitor

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Complements the pure behavior tests; Android/Binder execution still needs a real device. */
class ManualSourceBoundaryTest {
    private fun source(name: String): String {
        val file = File("src/main/java/com/blreay/bluetoothmonitor/$name.kt")
        assertTrue("Missing $name", file.isFile)
        return file.readText()
    }

    @Test fun `launcher does not dispatch legacy reset or finish immediately`() {
        val activity = source("MainActivity")
        assertFalse(activity.contains("ResetService.start"))
        assertFalse(activity.contains("BandResetController("))
        assertFalse(activity.contains("finish()"))
        for (button in listOf("申请shizuku权限", "关闭蓝牙", "打开蓝牙", "扫描蓝牙")) {
            assertTrue("Missing exact button $button", activity.contains(button))
        }
    }

    @Test fun `manual path never invokes ordinary toggle or target reset`() {
        for (name in listOf("MainActivity", "ShizukuBluetoothClient", "ShizukuUserServiceBroker", "ShizukuRegistrationBroker",
            "PrivilegedBluetoothService", "BluetoothDiscovery")) {
            val text = source(name)
            for (forbidden in listOf("BluetoothSwitch.enable(", "BluetoothSwitch.disable(", "adapter.enable(",
                "adapter.disable(", "ACTION_REQUEST_ENABLE", "cycleBluetooth(", "newProcess(", "BandMatcher", "BandResetController(")) {
                assertFalse("$name must not contain $forbidden", text.contains(forbidden))
            }
        }
    }

    @Test fun `user service uses official binding and dedicated process`() {
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
    }

    @Test fun `provider is protected and launcher is opaque and in recents`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("rikka.shizuku.ShizukuProvider"))
        assertTrue(manifest.contains("android.permission.INTERACT_ACROSS_USERS_FULL"))
        assertFalse(manifest.contains(".PermissionActivity"))
        assertFalse(manifest.contains("Theme.NoDisplay"))
        assertFalse(manifest.contains("excludeFromRecents=\"true\""))
    }

    @Test fun `classic completion belongs to an announced discovery session`() {
        val text = source("BluetoothDiscovery")
        assertTrue(text.contains("BluetoothAdapter.ACTION_DISCOVERY_STARTED"))
        assertTrue(text.contains("classicAnnounced"))
    }

    @Test fun `SDK registrations use one process wide slot and never unsafe false unbind`() {
        val text = source("ShizukuUserServiceBroker")
        assertTrue(text.contains("ShizukuRegistrationBroker<IBinder>"))
        assertTrue(text.contains("management.execute"))
        assertTrue(text.contains("handler.post { barrier() }"))
        assertTrue(text.contains("Shizuku.unbindUserService(registration.args, registration.connection, true)"))
        assertFalse(text.contains("registration.connection, false"))
        assertTrue(text.contains("UUID.randomUUID()"))
        assertTrue(text.contains("强制停止本应用"))
        val client = source("ShizukuBluetoothClient")
        assertFalse(client.contains("Shizuku.bindUserService("))
        assertFalse(client.contains("Shizuku.unbindUserService("))
        assertTrue(client.contains("ShizukuUserServiceBroker.retire("))
    }

    @Test fun `repeat authorization reconfirms exact live binding before any teardown`() {
        val text = source("ShizukuBluetoothClient")
        val connect = text.substringAfter("private fun connect() {").substringBefore("/** Fresh preflight")
        assertTrue(connect.contains("auth.granted"))
        assertTrue(connect.contains("endpoint?.isBinderAlive == true"))
        assertTrue(connect.contains("ShizukuUserServiceBroker.canReconfirm(registration, endpoint)"))
        assertTrue(connect.indexOf("auth.serviceConnected()") < connect.indexOf("dispose(it)"))
        assertTrue(connect.indexOf("return") < connect.indexOf("dispose(it)"))
    }

    @Test fun `quarantine remains explicit across UI reopen instead of offering transparent retries`() {
        val text = source("ShizukuBluetoothClient")
        assertTrue(text.contains("if (ShizukuUserServiceBroker.quarantined)"))
        assertTrue(text.contains("ShizukuUserServiceBroker.quarantined -> ShizukuUserServiceBroker.UNAVAILABLE"))
    }

    @Test fun `queued binder listeners are harmless after lifecycle close`() {
        val text = source("ShizukuBluetoothClient")
        assertTrue(text.contains("if (!started) return@OnBinderReceivedListener"))
        assertTrue(text.contains("if (!started) return@OnBinderDeadListener"))
        assertTrue(text.contains("private fun createReceived(life: Int)"))
        assertTrue(text.contains("private fun createDead(life: Int)"))
        assertTrue(text.contains("private fun createPermissionResult(life: Int)"))
    }

    @Test fun `startup status RPC is tracked bounded and lifecycle canceled`() {
        val text = source("ShizukuBluetoothClient")
        assertTrue(text.contains("statusRead = statusIo.submit"))
        assertTrue(text.contains("auth.timeoutStatusRead(token)"))
        assertTrue(text.contains("statusRead?.cancel(true)"))
        assertTrue(text.contains("statusIo.remove("))
        assertTrue(text.contains("ArrayBlockingQueue(1)"))
        assertTrue(text.contains("shizuku授权状态未知"))
    }

    @Test fun `whole manual page scrolls including verbose operation status`() {
        val text = source("MainActivity")
        assertTrue(text.contains("setContentView(pageScroll)"))
        assertTrue(text.contains("addView(root)"))
        assertFalse(text.contains("MATCH_PARENT, 0, 1f"))
    }

    @Test fun `legacy permission hint points to the explicit scan button`() {
        val text = source("TriggerReceiver")
        assertTrue(text.contains("点击扫描蓝牙"))
        assertFalse(text.contains("launch the app icon once"))
        assertFalse(text.contains("launching the icon once"))
    }

    @Test fun `authorization click waits for automatic provider binder without private registration`() {
        val text = source("ShizukuBluetoothClient")
        assertTrue(text.contains("awaitingBinder"))
        assertTrue(text.contains("Thread.sleep(100)"))
        assertFalse(text.contains("requestBinderForNonProviderProcess"))
    }

    @Test fun `legacy scan and alarm are gated by shared manual interlock`() {
        assertTrue(source("BandResetController").contains("isLegacyCurrent("))
        assertTrue(source("ReEnableReceiver").contains("manualActive"))
        val text = source("MainActivity")
        assertTrue(text.contains("ReEnableScheduler.cancel("))
        assertTrue(text.contains("PendingResetStore.clear("))
    }
}
