package com.redclient.orbital.engine.stub

import com.redclient.orbital.engine.loader.GuestLoader
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-process state for the stub, held in an [AtomicReference] so the
 * main-thread Handler callback and the Instrumentation shim can observe
 * updates safely without a lock.
 *
 * There is exactly one instance per stub process (a `:pN` process runs in
 * isolation from every other one). The host process never touches this.
 */
internal object StubState {

    private val _bundle = AtomicReference<GuestLoader.Bundle?>(null)

    /** The loaded guest bundle, or null before [StubBootstrap] has run. */
    val bundle: GuestLoader.Bundle? get() = _bundle.get()

    fun setBundle(value: GuestLoader.Bundle) {
        _bundle.set(value)
    }
}
