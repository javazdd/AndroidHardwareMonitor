package com.s23ultra.monitor

import android.content.Context
import android.os.Build

/**
 * Runtime configuration backed by SharedPreferences.
 * All keys are user-supplied via SettingsActivity — nothing is hardcoded.
 */
object AppConfig {

    private const val PREFS         = "monitor_config"
    private const val KEY_API_KEY   = "api_key"
    private const val KEY_SITE      = "site"
    private const val KEY_DEVICE_ID = "device_id"

    const val MAX_CUSTOM_TAGS = 5

    /** Allowed emission intervals in seconds, shown as-is in the settings dropdown. */
    val INTERVAL_OPTIONS = listOf(15L, 30L, 60L, 120L, 300L)

    enum class Site(val label: String, val host: String) {
        US1("AWS — US1",   "datadoghq.com"),
        US5("GCP — US5",   "us5.datadoghq.com"),
        US3("Azure — US3", "us3.datadoghq.com"),
    }

    // ── Core config ────────────────────────────────────────────────────────────

    fun apiKey(ctx: Context): String =
        prefs(ctx).getString(KEY_API_KEY, "") ?: ""

    fun site(ctx: Context): String =
        prefs(ctx).getString(KEY_SITE, Site.US1.host) ?: Site.US1.host

    fun deviceTag(ctx: Context): String {
        val id = prefs(ctx).getString(KEY_DEVICE_ID, "")?.trim()
        return if (id.isNullOrEmpty()) "device:unknown" else "device:$id"
    }

    fun isConfigured(ctx: Context): Boolean = apiKey(ctx).isNotBlank()

    fun isDebugLoggingEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("debug_logging", false)

    fun saveDebugLogging(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("debug_logging", enabled).apply()
    }

    fun pollIntervalSeconds(ctx: Context): Long =
        prefs(ctx).getLong("poll_interval_sec", 60L)
            .coerceIn(INTERVAL_OPTIONS.first(), INTERVAL_OPTIONS.last())

    fun save(ctx: Context, apiKey: String, siteHost: String, deviceId: String, intervalSec: Long) {
        prefs(ctx).edit()
            .putString(KEY_API_KEY,        apiKey.trim())
            .putString(KEY_SITE,           siteHost)
            .putString(KEY_DEVICE_ID,      deviceId.trim())
            .putLong("poll_interval_sec",  intervalSec)
            .apply()
    }

    // ── Hardware tags (auto-detected from Build.*) ─────────────────────────────
    // These are always appended to every metric and event — no user config needed.

    fun hardwareTags(): List<String> = listOf(
        "manufacturer:${Build.MANUFACTURER.lowercase().replace(" ", "_")}",
        "model:${Build.MODEL.lowercase().replace(" ", "_")}",
        "brand:${Build.BRAND.lowercase().replace(" ", "_")}",
        "android:${Build.VERSION.RELEASE}",
        "hardware:${Build.HARDWARE.lowercase().replace(" ", "_")}",
        "device_codename:${Build.DEVICE.lowercase().replace(" ", "_")}"
    )

    // ── Custom tags (up to 5 user-defined KVPs) ────────────────────────────────

    fun customTags(ctx: Context): List<String> {
        val p = prefs(ctx)
        return (0 until MAX_CUSTOM_TAGS).mapNotNull { i ->
            val k = sanitizeTagKey(p.getString("custom_tag_key_$i", "") ?: "")
            val v = sanitizeTagValue(p.getString("custom_tag_value_$i", "") ?: "")
            if (k.isNotEmpty() && v.isNotEmpty()) "$k:$v" else null
        }
    }

    /**
     * Tag keys must not contain colons (which separate key from value) or commas
     * (which separate tags in some serialisation formats). Enforce a 100-char cap.
     */
    private fun sanitizeTagKey(raw: String): String =
        raw.trim().replace(Regex("[:\\s,]"), "_").take(100)

    /**
     * Tag values must not contain commas. Enforce a 200-char cap.
     */
    private fun sanitizeTagValue(raw: String): String =
        raw.trim().replace(",", "_").take(200)

    fun loadCustomTagPairs(ctx: Context): List<Pair<String, String>> {
        val p = prefs(ctx)
        return (0 until MAX_CUSTOM_TAGS).map { i ->
            Pair(
                p.getString("custom_tag_key_$i", "") ?: "",
                p.getString("custom_tag_value_$i", "") ?: ""
            )
        }
    }

    fun saveCustomTags(ctx: Context, pairs: List<Pair<String, String>>) {
        val edit = prefs(ctx).edit()
        pairs.take(MAX_CUSTOM_TAGS).forEachIndexed { i, (k, v) ->
            edit.putString("custom_tag_key_$i", k.trim())
            edit.putString("custom_tag_value_$i", v.trim())
        }
        edit.apply()
    }

    // ── Aggregated tag list for every outbound payload ─────────────────────────

    fun allTags(ctx: Context): List<String> =
        listOf(deviceTag(ctx)) + hardwareTags() + customTags(ctx)

    // ── MDM / Managed Configuration ────────────────────────────────────────────

    fun isMdmManaged(ctx: Context): Boolean {
        val rm = ctx.getSystemService(Context.RESTRICTIONS_SERVICE)
            as android.content.RestrictionsManager
        val bundle = rm.applicationRestrictions
        return bundle != null && !bundle.isEmpty
    }

    fun applyManagedConfig(ctx: Context) {
        val rm = ctx.getSystemService(Context.RESTRICTIONS_SERVICE)
            as android.content.RestrictionsManager
        val bundle = rm.applicationRestrictions
        if (bundle == null || bundle.isEmpty) return

        val edit = prefs(ctx).edit()

        val apiKeyVal = bundle.getString("api_key")
        if (!apiKeyVal.isNullOrBlank()) edit.putString(KEY_API_KEY, apiKeyVal.trim())

        val siteVal = bundle.getString("site")
        if (!siteVal.isNullOrBlank()) edit.putString(KEY_SITE, siteVal.trim())

        val deviceIdVal = bundle.getString("device_id")
        if (!deviceIdVal.isNullOrBlank()) edit.putString(KEY_DEVICE_ID, deviceIdVal.trim())

        val intervalVal = bundle.getString("poll_interval_sec")?.toLongOrNull()
        if (intervalVal != null) {
            edit.putLong("poll_interval_sec",
                intervalVal.coerceIn(INTERVAL_OPTIONS.first(), INTERVAL_OPTIONS.last()))
        }

        if (bundle.containsKey("debug_logging")) {
            edit.putBoolean("debug_logging", bundle.getBoolean("debug_logging", false))
        }

        val customTagsVal = bundle.getString("custom_tags")
        if (customTagsVal != null) {
            val pairs = parseMdmCustomTags(customTagsVal)
            pairs.take(MAX_CUSTOM_TAGS).forEachIndexed { i, pair ->
                edit.putString("custom_tag_key_$i", pair.first)
                edit.putString("custom_tag_value_$i", pair.second)
            }
            for (i in pairs.size until MAX_CUSTOM_TAGS) {
                edit.putString("custom_tag_key_$i", "")
                edit.putString("custom_tag_value_$i", "")
            }
        }

        edit.apply()
    }

    private fun parseMdmCustomTags(raw: String): List<Pair<String, String>> =
        raw.split(",").mapNotNull { entry ->
            val idx = entry.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val k = sanitizeTagKey(entry.substring(0, idx))
            val v = sanitizeTagValue(entry.substring(idx + 1))
            if (k.isNotEmpty() && v.isNotEmpty()) Pair(k, v) else null
        }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
