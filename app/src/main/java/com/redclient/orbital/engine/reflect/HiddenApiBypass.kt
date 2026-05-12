package com.redclient.orbital.engine.reflect

import android.os.Build
import timber.log.Timber
import java.lang.reflect.Method

/**
 * Disables Android's hidden-API restrictions for the current process.
 *
 * On API 28+, apps targeting SDK 28+ are denied reflective access to
 * non-SDK framework internals. Our engine needs ActivityThread.mH,
 * Handler.mCallback, and Instrumentation — all blocked.
 *
 * ## Why the previous approach failed
 *
 * The naive "meta-reflection" trick calls Class.getDeclaredMethod to
 * resolve VMRuntime.setHiddenApiExemptions. But on Android 13 (API 33),
 * even THAT lookup is blocked because the hidden-API enforcement catches
 * the getDeclaredMethod call itself when the target is a core-platform
 * method.
 *
 * ## The working approach (Android 9–14 inclusive)
 *
 * We use `Class.forName` + `Class.getMethod` on the `Method` class to
 * obtain `Method.invoke`. Then we use `Class.getMethod("getDeclaredMethod",...)`
 * on `Class` itself — this is ALWAYS allowed because `Class` is a public
 * SDK class. We then invoke THAT method object to resolve the hidden
 * VMRuntime methods. The key insight: when we call `method.invoke(null, ...)`
 * the hidden-API check sees the CALLER as the framework's own Method class
 * (not our app), so it passes.
 *
 * This is the technique used by:
 * - LSPosed's libxposed_art
 * - tiann/FreeReflection
 * - VirtualXposed
 * - ChickenHook/RestrictionBypass
 *
 * Tested on Android 9 (P) through Android 14 (U).
 */
internal object HiddenApiBypass {

    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun install() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!installed.compareAndSet(false, true)) return

        try {
            exemptAll()
            Timber.i("HiddenApiBypass: installed successfully")
        } catch (t: Throwable) {
            Timber.e(t, "HiddenApiBypass: FAILED — trying fallback")
            installed.set(false)
            // Fallback: try the unsealedApis approach
            try {
                fallbackUnseal()
                installed.set(true)
                Timber.i("HiddenApiBypass: fallback (unseal) succeeded")
            } catch (t2: Throwable) {
                Timber.e(t2, "HiddenApiBypass: all approaches failed")
            }
        }
    }

    /**
     * Primary approach: double-indirect via Method.invoke to call
     * VMRuntime.setHiddenApiExemptions.
     *
     * The trick: we use getDeclaredMethod obtained via the Class class
     * itself (which is always accessible), then invoke it to get the
     * hidden method. The runtime's caller-sensitivity check sees
     * java.lang.reflect.Method as the immediate caller, not our app.
     */
    private fun exemptAll() {
        // Step 1: Get Method objects we need using only public APIs
        val forNameMethod: Method = Class::class.java.getMethod(
            "forName", String::class.java
        )
        val getDeclaredMethodMethod: Method = Class::class.java.getMethod(
            "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java
        )

        // Step 2: Use forName to get VMRuntime class
        // forName itself is a public API — always works
        val vmRuntimeClass = forNameMethod.invoke(null, "dalvik.system.VMRuntime") as Class<*>

        // Step 3: Use getDeclaredMethod (obtained from Class.class above,
        // so the caller in the stack is java.lang.reflect.Method, not us)
        // to resolve getRuntime and setHiddenApiExemptions
        val getRuntime = getDeclaredMethodMethod.invoke(
            vmRuntimeClass, "getRuntime", emptyArray<Class<*>>()
        ) as Method

        val setExemptions = getDeclaredMethodMethod.invoke(
            vmRuntimeClass, "setHiddenApiExemptions", arrayOf<Class<*>>(arrayOf<String>()::class.java)
        ) as Method

        // Step 4: Make them accessible and invoke
        getRuntime.isAccessible = true
        setExemptions.isAccessible = true

        val runtime = getRuntime.invoke(null)
        // "L" prefix matches all reference types — exempts everything
        setExemptions.invoke(runtime, arrayOf("L") as Any)
    }

    /**
     * Fallback: set the hidden API enforcement policy to NOCHECK via
     * dalvik.system.VMRuntime or the system property.
     *
     * This works on some devices/ROMs where the primary approach fails
     * due to additional restrictions (Samsung Knox, Huawei EMUI, etc.).
     */
    private fun fallbackUnseal() {
        // Approach: use Unsafe to write directly to the runtime's
        // hidden API policy field. This is more invasive but works on
        // devices that block even the double-indirect technique.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }.get(null)

        val objectFieldOffset = unsafeClass.getMethod(
            "objectFieldOffset", java.lang.reflect.Field::class.java
        )
        val putInt = unsafeClass.getMethod(
            "putInt", Any::class.java, Long::class.java, Int::class.java
        )

        // On Android 12+, the enforcement lives in VMRuntime's
        // targetSdkVersion field. Setting it to 27 (below P) disables
        // hidden API checks for this process.
        val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
        val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime").apply {
            isAccessible = true
        }
        val runtime = getRuntime.invoke(null)

        // Find targetSdkVersion field
        val targetSdkField = vmRuntimeClass.getDeclaredField("targetSdkVersion")
        val offset = objectFieldOffset.invoke(theUnsafe, targetSdkField) as Long

        // Set to 27 (pre-P) — disables hidden API enforcement
        putInt.invoke(theUnsafe, runtime, offset, 27)
    }
}
