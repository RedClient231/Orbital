package com.redclient.orbital.engine.loader

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import com.redclient.orbital.core.GuestManifest
import dalvik.system.DexClassLoader
import timber.log.Timber
import java.io.File

/**
 * Loads a guest APK into the current (stub) process.
 *
 * Every method of this class runs in a stub `:pN` process, not in the host.
 * The bundle it produces is stashed in [com.redclient.orbital.engine.stub.StubState]
 * so the [com.redclient.orbital.engine.hook.HCallback] and
 * [com.redclient.orbital.engine.hook.GuestInstrumentation] can use it.
 *
 * The loading steps are:
 *  1. Build a [DexClassLoader] pointing at the guest's APK + splits
 *  2. Build a [Resources] object backed by an [AssetManager] that has the
 *     APK's asset paths added (via the hidden `addAssetPath` method)
 *  3. Explicitly load native libraries with [System.load] so they appear
 *     in `/proc/self/maps` (GameGuardian compatibility)
 */
class GuestLoader(private val hostContext: Context) {

    data class Bundle(
        val manifest: GuestManifest,
        val classLoader: DexClassLoader,
        val resources: Resources,
    )

    fun load(manifest: GuestManifest, dexCacheDir: File): Bundle {
        val classLoader = buildClassLoader(manifest, dexCacheDir)
        val resources = buildResources(manifest)
        loadNativeLibraries(manifest)
        Timber.i("GuestLoader: ready for %s", manifest.packageName)
        return Bundle(manifest, classLoader, resources)
    }

    private fun buildClassLoader(manifest: GuestManifest, dexCacheDir: File): DexClassLoader {
        if (!dexCacheDir.exists()) dexCacheDir.mkdirs()

        val dexPath = buildString {
            append(manifest.apkPath)
            manifest.splitApkPaths
                .filter { File(it).exists() }
                .forEach { append(File.pathSeparator).append(it) }
        }

        val libPath = manifest.nativeLibDir?.let { resolveAbiLibPath(it) }

        // Parent = this class's own loader — gives the guest access to
        // framework classes (Activity, View, ...) without letting it see
        // Orbital's own classes (Timber, etc.).
        return DexClassLoader(
            /* dexPath        = */ dexPath,
            /* optimizedDir   = */ dexCacheDir.absolutePath,
            /* librarySearch  = */ libPath,
            /* parent         = */ GuestLoader::class.java.classLoader!!,
        )
    }

    private fun buildResources(manifest: GuestManifest): Resources {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java
            .getDeclaredMethod("addAssetPath", String::class.java)
            .apply { isAccessible = true }

        addAssetPath.invoke(assets, manifest.apkPath)
        manifest.splitApkPaths
            .filter { File(it).exists() }
            .forEach { addAssetPath.invoke(assets, it) }

        val host = hostContext.resources
        return Resources(assets, host.displayMetrics, host.configuration)
    }

    private fun loadNativeLibraries(manifest: GuestManifest) {
        val dir = manifest.nativeLibDir ?: return
        val abiDir = resolveAbiLibPath(dir) ?: return

        File(abiDir).listFiles { f -> f.name.endsWith(".so") }
            ?.forEach { lib ->
                try {
                    System.load(lib.absolutePath)
                    Timber.d("GuestLoader: loaded %s", lib.name)
                } catch (t: Throwable) {
                    // Missing dependencies get picked up when the guest
                    // itself calls System.loadLibrary later, so a failure
                    // here isn't fatal.
                    Timber.w(t, "GuestLoader: could not eagerly load %s", lib.name)
                }
            }
    }

    /**
     * Picks the first device-supported ABI directory under [libRoot].
     * Android only ships one ABI per APK today, but XAPKs sometimes bundle
     * multiple.
     */
    private fun resolveAbiLibPath(libRoot: String): String? {
        val root = File(libRoot)
        if (!root.exists()) return null
        for (abi in android.os.Build.SUPPORTED_ABIS) {
            val abiDir = File(root, abi)
            if (abiDir.isDirectory && (abiDir.listFiles()?.isNotEmpty() == true)) {
                return abiDir.absolutePath
            }
        }
        // Fallback: .so files sitting directly in the root (rare).
        if (root.listFiles { f -> f.name.endsWith(".so") }?.isNotEmpty() == true) {
            return root.absolutePath
        }
        return null
    }
}
