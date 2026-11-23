package com.blreay.bandreset.gpt.shizuku;

/** Describes which mechanism was actually used for the last Bluetooth toggle. */
public enum BluetoothControlMode {
    /** Shizuku privileged shell executed the command. */
    SHIZUKU,
    /** Normal BluetoothAdapter API was used (may show OEM confirmation). */
    NORMAL,
    /** Not yet evaluated or unknown. */
    UNKNOWN
}