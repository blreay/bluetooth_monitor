package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class PrivilegedBluetoothCommandTest {
    @Test fun `only fixed svc arguments can be executed for enable and disable`() {
        val calls = mutableListOf<List<String>>()
        for (enabled in listOf(true, false)) {
            val result = PrivilegedBluetoothCommand.execute(2000, enabled) {
                calls.add(it)
                BluetoothCommandResult(BluetoothCommandResult.Status.SUBMITTED, "")
            }
            assertEquals(BluetoothCommandResult.Status.SUBMITTED, result.status)
        }
        assertEquals(listOf(
            listOf("/system/bin/svc", "bluetooth", "enable"),
            listOf("/system/bin/svc", "bluetooth", "disable"),
        ), calls)
    }

    @Test fun `ordinary app UID never executes a command`() {
        var called = false
        val result = PrivilegedBluetoothCommand.execute(10123, false) {
            called = true
            BluetoothCommandResult(BluetoothCommandResult.Status.SUBMITTED, "")
        }
        assertFalse(called)
        assertEquals(BluetoothCommandResult.Status.ERROR, result.status)
    }

    @Test fun `root UID is permitted`() {
        var called = false
        PrivilegedBluetoothCommand.execute(0, true) {
            called = true
            BluetoothCommandResult(BluetoothCommandResult.Status.SUBMITTED, "")
        }
        assertTrue(called)
    }

    @Test fun `exit zero only means submitted not confirmed state`() {
        assertEquals(BluetoothCommandResult.Status.SUBMITTED, BluetoothCommandResult.fromExit(0, "").status)
        assertEquals(BluetoothCommandResult.Status.ERROR, BluetoothCommandResult.fromExit(1, "OEM denied").status)
        assertTrue(BluetoothCommandResult.fromExit(1, "OEM denied").output.contains("OEM denied"))
    }

    @Test fun `wire result is bounded and preserves error output`() {
        val result = BluetoothCommandResult(BluetoothCommandResult.Status.ERROR, "拒绝\n" + "x".repeat(10000))
        val wire = result.encode()
        assertTrue(wire.length <= BluetoothCommandResult.OUTPUT_LIMIT + 32)
        val decoded = BluetoothCommandResult.decode(wire)
        assertEquals(result.status, decoded.status)
        assertTrue(decoded.output.startsWith("拒绝\n"))
    }

    @Test fun `malformed result is an error never successful`() {
        assertEquals(BluetoothCommandResult.Status.ERROR, BluetoothCommandResult.decode("garbage").status)
    }
}
