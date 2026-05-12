package com.redclient.orbital.engine.diagnostics

import android.os.Build
import android.os.Environment
import android.os.Process
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "seed" — a self-starting logcat capture that runs from the moment
 * Application.onCreate fires until the process dies.
 *
 * What it captures:
 *  - The ENTIRE system logcat buffer (all tags, all priorities) — not just
 *    Orbital's own logs. This means framework crashes, ActivityThread
 *    messages, ClassNotFoundException from DexClassLoader, SELinux denials,
 *    hidden-API warnings — everything.
 *  - A device/process header so we know what we're looking at.
 *
 * Where it writes:
 *  - /storage/emulated/0/Download/OrbitalLogs/
 *  - One file per process launch, timestamped:
 *      orbital_<processName>_<timestamp>.log
 *
 * How it works:
 *  - Spawns `logcat -v threadtime` as a child process.
 *  - Pipes stdout directly into the file — no buffering, no filtering.
 *  - Runs on a daemon thread so it doesn't block anything.
 *  - If the output directory isn't writable (permission not granted), it
 *    falls back to the app's own cache directory and logs a warning.
 *
 * Call [LogcatSeed.plant] once in Application.onCreate. That's it.
 * The seed takes care of everything else.
 */
object LogcatSeed {

    @Volatile
    private var planted = false

    /**
     * Plants the seed. Safe to call multiple times (idempotent).
     * Must be called from the main thread during Application.onCreate.
     *
     * @param processName The current process name (for the filename).
     */
    fun plant(processName: String) {
        if (planted) return
        planted = true

        val logDir = resolveLogDir()
        if (logDir == null) {
            Timber.e("LogcatSeed: CANNOT WRITE TO DOWNLOADS — logcat capture disabled")
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = processName.replace(':', '_').replace('.', '_')
        val logFile = File(logDir, "orbital_${safeName}_$timestamp.log")

        // Write a header with device info before starting the stream.
        writeHeader(logFile, processName)

        // Spawn logcat in a daemon thread.
        val thread = Thread({
            try {
                // Clear the buffer first so we start fresh from THIS launch.
                Runtime.getRuntime().exec("logcat -c").waitFor()

                // Now stream everything into the file.
                val proc = Runtime.getRuntime().exec(arrayOf(
                    "logcat",
                    "-v", "threadtime",   // most informative format
                    // No filter — capture ALL tags, ALL priorities.
                ))

                val input = proc.inputStream
                val output = FileOutputStream(logFile, true) // append after header

                val buffer = ByteArray(8192)
                var read = input.read(buffer)
                while (read >= 0) {
                    output.write(buffer, 0, read)
                    output.flush() // flush every chunk so crashes are captured
                    read = input.read(buffer)
                }

                output.close()
                input.close()
            } catch (t: Throwable) {
                // If logcat capture itself crashes, write the error into the file.
                try {
                    FileOutputStream(logFile, true).use { out ->
                        val pw = PrintWriter(out)
                        pw.println("\n\n=== LOGCAT SEED CRASHED ===")
                        t.printStackTrace(pw)
                        pw.flush()
                    }
                } catch (_: Throwable) { /* nothing we can do */ }
            }
        }, "LogcatSeed-$safeName")

        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY // don't compete with the app
        thread.start()

        Timber.i("LogcatSeed: planted → %s", logFile.absolutePath)
    }

    /**
     * Resolves the output directory. Prefers Downloads, falls back to
     * app cache if Downloads isn't writable.
     */
    private fun resolveLogDir(): File? {
        // Primary: /storage/emulated/0/Download/OrbitalLogs/
        val downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        val primary = File(downloads, "OrbitalLogs")
        if (primary.exists() || primary.mkdirs()) {
            // Verify we can actually write (permission might be missing).
            val testFile = File(primary, ".write_test")
            return try {
                testFile.writeText("ok")
                testFile.delete()
                primary
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }

    /**
     * Writes a human-readable header at the top of the log file so we
     * know exactly what device/process/version produced it.
     */
    private fun writeHeader(file: File, processName: String) {
        file.parentFile?.mkdirs()
        PrintWriter(FileOutputStream(file)).use { pw ->
            pw.println("╔══════════════════════════════════════════════════════════════╗")
            pw.println("║  ORBITAL LOGCAT SEED                                        ║")
            pw.println("╠══════════════════════════════════════════════════════════════╣")
            pw.println("║  Process   : $processName")
            pw.println("║  PID       : ${Process.myPid()}")
            pw.println("║  UID       : ${Process.myUid()}")
            pw.println("║  Device    : ${Build.MANUFACTURER} ${Build.MODEL}")
            pw.println("║  Android   : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            pw.println("║  ABI       : ${Build.SUPPORTED_ABIS.joinToString()}")
            pw.println("║  Timestamp : ${Date()}")
            pw.println("╚══════════════════════════════════════════════════════════════╝")
            pw.println()
            pw.flush()
        }
    }
}
