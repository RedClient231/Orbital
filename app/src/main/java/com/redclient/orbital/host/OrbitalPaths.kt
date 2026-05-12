package com.redclient.orbital.host

import android.content.Context
import java.io.File

/**
 * Central helper for every filesystem path Orbital uses.
 *
 * All paths live under the app's private storage (`getFilesDir`'s parent).
 * By centralising the layout here we avoid scattering `File(..., "subdir")`
 * literals across the engine and host layers, which would make a storage
 * migration extremely painful.
 *
 * Directory structure:
 * ```
 * /data/data/com.redclient.orbital/
 *   orbital/
 *     registry/          <pkg>.json      — per-guest manifest
 *     apks/<pkg>/        base.apk        — copied guest APK
 *                        split_<i>.apk   — optional XAPK splits
 *                        lib/<abi>/...   — extracted native libs
 *     slots/             slot_<N>.hint   — stub-process ↔ guest package
 *     dex-cache/<pkg>/   — DexClassLoader optimized dex output
 * ```
 */
class OrbitalPaths(context: Context) {

    /** Root directory for everything Orbital owns. Created eagerly. */
    val root: File = File(context.filesDir.parentFile, "orbital").apply { mkdirs() }

    val registryDir: File = File(root, "registry").apply { mkdirs() }
    val apksDir: File = File(root, "apks").apply { mkdirs() }
    val slotsDir: File = File(root, "slots").apply { mkdirs() }
    val dexCacheDir: File = File(root, "dex-cache").apply { mkdirs() }

    fun registryFileFor(pkg: String): File = File(registryDir, "$pkg.json")

    fun apkDirFor(pkg: String): File = File(apksDir, pkg).apply { mkdirs() }

    fun slotHintFile(slotIndex: Int): File = File(slotsDir, "slot_$slotIndex.hint")

    fun dexCacheFor(pkg: String): File = File(dexCacheDir, pkg).apply { mkdirs() }
}
