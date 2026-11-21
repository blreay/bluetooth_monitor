package com.blreay.bandreset.gpt;

/** One-shot user feedback decisions, separate from platform Toast delivery. */
final class SessionFeedback {
    private boolean matched;
    private boolean finished;
    boolean match() {
        if (matched || finished) return false;
        matched = true;
        return true;
    }
    boolean terminal(String outcome, ResetController.BluetoothState snapshot) {
        if (finished) return false;
        finished = true;
        boolean restored = "RESET_COMPLETE".equals(outcome) || "RESTORED_EXTERNALLY".equals(outcome)
                || outcome.endsWith("_RESTORED");
        return matched && !restored && snapshot != ResetController.BluetoothState.ON;
    }
}
