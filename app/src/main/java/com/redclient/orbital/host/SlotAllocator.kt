package com.redclient.orbital.host

import com.redclient.orbital.core.ProcessSlot
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

/**
 * Allocates stub-process slots for guests launching from the host UI.
 *
 * The allocator keeps an immutable snapshot of slot state in an
 * [AtomicReference] and updates it with compare-and-set on every mutation.
 * That lets callers read the current state from any thread without a lock.
 *
 * LRU eviction kicks in when all [ProcessSlot.SLOT_COUNT] slots are bound
 * to different guests.
 */
class SlotAllocator {

    private val state = AtomicReference(
        List(ProcessSlot.SLOT_COUNT) { ProcessSlot.idle(it) }
    )

    val snapshot: List<ProcessSlot> get() = state.get()

    /**
     * Returns the slot already bound to [pkg] if any; otherwise claims the
     * oldest idle slot; otherwise evicts the least-recently-used one.
     */
    fun acquire(pkg: String): ProcessSlot {
        while (true) {
            val current = state.get()
            val chosen = pickSlot(current, pkg)

            val updated = current.toMutableList().also {
                it[chosen.index] = chosen.copy(
                    guestPackage = pkg,
                    lastUsedAt = System.currentTimeMillis(),
                )
            }

            if (state.compareAndSet(current, updated)) {
                if (chosen.guestPackage != null && chosen.guestPackage != pkg) {
                    Timber.w(
                        "SlotAllocator: evicting %s from %s to host %s",
                        chosen.guestPackage, chosen.processName, pkg,
                    )
                } else if (chosen.guestPackage == null) {
                    Timber.i("SlotAllocator: %s -> %s", pkg, chosen.processName)
                }
                return updated[chosen.index]
            }
            // Lost the race — retry with the fresh snapshot.
        }
    }

    /** Marks the slot holding [pkg] as idle. No-op if [pkg] isn't bound. */
    fun release(pkg: String) {
        while (true) {
            val current = state.get()
            val idx = current.indexOfFirst { it.guestPackage == pkg }
            if (idx < 0) return

            val updated = current.toMutableList().also {
                it[idx] = ProcessSlot.idle(idx)
            }
            if (state.compareAndSet(current, updated)) return
        }
    }

    private fun pickSlot(snapshot: List<ProcessSlot>, pkg: String): ProcessSlot {
        // 1. Already bound?
        snapshot.firstOrNull { it.guestPackage == pkg }?.let { return it }

        // 2. Any idle slot?
        snapshot.firstOrNull { it.isIdle }?.let { return it }

        // 3. Evict LRU
        return snapshot.minBy { it.lastUsedAt }
    }
}
