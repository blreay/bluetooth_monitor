package com.blreay.bandreset.gpt.shizuku;

import android.os.Process;
import android.util.Log;

import com.blreay.bandreset.gpt.IPrivilegedBluetoothService;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shizuku UserService — Shizuku constructs this zero-argument Binder <em>inside</em>
 * its own privileged (shell/root) process.  Any command it executes inherits that
 * privilege, which is what lets {@code svc bluetooth enable/disable} succeed
 * without popping up the OEM confirmation dialog.
 */
public final class PrivilegedBluetoothService extends IPrivilegedBluetoothService.Stub {
    private static final String TAG = "BandResetGPT";
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final BluetoothCommandRunner runner = new BluetoothCommandRunner();

    @Override
    public String setBluetoothEnabled(boolean enabled) {
        if (!running.compareAndSet(false, true)) {
            return new BluetoothCommandResult(BluetoothCommandResult.Status.ERROR,
                    "另一条蓝牙命令仍在执行").encode();
        }
        try {
            int uid = Process.myUid();
            if (uid != 0 && uid != 2000) {
                return new BluetoothCommandResult(BluetoothCommandResult.Status.ERROR,
                        "拒绝执行：服务 UID=" + uid + "，要求 root 或 shell").encode();
            }
            List<String> arguments = Arrays.asList(
                    "/system/bin/svc", "bluetooth", enabled ? "enable" : "disable");
            return runner.run(arguments, 5000L).encode();
        } catch (Throwable t) {
            return new BluetoothCommandResult(BluetoothCommandResult.Status.ERROR,
                    "执行失败：" + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName())).encode();
        } finally {
            running.set(false);
        }
    }

    @Override
    public void destroy() {
        runner.destroyChild();
        int uid = Process.myUid();
        if (uid == 0 || uid == 2000) {
            Process.killProcess(Process.myPid());
        }
    }
}