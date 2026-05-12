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

        // Timber FIRST so LogcatSeed can log its own status.
        Timber.plant(Timber.DebugTree())

        // SEED — captures the ENTIRE logcat to /Download/OrbitalLogs/
        // from this exact moment until the process dies. Every tag, every
        // priority, every framework message. This is our reverse engineering.
        val procName = resolveProcessName() ?: packageName
        com.redclient.orbital.engine.diagnostics.LogcatSeed.plant(procName)

        // On Android 11+, request MANAGE_EXTERNAL_STORAGE so the seed can
        // write to /Download/. Without this, the logcat file won't be created.
        // The user will see a one-time "Allow access to manage all files" prompt
        // if not already granted.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (_: Throwable) {
                    Timber.w("OrbitalApp: could not request MANAGE_EXTERNAL_STORAGE")
                }
            }
        }

        // Bypass SECOND — before any reflection on framework internals.
        HiddenApiBypass.install()

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
