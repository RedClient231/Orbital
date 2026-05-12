package com.redclient.orbital.engine.stub

import com.redclient.orbital.core.ProcessSlot

/**
 * Compile-time map between (slot, launchMode) pairs and the concrete stub
 * Activity class that's declared in the manifest for that pair.
 *
 * Atlas Android's ActivityManager decides which process hosts a new
 * Activity based on `android:process` in the manifest. By declaring
 * 10 slots × 4 launchModes = 40 Activity classes, the host can pick
 * any (slot, mode) combination and the framework will fork the right
 * `:pN` process for us.
 *
 * Class names follow the pattern:
 *    StubActivity_P{slot}_{ModeSuffix}
 * and live in this package. The manifest references them by fully-qualified
 * name. Add or remove slots by editing [ProcessSlot.SLOT_COUNT] and the
 * corresponding manifest entries.
 */
object StubActivities {

    /** Activity launch modes we support. Matches the four Android launch modes. */
    enum class Mode(val suffix: String, val frameworkValue: Int) {
        Standard("Std", 0),
        SingleTop("Top", 1),
        SingleTask("Task", 2),
        SingleInstance("Instance", 3);

        companion object {
            fun fromFramework(v: Int): Mode = values().firstOrNull { it.frameworkValue == v } ?: Standard
        }
    }

    data class StubId(val slotIndex: Int, val mode: Mode)

    /** Builds the FQCN that the manifest registers for the given pair. */
    fun className(slot: Int, mode: Mode): String {
        require(slot in 0 until ProcessSlot.SLOT_COUNT)
        return "com.redclient.orbital.engine.stub.StubActivity_P${slot}_${mode.suffix}"
    }

    /**
     * Reverses [className] into a [StubId], or null if the name isn't
     * one of ours. Used by [com.redclient.orbital.engine.hook.HCallback]
     * to tell our messages apart from unrelated ones.
     */
    fun parse(className: String): StubId? {
        val marker = "StubActivity_P"
        val idx = className.lastIndexOf(marker)
        if (idx < 0) return null
        val tail = className.substring(idx + marker.length)
        val underscore = tail.indexOf('_')
        if (underscore <= 0) return null

        val slot = tail.substring(0, underscore).toIntOrNull() ?: return null
        if (slot !in 0 until ProcessSlot.SLOT_COUNT) return null

        val suffix = tail.substring(underscore + 1)
        val mode = Mode.values().firstOrNull { it.suffix == suffix } ?: return null
        return StubId(slot, mode)
    }
}
