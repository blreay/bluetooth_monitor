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

/**
 * The fast phase: find the band, then turn Bluetooth off and hand the
 * turning-back-on to the alarm chain.
 *
 * Deliberately free of any UI dependency so [ResetService] and
 * [TriggerReceiver] share one implementation and cannot drift apart. The
 * caller supplies the scan window: the Service can afford 8s, the broadcast
 * receiver must fit inside goAsync()'s ~10s budget and uses 6s.
 *
 * Callback contract: [Callback.onResult] fires exactly once and means "the
 * fast phase is finished". For [Result.RESET_TRIGGERED] it does NOT mean
 * Bluetooth is off yet, and it certainly does not mean it is back on -
 * disable() only begins the transition. The caller should stop itself
 * immediately; the slow phase is owned by [ReEnableReceiver] and survives
 * this process dying.
 *
 * v1 told callers to stay alive for 3.5s here to protect an in-process
 * re-enable timer. That is gone, and with it the reason the launcher
 * Activity had to hold input focus while the user was looking at their home
 * screen.
 */
// `context` must be an application context: the scan callback outlives
// whichever caller supplied it.
class BandResetController(private val context: Context) {

    enum class Result {
        /** This device has no Bluetooth radio. */
        NO_ADAPTER,

        /**
         * Bluetooth was not in STATE_ON, so there was nothing to reset. Covers
         * OFF as well as TURNING_OFF / TURNING_ON: starting a cycle against a
         * radio that is mid-transition is what produced v1's stuck-off bug.
         */
        BLUETOOTH_OFF,

        /** A Nearby-devices runtime permission is not granted. */
        NEEDS_PERMISSION,

        /** The scan window elapsed without the band becoming visible. */
        NOT_FOUND,

        /**
         * The band was found but the system refused to turn Bluetooth off.
         * Bluetooth is still ON. No re-enable was scheduled.
         */
        DISABLE_FAILED,

        /**
         * Band found, disable() accepted, alarm chain armed. This says nothing
         * about the radio's state right now - it is almost certainly still
         * TURNING_OFF. [ReEnableReceiver] owns the outcome from here.
         */
        RESET_TRIGGERED,

        /** Something unexpected threw. Details are in logcat. */
        ERROR,
    }

    fun interface Callback {
        fun onResult(result: Result)
    }

