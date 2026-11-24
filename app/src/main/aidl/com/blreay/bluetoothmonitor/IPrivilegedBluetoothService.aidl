package com.blreay.bluetoothmonitor;

interface IPrivilegedBluetoothService {
    // Bounded status + output, never a caller-controlled command.
    String setBluetoothEnabled(boolean enabled) = 1;
    void destroy() = 16777114;
}
