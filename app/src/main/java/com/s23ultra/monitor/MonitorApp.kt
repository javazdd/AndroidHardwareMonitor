package com.s23ultra.monitor

import android.app.Application

class MonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        StartupLog.step(this, "MonitorApp.onCreate start")
        AppConfig.applyManagedConfig(this)
        StartupLog.step(this, "MDM config applied")
        installCrashHandler()
        StartupLog.step(this, "CrashHandler installed")
        sendPendingCrashReport()
        StartupLog.step(this, "MonitorApp.onCreate complete")
    }

    // ── Crash capture ─────────────────────────────────────────────────────────

    private fun installCrashHandler() {
        val platform = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildReport(thread, throwable)
                // Write to external files dir so the user can read it with My Files
                // without needing ADB. Path: Android/data/com.s23ultra.monitor/files/last_crash.txt
                StartupLog.writeCrash(this, report)
                StartupLog.step(this, "CRASH captured: ${throwable.javaClass.simpleName}: ${throwable.message?.take(120)}")
            } catch (_: Throwable) {}
            platform?.uncaughtException(thread, throwable)
        }
    }

    private fun buildReport(thread: Thread, t: Throwable): String = buildString {
        append("thread=${thread.name}\n")
        append("exception=${t.javaClass.name}\n")
        val msg = t.message?.take(500)
        if (!msg.isNullOrBlank()) append("message=$msg\n")
        append("stack=\n")
        t.stackTrace.take(20).forEach { frame -> append("  at $frame\n") }
        val cause = t.cause
        if (cause != null) {
            append("caused_by=${cause.javaClass.name}: ${cause.message?.take(300)}\n")
            cause.stackTrace.take(10).forEach { frame -> append("  at $frame\n") }
        }
    }

    // ── Crash replay ──────────────────────────────────────────────────────────

    private fun sendPendingCrashReport() {
        // UI display of the crash is handled by MainActivity (shows an AlertDialog).
        // Here we only handle sending to Datadog once the API key is configured.
        if (!AppConfig.isConfigured(this)) return

        val report = StartupLog.readCrash(this) ?: return
        StartupLog.deleteCrash(this)

        if (report.isNotBlank()) {
            DiagLogger.logCrash(this, "Crash report from previous session:\n$report")
        }
    }
}
