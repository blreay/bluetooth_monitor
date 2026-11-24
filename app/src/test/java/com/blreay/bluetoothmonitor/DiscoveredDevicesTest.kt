package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class DiscoveredDevicesTest {
    @Test fun `MAC dedup merges transport types and latest RSSI`() {
        val devices = DiscoveredDevices()
        devices.add("aa:bb:cc:dd:ee:ff", "耳机", -80, "BLE")
        devices.add(" AA:BB:CC:DD:EE:FF ", null, -42, "经典")
        assertEquals(1, devices.snapshot().size)
        val row = devices.snapshot().single()
        assertEquals("AA:BB:CC:DD:EE:FF", row.mac)
        assertEquals("耳机", row.name)
        assertEquals(-42, row.rssi)
        assertEquals(setOf("BLE", "经典"), row.types)
    }

    @Test fun `blank names do not erase a discovered name`() {
        val devices = DiscoveredDevices()
        devices.add("AA", "手环", -20, "BLE")
        devices.add("AA", "  ", null, "BLE")
        assertEquals(1, devices.snapshot().size)
        assertEquals("手环", devices.snapshot().single().name)
    }

    @Test fun `later actual name replaces unknown while order remains stable`() {
        val devices = DiscoveredDevices()
        devices.add("AA", null, -90, "BLE")
        devices.add("BB", "第二台", -30, "经典")
        assertEquals(2, devices.snapshot().size)
        assertEquals("未知设备", devices.snapshot().first().name)
        devices.add("aa", "第一台", -60, "BLE")
        assertEquals(listOf("AA", "BB"), devices.snapshot().map { it.mac })
        assertEquals("第一台", devices.snapshot().first().name)
    }

    @Test fun `the target is just a discovered device never a reset action`() {
        val devices = DiscoveredDevices()
        devices.add("11:22:33:44:5D:7A", "Xiaomi Smart Band 9", -40, "BLE")
        assertEquals(1, devices.snapshot().size)
        assertEquals("Xiaomi Smart Band 9", devices.snapshot().single().name)
    }

    @Test fun `an observation without RSSI retains the latest actual measurement`() {
        val devices = DiscoveredDevices()
        devices.add("AA", "耳机", -55, "BLE")
        devices.add("AA", null, null, "经典")
        assertEquals(-55, devices.snapshot().single().rssi)
    }

    @Test fun `clear removes discoveries without adding paired cache`() {
        val devices = DiscoveredDevices()
        devices.add("AA", null, -20, "BLE")
        devices.clear()
        assertTrue(devices.snapshot().isEmpty())
    }
}
