package com.blreay.bluetoothmonitor

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.blreay.bluetoothmonitor.ManualScanStartedDelivery.Started

/** One bounded BLE-advertisement observer. No reset, bonded cache, classic discovery or enable. */
class ManualBleScanner(
    private val context: Context,
    private val operation: ManualOperation,
    private val lease: CommandLease,
    private val retirement: ScanRegistrationRetirement,
    private val events: ManualScanEvents,
    private val target: TargetSnapshot?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val devices = DiscoveredDevices(100)
    private val summaryObservation = ScanSummaryObservation(target)
    private var ticket: ScanRegistrationRetirement.Ticket? = null
    @Volatile private var finished = false
    private var updateQueued = false
    private var entered = false
    private val observationWindow = ManualObservationWindow()
    private val flush = Runnable { updateQueued = false; events.devices(devices.snapshot(), devices.overflow) }
    private val timeout = Runnable { finish("8秒 BLE 广播观察结束", false) }
    private data class Prepared(val scanner: BluetoothLeScanner?, val reason: String, val returnedAt: Long?, val ended: Boolean)
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            AdvertisementDelivery.deliver(listOf(result), { SystemClock.elapsedRealtimeNanos() }, ::dispatchAdvertisement, ::observe)
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            AdvertisementDelivery.deliver(results, { SystemClock.elapsedRealtimeNanos() }, ::dispatchAdvertisement, ::observe)
        }
        override fun onScanFailed(errorCode: Int) {
            dispatchAdvertisement { finish("BLE 扫描失败代码 $errorCode；等待原注册清理", true) }
        }
    }
    private fun dispatchAdvertisement(deliver: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) deliver() else handler.post { deliver() }
    }
    private fun observe(result: ScanResult, receivedElapsedNanos: Long) {
        if (finished || !lease.valid() || !entered) return
        if (!observationWindow.admit(result.timestampNanos, receivedElapsedNanos, SystemClock.elapsedRealtimeNanos()) { timeout.run() }) return
        val address = try { result.device?.address } catch (_: SecurityException) {
            finish("附近设备权限已撤销；等待原 BLE 注册清理", true); return
        } ?: return
        val name = result.scanRecord?.deviceName // only this advertisement, never BluetoothDevice.name
        val rssi = result.rssi
        summaryObservation.admitted(address, name) // admitted current-window packet, BEFORE the existing cap check
        devices.add(address, name, rssi, "BLE")
        if (!updateQueued) { updateQueued = true; handler.postDelayed(flush, 250L) }
    }
    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.ADAPTER, lease, 1_500L,
            { Prepared(null, "BLE 准备未进入执行；队列/窗口占用", null, true) },
            { Prepared(null, "BLE 准备超时；执行结束未知", null, false) }, {
                try {
                    val permitted = BtPermissions.allGranted(context)
                    val state = if (permitted) BluetoothSwitch.state(context) else AdapterState.UNKNOWN
                    val scanner = if (permitted && state == AdapterState.ON) BluetoothSwitch.adapter(context)?.bluetoothLeScanner else null
                    Prepared(scanner, if (scanner == null) "蓝牙未开启/不可用或缺少附近设备权限；未自动开启" else "BLE 已准备",
                        System.currentTimeMillis(), true)
                } catch (_: SecurityException) {
                    Prepared(null, "附近设备权限已撤销；未注册 BLE 扫描", System.currentTimeMillis(), true)
                } catch (_: Exception) {
                    Prepared(null, "BLE 准备失败且已返回；未注册扫描", System.currentTimeMillis(), true)
                }
            }) { prepared, late ->
                if (late || finished || !lease.valid()) {
                    if (prepared.ended) events.preparationEnded(prepared.returnedAt)
                    if (!finished) finish("准备窗口结束；未注册新扫描", true)
                } else if (prepared.scanner == null) {
                    finish(prepared.reason, true)
                    if (prepared.ended) events.preparationEnded(prepared.returnedAt)
                } else register(prepared.scanner)
            }
    }
    private fun register(scanner: BluetoothLeScanner) {
        val exact = retirement.claim(operation.operationId) {
            try { scanner.stopScan(callback); true }
            catch (_: SecurityException) { false } // retain exact ticket; explicit retry after permission restoration
            catch (_: Exception) { false }
        } ?: run { finish("扫描槽仍被旧注册占用；未开始新扫描", true); events.preparationEnded(null); return }
        ticket = exact
        if (!events.registered(identity(exact))) {
            retirement.retire(exact) // failed fence write cannot prevent cleanup of a claimed, never-entered ticket
            finish("扫描注册隔离保存失败；未开始扫描", true)
            events.preparationEnded(null)
            return
        }
        BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.ADAPTER, lease, 1_500L,
            { Started(false, null, true) }, { Started(false, null, false) }, {
                var registrationEntered = false
                val ok = if (!lease.valid() || finished) { retirement.retire(exact); false }
                else retirement.start(exact) registration@{
                    if (!lease.valid() || finished) return@registration false
                    registrationEntered = true
                    val at = System.currentTimeMillis()
                    observationWindow.captureEntry(SystemClock.elapsedRealtimeNanos())
                    handler.post { entered = true; events.entered(at) }
                    val started = try {
                        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                        true
                    } catch (_: SecurityException) { false } catch (_: Exception) { false }
                    started
                }
                Started(ok, System.currentTimeMillis().takeIf { registrationEntered }, true)
            }) { start, late ->
                ManualScanStartedDelivery.deliver(start, late, { finished }, { lease.valid() }, events::returned,
                    { value -> finish("BLE 注册${if (value.ended) "已返回失败/窗口结束" else "超时；执行结束未知"}；等待原注册真实清理", true, value) },
                    ::retire) {
                    observationWindow.startResult(SystemClock.elapsedRealtimeNanos(), { timeout.run() }) { remaining ->
                        handler.postDelayed(timeout, remaining)
                    }
                }
            }
    }
    private fun closeObservation(result: String, start: Started? = null) {
        finished = true
        handler.removeCallbacks(timeout); handler.removeCallbacks(flush); updateQueued = false
        val snapshot = devices.snapshot()
        summaryObservation.closeManual(snapshot.size, devices.overflow,
            observationWindow.capturedEntryElapsedNanos() != null, ticket != null, result, start)?.let { value ->
            ManualScanPresentation.finalSnapshot({ events.devices(snapshot, devices.overflow) },
                { events.summary(value, snapshot) }) // both final observers run before returned/terminal retirement
        }
    }
    private fun finish(reason: String, failed: Boolean, start: Started? = null) {
        if (finished) return
        ManualScanPresentation.finish({ closeObservation(if (failed) "失败（部分结果）" else "成功", start) },
            { events.finished(reason, failed) }, ::retire)
    }
    fun retire() {
        try { closeObservation("取消（部分结果）") } // silent retirement publishes once; never recursively calls finished
        finally { ticket?.let { retirement.retire(it) } }
    }
    fun registrationTicket(): ScanRegistrationRetirement.Ticket? = ticket
    fun retirementConfirmed(exact: ScanRegistrationRetirement.Ticket) {
        if (ticket == exact) events.retired(identity(exact))
    }
    companion object { fun identity(ticket: ScanRegistrationRetirement.Ticket) = "${ticket.runId}/${ticket.serial}" }
}
