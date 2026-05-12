package com.redclient.orbital.engine.hook

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import com.redclient.orbital.engine.stub.StubState
import timber.log.Timber

/**
 * Drop-in replacement for `ActivityThread.mInstrumentation`, installed by
 * [com.redclient.orbital.engine.stub.StubBootstrap] in every stub process.
 *
 * The framework's [Instrumentation.newActivity] is what actually instantiates
 * an Activity class by name — `ActivityThread.performLaunchActivity` calls
 * it right after receiving a launch message from `system_server`. That gives
 * us the exact interception point we need to substitute the guest's class.
 *
 * Flow for a guest activity launch:
 *  1. HCallback has already rewritten the intent + ActivityInfo to point at
 *     the guest's component (see [HCallback]).
 *  2. The framework eventually calls `newActivity(cl, className, intent)`
 *     where `className` is now the guest's fully-qualified class.
 *  3. We detect that by package-prefix and load the class via the guest's
 *     DexClassLoader from [StubState].
 *  4. We return a fresh instance — the framework then does the rest of the
 *     launch (`attach`, `onCreate`, ...).
 */
class GuestInstrumentation(
    private val delegate: Instrumentation,
) : Instrumentation() {

    override fun newActivity(
        cl: ClassLoader?,
        className: String,
        intent: Intent?,
    ): Activity {
        val bundle = StubState.bundle

        if (bundle != null && className.startsWith(bundle.manifest.packageName)) {
            try {
                val guestClass = bundle.classLoader.loadClass(className)
                Timber.i("GuestInstrumentation: instantiating %s from guest loader", className)
                return guestClass.getDeclaredConstructor().newInstance() as Activity
            } catch (t: Throwable) {
                Timber.e(t, "GuestInstrumentation: could not load %s", className)
                // Fall through to default loader — produces a clear ClassNotFoundException
                // we can surface to the user instead of a blank screen.
            }
        }

        return super.newActivity(cl, className, intent)
    }

    // --- Delegate every other lifecycle callback to the original -------

    override fun callActivityOnCreate(activity: Activity, icicle: android.os.Bundle?) =
        delegate.callActivityOnCreate(activity, icicle)

    override fun callActivityOnCreate(
        activity: Activity,
        icicle: android.os.Bundle?,
        persistentState: android.os.PersistableBundle?,
    ) = delegate.callActivityOnCreate(activity, icicle, persistentState)

    override fun callActivityOnStart(activity: Activity) = delegate.callActivityOnStart(activity)
    override fun callActivityOnRestart(activity: Activity) = delegate.callActivityOnRestart(activity)
    override fun callActivityOnResume(activity: Activity) = delegate.callActivityOnResume(activity)
    override fun callActivityOnPause(activity: Activity) = delegate.callActivityOnPause(activity)
    override fun callActivityOnStop(activity: Activity) = delegate.callActivityOnStop(activity)
    override fun callActivityOnDestroy(activity: Activity) = delegate.callActivityOnDestroy(activity)
    override fun callActivityOnSaveInstanceState(activity: Activity, outState: android.os.Bundle) =
        delegate.callActivityOnSaveInstanceState(activity, outState)
    override fun callActivityOnSaveInstanceState(
        activity: Activity,
        outState: android.os.Bundle,
        outPersistentState: android.os.PersistableBundle,
    ) = delegate.callActivityOnSaveInstanceState(activity, outState, outPersistentState)
}
