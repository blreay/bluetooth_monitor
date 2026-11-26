package com.blreay.bluetoothmonitor

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** One bounded BLE-advertisement observer. No matcher/reset, bonded cache, classic discovery or enable. */
class ManualBleScanner(
    private val context: Context,
    private val operation: ManualOperation,
    private val lease: CommandLease,
    private val retirement: ScanRegistrationRetirement,
    private val events: ManualScanEvents,
    private val audit: AuditSink = AuditSink.NONE,
) {
    private val runtimeAudit = RuntimeAudit(audit)
    private val auditIdentity = RuntimeIdentity("manual", operation.operationId, operation.commandId)
    private fun log(name: String, vararg fields: Pair<String, Any?>, at: Long? = null) {
        runtimeAudit.event(name, auditIdentity, "scannerIdentity" to ticket?.let { identity(it) }, *fields, at = at)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val devices = DiscoveredDevices(100)
    private var ticket: ScanRegistrationRetirement.Ticket? = null
    @Volatile private var finished = false
    private var updateQueued = false
    private var entered = false
    private val observationWindow = ManualObservationWindow()
    private val flush = Runnable { updateQueued = false; events.devices(devices.snapshot(), devices.overflow) }
    private val timeout = Runnable { finish("8秒 BLE 广播观察结束", false) }
    private data class Prepared(val scanner: BluetoothLeScanner?, val reason: String, val returnedAt: Long?, val ended: Boolean,
                                val state: AdapterState? = null, val permissions: Boolean? = null,
                                val sampledElapsedMs: Long? = null, val stateSampledAt: Long? = null)
    private data class Started(val ok: Boolean, val returnedAt: Long?, val ended: Boolean)
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val receivedAt = System.currentTimeMillis()
            AdvertisementDelivery.deliver(listOf(result), { SystemClock.elapsedRealtimeNanos() }, ::dispatchAdvertisement) { value, elapsed -> observe(value, elapsed, receivedAt) }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            val receivedAt = System.currentTimeMillis() // one wall receipt shared by the entire batch
            AdvertisementDelivery.deliver(results, { SystemClock.elapsedRealtimeNanos() }, ::dispatchAdvertisement) { value, elapsed -> observe(value, elapsed, receivedAt) }
        }
        override fun onScanFailed(errorCode: Int) {
            val occurredAt = System.currentTimeMillis()
            dispatchAdvertisement {
                log("manual.ble.callback.failed", "errorCode" to errorCode, at = occurredAt)
                finish("BLE 扫描失败代码 $errorCode；等待原注册清理", true)
            }
        }
    }
    private fun dispatchAdvertisement(deliver: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) deliver() else handler.post { deliver() }
    }
    private fun observe(result: ScanResult, receivedElapsedNanos: Long, receivedAt: Long) {
        if (finished || !lease.valid() || !entered) return
        if (!observationWindow.admit(result.timestampNanos, receivedElapsedNanos, SystemClock.elapsedRealtimeNanos()) { timeout.run() }) return
        val address = try { result.device?.address } catch (_: SecurityException) {
            finish("附近设备权限已撤销；等待原 BLE 注册清理", true); return
        } ?: return
        val name = result.scanRecord?.deviceName // only this advertisement, never BluetoothDevice.name
        val rssi = result.rssi
        runtimeAudit.advertisement(auditIdentity, ticket?.let { identity(it) }, address, name, rssi,
            result.timestampNanos, receivedAt, receivedElapsedNanos, SystemClock.elapsedRealtimeNanos(), displayOnly = true)
        devices.add(address, name, rssi, "BLE")
        if (!updateQueued) { updateQueued = true; handler.postDelayed(flush, 250L) }
    }
    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        log("manual.ble.prepare.request", "windowMs" to 8_000L)
        BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.ADAPTER, lease, 1_500L,
            { Prepared(null, "BLE 准备未进入执行；队列/窗口占用", null, true) },
            { Prepared(null, "BLE 准备超时；执行结束未知", null, false) }, {
                try {
                    val permitted = BtPermissions.allGranted(context)
                    val state = if (permitted) BluetoothSwitch.state(context) else AdapterState.UNKNOWN
                    val stateSampledAt = System.currentTimeMillis().takeIf { permitted }
                    val sampledElapsedMs = SystemClock.elapsedRealtime().takeIf { permitted }
                    val scanner = if (permitted && state == AdapterState.ON) BluetoothSwitch.adapter(context)?.bluetoothLeScanner else null
                    Prepared(scanner, if (scanner == null) "蓝牙未开启/不可用或缺少附近设备权限；未自动开启" else "BLE 已准备",
                        System.currentTimeMillis(), true, state, permitted, sampledElapsedMs, stateSampledAt)
                } catch (_: SecurityException) {
                    Prepared(null, "附近设备权限已撤销；未注册 BLE 扫描", System.currentTimeMillis(), true)
                } catch (_: Exception) {
                    Prepared(null, "BLE 准备失败且已返回；未注册扫描", System.currentTimeMillis(), true)
                }
            }) { prepared, late ->
                log("manual.ble.prepared", "state" to prepared.state, "permissions" to prepared.permissions,
                    "stateSampledAt" to prepared.stateSampledAt, "sampledElapsedMs" to prepared.sampledElapsedMs, "reason" to prepared.reason,
                    "authorityEnded" to prepared.ended, "late" to late, at = prepared.returnedAt)
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
                    log("manual.ble.startScan.entry", "enteredElapsedNanos" to observationWindow.capturedEntryElapsedNanos(), "windowMs" to 8_000L, at = at)
                    handler.post { entered = true; events.entered(at) }
                    val started = try {
                        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                        true
                    } catch (failure: SecurityException) {
                        log("manual.ble.startScan.failed", "failureType" to failure.javaClass.simpleName); false
                    } catch (failure: Exception) {
                        log("manual.ble.startScan.failed", "failureType" to failure.javaClass.simpleName); false
                    }
                    log("manual.ble.startScan.return", "started" to started, "registrationRetired" to false, at = System.currentTimeMillis())
                    started
                }
                Started(ok, System.currentTimeMillis().takeIf { registrationEntered }, true)
            }) { start, late ->
                if (start.ended) events.returned(start.returnedAt, start.ok)
                if (!start.ok || late || finished || !lease.valid()) {
                    finish("BLE 注册${if (start.ended) "已返回失败/窗口结束" else "超时；执行结束未知"}；等待原注册真实清理", true)
                    retire()
                } else {
                    observationWindow.startResult(SystemClock.elapsedRealtimeNanos(), { timeout.run() }) { remaining ->
                        handler.postDelayed(timeout, remaining)
                    }
                }
            }
    }
    private fun finish(reason: String, failed: Boolean) {
        if (finished) return
        finished = true
        log("manual.ble.result", "reason" to reason, "failed" to failed, "deviceCount" to devices.snapshot().size,
            "overflow" to devices.overflow, "registrationRetired" to false)
        handler.removeCallbacks(timeout); handler.removeCallbacks(flush); updateQueued = false
        events.devices(devices.snapshot(), devices.overflow)
        events.finished(reason, failed)
        retire()
    }
    fun retire() {
        log("manual.ble.cancel", "registrationRetired" to false)
        finished = true
        handler.removeCallbacks(timeout); handler.removeCallbacks(flush)
        ticket?.let { retirement.retire(it) }
    }
    fun registrationTicket(): ScanRegistrationRetirement.Ticket? = ticket
    fun retirementConfirmed(exact: ScanRegistrationRetirement.Ticket) {
        if (ticket == exact) events.retired(identity(exact))
    }
    companion object { fun identity(ticket: ScanRegistrationRetirement.Ticket) = "${ticket.runId}/${ticket.serial}" }
}
