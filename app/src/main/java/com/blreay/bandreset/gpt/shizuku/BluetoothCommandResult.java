package com.blreay.bandreset.gpt.shizuku;

/**
 * Immutable result from a privileged Bluetooth command executed inside
 * Shizuku's process.  Serialised over AIDL as {@code STATUS\noutput}.
 */
final class BluetoothCommandResult {
    enum Status { SUBMITTED, ERROR, TIMEOUT }

    static final int OUTPUT_LIMIT = 4096;

    final Status status;
    final String output;

    BluetoothCommandResult(Status status, String output) {
        this.status = status;
        this.output = output;
    }

    /** Encode for the AIDL wire: {@code STATUS\noutput}. */
    String encode() {
        return status.name() + "\n" + truncate(output, OUTPUT_LIMIT);
    }

    /** Decode from the AIDL wire.  Never throws — returns ERROR on any parse failure. */
    static BluetoothCommandResult decode(String wire) {
        int delimiter = wire.indexOf('\n');
        if (delimiter < 0 || delimiter > 16) {
            return new BluetoothCommandResult(Status.ERROR, "无法识别服务返回值");
        }
        Status status;
        try {
            status = Status.valueOf(wire.substring(0, delimiter));
        } catch (IllegalArgumentException e) {
            return new BluetoothCommandResult(Status.ERROR, "无法识别服务返回值");
        }
        return new BluetoothCommandResult(status, wire.substring(delimiter + 1));
    }

    static BluetoothCommandResult fromExit(int exit, String output) {
        return exit == 0
                ? new BluetoothCommandResult(Status.SUBMITTED, truncate(output, OUTPUT_LIMIT))
                : new BluetoothCommandResult(Status.ERROR,
                        "svc 退出码 " + exit + "：" + truncate(output, OUTPUT_LIMIT - 64));
    }

    private static String truncate(String s, int limit) {
        return s.length() <= limit ? s : s.substring(0, limit);
    }
}