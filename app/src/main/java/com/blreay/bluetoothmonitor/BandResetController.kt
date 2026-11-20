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
 * The entire point of the app: find the band, then cycle Bluetooth.
 *
 * Deliberately free of any UI dependency so [MainActivity] and
 * [TriggerReceiver] share one implementation and cannot drift apart. The
 * caller supplies the scan window: the Activity can afford 8s, the broadcast
 * receiver must fit inside goAsync()'s ~10s budget and uses 6s.
 *
 * Callback contract: [Callback.onResult] fires exactly once and means "the
 * decision phase is finished". For [Result.RESET_TRIGGERED] it does NOT mean
 * Bluetooth is back on yet - the caller must stay alive for
 * [ReEnableScheduler.IN_PROCESS_DELAY_MS] to let the in-process path run,
 * otherwise the process loses foreground priority and MIUI may reclaim it
 * mid-wait. [MainActivity] does this; [TriggerReceiver] cannot, and relies on
 * the alarm instead.
 */
// `context` must be an application context: the scan callback and the
// re-enable Runnable both outlive whichever caller supplied it.
class BandResetController(private val context: Context) {

    enum class Result {
        /** This device has no Bluetooth radio. */
        NO_ADAPTER,

        /** Bluetooth was already off. Per spec, do nothing and exit. */
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

        /** Band found, Bluetooth turned off, re-enable scheduled two ways. */
        RESET_TRIGGERED,

        /** Something unexpected threw. Details are in logcat. */
        ERROR,
    }

    fun interface Callback {
        fun onResult(result: Result)
    }

    /**
     * Starts the flow. Returns immediately; the outcome arrives on
     * [callback], on the main thread, exactly once.
     *
     * @param scanTimeoutMs how long to scan before reporting NOT_FOUND. The
     *        Activity can afford 8s; the broadcast receiver must fit inside
     *        goAsync()'s ~10s budget and uses 6s.
     */
    fun run(scanTimeoutMs: Long, callback: Callback) {
        try {
            val adapter = BluetoothSwitch.adapter(context)
            if (adapter == null) {
                Log.e(TAG, "no BluetoothAdapter; this device has no Bluetooth radio")
                callback.onResult(Result.NO_ADAPTER)
                return
            }
            if (!adapter.isEnabled) {
                Log.i(TAG, "Bluetooth is off; exiting without doing anything")
                callback.onResult(Result.BLUETOOTH_OFF)
                return
            }
            if (!BtPermissions.allGranted(context)) {
                Log.w(TAG, "missing permission(s) ${BtPermissions.missing(context)}; cannot scan")
                callback.onResult(Result.NEEDS_PERMISSION)
                return
            }
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                Log.e(TAG, "BluetoothLeScanner is null even though Bluetooth reports enabled")
                callback.onResult(Result.ERROR)
                return
            }
            Session(adapter, scanner, callback).start(scanTimeoutMs)
        } catch (t: Throwable) {
            Log.e(TAG, "controller failed before scanning", t)
            callback.onResult(Result.ERROR)
        }
    }

    /**
     * One scan-and-cycle attempt. Holds the mutable "have I already reported
     * a result" state so the timeout, the scan callback and a scan failure
     * cannot race into reporting twice.
     */
    private inner class Session(
        private val adapter: BluetoothAdapter,
        private val scanner: BluetoothLeScanner,
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
                // Bluetooth never went off, so nothing must be scheduled.
                // Scheduling a re-enable here would be a no-op at best and
                // would mask the real failure at worst.
                Log.e(TAG, "system refused to turn Bluetooth off; leaving it ON and exiting")
                emit(Result.DISABLE_FAILED)
                return
            }
            Log.i(TAG, "Bluetooth is now off; re-enable scheduled two ways")
            ReEnableScheduler.schedule(context, handler)
            emit(Result.RESET_TRIGGERED)
        }

        private fun emit(result: Result) {
            if (settled) return
            settled = true
            callback.onResult(result)
        }
    }

    companion object {
        private const val TAG = "BtReset.Ctrl"

        /** Mirrors ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED. */
        private const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2
    }
}
