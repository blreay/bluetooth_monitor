package com.blreay.bluetoothmonitor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log

/** Ordinary fallback is concentrated here. Only the asynchronous central driver calls ordinary(). */
object BluetoothSwitch {
    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun state(context: Context): AdapterState = try {
        when (adapter(context)?.state) {
            BluetoothAdapter.STATE_OFF -> AdapterState.OFF
            BluetoothAdapter.STATE_TURNING_ON -> AdapterState.TURNING_ON
            BluetoothAdapter.STATE_ON -> AdapterState.ON
            BluetoothAdapter.STATE_TURNING_OFF -> AdapterState.TURNING_OFF
            else -> AdapterState.UNKNOWN
        }
    } catch (e: Exception) {
        Log.e("BtReset.Switch", "state unavailable", e)
        AdapterState.UNKNOWN
    }

    fun ordinary(context: Context, enabled: Boolean, atNativeEntry: () -> Boolean = { true }): SwitchOutcome {
        if (!BtPermissions.allGranted(context)) return SwitchOutcome.notInvoked("缺少附近设备 SCAN/CONNECT 权限；未调用普通开关")
        val adapter = adapter(context) ?: return SwitchOutcome.notInvoked("没有蓝牙适配器")
        return try {
            // Adapter retrieval may have blocked. Recheck the actual caller lease immediately before the API.
            if (!atNativeEntry()) return SwitchOutcome.notInvoked("普通开关进入前执行窗口已失效；未提交")
            @Suppress("DEPRECATION")
            val accepted = if (enabled) adapter.enable() else adapter.disable()
            if (accepted) SwitchOutcome.submitted(SwitchMode.ORDINARY, "普通方式已接受请求；等待实际状态，系统可能要求必要确认")
            else SwitchOutcome.rejected(SwitchMode.ORDINARY, "普通方式被系统/OEM 拒绝；尚未确认目标状态")
        } catch (e: SecurityException) {
            SwitchOutcome.rejected(SwitchMode.ORDINARY, "普通方式权限/安全限制：${e.message}")
        } catch (e: Exception) {
            SwitchOutcome.uncertain(SwitchMode.ORDINARY, "普通方式调用异常；不能证明未提交：${e.message}")
        }
    }
}
