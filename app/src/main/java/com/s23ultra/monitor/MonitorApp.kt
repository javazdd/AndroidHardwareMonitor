package com.s23ultra.monitor

import android.app.Application
import java.io.File

/**
 * Custom Application class.
 *
 * Installs a global UncaughtExceptionHandler on every process start.
 * When the JVM has an unhandled exception (crash), the handler writes a
 * compact crash report to internal storage before handing off to the
 * platform default handler (which shows the "App has stopped" dialog).
 *
 * On the next successful launch, the report is read, sent to the backend
 * as a crash-level log, and deleted. Because the send requires a configured
 * API key, the report is held on disk until the app is provisioned — it will
 * not accumulate; at most one file exists at a time (the latest crash).
 */
class MonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        sendPendingCrashReport()
    }

    // ── Crash capture ─────────────────────────────────────────────────────────

    private fun installCrashHandler() {
        val platform = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildReport(thread, throwable)
                File(filesDir, CRASH_FILE).writeText(report)
            } catch (_: Throwable) {
                // Never let the crash handler itself crash — silently skip file write.
            }
            // Always invoke the platform handler so Android can show its dialog
            // and record the crash in the system log.
            platform?.uncaughtException(thread, throwable)
        }
    }

    private fun buildReport(thread: Thread, t: Throwable): String = buildString {
        append("thread=${thread.name}\n")
        append("exception=${t.javaClass.name}\n")
        val msg = t.message?.take(500)
        if (!msg.isNullOrBlank()) append("message=$msg\n")
        append("stack=\n")
        // First 20 frames are usually enough to pinpoint the cause.
        t.stackTrace.take(20).forEach { frame -> append("  at $frame\n") }
        val cause = t.cause
        if (cause != null) {
            append("caused_by=${cause.javaClass.name}: ${cause.message?.take(300)}\n")
        }
    }

    // ── Crash replay ──────────────────────────────────────────────────────────

    private fun sendPendingCrashReport() {
        if (!AppConfig.isConfigured(this)) return   // can't send without an API key

        val file = File(filesDir, CRASH_FILE)
        if (!file.exists()) return

        val report = try {
            file.readText().take(3_000)
        } catch (_: Exception) {
            file.delete()
            return
        }

        // Delete before sending — we never want to re-send a stale crash on
        // every subsequent launch if the network send happens to fail.
        file.delete()

        if (report.isNotBlank()) {
            DiagLogger.logCrash(this, "Crash report from previous session:\n$report")
        }
    }

    companion object {
        private const val CRASH_FILE = "last_crash.txt"
    }
}
