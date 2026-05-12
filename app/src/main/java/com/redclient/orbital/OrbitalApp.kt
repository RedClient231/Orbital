package com.redclient.orbital

import android.app.Application
import com.redclient.orbital.engine.stub.StubBootstrap
import com.redclient.orbital.host.OrbitalPaths
import timber.log.Timber

/**
 * Runs in every Orbital process — the host process AND each stub `:pN`
 * process. The first thing we do is figure out which we are, because
 * the initialisation paths diverge completely.
 *
 * Host process:
 *  - Plant Timber trees
 *  - Build [OrbitalGraph] lazily (accessed when the first Activity starts)
 *
 * Stub process (`:pN`):
 *  - Plant Timber trees
 *  - Hand off to [StubBootstrap] which installs the framework hooks and
 *    loads the guest APK. Everything below this point in onCreate is
 *    skipped for stubs.
 */
class OrbitalApp : Application() {

    /**
     * Lazily-built host graph. Only valid in the host process — touching it
     * from a stub process would be a bug (stubs have their own lifecycle
     * driven entirely by framework hooks).
     */
    lateinit var graph: OrbitalGraph
        private set

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())

        val proc = currentProcessName() ?: packageName
        if (proc != packageName) {
            val slot = proc.substringAfterLast(":p").toIntOrNull()
            if (slot != null) {
                Timber.i("OrbitalApp: stub %s (slot %d) booting", proc, slot)
                StubBootstrap.boot(this, slot)
                return // host-only init below is skipped
            }
            Timber.w("OrbitalApp: unknown non-host process %s", proc)
            return
        }

        // Host process
        graph = OrbitalGraph(this)
        Timber.i("OrbitalApp: host process ready")
    }

    /**
     * Pulls the process name out of ActivityThread. Using `Application.getProcessName()`
     * would be simpler but it's API 28+; our minSdk is 26.
     */
    private fun currentProcessName(): String? = try {
        val at = Class.forName("android.app.ActivityThread")
        val currentAt = at.getDeclaredMethod("currentActivityThread").invoke(null)
        at.getDeclaredField("mProcessName").apply { isAccessible = true }.get(currentAt) as? String
    } catch (t: Throwable) {
        Timber.w(t, "OrbitalApp: could not determine process name")
        null
    }
}

/** Convenience accessor for the host graph from any Activity/ViewModel. */
fun android.content.Context.orbital(): OrbitalGraph =
    (applicationContext as OrbitalApp).graph
