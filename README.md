# Orbital

A virtual space runtime for Android. Imported APKs launch inside Orbital's own
process tree without being installed on the real device, so GameGuardian and
other debuggers can attach to the game's memory.

## Status

**Work in progress** — the minimum viable flow is in place:

- Import APK / XAPK from file picker
- Parse manifest, cache in Orbital's private storage
- Launch guest into one of 10 stub processes (`:p0` through `:p9`)
- `ActivityThread` hook rewrites the intent so Android instantiates the real
  guest Activity with a real `Token`, real window, real lifecycle

## Architecture

See [`docs/architecture.md`](docs/architecture.md) for the design. High level:

```
ui/          Jetpack Compose UI (Home, Import, Settings)
host/        App registry + local file storage
engine/      Stub processes + ActivityThread hook + guest class loading
core/        Pure Kotlin types used everywhere
```

## Out of scope

- Play Integrity / SafetyNet attestation
- Multi-user / multi-profile isolation
- Guest-declared services, content providers, broadcast receivers (v2)
- Games with kernel-level anti-cheat

## Build

```
./gradlew assembleDebug
```

Requires JDK 17. Android SDK + NDK are provisioned by AGP on first build.
