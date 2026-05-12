package com.redclient.orbital

import android.content.Context
import com.redclient.orbital.engine.GuestLauncher
import com.redclient.orbital.host.ApkImporter
import com.redclient.orbital.host.GuestRegistry
import com.redclient.orbital.host.OrbitalPaths
import com.redclient.orbital.host.SlotAllocator

/**
 * Hand-rolled service locator for Orbital's host-process singletons.
 *
 * We avoid Hilt for now — the graph is small (5 objects), construction is
 * cheap, and skipping the annotation processor keeps clean build times
 * under a minute. If the graph grows past ~10 entries we can migrate.
 *
 * All fields are initialised lazily so accessing one doesn't pull in the
 * others unless they're actually needed (e.g. the UI can read the
 * registry without touching the launcher).
 */
class OrbitalGraph(applicationContext: Context) {

    val paths: OrbitalPaths by lazy { OrbitalPaths(applicationContext) }

    val registry: GuestRegistry by lazy { GuestRegistry(paths) }

    val slots: SlotAllocator by lazy { SlotAllocator() }

    val importer: ApkImporter by lazy {
        ApkImporter(applicationContext, paths, registry)
    }

    val launcher: GuestLauncher by lazy {
        GuestLauncher(applicationContext, registry, slots, paths)
    }
}
