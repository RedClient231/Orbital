package com.redclient.orbital.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.redclient.orbital.core.GuestManifest
import com.redclient.orbital.core.OrbitalResult
import com.redclient.orbital.core.ProcessSlot
import com.redclient.orbital.engine.hook.HCallback
import com.redclient.orbital.engine.stub.StubActivities
import com.redclient.orbital.host.GuestRegistry
import com.redclient.orbital.host.OrbitalPaths
import com.redclient.orbital.host.SlotAllocator
import timber.log.Timber

/**
 * The single entry point the UI uses to launch a guest.
 *
 * Orchestration:
 *  1. Look up the guest in [GuestRegistry]
 *  2. [SlotAllocator.acquire] gives us a `:pN` slot
 *  3. Write the slot hint file so the stub process's
 *     [com.redclient.orbital.engine.stub.StubBootstrap] can discover the guest
 *  4. Build an Intent targeting the (slot, launchMode) stub Activity with
 *     the real guest component stashed in extras
 *  5. `context.startActivity(intent)` — the framework forks the stub
 *     process if needed and the hooks take over from there
 */
class GuestLauncher(
    private val context: Context,
    private val registry: GuestRegistry,
    private val slots: SlotAllocator,
    private val paths: OrbitalPaths,
) {

    fun launch(packageName: String): OrbitalResult<Unit> {
        val manifest = registry.findByPackage(packageName)
            ?: return OrbitalResult.Err("Guest not found: $packageName")

        val slot = slots.acquire(packageName)
        val writeOk = writeSlotHint(slot)
        if (!writeOk) {
            slots.release(packageName)
            return OrbitalResult.Err("Could not write slot hint for ${slot.processName}")
        }

        val intent = buildStubIntent(manifest, slot)

        return OrbitalResult.runCatching("startActivity failed for $packageName") {
            context.startActivity(intent)
            Timber.i("GuestLauncher: dispatched %s -> %s", packageName, slot.processName)
        }.onErr {
            // Launch failed — release the slot so we don't leak it
            slots.release(packageName)
            paths.slotHintFile(slot.index).delete()
        }
    }

    private fun writeSlotHint(slot: ProcessSlot): Boolean {
        val hint = paths.slotHintFile(slot.index)
        return try {
            hint.writeText(slot.guestPackage!!)
            true
        } catch (t: Throwable) {
            Timber.e(t, "GuestLauncher: could not write slot hint")
            false
        }
    }

    private fun buildStubIntent(m: GuestManifest, slot: ProcessSlot): Intent {
        // v1 only supports the standard launchMode. Different modes per guest
        // are a v2 feature; most games use Standard anyway.
        val stubClass = StubActivities.className(slot.index, StubActivities.Mode.Standard)
        val guestComponent = ComponentName(m.packageName, m.mainActivity)

        return Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, stubClass)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

            putExtra(HCallback.EXTRA_GUEST_COMPONENT, guestComponent)
            putExtra(HCallback.EXTRA_GUEST_ACTION, Intent.ACTION_MAIN)
        }
    }
}
