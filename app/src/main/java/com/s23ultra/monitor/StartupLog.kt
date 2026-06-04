package com.s23ultra.monitor

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight breadcrumb logger for diagnosing startup crashes.
 *
 * Writes timestamped lines to:
 *   <external-files-dir>/startup.log
 *
 * On a Samsung device this resolves to:
 *   /sdcard/Android/data/com.s23ultra.monitor/files/startup.log
 *
 * The file is readable from "My Files" without root or ADB.
 * Falls back to internal filesDir if external storage is unavailable.
 *
 * Usage: call StartupLog.step(ctx, "tag") at each major checkpoint.
 * In MainActivity, call StartupLog.showDialogIfCrashPending(this) to
 * surface the crash content as an AlertDialog before any other UI logic.
 */
object StartupLog {

    private const val LOG_FILE   = "startup.log"
    private const val CRASH_FILE = "last_crash.txt"
    private const val MAX_BYTES  = 60_000L

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // ── Write a breadcrumb ────────────────────────────────────────────────────

    fun step(ctx: Context, message: String) {
        try {
            val file = logFile(ctx)
            val line = "${fmt.format(Date())}  $message\n"
            file.appendText(line)
            trimIfNeeded(file)
        } catch (_: Throwable) {}
    }

    // ── Write a crash report (called from MonitorApp's UncaughtExceptionHandler)

    fun writeCrash(ctx: Context, report: String) {
        try {
            crashFile(ctx).writeText(report)
        } catch (_: Throwable) {}
    }

    // ── Read back ─────────────────────────────────────────────────────────────

    fun readLog(ctx: Context): String? = try {
        val f = logFile(ctx)
        if (f.exists()) f.readText().takeLast(4_000) else null
    } catch (_: Throwable) { null }

    fun readCrash(ctx: Context): String? = try {
        val f = crashFile(ctx)
        if (f.exists()) f.readText().take(3_000) else null
    } catch (_: Throwable) { null }

    fun deleteCrash(ctx: Context) {
        try { crashFile(ctx).delete() } catch (_: Throwable) {}
    }

    /** Absolute path shown to the user so they know where to look in My Files. */
    fun logPath(ctx: Context): String = logFile(ctx).absolutePath

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun dir(ctx: Context): File =
        ctx.getExternalFilesDir(null) ?: ctx.filesDir

    private fun logFile(ctx: Context)   = File(dir(ctx), LOG_FILE)
    private fun crashFile(ctx: Context) = File(dir(ctx), CRASH_FILE)

    private fun trimIfNeeded(file: File) {
        if (file.length() > MAX_BYTES) {
            val trimmed = file.readLines().takeLast(300).joinToString("\n") + "\n"
            file.writeText(trimmed)
        }
    }
}
