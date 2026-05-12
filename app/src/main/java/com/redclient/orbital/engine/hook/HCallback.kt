package com.redclient.orbital.engine.hook

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Message
import com.redclient.orbital.engine.reflect.Reflect
import com.redclient.orbital.engine.stub.StubActivities
import com.redclient.orbital.engine.stub.StubState
import timber.log.Timber

/**
 * First-line handler for messages dispatched to `ActivityThread.mH`.
 *
 * On modern Android, every activity lifecycle change arrives at
 * `ActivityThread.H` as either:
 *  - `EXECUTE_TRANSACTION` (code 159), with payload
 *    `android.app.servertransaction.ClientTransaction` — API 28+.
 *  - The legacy `LAUNCH_ACTIVITY` (code 100) with payload
 *    `ActivityThread$ActivityClientRecord` — still used on some paths.
 *
 * We install ourselves as `mH.mCallback` so we get first look at each
 * message before `H.handleMessage` does its default dispatch. Returning
 * `false` lets the framework continue its normal handling — we never
 * *consume* messages, we only rewrite them in place.
 *
 * For launches targeting our stub, we:
 *  1. Read the original guest component from the intent extras
 *  2. Overwrite `intent.component` with the real guest component
 *  3. Overwrite the `ActivityInfo.name` and the nested `ApplicationInfo`
 *     with guest values — so the framework's class loader looks up the
 *     guest class and `GuestInstrumentation.newActivity` is called with
 *     the guest's fully-qualified class name.
 *
 * The companion [GuestInstrumentation] finishes the job by loading that
 * class from the guest's `DexClassLoader`.
 */
class HCallback : Handler.Callback {

    override fun handleMessage(msg: Message): Boolean {
        if (msg.what == EXECUTE_TRANSACTION) rewriteClientTransaction(msg)
        else if (msg.what == LAUNCH_ACTIVITY) rewriteLegacyLaunch(msg)
        return false // always fall through to H.handleMessage
    }

    // -- Modern path: ClientTransaction with a list of LaunchActivityItems -

    private fun rewriteClientTransaction(msg: Message) {
        val transaction = msg.obj ?: return
        val callbacksList = Reflect.read<List<*>>(
            transaction, transaction.javaClass, "mActivityCallbacks"
        ) ?: return

        for (callback in callbacksList) {
            if (callback == null) continue
            val clazz = callback.javaClass
            // LaunchActivityItem is the only type we rewrite; other items
            // (pause, resume, relaunch) don't carry a launch intent.
            if (clazz.simpleName != "LaunchActivityItem") continue
            rewriteOne(callback, clazz, intentField = "mIntent", infoField = "mInfo")
        }
    }

    // -- Legacy path: ActivityClientRecord direct in msg.obj --------------

    private fun rewriteLegacyLaunch(msg: Message) {
        val record = msg.obj ?: return
        rewriteOne(
            record, record.javaClass, intentField = "intent", infoField = "activityInfo",
        )
    }

    // -- Shared rewriting logic -------------------------------------------

    private fun rewriteOne(
        item: Any,
        itemClass: Class<*>,
        intentField: String,
        infoField: String,
    ) {
        val intent = Reflect.read<Intent>(item, itemClass, intentField) ?: return
        val component = intent.component ?: return

        // Only act if this is one of our stub classes
        val slotAndMode = StubActivities.parse(component.className) ?: return
        val guestComponent = intent.getParcelableExtra<ComponentName>(EXTRA_GUEST_COMPONENT)
            ?: run {
                Timber.w("HCallback: stub %s with no guest component extra", component)
                return
            }

        val bundle = StubState.bundle
        if (bundle == null) {
            Timber.w("HCallback: stub %s fired before guest bundle loaded", component)
            return
        }

        // -- Rewrite the intent ---
        val rewrittenIntent = Intent(intent).apply {
            setComponent(guestComponent)
            // Fold the originally-requested action back in, if we preserved it
            getStringExtra(EXTRA_GUEST_ACTION)?.let { action = it }
            // Strip our internal extras so the guest doesn't see them
            removeExtra(EXTRA_GUEST_COMPONENT)
            removeExtra(EXTRA_GUEST_ACTION)
        }
        Reflect.write(item, itemClass, intentField, rewrittenIntent)

        // -- Rewrite the ActivityInfo ---
        val stubInfo = Reflect.read<ActivityInfo>(item, itemClass, infoField) ?: return
        val rewrittenInfo = ActivityInfo(stubInfo).apply {
            name = guestComponent.className
            packageName = guestComponent.packageName
            applicationInfo = synthesizeAppInfo(stubInfo.applicationInfo, bundle.manifest.let { m ->
                SyntheticApp(
                    pkg = m.packageName,
                    apkPath = m.apkPath,
                    splits = m.splitApkPaths,
                    nativeLibDir = m.nativeLibDir,
                    targetSdk = m.targetSdk,
                )
            })
        }
        Reflect.write(item, itemClass, infoField, rewrittenInfo)

        Timber.i(
            "HCallback: rewrote slot=%d stub=%s -> guest=%s",
            slotAndMode.slotIndex,
            component.className,
            guestComponent,
        )
    }

    private data class SyntheticApp(
        val pkg: String,
        val apkPath: String,
        val splits: List<String>,
        val nativeLibDir: String?,
        val targetSdk: Int,
    )

    /**
     * Synthesises an [ApplicationInfo] by cloning the stub's own and
     * overriding the guest-sensitive fields. We keep the stub's processName
     * and uid so the framework's existing process-binding state stays valid.
     */
    private fun synthesizeAppInfo(stub: ApplicationInfo, g: SyntheticApp) =
        ApplicationInfo(stub).apply {
            packageName = g.pkg
            sourceDir = g.apkPath
            publicSourceDir = g.apkPath
            splitSourceDirs = g.splits.toTypedArray()
            splitPublicSourceDirs = g.splits.toTypedArray()
            if (g.nativeLibDir != null) nativeLibraryDir = g.nativeLibDir
            targetSdkVersion = g.targetSdk
            // Clear the guest's custom Application class name, if any —
            // we have no way to instantiate a guest Application here without
            // further hooking. Most games don't have one.
            className = null
            name = null
            // Ensure the framework doesn't mark it as "not installed".
            flags = flags or ApplicationInfo.FLAG_INSTALLED or ApplicationInfo.FLAG_HAS_CODE
        }

    companion object {
        // Message codes from `android.app.ActivityThread$H`. These have been
        // stable across Android versions 8..14 despite being @hide.
        private const val LAUNCH_ACTIVITY = 100
        private const val EXECUTE_TRANSACTION = 159

        /** Key used when packing the guest component into the stub intent. */
        const val EXTRA_GUEST_COMPONENT = "com.redclient.orbital.extra.GUEST_COMPONENT"

        /** Key used when preserving the original Intent action. */
        const val EXTRA_GUEST_ACTION = "com.redclient.orbital.extra.GUEST_ACTION"
    }
}
