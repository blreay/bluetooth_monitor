package com.blreay.bluetoothmonitor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
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
    private val onMatch: (String) -> Boolean,
) {
    enum class Result { NO_ADAPTER, BLUETOOTH_OFF, NEEDS_PERMISSION, NOT_FOUND, DISABLE_FAILED, RESET_TRIGGERED, ERROR }
    fun interface Callback { fun onResult(result: Result) }
    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private val completion = CompletionGate()
    private var callback: Callback? = null
    private var wantsToast = false

    private data class Prepared(val adapter: BluetoothAdapter?, val scanner: BluetoothLeScanner?, val state: AdapterState, val bonded: Boolean, val failed: Boolean = false)
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
                    // Bonded-device data is diagnostic only and cannot delay scanner setup/receiver finish.
                    BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.DIAGNOSTIC, lease, 500L,
                        { false }, { false }, {
                            try { prepared.adapter.bondedDevices?.any { BandMatcher.isTarget(it.address, it.name) } ?: false }
                            catch (_: SecurityException) { false }
                        }) { bonded, diagnosticLate -> if (!diagnosticLate && current()) attempt.targetBonded = bonded }
                    attempt.start(scanTimeoutMs)
                }
            }
        }
    }

    fun cancel() {
        lease.cancel()
        session?.settled = true
        session?.teardown()
        if (completion.finish()) callback?.onResult(Result.ERROR)
    }
    private fun current() = lease.valid() && ownership.current(runId) && session?.settled != true
    private fun emit(result: Result, facts: RunReport.ScanFacts? = null, matchedLabel: String? = null,
                     nearMissSample: String? = null, adapterState: AdapterState? = null) {
        if (!completion.finish()) return
        session?.settled = true
        session?.teardown()
        Notifier.outcome(context, wantsToast, result.name)
        RunNotification.fastPhase(context, result, facts, matchedLabel, nearMissSample, adapterState)
        callback?.onResult(result)
    }

    private inner class Session(private val scanner: BluetoothLeScanner, var targetBonded: Boolean) {
        @Volatile var settled = false
        private var resetInFlight = false
        private var registration: ScanRegistrationRetirement.Ticket? = null
        private var retirementRequested = false
        private var windowMs = 0L
        private var startedAtMs = 0L
        private val seenAddresses = mutableSetOf<String>()
        private var namedSeen = 0
        private val nearMisses = mutableListOf<String>()
        private var scanFailedCode: Int? = null
        private var matchedLabel: String? = null
        private fun accepting() = current() && !resetInFlight
        private val onTimeout = Runnable { if (accepting()) report(Result.NOT_FOUND) }
        private val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { onScanResult(callbackType, result) }; return }
                if (!accepting()) return
                val mac = try { result.device?.address } catch (_: Exception) { null }
                // Use the current broadcast/scan response name, not a cached BluetoothDevice.name Binder read.
                val name = try { result.scanRecord?.deviceName } catch (_: Exception) { null }
                val target = BandMatcher.isTarget(mac, name)
                record(mac, name, target)
                if (!target || !ownership.claimMatch(runId)) return
                // Claim before stopScan: queued/batch results cannot produce a second OFF.
                resetInFlight = true
                matchedLabel = if (name != null) "$name ($mac)" else (mac ?: "?")
                teardown()
                cycleBluetooth()
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { onBatchScanResults(results) }; return }
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }
            override fun onScanFailed(errorCode: Int) {
                if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { onScanFailed(errorCode) }; return }
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
            startedAtMs = SystemClock.elapsedRealtime()
            handler.postDelayed(onTimeout, timeout)
            val gate = CompletionGate()
            BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.ADAPTER, lease, 1_500L,
                { false }, { false }, {
                    if (!lease.valid() || settled) { retirement.retire(ticket); false }
                    else retirement.start(ticket) {
                        try {
                            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
                            true
                        } catch (e: SecurityException) {
                            Log.w("BtReset.Ctrl", "scan permission revoked during setup", e)
                            false
                        }
                    }
                }) { ok, _ -> if (gate.finish() && !ok && accepting()) report(Result.ERROR) }
        }
        fun teardown() {
            handler.removeCallbacks(onTimeout)
            if (retirementRequested) return
            retirementRequested = true
            registration?.let {
                // Coalesced exact cleanup survives old lease expiry and entered late start completion.
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
            // Only a durable OFF_READY job is created here. Receiver scanning never spends leftover budget in RPC/human wait.
            val armed = onMatch(summary)
            report(if (armed) Result.RESET_TRIGGERED else Result.DISABLE_FAILED)
        }
        private fun factsLine() = "扫描 ${SystemClock.elapsedRealtime() - startedAtMs}/${windowMs}ms；不同 MAC=${seenAddresses.size}，广播名=$namedSeen"
        private fun record(mac: String?, name: String?, target: Boolean) {
            if (mac != null && seenAddresses.add(mac) && name != null) namedSeen++
            if (target || nearMisses.size >= 3) return
            if ((mac?.endsWith(BandMatcher.TARGET_MAC_SUFFIX, true) == true) ||
                name?.let { n -> listOf("Xiaomi", "Band", "Mi Smart").any { n.contains(it, true) } } == true) {
                nearMisses.add(if (name != null) "$name ($mac)" else (mac ?: "?"))
            }
        }
        private fun report(result: Result) = emit(result, RunReport.ScanFacts(windowMs,
            SystemClock.elapsedRealtime() - startedAtMs, seenAddresses.size, namedSeen, nearMisses.size,
            targetBonded, scanFailedCode), matchedLabel, nearMisses.takeIf { it.isNotEmpty() }?.joinToString("、"))
    }
}
