package com.redclient.orbital.core

/**
 * One slot in the pool of stub processes Orbital reserves via `android:process=":pN"`.
 *
 * The number of slots is fixed in the manifest at build time. Each slot hosts
 * at most one guest app at a time. The [SlotAllocator] picks slots using LRU
 * eviction when all are full.
 */
data class ProcessSlot(
    /** Slot index 0..[SLOT_COUNT]. */
    val index: Int,
    /** Null when the slot is idle. Set to the guest package when bound. */
    val guestPackage: String?,
    /** Epoch millis when the slot was last acquired; used for LRU ordering. */
    val lastUsedAt: Long,
) {
    /** The `android:process` name registered in the manifest. */
    val processName: String get() = ":p$index"

    val isIdle: Boolean get() = guestPackage == null

    companion object {
        /** Must match the number of `:p<N>` entries in the manifest. */
        const val SLOT_COUNT = 10

        fun idle(index: Int) = ProcessSlot(index, guestPackage = null, lastUsedAt = 0L)
    }
}
