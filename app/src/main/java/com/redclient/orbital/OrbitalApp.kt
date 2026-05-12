package com.redclient.orbital

import android.app.Application
import android.os.Build
import com.redclient.orbital.engine.reflect.HiddenApiBypass
import com.redclient.orbital.engine.stub.StubBootstrap
import timber.log.Timber

/**
 * Runs in every Orbital process — the host AND each stub `:pN`. The first
 * thing we do is figure out which we are, because the init paths diverge.
 *
 * Host process:
 *  - Plant Timber trees
 *  - Build [OrbitalGraph] (lazy singletons used by the UI)
 *
 * Stub process (`:pN`):
 *  - Plant Timber trees
 *  - Install [HiddenApiBypass] so reflection on ActivityThread works
 *  - Hand off to [StubBootstrap] to install the framework hooks and
 *    load the guest APK. Host-only init is skipped.
 */
class OrbitalApp : Application() {

    lateinit var graph: OrbitalGraph
        private set

    override fun onCreate() {
        super.onCreate()

        // Bypass FIRST — before any reflection on framework internals.
        // Even host-side code (AssetManager hidden ctor during guest load)
        // benefits from it, so we install in both branches.
        HiddenApiBypass.install()

        Timber.plant(Timber.DebugTree())

        val procName = resolveProcessName()
        Timber.i("OrbitalApp: starting in process %s", procName)

        // Stub process: parse the slot index from ":pN" and bootstrap.
        if (procName != null && procName != packageName && ":p" in procName) {
            val slot = procName.substringAfterLast(":p").toIntOrNull()
            if (slot != null) {
                Timber.i("OrbitalApp: stub slot %d", slot)
                StubBootstrap.boot(this, slot)
            } else {
                Timber.w("OrbitalApp: could not parse slot from %s", procName)
            }
            return
        }

        // Host process.
        graph = OrbitalGraph(this)
        Timber.i("OrbitalApp: host ready")
    }

    /**
     * Returns the current process name. Uses the public
     * [Application.getProcessName] on API 28+, falls back to a single
     * reflection read on older versions. Either path is covered by
     * [HiddenApiBypass].
     */
    private fun resolveProcessName(): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val at = Class.forName("android.app.ActivityThread")
            val current = at.getDeclaredMethod("currentActivityThread").invoke(null)
            at.getDeclaredField("mProcessName").apply { isAccessible = true }
                .get(current) as? String
        }
    } catch (t: Throwable) {
        Timber.w(t, "OrbitalApp: could not determine process name")
        null
    }
}

/** Host-side DI accessor. Must never be called from stub processes. */
fun android.content.Context.orbital(): OrbitalGraph =
    (applicationContext as OrbitalApp).graph
