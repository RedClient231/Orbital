package com.redclient.orbital.engine.stub

import android.app.Activity
import android.os.Bundle
import timber.log.Timber

/**
 * Fallback base class shared by every generated stub Activity.
 *
 * Under normal operation, the framework never actually instantiates one
 * of these: [com.redclient.orbital.engine.hook.HCallback] has already
 * rewritten the ActivityInfo by the time the framework looks up the class,
 * so [com.redclient.orbital.engine.hook.GuestInstrumentation] hands it the
 * guest's class instead.
 *
 * If you *do* end up here, something went wrong — the hook failed to
 * install, or the stub was launched without a guest component extra. We
 * log loudly and finish so the user isn't stuck on a blank screen.
 */
open class StubActivityBase : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.w(
            "StubActivityBase: unexpectedly reached onCreate for %s — hook didn't fire",
            javaClass.name,
        )
        finish()
    }
}
