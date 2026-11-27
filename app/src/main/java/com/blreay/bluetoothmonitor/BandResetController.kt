package com.blreay.bluetoothmonitor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** A single current-advertisement scan, never pairing, connecting, or matching a bonded cache. */
class BandResetController(
    private val context: Context,
    private val ownership: RunOwnership,
    private val runId: String,
    private val lease: CommandLease,
    private val retirement: ScanRegistrationRetirement,
    private val target: TargetSnapshot,
    private val audit: AuditSink = AuditSink.NONE,
    private val scanEntryPermit: MonitorScanEntryPermit? = null,
    private val onMatch: (AcceptedResetMatch) -> Boolean,
) {
    enum class Result { NO_ADAPTER, BLUETOOTH_OFF, NEEDS_PERMISSION, NOT_FOUND, DISABLE_FAILED, RESET_TRIGGERED, ERROR }
    fun interface Callback { fun onResult(result: Result) }
    data class ScanOutcome(val facts: RunReport.ScanFacts?, val matchedLabel: String?, val nearMissSample: String?, val adapterState: AdapterState?)
    var lastReport: ScanOutcome? = null
        private set
    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private val completion = CompletionGate()
    private var callback: Callback? = null
    private var wantsToast = false
    private val summaryObservation = ScanSummaryObservation(target)
    private fun publishSummary(result: String) {
        val executed = session?.scannerEntered == true
        val phrase = if (!executed && session?.entryPending() == true) "状态未确认" else result
        val value = summaryObservation.close(session?.deviceCount() ?: 0, false, executed, phrase) ?: return
        try { audit.event(value.entry()) } catch (_: Exception) { } // annotation is not teardown/authority evidence
    }

    private data class Prepared(val adapter: BluetoothAdapter?, val scanner: BluetoothLeScanner?, val state: AdapterState, val bonded: Boolean,
                                val failed: Boolean = false)
    fun run(scanTimeoutMs: Long, wantsToast: Boolean, callback: Callback) {
        check(Looper.myLooper() == Looper.getMainLooper())
        this.callback = callback
        this.wantsToast = wantsToast
        if (!BtPermissions.allGranted(context)) { emit(Result.NEEDS_PERMISSION); return }
        BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.ADAPTER, lease, 1_500L,
            { Prepared(null, null, AdapterState.UNKNOWN, false, true) }, { Prepared(null, null, AdapterState.UNKNOWN, false, true) },
            {
                val adapter = BluetoothSwitch.adapter(context)
                val state = BluetoothSwitch.state(context)
                Prepared(adapter, if (state == AdapterState.ON) adapter?.bluetoothLeScanner else null, state, false)
            },
        ) { prepared, late ->
            if (late || !current()) return@submit
            when {
                prepared.failed -> emit(Result.ERROR)
                prepared.adapter == null -> emit(Result.NO_ADAPTER)
                prepared.state != AdapterState.ON -> emit(Result.BLUETOOTH_OFF, adapterState = prepared.state)
                prepared.scanner == null -> emit(Result.ERROR)
                else -> {
                    val attempt = Session(prepared.scanner, prepared.bonded)
                    session = attempt
                    // Filtered current advertisements only; cached/bonded names are not matching evidence.
                    attempt.start(scanTimeoutMs)
                }
            }
        }
    }

    fun cancel() {
        lease.cancel()
        session?.settled = true
        session?.teardown()
        if (completion.finish()) { publishSummary("取消（部分结果）"); callback?.onResult(Result.ERROR) }
    }
    private fun current() = lease.valid() && ownership.current(runId) && session?.settled != true
    private fun emit(result: Result, facts: RunReport.ScanFacts? = null, matchedLabel: String? = null,
                     nearMissSample: String? = null, adapterState: AdapterState? = null) {
        if (!completion.finish()) return
        publishSummary(if (result == Result.NOT_FOUND || result == Result.RESET_TRIGGERED) "成功"
            else if (session?.scannerEntered == true) "失败（部分结果）" else "未执行")
        session?.settled = true
        session?.teardown()
        lastReport = ScanOutcome(facts, matchedLabel, nearMissSample, adapterState)
        try { Notifier.outcome(context, wantsToast, result.name) }
        finally { callback?.onResult(result) }
    }

    private inner class Session(private val scanner: BluetoothLeScanner, var targetBonded: Boolean) {
        @Volatile var settled = false
        @Volatile var scannerEntered = false
        fun deviceCount() = seenAddresses.size
        fun entryPending() = registration != null
        private var resetInFlight = false
        private var registration: ScanRegistrationRetirement.Ticket? = null
        private var retirementRequested = false
        private var windowMs = 0L
        @Volatile private var packetWindow: AutomaticPacketWindow? = null
        private var startedAtMs = 0L
        private var startedWall: Long? = null
        private var acceptedAt: Long? = null
        private var matchedName: String? = null
        private var matchedAddress: String? = null
        private val seenAddresses = mutableSetOf<String>()
        private var namedSeen = 0
        private val nearMisses = mutableListOf<String>()
        private var scanFailedCode: Int? = null
        private var matchedLabel: String? = null
        private fun accepting() = current() && !resetInFlight
        private val onTimeout = Runnable { if (accepting()) report(Result.NOT_FOUND) }
        private val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = advertisement(result, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
            private fun advertisement(result: ScanResult, receivedElapsedNanos: Long, receivedAt: Long) {
                if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { advertisement(result, receivedElapsedNanos, receivedAt) }; return }
                if (!accepting()) return
                if (packetWindow?.admit(result.timestampNanos, receivedElapsedNanos, SystemClock.elapsedRealtimeNanos()) != true) {
                    if (packetWindow?.remainingMs(SystemClock.elapsedRealtimeNanos()) == null) report(Result.NOT_FOUND)
                    return
                }
                val mac = try { result.device?.address } catch (_: Exception) { null }
                val name = try { result.scanRecord?.deviceName } catch (_: Exception) { null }
                if (!target.matches(name)) return // this packet only, never retained/cached display name
                summaryObservation.admitted(mac, name) // sticky observation precedes claim/arm; not authority
                record(mac, name) // filtered accepted advertisements only, with no manual collector cap
                val claimed = ownership.claimMatch(runId)
                if (!claimed) return
                // Claim before stopScan: queued/batch results cannot produce a second OFF.
                resetInFlight = true
                matchedLabel = if (name != null) "$name ($mac)" else (mac ?: "?")
                matchedName = name; matchedAddress = mac; acceptedAt = receivedAt
                teardown()
                cycleBluetooth()
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                val receivedWall = System.currentTimeMillis()
                AdvertisementDelivery.deliver(results, { SystemClock.elapsedRealtimeNanos() }, { task ->
                    if (Looper.myLooper() == Looper.getMainLooper()) task() else handler.post(task)
                }) { result, receivedElapsed -> advertisement(result, receivedElapsed, receivedWall) }
            }
            override fun onScanFailed(errorCode: Int) = failed(errorCode)
            private fun failed(errorCode: Int) {
                if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { failed(errorCode) }; return }
                if (!accepting()) return
                scanFailedCode = errorCode
                report(Result.ERROR)
            }
        }
        fun start(timeout: Long) {
            val ticket = retirement.claim(runId) {
                try { scanner.stopScan(scanCallback); true }
                catch (e: SecurityException) { Log.w("BtReset.Ctrl", "scan permission revoked during cleanup", e); false }
            } ?: run { report(Result.ERROR); return }
            registration = ticket
            windowMs = timeout
            packetWindow = AutomaticPacketWindow(timeout)
            val gate = CompletionGate()
            BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.ADAPTER, lease, 1_500L,
                { false }, { false }, {
                    if (!lease.valid() || settled) { retirement.retire(ticket); false }
                    else retirement.start(ticket) registration@{
                        if (!lease.valid() || settled || scanEntryPermit?.tryEnter() == false) return@registration false
                        // This exact scan-only permit is the worker handoff. Never read Main host/store/gate here.
                        val elapsed = SystemClock.elapsedRealtime()
                        val wall = System.currentTimeMillis()
                        packetWindow?.captureEntry(SystemClock.elapsedRealtimeNanos())
                        scannerEntered = true
                        handler.post {
                            startedAtMs = elapsed; startedWall = wall
                            if (accepting()) {
                                val remaining = packetWindow?.remainingMs(SystemClock.elapsedRealtimeNanos())
                                if (remaining == null) report(Result.NOT_FOUND) else handler.postDelayed(onTimeout, remaining)
                            }
                        }
                        val started = try {
                            val filters = listOf(ScanFilter.Builder().setDeviceName(target.advertisementName).build())
                            scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
                            true
                        } catch (e: SecurityException) {
                            Log.w("BtReset.Ctrl", "scan permission revoked during setup", e)
                            false
                        }
                        started
                    }
                }) { ok, _ -> if (gate.finish() && !ok && accepting()) report(Result.ERROR) }
        }
        fun teardown() {
            handler.removeCallbacks(onTimeout)
            if (retirementRequested) return
            retirementRequested = true
            registration?.let {
                retirement.retire(it)
                handler.postDelayed({
                    retirement.retirementAge(it)?.let { age ->
                        RunNotification.message(context, "扫描清理仍待确认", "原扫描注册清理已等待 ${age}ms；不会叠加新扫描。失败时恢复权限后重新启动图标可只重试清理")
                    }
                }, 1_000L)
            }
        }
        private fun cycleBluetooth() {
            if (!current()) return
            val summary = "目标 ${matchedLabel ?: "?"}；${factsLine()}；疑似未匹配：${nearMisses.joinToString("、")}；已配对诊断=$targetBonded"
            val armed = onMatch(AcceptedResetMatch(runId, matchedName, matchedAddress, facts(), summary,
                acceptedAt, startedWall, acceptedAt, startedAtMs.takeIf { startedWall != null }))
            report(if (armed) Result.RESET_TRIGGERED else Result.DISABLE_FAILED)
        }
        private fun factsLine() = "扫描 ${SystemClock.elapsedRealtime() - startedAtMs}/${windowMs}ms；不同 MAC=${seenAddresses.size}，广播名=$namedSeen"
        private fun record(mac: String?, name: String?) {
            if (mac != null && seenAddresses.add(mac) && name != null) namedSeen++
        }
        private fun facts() = RunReport.ScanFacts(windowMs,
            if (startedWall == null) 0L else (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L),
            seenAddresses.size, namedSeen, nearMisses.size, targetBonded, scanFailedCode)
        private fun report(result: Result) = emit(result, facts(), matchedLabel, nearMisses.takeIf { it.isNotEmpty() }?.joinToString("、"))
    }
}
