package com.blreay.bluetoothmonitor

import java.util.Locale

/** Only observations belong here, never the paired cache or target/reset policy. */
class DiscoveredDevices {
    data class Device(val mac: String, val name: String, val rssi: Int?, val types: Set<String>)
    private val devices = linkedMapOf<String, Device>()

    fun add(mac: String, name: String?, rssi: Int?, type: String) {
        val address = mac.trim().uppercase(Locale.ROOT)
        if (address.isEmpty()) return
        val old = devices[address]
        val actualName = name?.trim()?.takeIf { it.isNotEmpty() }
        devices[address] = Device(
            address,
            actualName ?: old?.name ?: "未知设备",
            rssi ?: old?.rssi,
            (old?.types ?: emptySet()) + type,
        )
    }

    fun snapshot(): List<Device> = devices.values.toList()
    fun clear() { devices.clear() }
}
