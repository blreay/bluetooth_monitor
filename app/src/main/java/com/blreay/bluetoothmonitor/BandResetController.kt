package com.blreay.bluetoothmonitor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
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
                emit(callback, wantsToast, Result.BLUETOOTH_OFF)
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
            Session(adapter, scanner, wantsToast, callback).start(scanTimeoutMs)
        } catch (t: Throwable) {
            Log.e(TAG, "controller failed before scanning", t)
            emit(callback, wantsToast, Result.ERROR)
        }
    }

    /**
     * The single exit for every early return, so the debug toast and the
     * callback cannot get out of step. [Session] has its own emit() because it
     * also has to guard against reporting twice; the two differ by arity, so
     * Kotlin resolves them unambiguously.
     */
    private fun emit(callback: Callback, wantsToast: Boolean, result: Result) {
        Notifier.outcome(context, wantsToast, result.name)
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
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var settled = false

        private val onTimeout = Runnable {
            if (settled) return@Runnable
            Log.i(TAG, "scan window elapsed; band is not visible")
            teardown()
            emit(Result.NOT_FOUND)
        }

        private val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (settled) return
                val mac = result.device?.address
                val name = advertisedName(result)
                Log.d(TAG, "seen $mac name=${name ?: "<none>"}")
                if (!BandMatcher.isTarget(mac, name)) return
                Log.i(TAG, "band is visible: $mac name=${name ?: "<none>"}")
                teardown()
                // Before disable(), so the message appears even when MIUI then
                // refuses the toggle. It is also the moment the user asked to
                // be told about.
                Notifier.bandFound(context)
                cycleBluetooth()
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (settled) return
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (settled) return
                Log.e(TAG, "BLE scan failed errorCode=$errorCode")
                if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) {
                    Log.e(TAG, "registration failed - Android allows only 5 scans per 30s; " +
                        "wait 30s and retry, this is throttling not a bug")
                }
                teardown()
                emit(Result.ERROR)
            }
        }

        fun start(scanTimeoutMs: Long) {
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
            PendingResetStore.arm(context, wantsToast)
            ReEnableScheduler.armNextTick(context)
            emit(Result.RESET_TRIGGERED)
        }

        private fun emit(result: Result) {
            if (settled) return
            settled = true
            Notifier.outcome(context, wantsToast, result.name)
            callback.onResult(result)
        }
    }

    companion object {
        private const val TAG = "BtReset.Ctrl"

        /** Mirrors ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED. */
        private const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2
    }
}
