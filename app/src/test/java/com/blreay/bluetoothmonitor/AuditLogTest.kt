package com.blreay.bluetoothmonitor

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

internal class LogTestClock(var wall: Long = 1_700_000_000_123L) : AuditClock {
    override fun wallMillis() = wall
}

class AuditLogTest {
    private val clock = LogTestClock()
    private fun logger() = AuditLog(clock, ZoneId.of("Asia/Shanghai"))
    @Test fun `process line is plain text with local seconds only`() {
        assertEquals("2023-11-15 06:13:20 进程启动\n", logger().capture(CompactLogEntry.ProcessStarted))
    }
    @Test fun `captured timestamp is not replaced by a later drain`() {
        val row = logger().capture(CompactLogEntry.ProcessStarted)
        clock.wall += 60_000
        assertEquals("2023-11-15 06:13:20 进程启动\n", row)
    }
    @Test fun `clock is wall only and zone is explicit`() {
        assertEquals("2023-11-14 22:13:20 进程启动\n", AuditLog(clock, ZoneId.of("UTC")).capture(CompactLogEntry.ProcessStarted))
        clock.wall = 0
        assertEquals("1970-01-01 08:00:00 进程启动\n", logger().capture(CompactLogEntry.ProcessStarted))
    }
    @Test fun `normal scan has only count and target presence`() {
        assertEquals("2023-11-15 06:13:20 扫描：5个设备，目标手环：有\n",
            logger().capture(CompactLogEntry.ScanEnded(5, false, true, "成功")))
    }
    @Test fun `scan absence is explicit`() {
        assertEquals("2023-11-15 06:13:20 扫描：0个设备，目标手环：无\n",
            logger().capture(CompactLogEntry.ScanEnded(0, false, false, "成功")))
    }
    @Test fun `scan cap displays 100 plus`() {
        assertEquals("2023-11-15 06:13:20 扫描：100+个设备，目标手环：有\n",
            logger().capture(CompactLogEntry.ScanEnded(100, true, true, "成功")))
    }
    @Test fun `unexecuted scan never invents zero or target absence`() {
        assertEquals("2023-11-15 06:13:20 扫描：设备数未知，目标手环：未知，未执行\n",
            logger().capture(CompactLogEntry.ScanEnded(null, false, null, "未执行")))
    }
    @Test fun `failed partial scan keeps admitted count and target`() {
        assertEquals("2023-11-15 06:13:20 扫描：3个设备，目标手环：有，失败（部分结果）\n",
            logger().capture(CompactLogEntry.ScanEnded(3, false, true, "失败（部分结果）")))
    }
    @Test fun `off and on use actual ordinary route`() {
        assertEquals("2023-11-15 06:13:20 关闭蓝牙：普通，成功\n",
            logger().capture(CompactLogEntry.BluetoothEnded(false, SwitchMode.ORDINARY, "成功")))
        assertEquals("2023-11-15 06:13:20 打开蓝牙：普通，失败\n",
            logger().capture(CompactLogEntry.BluetoothEnded(true, SwitchMode.ORDINARY, "失败")))
    }
    @Test fun `root and shell both display Shizuku only`() {
        for (mode in listOf(SwitchMode.SHIZUKU_ROOT, SwitchMode.SHIZUKU_SHELL)) {
            assertEquals("2023-11-15 06:13:20 关闭蓝牙：Shizuku，成功\n",
                logger().capture(CompactLogEntry.BluetoothEnded(false, mode, "成功")))
        }
    }
    @Test fun `known none is different from unknown route`() {
        assertEquals("2023-11-15 06:13:20 打开蓝牙：未调用，未执行\n",
            logger().capture(CompactLogEntry.BluetoothEnded(true, SwitchMode.NONE, "未执行")))
        assertEquals("2023-11-15 06:13:20 打开蓝牙：方式未知，状态未确认\n",
            logger().capture(CompactLogEntry.BluetoothEnded(true, null, "状态未确认")))
    }
    @Test fun `no process identities event IDs JSON or extra event types exist`() {
        val lines = listOf(CompactLogEntry.ProcessStarted, CompactLogEntry.ScanEnded(1, false, false, "成功"),
            CompactLogEntry.BluetoothEnded(true, SwitchMode.NONE, "未执行")).map { logger().capture(it) }
        for (line in lines) for (forbidden in listOf("{", "pid", "session", "sequence", "eventId", "elapsed", "received", "process.start"))
            assertFalse(line.contains(forbidden))
        assertEquals(3, CompactLogEntry::class.java.declaredClasses.count { CompactLogEntry::class.java.isAssignableFrom(it) })
    }
    @Test fun `control characters cannot forge another record`() {
        val line = logger().capture(CompactLogEntry.BluetoothEnded(true, null, "中文😀\n\r\t\u0000\u0001\u007f\u0085\u009b  end"))
        assertEquals(1, line.count { it == '\n' })
        assertFalse(line.dropLast(1).any { Character.isISOControl(it) || it.code == 0x2028 || it.code == 0x2029 })
        assertTrue(line.contains("中文😀"))
    }
    @Test fun `hostile fresh strings are prefix bounded before encoding with valid Unicode`() {
        val input = String(CharArray(2_000_000) { '中' })
        val line = logger().capture(CompactLogEntry.ScanEnded(100, true, true, input))
        assertTrue(line.toByteArray(Charsets.UTF_8).size <= AuditLog.MAX_NEW_LINE_BYTES)
        assertEquals(512, AuditLog.MAX_NEW_LINE_BYTES)
        assertEquals(8192, AuditLog.MAX_LINE_BYTES) // old JSON rows remain readable
        assertEquals(line, line.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertFalse(line.contains('�'))
    }
    @Test fun `emoji is not split at byte budget`() {
        val line = logger().capture(CompactLogEntry.BluetoothEnded(true, null, "😀".repeat(100_000)))
        assertTrue(line.toByteArray(Charsets.UTF_8).size <= 512)
        assertEquals(line, line.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertFalse(line.contains('�'))
    }
    @Test fun `unpaired surrogates are safely replaced`() {
        val line = logger().capture(CompactLogEntry.BluetoothEnded(true, null, "x\uD800z\uDC00"))
        assertFalse(line.any { Character.isSurrogate(it) })
        assertEquals(line, line.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }
    @Test fun `event isolation catches failing sink and NONE is harmless`() {
        var invoked = false
        val sink = AuditSink { invoked = true; throw IllegalStateException("secret failure") }
        sink.event(CompactLogEntry.ProcessStarted)
        assertTrue(invoked)
        AuditSink.NONE.event(CompactLogEntry.ProcessStarted)
    }
}