    /**
     * Starts the fast phase. Returns immediately; the outcome arrives on
     * [callback], on the main thread, exactly once.
     *
     * @param scanTimeoutMs how long to scan before reporting NOT_FOUND. The
     *        Service can afford 8s; the broadcast receiver must fit inside
     *        goAsync()'s ~10s budget and uses 6s.
     * @param wantsToast the debug flag from [Notifier.EXTRA_TOAST]. Passed in
     *        rather than read from an Intent so both entry points behave
     *        identically and neither has to know how the other was invoked.
     */
    fun run(scanTimeoutMs: Long, wantsToast: Boolean, callback: Callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { run(scanTimeoutMs, wantsToast, callback) }
            return
        }
        val token = ManualBluetoothInterlock.shared.legacyToken()
        if (token == null) {
            Log.i(TAG, "manual operation active; rejecting legacy reset")
            callback.onResult(Result.ERROR)
            return
        }
        try {
            val adapter = BluetoothSwitch.adapter(context)
            if (adapter == null) {
                Log.e(TAG, "no BluetoothAdapter; this device has no Bluetooth radio")
                emit(callback, wantsToast, Result.NO_ADAPTER)
                return
            }
            // state, not isEnabled. isEnabled is documented as
            // getState() == STATE_ON, so it is false during TURNING_OFF and
            // TURNING_ON too - which would let a second tap on the launcher
            // icon start a second cycle against a radio that is mid-transition.
            val state = BluetoothSwitch.state(context)
            if (state != AdapterState.ON) {
                Log.i(TAG, "Bluetooth is not ON (state=$state); exiting without doing anything")
                emit(callback, wantsToast, Result.BLUETOOTH_OFF, adapterState = state)
                return
            }
            if (!BtPermissions.allGranted(context)) {
                Log.w(TAG, "missing permission(s) ${BtPermissions.missing(context)}; cannot scan")
                emit(callback, wantsToast, Result.NEEDS_PERMISSION)
                return
            }
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                Log.e(TAG, "BluetoothLeScanner is null even though Bluetooth reports ON")
                emit(callback, wantsToast, Result.ERROR)
                return
            }
            Session(adapter, scanner, wantsToast, callback, token).start(scanTimeoutMs)
        } catch (t: Throwable) {
            Log.e(TAG, "controller failed before scanning", t)
            emit(callback, wantsToast, Result.ERROR)
        }
    }

    /**
     * The single exit for every path, so the debug toast, the run-result
     * notification and the callback cannot get out of step. [Session] has its
     * own one-argument emit() that adds the "have I already reported" guard and
     * supplies the scan facts; the two differ by arity, so Kotlin resolves them
     * unambiguously.
     *
     * The notification is unconditional here. It used to be that nothing at all
     * was shown unless [Notifier.EXTRA_TOAST] was passed, which made six
     * different failures indistinguishable from a working run.
     */
    private fun emit(
        callback: Callback,
        wantsToast: Boolean,
        result: Result,
        facts: RunReport.ScanFacts? = null,
        matchedLabel: String? = null,
        nearMissSample: String? = null,
        adapterState: AdapterState? = null,
    ) {
        Notifier.outcome(context, wantsToast, result.name)
        RunNotification.fastPhase(context, result, facts, matchedLabel, nearMissSample, adapterState)
        callback.onResult(result)
    }

    /**
     * One scan-and-cycle attempt. Holds the mutable "have I already reported
     * a result" state so the timeout, the scan callback and a scan failure
     * cannot race into reporting twice.
     *
     * The Handler here is for the scan timeout only. v1 also used it to post
     * the re-enable; that is what made the launcher Activity hold input focus
     * for seconds, and it is gone.
     */
    private inner class Session(
        private val adapter: BluetoothAdapter,
        private val scanner: BluetoothLeScanner,
        private val wantsToast: Boolean,
        private val callback: Callback,
        private val token: Long,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var settled = false

        private fun current(): Boolean {
            if (settled) return false
            if (!ManualBluetoothInterlock.shared.isLegacyCurrent(token)) {
                teardown()
                settled = true
                Log.i(TAG, "legacy scan invalidated by a manual operation")
                // Terminal callback still releases goAsync()/stops the service, without a stale notification.
                callback.onResult(Result.ERROR)
                return false
            }
            return true
        }

        // --- what the scan actually observed, for the run-result notification.
        // All of it is cheap to collect while the scan is running and
        // impossible to reconstruct afterwards, which is the only reason it is
        // collected at all. devicesSeen is the number that matters: zero means
        // the system starved the scan, a healthy count means the scan worked
        // and the band simply was not advertising.
        private var windowMs = 0L
        private var startedAtMs = 0L
        private val seenAddresses = mutableSetOf<String>()
        private var namedSeen = 0
        private val nearMisses = mutableListOf<String>()
        private var scanFailedCode: Int? = null
        private var targetBonded = false
        private var matchedLabel: String? = null

        private val onTimeout = Runnable {
            if (!current()) return@Runnable
            Log.i(TAG, "scan window elapsed; band is not visible")
            teardown()
            emit(Result.NOT_FOUND)
        }

        private val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    handler.post { onScanResult(callbackType, result) }
                    return
                }
                if (!current()) return
                val mac = result.device?.address
                val name = advertisedName(result)
                Log.d(TAG, "seen $mac name=${name ?: "<none>"}")
                val isTarget = BandMatcher.isTarget(mac, name)
                record(mac, name, isTarget)
                if (!isTarget) return
                Log.i(TAG, "band is visible: $mac name=${name ?: "<none>"}")
                matchedLabel = if (name != null) "$name ($mac)" else (mac ?: "?")
                teardown()
                // Before disable(), so the message appears even when MIUI then
                // refuses the toggle. It is also the moment the user asked to
                // be told about.
                if (!current()) return
                Notifier.bandFound(context)
                cycleBluetooth()
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    handler.post { onBatchScanResults(results) }
                    return
                }
                if (!current()) return
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    handler.post { onScanFailed(errorCode) }
                    return
                }
                if (!current()) return
                Log.e(TAG, "BLE scan failed errorCode=$errorCode")
                if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) {
                    // Scan client registration failed, i.e. too many scanners
                    // system-wide. This is NOT the 5-starts-per-30s throttle:
                    // the throttle never reaches this callback. The 6th
                    // startScan in a 30s window is accepted and then silently
                    // starved of results, so it surfaces as NOT_FOUND with
                    // devicesSeen at zero, not as an error here.
                    Log.e(TAG, "scan client registration failed; too many concurrent scanners system-wide")
                }
                scanFailedCode = errorCode
                teardown()
                emit(Result.ERROR)
            }
        }

        fun start(scanTimeoutMs: Long) {
            if (!current()) return
            // Recorded before startScan so that even a throwing startScan still
            // reports a coherent set of facts rather than zeros.
            windowMs = scanTimeoutMs
            startedAtMs = SystemClock.elapsedRealtime()
            targetBonded = isTargetBonded()
            try {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                // No hardware ScanFilter on purpose: name filters are
                // unreliable when the name is truncated by advertising limits
                // or carried in the scan response instead.
                scanner.startScan(null, settings, scanCallback)
            } catch (t: Throwable) {
                Log.e(TAG, "startScan threw", t)
                emit(Result.ERROR)
                return
            }
            handler.postDelayed(onTimeout, scanTimeoutMs)
            Log.i(TAG, "BLE scan started, window=${scanTimeoutMs}ms")
        }

        /**
         * The advertised name, trying both sources. The scan record carries
         * what was actually broadcast; BluetoothDevice.getName() reads the
         * cache, needs BLUETOOTH_CONNECT, and can throw.
         */
        private fun advertisedName(result: ScanResult): String? {
            val fromRecord = try {
                result.scanRecord?.deviceName
            } catch (t: Throwable) {
                null
            }
            if (fromRecord != null) return fromRecord
            return try {
                result.device?.name
            } catch (t: Throwable) {
                null
            }
        }

        /**
         * Counts distinct devices and collects the ones that look band-shaped
         * but did not match. A near miss is the most actionable thing a failed
         * scan can report: it means the band was advertising and this build
         * simply does not recognise the name or address it is using.
         */
        private fun record(mac: String?, name: String?, isTarget: Boolean) {
            if (mac != null && seenAddresses.add(mac) && name != null) {
                namedSeen++
            }
            if (isTarget || nearMisses.size >= NEAR_MISS_SAMPLE_LIMIT) return
            if (looksLikeTarget(mac, name)) {
                nearMisses.add(if (name != null) "$name ($mac)" else (mac ?: "?"))
            }
        }

        /**
         * Deliberately looser than [BandMatcher.isTarget]. The point is to
         * notice a band that changed its advertised name or address, so this
         * casts wider and lets the notification say what it actually saw.
         */
        private fun looksLikeTarget(mac: String?, name: String?): Boolean {
            if (mac != null && mac.endsWith(BandMatcher.TARGET_MAC_SUFFIX, ignoreCase = true)) {
                return true
            }
            if (name == null) return false
            return NEAR_MISS_NAME_HINTS.any { name.contains(it, ignoreCase = true) }
        }

        /**
         * Whether the target is in the paired-device list. This is where a user
         * "sees" the band in system settings, and it is not the same thing as a
         * BLE advertisement: a band that is bonded and connected to Mi Fitness
         * has stopped advertising, so bonded plus not-seen is the expected
         * combination rather than a fault.
         */
        private fun isTargetBonded(): Boolean = try {
            @Suppress("MissingPermission")
            adapter.bondedDevices?.any { device ->
                val name = try {
                    device.name
                } catch (t: Throwable) {
                    null
                }
                BandMatcher.isTarget(device.address, name)
            } ?: false
        } catch (t: Throwable) {
            Log.w(TAG, "bondedDevices threw", t)
            false
        }

        private fun facts() = RunReport.ScanFacts(
            windowMs = windowMs,
            // elapsedRealtime, not currentTimeMillis: monotonic, so a clock
            // adjustment mid-scan cannot make this negative or absurd.
            elapsedMs = if (startedAtMs == 0L) 0L else SystemClock.elapsedRealtime() - startedAtMs,
            devicesSeen = seenAddresses.size,
            namedSeen = namedSeen,
            nearMisses = nearMisses.size,
            targetBonded = targetBonded,
            scanFailedCode = scanFailedCode,
        )

        /** Capped at [NEAR_MISS_SAMPLE_LIMIT] entries so the notification stays readable. */
        private fun nearMissSample(): String? =
            if (nearMisses.isEmpty()) null else nearMisses.joinToString("、")

        private fun teardown() {
            handler.removeCallbacks(onTimeout)
            try {
                scanner.stopScan(scanCallback)
            } catch (t: Throwable) {
                // Already-terminated scans throw here; harmless.
                Log.w(TAG, "stopScan threw", t)
            }
        }

        private fun cycleBluetooth() {
            if (!current()) return
            if (!BluetoothSwitch.disable(adapter)) {
                // Bluetooth never began turning off, so nothing must be armed.
                // Arming a chain here would eventually enable() a radio that
                // was already on.
                Log.e(TAG, "system refused to turn Bluetooth off; leaving it ON and exiting")
                emit(Result.DISABLE_FAILED)
                return
            }
            // disable() returning true means the transition has BEGUN. The
            // radio is almost certainly still in STATE_TURNING_OFF right now,
            // and calling enable() here is exactly what broke v1. Arm the
            // chain and let it wait for a real STATE_OFF.
            Log.i(TAG, "disable() accepted; arming the state-driven re-enable chain")
            if (!current()) return
            PendingResetStore.arm(context, wantsToast)
            ReEnableScheduler.armNextTick(context)
            emit(Result.RESET_TRIGGERED)
        }

        private fun emit(result: Result) {
            if (!current()) return
            settled = true
            // Delegates to the controller's emit so the toast, the notification
            // and the callback cannot drift apart. All this one adds is the
            // guard against reporting twice and the facts this session
            // collected, which the controller-level early returns do not have.
            emit(
                callback,
                wantsToast,
                result,
                facts = facts(),
                matchedLabel = matchedLabel,
                nearMissSample = nearMissSample(),
            )
        }
    }

    companion object {
        private const val TAG = "BtReset.Ctrl"

        /** Mirrors ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED. */
        private const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2

        /** How many near-miss devices to name in the notification. */
        private const val NEAR_MISS_SAMPLE_LIMIT = 3

        /**
         * Substrings suggesting a Xiaomi band, without requiring the exact
         * match [BandMatcher] demands. Used only to report near misses.
         */
        private val NEAR_MISS_NAME_HINTS = listOf("Xiaomi", "Band", "Mi Smart")
    }
}
