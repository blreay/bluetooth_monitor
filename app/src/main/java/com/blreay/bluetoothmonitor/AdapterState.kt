package com.blreay.bluetoothmonitor

/**
 * The Bluetooth adapter's state, without the framework's int encoding.
 *
 * This enum exists so [ReEnablePolicy] can decide what to do next without
 * importing anything from android.* - which is what makes the slow-phase
 * logic unit-testable on a plain JVM. v1 had no tests at all for its
 * re-enable path, and that is precisely the part that was broken.
 *
 * The int -> enum mapping deliberately lives in [BluetoothSwitch.state], next
 * to the real BluetoothAdapter.STATE_* constants, rather than here. Copying
 * 10/11/12/13 into this file would mean a test could only ever verify that
 * the copy agrees with itself, and transposing TURNING_ON with TURNING_OFF
 * would reproduce the exact bug v2 exists to fix.
 *
 * UNKNOWN covers both "no BluetoothAdapter on this device" and "getState()
 * returned something outside the four documented values or threw".
 */
enum class AdapterState {
    OFF,
    TURNING_ON,
    ON,
    TURNING_OFF,
    UNKNOWN,
}
