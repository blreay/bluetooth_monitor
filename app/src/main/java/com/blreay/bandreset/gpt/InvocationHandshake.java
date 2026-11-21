package com.blreay.bandreset.gpt;

/** Main-thread-only service handshake; permission time is outside both deadlines. */
final class InvocationHandshake {
    static final long READY_TIMEOUT_MS = 3500;
    static final long OPERATION_TIMEOUT_MS = 90000;
    private Runnable attached;
    private boolean requested;
    private boolean ready;
    private String result;
    private long requestedAt;

    void attach(Runnable callback) {
        attached = callback;
        if (isFinished()) deliver();
    }
    void detach(Runnable callback) { if (attached == callback) attached = null; }
    boolean request(long now) {
        if (requested || isFinished()) return false;
        requested = true;
        requestedAt = now;
        return true;
    }
    void ready() { if (requested && !isFinished()) ready = true; }
    void terminal(String outcome) {
        if (isFinished()) return;
        result = outcome;
        deliver();
    }
    void timeout(long now) {
        if (!requested || isFinished()) return;
        if (!ready && now - requestedAt >= READY_TIMEOUT_MS) terminal("SERVICE_READY_TIMEOUT");
        else if (now - requestedAt >= OPERATION_TIMEOUT_MS) terminal("OPERATION_TIMEOUT");
    }
    boolean isFinished() { return result != null; }
    String outcome() { return result; }
    private void deliver() {
        Runnable callback = attached;
        attached = null; // Never retain a destroyed/finished Activity, even under reentry.
        if (callback != null) callback.run();
    }
}
