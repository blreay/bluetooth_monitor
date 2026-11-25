package com.blreay.bluetoothmonitor

import java.util.concurrent.atomic.AtomicInteger

/** Actual worker admission, shared by Android's bounded lanes and JVM latch tests. */
class NativeWorkAuthority {
    private val state = AtomicInteger(0) // queued, entered, expired-before-entry, returned
    fun enter(valid: Boolean): Boolean = valid && state.compareAndSet(0, 1)
    fun expireBeforeEntry(): Boolean {
        if (state.compareAndSet(0, 2)) return true
        return state.get() == 2
    }
    fun returned() { state.set(3) }
    fun hasReturned() = state.get() == 3
}
