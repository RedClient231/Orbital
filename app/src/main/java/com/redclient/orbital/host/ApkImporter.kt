package com.redclient.orbital.host

import android.content.Context
import android.net.Uri
import com.redclient.orbital.core.GuestManifest
import com.redclient.orbital.core.OrbitalResult
import net.dongliu.apk.parser.ApkFile
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Imports APK and XAPK files into Orbital's private storage.
 *
 * Import does three things, in this order:
 *
 *  1. Copy the source file into Orbital's app-private `apks/<pkg>/` directory.
 *     (We need a private-storage path because `DexClassLoader` requires it
 *     and because the source URI is often a one-shot content:// stream.)
 *  2. Extract native libraries from the APK into `apks/<pkg>/lib/<abi>/...`.
 *     `DexClassLoader` needs them on disk so it can set up the library path.
 *  3. Parse the APK's manifest, build a [GuestManifest], persist via
 *     [GuestRegistry].
 *
 * The two supported source formats are:
 *  - Plain `.apk` — treated as a base APK with no splits.
 *  - `.xapk` / `.apks` ZIP — a ZIP containing one base APK plus optional
 *    `config.*.apk` / `split_*.apk` files. We extract all of them and
 *    install the base.
 */
class ApkImporter(
    private val context: Context,
    private val paths: OrbitalPaths,
    private val registry: GuestRegistry,
) {

    /** Result of a successful import, returned so the UI can show a confirmation. */
    data class Imported(val manifest: GuestManifest)

    /**
     * Imports whatever the user picked from the file chooser. Inspects the
     * first few bytes to tell plain APK from XAPK ZIP.
     */
    fun importFromUri(sourceUri: Uri): OrbitalResult<Imported> =
        OrbitalResult.runCatching("Failed to import APK") {
            // Materialise the source into a temp file so we can stream it
            // multiple times (format detection, zip extraction, manifest parse).
            val temp = File.createTempFile("import-", ".tmp", context.cacheDir)
            try {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(temp).use { out -> input.copyTo(out) }
                } ?: error("Could not open source URI")

                val format = detectFormat(temp)
                Timber.i("ApkImporter: detected %s for %s", format, sourceUri)

                when (format) {
                    Format.APK -> importPlainApk(temp)
                    Format.XAPK -> importXapk(temp)
                }
            } finally {
                temp.delete()
            }
        }

    // -- Plain APK ------------------------------------------------------

    private fun importPlainApk(apk: File): Imported {
        val manifest = parseManifest(apk, splits = emptyList(), nativeLibDir = null)
            ?: error("Could not parse APK manifest")

        val dstDir = paths.apkDirFor(manifest.packageName)
        val dstApk = File(dstDir, "base.apk").apply {
            if (exists()) delete()
            apk.copyTo(this, overwrite = true)
        }

        val libDir = extractNativeLibs(dstApk, File(dstDir, "lib"))

        val finalManifest = manifest.copy(
            apkPath = dstApk.absolutePath,
            splitApkPaths = emptyList(),
            nativeLibDir = libDir?.absolutePath,
        )
        registry.upsert(finalManifest).onErr { error(it.message) }
        return Imported(finalManifest)
    }

    // -- XAPK -----------------------------------------------------------

    private fun importXapk(xapk: File): Imported {
        // Extract APKs + OBBs into a staging tree that preserves depth,
        // so two splits named "config.arm64_v8a.apk" in different
        // subfolders don't collide.
        val stage = File(context.cacheDir, "xapk-stage-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            val extractedApks = mutableListOf<File>()
            val extractedObbs = mutableListOf<File>()

            ZipInputStream(FileInputStream(xapk)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory) {
                        val looksApk = name.endsWith(".apk", ignoreCase = true)
                        val looksObb = name.endsWith(".obb", ignoreCase = true)
                        if (looksApk || looksObb) {
                            val safe = name.replace('/', '_').replace('\\', '_')
                            val out = File(stage, safe)
                            // CRITICAL: must NOT use InputStream.copyTo here.
                            // copyTo reads until EOF of the whole ZipInputStream,
                            // consuming ALL remaining entries. We must read only
                            // the bytes belonging to THIS entry (bounded by
                            // closeEntry/nextEntry).
                            FileOutputStream(out).use { fos ->
                                val buf = ByteArray(65536)
                                var n = zip.read(buf)
                                while (n >= 0) {
                                    fos.write(buf, 0, n)
                                    n = zip.read(buf)
                                }
                            }
                            if (looksApk) extractedApks += out else extractedObbs += out
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            require(extractedApks.isNotEmpty()) {
                "XAPK contains no .apk files (found: ${stage.list()?.joinToString() ?: "nothing"})"
            }

            // Heuristic: base APK is the one without a split marker.
            val baseApk = extractedApks.firstOrNull {
                val n = it.name
                n.endsWith("base.apk") || (!n.startsWith("config.") && !n.startsWith("split_") && !n.contains("_config."))
            } ?: extractedApks.first()
            val splits = extractedApks.filter { it != baseApk }

            val parsed = parseManifest(baseApk, splits = splits.map { it.absolutePath }, nativeLibDir = null)
                ?: error("XAPK base APK has no readable manifest")

            val dstDir = paths.apkDirFor(parsed.packageName)
            val dstBase = File(dstDir, "base.apk").apply {
                if (exists()) delete()
                baseApk.copyTo(this, overwrite = true)
            }
            val dstSplits = splits.mapIndexed { i, src ->
                File(dstDir, "split_$i.apk").apply {
                    if (exists()) delete()
                    src.copyTo(this, overwrite = true)
                }
            }

            // Native libs can live in the base APK or any of the splits.
            val libDir = File(dstDir, "lib")
            libDir.deleteRecursively(); libDir.mkdirs()
            (listOf(dstBase) + dstSplits).forEach { extractNativeLibs(it, libDir) }
            val libDirResolved =
                if (libDir.exists() && libDir.walkTopDown().any { it.isFile }) libDir else null

            // Copy any .obb files the XAPK carried. We store them under the
            // guest's private obb/ dir so GuestContextOverrides can expose
            // them later via Context.getObbDir() if needed.
            if (extractedObbs.isNotEmpty()) {
                val obbDir = File(dstDir, "obb").apply { mkdirs() }
                extractedObbs.forEach { obb ->
                    val basename = obb.name.substringAfterLast('_')
                    obb.copyTo(File(obbDir, basename), overwrite = true)
                }
                Timber.i("ApkImporter: copied %d OBB file(s) for %s", extractedObbs.size, parsed.packageName)
            }

            val finalManifest = parsed.copy(
                apkPath = dstBase.absolutePath,
                splitApkPaths = dstSplits.map { it.absolutePath },
                nativeLibDir = libDirResolved?.absolutePath,
            )
            registry.upsert(finalManifest).onErr { error(it.message) }
            return Imported(finalManifest)
        } finally {
            stage.deleteRecursively()
        }
    }

    // -- Format detection ----------------------------------------------

    private enum class Format { APK, XAPK }

    private fun detectFormat(file: File): Format {
        // Both APK and XAPK are ZIP archives, but the distinguishing feature
        // is reliable: an APK's ZIP directory contains "AndroidManifest.xml"
        // at the ROOT in binary form. An XAPK may contain a JSON file called
        // "manifest.json" at the root plus one or more "*.apk" entries.
        //
        // We collect signals and decide at the end, because the order in
        // which ZIP entries appear is not guaranteed.
        var hasRootAndroidManifest = false
        var hasApkEntry = false
        var hasXapkManifestJson = false

        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == "AndroidManifest.xml" -> hasRootAndroidManifest = true
                    name == "manifest.json" -> hasXapkManifestJson = true
                    name.endsWith(".apk", ignoreCase = true) -> hasApkEntry = true
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // An XAPK ALWAYS contains .apk entries; a plain APK never does.
        // The presence of a nested .apk is the strongest XAPK signal.
        return when {
            hasApkEntry -> Format.XAPK
            hasXapkManifestJson -> Format.XAPK
            hasRootAndroidManifest -> Format.APK
            else -> Format.APK // unknown — let the manifest parser report it
        }
    }

    // -- Native lib extraction -----------------------------------------

    /**
     * Walks the APK ZIP and copies every `lib/ABI/NAME.so` entry into the
     * corresponding `targetDir/ABI/NAME.so`. Returns the target directory
     * if any libs were found, null otherwise.
     */
    private fun extractNativeLibs(apk: File, targetDir: File): File? {
        var anyExtracted = false
        ZipInputStream(FileInputStream(apk)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name.startsWith("lib/") && name.endsWith(".so")) {
                    val out = File(targetDir, name.removePrefix("lib/"))
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zip.copyTo(it) }
                    anyExtracted = true
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return if (anyExtracted) targetDir else null
    }

    // -- Manifest parsing ----------------------------------------------

    private fun parseManifest(
        apk: File,
        splits: List<String>,
        nativeLibDir: String?,
    ): GuestManifest? = try {
        ApkFile(apk).use { parser ->
            val meta = parser.apkMeta ?: return null
            val xml = parser.manifestXml ?: return null

            val launcher = findLauncherActivity(xml, meta.packageName ?: "") ?: return null

            GuestManifest(
                packageName = meta.packageName ?: return null,
                appName = meta.label ?: (meta.packageName ?: "").substringAfterLast('.'),
                versionName = meta.versionName ?: "unknown",
                versionCode = meta.versionCode?.toLong() ?: -1L,
                mainActivity = launcher,
                apkPath = apk.absolutePath, // Overwritten by caller after copy
                splitApkPaths = splits,
                nativeLibDir = nativeLibDir,
                targetSdk = meta.targetSdkVersion?.toIntOrNull() ?: 21,
            )
        }
    } catch (t: Throwable) {
        Timber.w(t, "ApkImporter: failed to parse %s", apk)
        null
    }

    /** Walks the manifest XML via SAX to find the ACTION_MAIN/CATEGORY_LAUNCHER activity. */
    private fun findLauncherActivity(xml: String, pkg: String): String? {
        var launcher: String? = null
        val handler = object : DefaultHandler() {
            var currentActivity: String? = null
            var inIntentFilter = false
            var sawMain = false
            var sawLauncher = false

            override fun startElement(
                uri: String?, localName: String?, qName: String?, a: Attributes?,
            ) {
                val tag = (qName ?: localName)?.lowercase() ?: return
                when (tag) {
                    "activity", "activity-alias" -> {
                        currentActivity = a?.getValue("android:name") ?: a?.getValue("name")
                        inIntentFilter = false
                        sawMain = false
                        sawLauncher = false
                    }
                    "intent-filter" -> inIntentFilter = true
                    "action" -> if (inIntentFilter &&
                        (a?.getValue("android:name") ?: a?.getValue("name")) == "android.intent.action.MAIN"
                    ) sawMain = true
                    "category" -> if (inIntentFilter &&
                        (a?.getValue("android:name") ?: a?.getValue("name")) == "android.intent.category.LAUNCHER"
                    ) sawLauncher = true
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val tag = (qName ?: localName)?.lowercase() ?: return
                if (tag == "intent-filter" && sawMain && sawLauncher && launcher == null) {
                    currentActivity?.let { launcher = resolveActivity(it, pkg) }
                }
                if (tag == "intent-filter") {
                    inIntentFilter = false; sawMain = false; sawLauncher = false
                }
                if (tag == "activity" || tag == "activity-alias") currentActivity = null
            }
        }

        SAXParserFactory.newInstance().newSAXParser()
            .parse(xml.byteInputStream(Charsets.UTF_8), handler)
        return launcher
    }

    private fun resolveActivity(name: String, pkg: String): String = when {
        name.startsWith(".") -> "$pkg$name"
        !name.contains('.') -> "$pkg.$name"
        else -> name
    }

    // stdlib's InputStream.copyTo(OutputStream) covers this; no custom ext needed.
}
