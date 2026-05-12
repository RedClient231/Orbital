package com.redclient.orbital.engine.stub

import android.app.Application
import android.app.Instrumentation
import android.os.Handler
import com.redclient.orbital.core.GuestManifest
import com.redclient.orbital.engine.hook.GuestInstrumentation
import com.redclient.orbital.engine.hook.HCallback
import com.redclient.orbital.engine.loader.GuestLoader
import com.redclient.orbital.engine.reflect.Reflect
import com.redclient.orbital.host.GuestRegistry
import com.redclient.orbital.host.OrbitalPaths
import timber.log.Timber

/**
 * Runs **once** in every stub `:pN` process, right after `Application.onCreate`.
 *
 * The job is to prepare the process to host exactly one guest:
 *  1. Figure out which guest this process is supposed to run (the host
 *     wrote a slot hint file before calling startActivity).
 *  2. Load the guest APK via [GuestLoader].
 *  3. Patch `ActivityThread.mH.mCallback` with [HCallback] so LAUNCH_ACTIVITY
 *     messages get rewritten on the way to the framework's default dispatch.
 *  4. Replace `ActivityThread.mInstrumentation` with [GuestInstrumentation]
 *     so `newActivity` loads the guest class instead of falling back to the
 *     stub base.
 *
 * All of this runs **synchronously on the main thread** during Application
 * initialisation. It's fast (< 50ms typically) because every step is either
 * reflection or a one-time file read.
 */
internal object StubBootstrap {

    fun boot(app: Application, slotIndex: Int) {
        val paths = OrbitalPaths(app)

        val guestPackage = readSlotHint(paths, slotIndex)
        if (guestPackage.isNullOrBlank()) {
            Timber.w("StubBootstrap: no slot hint for :p%d — idling", slotIndex)
            return
        }
        Timber.i("StubBootstrap: slot %d -> %s", slotIndex, guestPackage)

        val manifest = readManifest(paths, guestPackage)
        if (manifest == null) {
            Timber.w("StubBootstrap: no registry entry for %s — idling", guestPackage)
            return
        }
        Timber.i(
            "StubBootstrap: manifest ok — pkg=%s main=%s apk=%s splits=%d nativeLibDir=%s",
            manifest.packageName, manifest.mainActivity, manifest.apkPath,
            manifest.splitApkPaths.size, manifest.nativeLibDir,
        )

        // 1. Load the guest: DexClassLoader + Resources + native libs.
        val bundle = try {
            GuestLoader(app).load(manifest, paths.dexCacheFor(guestPackage))
        } catch (t: Throwable) {
            Timber.e(t, "StubBootstrap: guest load failed for %s", guestPackage)
            return
        }
        StubState.setBundle(bundle)

        // 2. Install the two framework hooks.
        val hChained = installHCallback()
        val iShimmed = installInstrumentationShim()

        if (!hChained || !iShimmed) {
            Timber.e(
                "StubBootstrap: HOOK INSTALL FAILED — hCallback=%s instrShim=%s — " +
                    "guest will not launch. Check HiddenApiBypass ran first.",
                hChained, iShimmed,
            )
        } else {
            Timber.i("StubBootstrap: ready — hCallback=true instrShim=true")
        }
    }

    // -- Slot hint I/O -------------------------------------------------

    private fun readSlotHint(paths: OrbitalPaths, slot: Int): String? = try {
        val f = paths.slotHintFile(slot)
        if (f.exists()) f.readText().trim().ifBlank { null } else null
    } catch (t: Throwable) {
        Timber.w(t, "StubBootstrap: could not read slot hint %d", slot)
        null
    }

    private fun readManifest(paths: OrbitalPaths, pkg: String): GuestManifest? {
        // We instantiate a fresh GuestRegistry here rather than sharing the
        // host's singleton because stub processes don't go through the host's
        // DI graph.
        return GuestRegistry(paths).findByPackage(pkg)
    }

    // -- HCallback install --------------------------------------------

    private fun installHCallback(): Boolean {
        val atClass = Reflect.classOrNull("android.app.ActivityThread") ?: return false
        val currentAt = Reflect.method(atClass, "currentActivityThread")?.invoke(null) ?: return false
        val mH = Reflect.read<Handler>(currentAt, atClass, "mH") ?: return false

        val existing = Reflect.read<Handler.Callback>(mH, Handler::class.java, "mCallback")
        val ours = HCallback()

        // Chain: our callback first, then the existing one if any, then the
        // framework's own handleMessage. Returning false lets each layer see
        // the message.
        val chained = Handler.Callback { msg ->
            val handled = try { ours.handleMessage(msg) } catch (t: Throwable) {
                Timber.e(t, "StubBootstrap: HCallback threw"); false
            }
            if (handled) true else existing?.handleMessage(msg) ?: false
        }
        return Reflect.write(mH, Handler::class.java, "mCallback", chained)
    }

    // -- Instrumentation shim install ---------------------------------

    private fun installInstrumentationShim(): Boolean {
        val atClass = Reflect.classOrNull("android.app.ActivityThread") ?: return false
        val currentAt = Reflect.method(atClass, "currentActivityThread")?.invoke(null) ?: return false
        val original = Reflect.read<Instrumentation>(currentAt, atClass, "mInstrumentation") ?: return false

        val shim = GuestInstrumentation(original)
        return Reflect.write(currentAt, atClass, "mInstrumentation", shim)
    }
}
