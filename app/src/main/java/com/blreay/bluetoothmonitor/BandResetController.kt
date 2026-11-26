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
    private val audit: AuditSink = AuditSink.NONE,
    private val onMatch: (AcceptedResetMatch) -> Boolean,
) {
    enum class Result { NO_ADAPTER, BLUETOOTH_OFF, NEEDS_PERMISSION, NOT_FOUND, DISABLE_FAILED, RESET_TRIGGERED, ERROR }
    fun interface Callback { fun onResult(result: Result) }
    data class ScanOutcome(val facts: RunReport.ScanFacts?, val matchedLabel: String?, val nearMissSample: String?, val adapterState: AdapterState?)
    var lastReport: ScanOutcome? = null
        private set
    private val runtimeAudit = RuntimeAudit(audit)
    private val auditIdentity = RuntimeIdentity("automatic", runId, "")
    private fun log(name: String, vararg fields: Pair<String, Any?>, at: Long? = null) {
        runtimeAudit.event(name, auditIdentity, *fields, at = at)
    }
    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private val completion = CompletionGate()
    private var callback: Callback? = null
    private var wantsToast = false

    private data class Prepared(val adapter: BluetoothAdapter?, val scanner: BluetoothLeScanner?, val state: AdapterState, val bonded: Boolean,
                                val failed: Boolean = false, val sampledAt: Long? = null, val sampledElapsedMs: Long? = null)
    fun run(scanTimeoutMs: Long, wantsToast: Boolean, callback: Callback) {
        check(Looper.myLooper() == Looper.getMainLooper())
        this.callback = callback
        this.wantsToast = wantsToast
        log("automatic.ble.prepare.request", "windowMs" to scanTimeoutMs)
        if (!BtPermissions.allGranted(context)) { emit(Result.NEEDS_PERMISSION); return }
        BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.ADAPTER, lease, 1_500L,
            { Prepared(null, null, AdapterState.UNKNOWN, false, true) }, { Prepared(null, null, AdapterState.UNKNOWN, false, true) },
            {
                val adapter = BluetoothSwitch.adapter(context)
                val state = BluetoothSwitch.state(context)
                val sampledAt = System.currentTimeMillis()
                val elapsed = SystemClock.elapsedRealtime()
                Prepared(adapter, if (state == AdapterState.ON) adapter?.bluetoothLeScanner else null, state, false,
                    sampledAt = sampledAt, sampledElapsedMs = elapsed)
            },
        ) { prepared, late ->
            log("automatic.ble.prepared", "state" to prepared.state, "adapterAvailable" to (prepared.adapter != null),
                "scannerAvailable" to (prepared.scanner != null), "failed" to prepared.failed, "late" to late,
                "sampledElapsedMs" to prepared.sampledElapsedMs, at = prepared.sampledAt)
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
        log("automatic.ble.cancel", "registrationRetired" to false)
        lease.cancel()
        session?.settled = true
        session?.teardown()
        if (completion.finish()) callback?.onResult(Result.ERROR)
    }
    private fun current() = lease.valid() && ownership.current(runId) && session?.settled != true
    private fun emit(result: Result, facts: RunReport.ScanFacts? = null, matchedLabel: String? = null,
                     nearMissSample: String? = null, adapterState: AdapterState? = null) {
        if (!completion.finish()) return
        log("automatic.ble.result", "result" to result, "windowMs" to facts?.windowMs,
            "elapsedMs" to facts?.elapsedMs, "devicesSeen" to facts?.devicesSeen, "namedSeen" to facts?.namedSeen,
            "nearMisses" to facts?.nearMisses, "scanFailedCode" to facts?.scanFailedCode,
            "matchedLabel" to matchedLabel, "adapterState" to adapterState, "registrationRetired" to false)
        session?.settled = true
        session?.teardown()
        Notifier.outcome(context, wantsToast, result.name)
        lastReport = ScanOutcome(facts, matchedLabel, nearMissSample, adapterState) // coordinator unifies reports; scanner cannot overwrite current runtime details
        callback?.onResult(result)
    }

    private inner class Session(private val scanner: BluetoothLeScanner, var targetBonded: Boolean) {
        @Volatile var settled = false
        private var resetInFlight = false
        private var registration: ScanRegistrationRetirement.Ticket? = null
        private var retirementRequested = false
        private var windowMs = 0L
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
        private val onTimeout = Runnable {
            if (accepting()) {
                log("automatic.ble.window.end", "windowMs" to windowMs, "registrationRetired" to false)
                report(Result.NOT_FOUND)
            }
        }
        private val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = advertisement(result, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
            private fun advertisement(result: ScanResult, receivedAt: Long, receivedElapsedNanos: Long) {
                if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { advertisement(result, receivedAt, receivedElapsedNanos) }; return }
                if (!accepting()) return
                val mac = try { result.device?.address } catch (_: Exception) { null }
                // Use the current broadcast/scan response name, not a cached BluetoothDevice.name Binder read.
                val name = try { result.scanRecord?.deviceName } catch (_: Exception) { null }
                val target = BandMatcher.isTarget(mac, name)
                runtimeAudit.advertisement(auditIdentity, registration?.let { "${it.runId}/${it.serial}" }, mac, name, result.rssi,
                    result.timestampNanos, receivedAt, receivedElapsedNanos, SystemClock.elapsedRealtimeNanos(), displayOnly = false)
                record(mac, name, target)
                if (!target) return
                val claimed = ownership.claimMatch(runId)
                log("automatic.ble.target.claim", "claimed" to claimed, "address" to mac, "name" to name,
                    "scannerIdentity" to registration?.let { "${it.runId}/${it.serial}" }, at = receivedAt)
                if (!claimed) return
                // Claim before stopScan: queued/batch results cannot produce a second OFF.
                resetInFlight = true
                matchedLabel = if (name != null) "$name ($mac)" else (mac ?: "?")
                matchedName = name; matchedAddress = mac; acceptedAt = receivedAt
                teardown()
                cycleBluetooth()
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                val receivedElapsedNanos = SystemClock.elapsedRealtimeNanos()
                AdvertisementDelivery.deliver(results, { System.currentTimeMillis() }, { task ->
                    if (Looper.myLooper() == Looper.getMainLooper()) task() else handler.post(task)
                }) { result, receivedAt -> advertisement(result, receivedAt, receivedElapsedNanos) }
            }
            override fun onScanFailed(errorCode: Int) = failed(errorCode, System.currentTimeMillis())
            private fun failed(errorCode: Int, occurredAt: Long) {
                if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { failed(errorCode, occurredAt) }; return }
                if (!accepting()) return
                log("automatic.ble.callback.failed", "errorCode" to errorCode, at = occurredAt)
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
            log("automatic.ble.ticket", "scannerIdentity" to "${ticket.runId}/${ticket.serial}")
            windowMs = timeout
            val gate = CompletionGate()
            BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.ADAPTER, lease, 1_500L,
                { false }, { false }, {
                    if (!lease.valid() || settled) { retirement.retire(ticket); false }
                    else retirement.start(ticket) registration@{
                        if (!lease.valid() || settled) return@registration false
                        val elapsed = SystemClock.elapsedRealtime()
                        val wall = System.currentTimeMillis()
                        log("automatic.ble.startScan.entry", "scannerIdentity" to "${ticket.runId}/${ticket.serial}",
                            "enteredElapsedMs" to elapsed, "windowMs" to timeout, at = wall)
                        handler.post {
                            startedAtMs = elapsed; startedWall = wall
                            if (accepting()) handler.postDelayed(onTimeout, (timeout - (SystemClock.elapsedRealtime() - elapsed)).coerceAtLeast(0L))
                        }
                        val started = try {
                            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
                            true
                        } catch (e: SecurityException) {
                            log("automatic.ble.startScan.failed", "failureType" to e.javaClass.simpleName)
                            Log.w("BtReset.Ctrl", "scan permission revoked during setup", e)
                            false
                        }
                        log("automatic.ble.startScan.return", "scannerIdentity" to "${ticket.runId}/${ticket.serial}",
                            "started" to started, "registrationRetired" to false, at = System.currentTimeMillis())
                        started
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
            val armed = onMatch(AcceptedResetMatch(runId, matchedName, matchedAddress, facts(), summary,
                acceptedAt, startedWall, acceptedAt, startedAtMs.takeIf { startedWall != null }))
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
        private fun facts() = RunReport.ScanFacts(windowMs,
            if (startedWall == null) 0L else (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L),
            seenAddresses.size, namedSeen, nearMisses.size, targetBonded, scanFailedCode)
        private fun report(result: Result) = emit(result, facts(), matchedLabel, nearMisses.takeIf { it.isNotEmpty() }?.joinToString("、"))
    }
}
