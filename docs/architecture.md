# Orbital Architecture

## Module layout (all in `app/`)

```
com.redclient.orbital.core       Pure types, zero Android dependencies
com.redclient.orbital.host       App registry, slot allocation, persistence
com.redclient.orbital.engine     Stub processes, ActivityThread hook, guest loader
com.redclient.orbital.ui         Compose UI (Home, Import, Settings)
com.redclient.orbital            OrbitalApp entry point + DI wiring
```

## Launch flow

1. User taps Launch on a guest.
2. `GuestLauncher.launch(pkg)` in the host process:
   - Reads `GuestManifest` from the registry
   - `SlotAllocator.acquire(pkg)` returns a free `:pN`
   - Writes `slot_N.hint` file with the guest package name
   - Calls `context.startActivity(intent)` pointing at `StubActivity_PN_Std`,
     stashing the real guest component in extras
3. Android forks `:pN` if not running, calls `OrbitalApp.onCreate`.
4. `OrbitalApp` detects the `:pN` process, reads `slot_N.hint`, and calls
   `StubBootstrap.bootstrap(guestPackage)`.
5. `StubBootstrap`:
   - Loads the guest APK via `GuestLoader`:
     - `DexClassLoader` pointing at the APK + splits, native lib dir
     - `AssetManager.addAssetPath` for the guest's resources
     - `System.load` for each .so in the correct ABI
   - Installs `HCallback` as `Handler.Callback` on `ActivityThread.mH`
   - Replaces `ActivityThread.mInstrumentation` with `GuestInstrumentation`
6. Android's `ActivityThread.H` dispatches `EXECUTE_TRANSACTION` /
   `LAUNCH_ACTIVITY` for the stub. `HCallback` rewrites:
   - `Intent.component` -> real guest component
   - `ActivityInfo.name`, `ApplicationInfo.sourceDir`, etc.
7. `GuestInstrumentation.newActivity` is called with the guest class name.
   It loads the class from the guest's `DexClassLoader` and returns an
   instance.
8. Android's framework does the real `Activity.attach(Token, Window, ...)`.
   The guest's `onCreate` runs. Views render. Touch, GL, everything works.

## Why this works for GameGuardian

The guest's `.dex` files are loaded into `:pN` via `DexClassLoader`, and its
`.so` files are loaded via `System.load`. Both result in real memory regions
in `/proc/{pN_pid}/maps` with the correct rwxp permissions. GG sees them the
same way it would see a normally-installed game.

## Why in-process reflection doesn't work

Previous attempts instantiated `Activity` with `Class.newInstance()` and
called `onCreate` via reflection. That produces a blank screen because
`Activity` requires `attach(Token, Window, Instrumentation, ActivityThread,
...)` with internal state only `ActivityThread.performLaunchActivity` can
provide. The only working approach is to let the framework drive
`performLaunchActivity` normally and redirect it to the guest class at the
two correct interception points (H message dispatch + Instrumentation).
