package com.blreay.bluetoothmonitor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log

/**
 * The only place in the app that touches the deprecated
 * [BluetoothAdapter.enable] and [BluetoothAdapter.disable], and the only
 * place that maps the framework's int state onto [AdapterState].
 *
 * Android 13 closed enable()/disable() to third-party apps targeting API
 * 33+; this app targets 32 specifically to keep them. The framework javadoc
 * lists the only exemptions as Device Owner, Profile Owner and system apps -
 * there is no permission a third-party app can request. They can still return
 * false when the OEM refuses, so every caller must check the result rather
 * than assume the radio actually changed state.
 *
 * Both calls are asynchronous: a true return means the transition has
 * *begun*, not that it finished. Callers must therefore never infer "it is
 * off now" from disable() returning true - they must observe STATE_OFF via
 * [state]. That inference is exactly what broke v1.
 */
object BluetoothSwitch {

    private const val TAG = "BtReset.Switch"

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * The adapter's state as our own enum, so [ReEnablePolicy] never has to
     * import android.bluetooth.
     *
     * UNKNOWN means "we cannot tell", covering a missing adapter, a
     * getState() that threw, and any value outside the four documented ones.
     * The policy gives up immediately on UNKNOWN, which is correct: no tick
     * can make progress without a readable state.
     */
    fun state(context: Context): AdapterState {
        val adapter = adapter(context) ?: return AdapterState.UNKNOWN
        return try {
            when (adapter.state) {
                BluetoothAdapter.STATE_OFF -> AdapterState.OFF
                BluetoothAdapter.STATE_TURNING_ON -> AdapterState.TURNING_ON
                BluetoothAdapter.STATE_ON -> AdapterState.ON
                BluetoothAdapter.STATE_TURNING_OFF -> AdapterState.TURNING_OFF
                else -> AdapterState.UNKNOWN
            }
        } catch (t: Throwable) {
            // SecurityException is possible if BLUETOOTH_CONNECT is revoked
            // mid-flight. "Cannot tell" is a safer answer than crashing the
            // alarm chain.
            Log.e(TAG, "getState() threw", t)
            AdapterState.UNKNOWN
        }
    }

    /**
     * Asks the system to turn Bluetooth on. Callers must not decide when to
     * call this from a timer or from `!isEnabled` - [ReEnablePolicy] decides,
     * and it only ever says yes at exactly STATE_OFF.
     *
     * There is deliberately no BLUETOOTH_CONNECT check here. The fast phase
     * already verified the grant before arming the chain. If it is revoked
     * mid-flight the call throws, is caught below, and the chain retries
     * until the cap - bounded, and visible in the log as a run of
     * "enable() threw". Checking here instead would only turn that into a
     * silent no-op, which is how v1 lost the radio without a trace.
     *
     * @return what the framework said. A false return does NOT end the chain:
     *         the next tick re-reads the state and tries again, up to
     *         [ReEnablePolicy.MAX_ATTEMPTS]. That retry loop is the whole
     *         reason v2 works where v1 did not.
     */
    fun enable(adapter: BluetoothAdapter): Boolean {
        @Suppress("DEPRECATION")
        val ok = try {
            adapter.enable()
        } catch (t: Throwable) {
            Log.e(TAG, "enable() threw", t)
            false
        }
        if (ok) {
            Log.i(TAG, "enable() accepted; the next tick confirms STATE_ON")
        } else {
            Log.e(TAG, "enable() returned false; the next tick will retry")
        }
        return ok
    }

    /**
     * @return true only if the system accepted the request. A false result
     *         means Bluetooth is still ON, so callers must not schedule a
     *         re-enable - that is the invariant which keeps this app from
     *         ever leaving the radio in the wrong state.
     */
    fun disable(adapter: BluetoothAdapter): Boolean {
        @Suppress("DEPRECATION")
        return try {
            adapter.disable()
        } catch (t: Throwable) {
            Log.e(TAG, "disable() threw", t)
            false
        }
    }
}
