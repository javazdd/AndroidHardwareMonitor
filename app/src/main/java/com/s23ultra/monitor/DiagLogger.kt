package com.s23ultra.monitor

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Fire-and-forget diagnostic logger that sends structured log entries to the
 * Datadog Logs v2 intake endpoint when debug logging is enabled in settings.
 *
 * All sends are offloaded to a single daemon executor thread so callers are
 * never blocked. DiagLogger reuses DatadogClient's shared OkHttpClient to
 * avoid spinning up an additional thread pool.
 *
 * Nothing is ever written to Logcat — this logger exists solely to surface
 * in-app diagnostics inside the backend when something is going wrong.
 */
object DiagLogger {

    enum class Level(val ddStatus: String) {
        DEBUG("debug"),
        INFO("info"),
        WARN("warn"),
        ERROR("error"),
    }

    /**
     * Single daemon thread — serialises log sends without blocking callers.
     * Daemon status means it will not prevent the process from exiting.
     */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-logger").apply { isDaemon = true }
    }

    private val mediaType = "application/json".toMediaType()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Enqueue a log entry. Returns immediately; the send happens on the
     * background thread. No-ops if debug logging is disabled in settings
     * or if the API key is not yet configured.
     *
     * @param error   Optional throwable — its class name and truncated message
     *                are appended to [message]. The full stack trace is NOT sent
     *                (excessive size; class+message is enough to diagnose).
     */
    fun log(
        ctx: Context,
        level: Level,
        message: String,
        error: Throwable? = null,
    ) {
        if (!AppConfig.isDebugLoggingEnabled(ctx)) return
        val appCtx = ctx.applicationContext
        executor.execute { send(appCtx, level, message, error) }
    }

    /**
     * Send a crash report regardless of the debug-logging toggle.
     * Only requires a configured API key — crash reports are always worth
     * sending when the backend is reachable.
     * Called by MonitorApp after reading a crash file written by the
     * UncaughtExceptionHandler on the previous run.
     */
    fun logCrash(ctx: Context, report: String) {
        if (AppConfig.apiKey(ctx).isBlank()) return
        val appCtx = ctx.applicationContext
        executor.execute { send(appCtx, Level.ERROR, report, null) }
    }

    // ── Internal send ─────────────────────────────────────────────────────────

    private fun send(ctx: Context, level: Level, message: String, error: Throwable?) {
        val apiKey = AppConfig.apiKey(ctx)
        if (apiKey.isBlank()) return

        val fullMessage = buildString {
            append(message)
            if (error != null) {
                append(" | ")
                append(error.javaClass.simpleName)
                val msg = error.message?.take(400)
                if (!msg.isNullOrBlank()) {
                    append(": ")
                    append(msg)
                }
            }
        }

        val deviceId = AppConfig.deviceTag(ctx).removePrefix("device:")
        val tags     = AppConfig.allTags(ctx).joinToString(",")

        val payload = JSONArray().put(
            JSONObject().apply {
                put("ddsource", "android-hardware-monitor")
                put("service",  "hardware-monitor")
                put("hostname", deviceId)
                put("message",  fullMessage)
                put("status",   level.ddStatus)
                put("ddtags",   tags)
            }
        ).toString()

        val url = "https://http-intake.logs.${AppConfig.site(ctx)}/api/v2/logs"
        val request = Request.Builder()
            .url(url)
            .addHeader("DD-API-KEY", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(mediaType))
            .build()

        try {
            // Use the shared OkHttpClient to avoid a second thread pool.
            DatadogClient.http.newCall(request).execute().use { }
        } catch (_: Exception) {
            // Silently swallow — never log a logging failure (infinite loop risk).
        }
    }
}
