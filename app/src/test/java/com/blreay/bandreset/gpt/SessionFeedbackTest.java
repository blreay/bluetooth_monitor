package com.blreay.bandreset.gpt;

import org.junit.Test;
import static org.junit.Assert.*;

public class SessionFeedbackTest {
    @Test public void matchIsOneShot() {
        SessionFeedback feedback = new SessionFeedback();
        assertTrue(feedback.match()); assertFalse(feedback.match());
    }
    @Test public void noMatchAndInitialOffNeverWarn() {
        assertFalse(new SessionFeedback().terminal("TARGET_NOT_FOUND", ResetController.BluetoothState.OFF));
        assertFalse(new SessionFeedback().terminal("INITIAL_BLUETOOTH_OFF", ResetController.BluetoothState.OFF));
    }
    @Test public void failureAfterMatchWarnsOnce() {
        SessionFeedback feedback = new SessionFeedback(); feedback.match();
        assertTrue(feedback.terminal("ENABLE_TIMEOUT", ResetController.BluetoothState.OFF));
        assertFalse(feedback.terminal("ENABLE_TIMEOUT", ResetController.BluetoothState.OFF));
    }
    @Test public void actualOnSuppressesFailureWarning() {
        SessionFeedback feedback = new SessionFeedback(); feedback.match();
        assertFalse(feedback.terminal("DISABLE_TIMEOUT", ResetController.BluetoothState.ON));
    }
    @Test public void unknownAfterMatchWarnsConservatively() {
        SessionFeedback feedback = new SessionFeedback(); feedback.match();
        assertTrue(feedback.terminal("SERVICE_DESTROYED", ResetController.BluetoothState.UNKNOWN));
    }
    @Test public void restoredOutcomeDoesNotWarnOnLaterUnknownRead() {
        for (String outcome : new String[]{"RESET_COMPLETE", "RESTORED_EXTERNALLY", "ERROR_RESTORED", "CANCELLED_RESTORED"}) {
            SessionFeedback feedback = new SessionFeedback(); feedback.match();
            assertFalse(feedback.terminal(outcome, ResetController.BluetoothState.UNKNOWN));
        }
    }
}
