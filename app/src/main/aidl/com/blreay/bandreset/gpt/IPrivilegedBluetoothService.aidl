package com.blreay.bandreset.gpt;

interface IPrivilegedBluetoothService {
    String setBluetoothEnabled(boolean enabled) = 1;
    void destroy() = 16777114;
}