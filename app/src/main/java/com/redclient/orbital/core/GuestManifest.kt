package com.redclient.orbital.core

/**
 * Immutable metadata describing a guest app that Orbital has imported.
 *
 * Stored as JSON in the host's registry directory. All fields are populated
 * by the manifest parser at import time; nothing is mutated afterwards
 * (re-import the APK to update any of them).
 *
 * @property packageName   The guest's package name, e.g. "com.some.game".
 * @property appName       Human-readable label as declared in the APK.
 * @property versionName   `android:versionName` value from the manifest.
 * @property versionCode   `android:versionCode` value; -1 if not present.
 * @property mainActivity  Fully-qualified class name of the launcher activity.
 * @property apkPath       Absolute path to the copied base APK inside Orbital's files dir.
 * @property splitApkPaths Absolute paths to any split APKs (empty for plain APKs).
 * @property nativeLibDir  Absolute path to the extracted native lib dir, or null if the
 *                         APK has no native code.
 * @property targetSdk     Guest's targetSdkVersion; used by the engine to synthesize
 *                         the guest's ApplicationInfo.
 */
data class GuestManifest(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val mainActivity: String,
    val apkPath: String,
    val splitApkPaths: List<String>,
    val nativeLibDir: String?,
    val targetSdk: Int,
)
