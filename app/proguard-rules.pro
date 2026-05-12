# Orbital relies heavily on reflection against framework internals
# (ActivityThread, Instrumentation, Handler.mCallback). R8 must not rename
# those field/method names in guest-side reflection targets.
# Note: we don't actually pull framework classes into the APK — these rules
# simply make sure any internal-facing reflection strings we construct at
# runtime keep their literal form.

# Keep all reflection helpers used by stub bootstrap
-keep class com.redclient.orbital.engine.reflect.** { *; }

# Keep stub Activities by name — the manifest references 40 of them
-keep class com.redclient.orbital.engine.stub.StubActivity* { *; }

# Keep guest-facing model classes (Room-less JSON models)
-keep class com.redclient.orbital.host.model.** { *; }

# Standard Kotlin + Compose
-keepclassmembers class **$Companion { *; }
-dontwarn kotlinx.**
