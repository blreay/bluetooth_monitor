package com.blreay.bluetoothmonitor

/** No caller-controlled executable or arguments cross the privilege boundary. */
object PrivilegedBluetoothCommand {
    fun execute(uid: Int, enabled: Boolean, run: (List<String>) -> BluetoothCommandResult): BluetoothCommandResult {
        if (uid != 0 && uid != 2000) {
            return BluetoothCommandResult(BluetoothCommandResult.Status.NOT_INVOKED, "拒绝执行：服务 UID=$uid，要求 root 或 shell", true)
        }
        return run(listOf("/system/bin/svc", "bluetooth", if (enabled) "enable" else "disable"))
    }
}

data class BluetoothCommandResult(
    val status: Status,
    val output: String,
    val authorityEnded: Boolean = status == Status.SUBMITTED || status == Status.NOT_INVOKED,
) {
    enum class Status { SUBMITTED, NOT_INVOKED, UNCERTAIN, ERROR, TIMEOUT }
    fun encode(): String = status.name + ":" + (if (authorityEnded) "1" else "0") + "\n" + output.take(OUTPUT_LIMIT)

    companion object {
        const val OUTPUT_LIMIT = 4096
        fun fromExit(exit: Int, output: String): BluetoothCommandResult = BluetoothCommandResult(
            if (exit == 0) Status.SUBMITTED else Status.ERROR,
            if (exit == 0) output.take(OUTPUT_LIMIT) else "svc 退出码 $exit：${output.take(OUTPUT_LIMIT - 64)}",
            true, // Child exited; submission is still NOT hardware confirmation.
        )

        fun decode(wire: String): BluetoothCommandResult {
            fun unknown() = BluetoothCommandResult(Status.UNCERTAIN, "无法识别服务返回值；可能已调用，状态未确认", false)
            val delimiter = wire.indexOf('\n')
            if (delimiter < 0 || delimiter > 16) return unknown()
            val header = wire.substring(0, delimiter).split(':')
            val status = try { Status.valueOf(header[0]) } catch (_: IllegalArgumentException) { return unknown() }
            if (header.size > 2 || (header.size == 2 && header[1] != "0" && header[1] != "1")) return unknown()
            // Old protocol errors were ambiguous. Never reinterpret them as definitely uninvoked.
            val ended = if (header.size == 2) header[1] == "1" else status == Status.SUBMITTED || status == Status.NOT_INVOKED
            return BluetoothCommandResult(status, wire.substring(delimiter + 1).take(OUTPUT_LIMIT), ended)
        }
    }
}
