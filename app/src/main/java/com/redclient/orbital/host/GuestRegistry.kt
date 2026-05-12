package com.redclient.orbital.host

import com.redclient.orbital.core.GuestManifest
import com.redclient.orbital.core.OrbitalResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * In-memory cache of installed guests, backed by JSON files under
 * [OrbitalPaths.registryDir].
 *
 * JSON was chosen over Room because:
 * - Fewer than ~20 entries are expected per device
 * - The registry is read at stub-process startup, where Room's init cost
 *   (schema generation, migration scan) is a real noticeable delay
 * - Each entry is self-contained — there are no joins or complex queries
 * - A failed write only loses one file instead of corrupting a shared DB
 *
 * This class is safe to use from any thread; JSON writes are serialised
 * with `synchronized(lock)` and the reactive [guests] flow is a simple
 * snapshot that's republished after every mutation.
 */
class GuestRegistry(private val paths: OrbitalPaths) {

    private val lock = Any()

    private val _guests = MutableStateFlow<List<GuestManifest>>(emptyList())
    val guests: StateFlow<List<GuestManifest>> = _guests.asStateFlow()

    init {
        reload()
    }

    /** Re-scans the registry directory. Call after external changes. */
    fun reload() {
        synchronized(lock) {
            val scanned = paths.registryDir
                .listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.mapNotNull { readOrNull(it) }
                ?.sortedBy { it.appName.lowercase() }
                ?: emptyList()

            _guests.value = scanned
            Timber.i("GuestRegistry: loaded %d guests", scanned.size)
        }
    }

    fun findByPackage(pkg: String): GuestManifest? =
        _guests.value.firstOrNull { it.packageName == pkg }

    /**
     * Persists [manifest] to the registry, overwriting any existing entry
     * for the same package.
     */
    fun upsert(manifest: GuestManifest): OrbitalResult<Unit> = synchronized(lock) {
        OrbitalResult.runCatching("Failed to save registry for ${manifest.packageName}") {
            val file = paths.registryFileFor(manifest.packageName)
            file.writeText(toJson(manifest).toString(2))
            Timber.i("GuestRegistry: saved %s", manifest.packageName)
            reload()
        }
    }

    /** Removes the registry entry. Does NOT delete APK/data files. */
    fun remove(pkg: String): OrbitalResult<Unit> = synchronized(lock) {
        OrbitalResult.runCatching("Failed to remove registry entry for $pkg") {
            paths.registryFileFor(pkg).delete()
            reload()
        }
    }

    // -- JSON encoding --------------------------------------------------

    private fun readOrNull(file: File): GuestManifest? = try {
        fromJson(JSONObject(file.readText()))
    } catch (t: Throwable) {
        Timber.w(t, "GuestRegistry: skipping malformed entry %s", file.name)
        null
    }

    private fun toJson(m: GuestManifest): JSONObject = JSONObject().apply {
        put("packageName", m.packageName)
        put("appName", m.appName)
        put("versionName", m.versionName)
        put("versionCode", m.versionCode)
        put("mainActivity", m.mainActivity)
        put("apkPath", m.apkPath)
        put("splitApkPaths", JSONArray(m.splitApkPaths))
        m.nativeLibDir?.let { put("nativeLibDir", it) }
        put("targetSdk", m.targetSdk)
    }

    private fun fromJson(o: JSONObject): GuestManifest = GuestManifest(
        packageName = o.getString("packageName"),
        appName = o.optString("appName", o.getString("packageName")),
        versionName = o.optString("versionName", "unknown"),
        versionCode = o.optLong("versionCode", -1L),
        mainActivity = o.getString("mainActivity"),
        apkPath = o.getString("apkPath"),
        splitApkPaths = o.optJSONArray("splitApkPaths")?.let { arr ->
            List(arr.length()) { i -> arr.getString(i) }
        } ?: emptyList(),
        nativeLibDir = o.optString("nativeLibDir").ifBlank { null },
        targetSdk = o.optInt("targetSdk", 21),
    )
}
