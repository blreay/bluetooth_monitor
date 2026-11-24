package com.blreay.bluetoothmonitor

/** No caller-controlled executable or arguments cross the privilege boundary. */
object PrivilegedBluetoothCommand {
    fun execute(uid: Int, enabled: Boolean, run: (List<String>) -> BluetoothCommandResult): BluetoothCommandResult {
        if (uid != 0 && uid != 2000) {
            return BluetoothCommandResult(BluetoothCommandResult.Status.ERROR, "拒绝执行：服务 UID=$uid，要求 root 或 shell")
        }
        return run(listOf("/system/bin/svc", "bluetooth", if (enabled) "enable" else "disable"))
    }
}

data class BluetoothCommandResult(val status: Status, val output: String) {
    enum class Status { SUBMITTED, ERROR, TIMEOUT }
    fun encode(): String = status.name + "\n" + output.take(OUTPUT_LIMIT)

    companion object {
        const val OUTPUT_LIMIT = 4096
        fun fromExit(exit: Int, output: String): BluetoothCommandResult = BluetoothCommandResult(
            if (exit == 0) Status.SUBMITTED else Status.ERROR,
            if (exit == 0) output.take(OUTPUT_LIMIT) else "svc 退出码 $exit：${output.take(OUTPUT_LIMIT - 64)}",
        )

        fun decode(wire: String): BluetoothCommandResult {
            val delimiter = wire.indexOf('\n')
            if (delimiter < 0 || delimiter > 16) return BluetoothCommandResult(Status.ERROR, "无法识别服务返回值")
            val status = try { Status.valueOf(wire.substring(0, delimiter)) } catch (_: IllegalArgumentException) {
                return BluetoothCommandResult(Status.ERROR, "无法识别服务返回值")
            }
            return BluetoothCommandResult(status, wire.substring(delimiter + 1).take(OUTPUT_LIMIT))
        }
    }
}
