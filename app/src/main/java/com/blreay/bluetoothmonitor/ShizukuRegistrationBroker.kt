package com.blreay.bluetoothmonitor

/** One process-wide SDK entry; state/callback ownership is confined to the main looper. */
internal class ShizukuRegistrationBroker<T>(
    private val postRetirementBarrier: (() -> Unit) -> Unit,
    private val destroy: (T) -> Unit,
) {
    interface Owner<T> {
        fun connected(endpoint: T)
        fun disconnected()
        fun failed(message: String)
    }

    class Slot<T> internal constructor(val generation: Long, internal var owner: Owner<T>?) {
        internal var endpoint: T? = null
        internal var retired = false
        internal var managementPending = 1 // the single initial bind
        internal var sdkRetirementComplete = false
    }

    private var generation = 0L
    private var active: Slot<T>? = null
    val quarantined: Boolean get() = active?.retired == true

    fun acquire(owner: Owner<T>): Slot<T>? {
        if (active != null) return null
        return Slot(++generation, owner).also { active = it }
    }

    fun isCurrent(slot: Slot<T>): Boolean = active === slot && !slot.retired
    fun canReconfirm(slot: Slot<T>, endpoint: T): Boolean = isCurrent(slot) && slot.endpoint === endpoint

    /** Idempotently revoke UI/command authority before submitting that slot's remove operation. */
    fun retire(slot: Slot<T>): Boolean {
        if (!isCurrent(slot)) return false
        slot.retired = true
        slot.owner = null // SDK callbacks for an ambiguous registration must never retain its Activity.
        slot.managementPending++
        slot.endpoint?.let(destroy)
        slot.endpoint = null
        return true
    }

    fun connected(slot: Slot<T>, endpoint: T) {
        if (!isCurrent(slot)) {
            destroy(endpoint) // exact retired endpoint only; never adopt it into another generation
            return
        }
        slot.endpoint = endpoint
        slot.owner?.connected(endpoint)
    }

    fun disconnected(slot: Slot<T>) {
        if (active !== slot) return
        slot.owner?.disconnected()
        // SDK 13.1.5 clears its callback set/removes its cache entry AFTER invoking this callback.
        // Only a subsequent main-loop turn can acknowledge that cleanup, not this callback itself.
        postRetirementBarrier {
            if (active !== slot) return@postRetirementBarrier
            slot.sdkRetirementComplete = true
            releaseIfSafe(slot)
        }
    }

    fun managementFinished(slot: Slot<T>, failure: String? = null) {
        if (active !== slot) return
        if (slot.managementPending > 0) slot.managementPending--
        if (failure != null && isCurrent(slot)) slot.owner?.failed(failure)
        releaseIfSafe(slot)
    }

    private fun releaseIfSafe(slot: Slot<T>) {
        if (active === slot && slot.retired && slot.managementPending == 0 && slot.sdkRetirementComplete) {
            active = null
        }
        // No callback/barrier proof (including never-connected bind failures) => one quarantined slot.
        // Completed remote removal or main Shizuku binder death alone cannot prove local cache removal.
    }
}
