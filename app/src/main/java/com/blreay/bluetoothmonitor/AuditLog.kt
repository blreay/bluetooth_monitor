package com.blreay.bluetoothmonitor

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Only three owner-supplied, observational conclusions; no device data or generic detail events. */
sealed interface CompactLogEntry {
    data object ProcessStarted : CompactLogEntry
    data class ScanEnded(val count: Int?, val overflow: Boolean, val targetSeen: Boolean?, val result: String) : CompactLogEntry
    data class BluetoothEnded(val enabled: Boolean, val mode: SwitchMode?, val result: String) : CompactLogEntry
}

fun interface AuditSink {
    fun record(entry: CompactLogEntry)
    fun event(entry: CompactLogEntry) { try { record(entry) } catch (_: Exception) { } }
    companion object { val NONE = AuditSink { } }
}

fun interface AuditClock { fun wallMillis(): Long }

/** Captures the complete immutable line before enqueue. Disk write time is never the event time. */
class AuditLog(private val clock: AuditClock, private val zone: ZoneId = ZoneId.systemDefault()) {
    companion object {
        const val MAX_LINE_BYTES = 8192 // compatibility budget for existing private/root rows
        const val MAX_NEW_LINE_BYTES = 512
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
    fun capture(entry: CompactLogEntry): String {
        val timestamp = TIME.format(Instant.ofEpochMilli(clock.wallMillis()).atZone(zone))
        val text = when (entry) {
            CompactLogEntry.ProcessStarted -> "进程启动"
            is CompactLogEntry.ScanEnded -> scanText(entry.count, entry.overflow, entry.targetSeen) +
                if (entry.result == "成功") "" else "，${bounded(entry.result, 160)}"
            is CompactLogEntry.BluetoothEnded -> "${if (entry.enabled) "打开" else "关闭"}蓝牙：${route(entry.mode)}，${bounded(entry.result, 160)}"
        }
        return "$timestamp $text\n"
    }
    private fun route(mode: SwitchMode?) = when (mode) {
        null -> "方式未知"
        SwitchMode.NONE -> "未调用"
        SwitchMode.SHIZUKU_SHELL, SwitchMode.SHIZUKU_ROOT -> "Shizuku"
        SwitchMode.ORDINARY -> "普通"
    }
    private fun scanText(count: Int?, overflow: Boolean, target: Boolean?) =
        "扫描：${if (count == null) "设备数未知" else "${if (overflow) "100+" else count.coerceAtLeast(0).toString()}个设备"}，目标手环：${when (target) { true -> "有"; false -> "无"; null -> "未知" }}"

    /** Constant bounded prefix work, before concatenation or encoding; never split a surrogate pair. */
    private fun bounded(input: String, budget: Int): String {
        val result = StringBuilder(minOf(input.length, budget))
        var index = 0
        var used = 0
        while (index < input.length && used < budget) {
            val char = input[index]
            val pair = Character.isHighSurrogate(char) && index + 1 < input.length && Character.isLowSurrogate(input[index + 1])
            var code = if (Character.isSurrogate(char) && !pair) '?'.code else Character.codePointAt(input, index)
            if (Character.isISOControl(code) || code == 0x2028 || code == 0x2029) code = ' '.code
            val cost = when { code < 128 -> 1; code < 2048 -> 2; code < 65536 -> 3; else -> 4 }
            if (used + cost > budget) break
            result.appendCodePoint(code)
            used += cost
            index += if (pair) 2 else 1
        }
        return result.toString()
    }
}
