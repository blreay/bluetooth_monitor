package com.blreay.bluetoothmonitor

import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/** Shizuku constructs this zero-argument Binder in its dedicated shell/root process. */
class PrivilegedBluetoothService : IPrivilegedBluetoothService.Stub() {
    private val running = AtomicBoolean(false)
    private val runner = BluetoothCommandRunner()

    override fun setBluetoothEnabled(enabled: Boolean): String {
        if (!running.compareAndSet(false, true)) {
            return BluetoothCommandResult(BluetoothCommandResult.Status.NOT_INVOKED, "另一条蓝牙命令仍在执行；本条未调用，但旧执行权限未结束，不能重放/反向开关", false).encode()
        }
        return try {
            PrivilegedBluetoothCommand.execute(Process.myUid(), enabled) { arguments ->
                runner.run(arguments)
            }.encode()
        } catch (t: Throwable) {
            BluetoothCommandResult(BluetoothCommandResult.Status.UNCERTAIN, "执行失败，可能已调用：${t.message ?: t.javaClass.simpleName}", false).encode()
        } finally {
            running.set(false)
        }
    }

    override fun destroy() {
        // Never acquire the command's lock: removal must work even when svc is stuck.
        runner.destroyChild()
        if (Process.myUid() == 0 || Process.myUid() == 2000) {
            Process.killProcess(Process.myPid())
        }
    }
}
