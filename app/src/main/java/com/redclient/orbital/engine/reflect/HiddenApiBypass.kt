package com.redclient.orbital.engine.reflect

import android.os.Build
import timber.log.Timber

/**
 * Disables Android's hidden-API restrictions for the current process.
 *
 * On API 28 and later, apps with `targetSdkVersion >= 28` are denied
 * reflective access to "non-SDK" framework internals — including exactly
 * the classes our engine depends on (`ActivityThread`, `H`,
 * `LaunchActivityItem`, `Instrumentation.mInstrumentation`, ...).
 *
 * Reflection against those members silently returns null (or throws
 * NoSuchFieldException with a warning in logcat), which means none of
 * our hooks install and the stub process falls through to
 * [com.redclient.orbital.engine.stub.StubActivityBase.finish].
 *
 * The standard workaround is the "meta-reflection" trick: the
 * `ClassLoader.getDeclaredMethod` reflection entry points themselves are
 * not subject to the hidden-API check, so we use THEM to obtain a handle
 * to `VMRuntime.setHiddenApiExemptions(String[])` and then invoke it
 * with `"L"` to exempt every descriptor. This is the same technique
 * LSPosed, VirtualXposed, and the community's FreeReflection library use.
 *
 * Must be called **before** any direct reflection on framework classes.
 * A good place is the first statement of `Application.onCreate()`.
 *
 * No-op on API < 28 (hidden-API restrictions didn't exist yet).
 */
internal object HiddenApiBypass {

    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun install() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!installed.compareAndSet(false, true)) return

        val ok = runCatching { exemptAllDescriptors() }
        if (ok.isSuccess) {
            Timber.i("HiddenApiBypass: installed")
        } else {
            Timber.e(ok.exceptionOrNull(), "HiddenApiBypass: failed — framework reflection will fail")
            installed.set(false) // allow a retry
        }
    }

    /**
     * Calls `VMRuntime.getRuntime().setHiddenApiExemptions(new String[]{"L"})`
     * using only reflection on java.lang.Class — which is itself exempt.
     *
     * The "L" descriptor is the JNI prefix for reference types and matches
     * anything — VMRuntime interprets the list as a set of
     * startsWith-prefixes applied against every internal name.
     */
    private fun exemptAllDescriptors() {
        // Step 1: reach `Class.forName` and `Class.getDeclaredMethod` via
        // the reflection bootstrap entry points that aren't blocked.
        val forName = Class::class.java.getDeclaredMethod(
            "forName", String::class.java,
        )

        val getDeclaredMethod = Class::class.java.getDeclaredMethod(
            "getDeclaredMethod",
            String::class.java,
            arrayOf<Class<*>>()::class.java,
        )

        // Step 2: resolve VMRuntime.getRuntime and VMRuntime.setHiddenApiExemptions.
        val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>

        val getRuntime = (getDeclaredMethod.invoke(
            vmRuntimeClass,
            "getRuntime",
            emptyArray<Class<*>>(),
        ) as java.lang.reflect.Method).apply { isAccessible = true }

        val setExemptions = (getDeclaredMethod.invoke(
            vmRuntimeClass,
            "setHiddenApiExemptions",
            arrayOf<Class<*>>(Array<String>::class.java),
        ) as java.lang.reflect.Method).apply { isAccessible = true }

        // Step 3: invoke.
        val runtime = getRuntime.invoke(null)
        setExemptions.invoke(runtime, arrayOf(arrayOf("L")))
    }
}
