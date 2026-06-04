package com.s23ultra.monitor

import android.content.Context

/**
 * Runtime configuration backed by SharedPreferences.
 * All keys are user-supplied via SettingsActivity — nothing is hardcoded.
 */
object AppConfig {

    private const val PREFS         = "datadog_config"
    private const val KEY_API_KEY   = "api_key"
    private const val KEY_SITE      = "site"
    private const val KEY_DEVICE_ID = "device_id"

    enum class Site(val label: String, val host: String) {
        US1("AWS — US1",   "datadoghq.com"),
        US5("GCP — US5",   "us5.datadoghq.com"),
        US3("Azure — US3", "us3.datadoghq.com"),
    }

    fun apiKey(ctx: Context): String =
        prefs(ctx).getString(KEY_API_KEY, "") ?: ""

    fun site(ctx: Context): String =
        prefs(ctx).getString(KEY_SITE, Site.US1.host) ?: Site.US1.host

    fun deviceTag(ctx: Context): String {
        val id = prefs(ctx).getString(KEY_DEVICE_ID, "")?.trim()
        return if (id.isNullOrEmpty()) "device:honeywell_ct47" else "device:$id"
    }

    fun isConfigured(ctx: Context): Boolean = apiKey(ctx).isNotBlank()

    fun save(ctx: Context, apiKey: String, siteHost: String, deviceId: String) {
        prefs(ctx).edit()
            .putString(KEY_API_KEY,   apiKey.trim())
            .putString(KEY_SITE,      siteHost)
            .putString(KEY_DEVICE_ID, deviceId.trim())
            .apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
