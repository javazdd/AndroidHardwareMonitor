package com.s23ultra.monitor

import android.content.Context
import android.os.SystemClock
import kotlin.math.abs

/**
 * Detects device reboots by comparing the current boot epoch
 * (wall-clock time minus elapsed realtime) against the last
 * stored value in SharedPreferences.
 */
class RebootTracker(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns true if the device has rebooted since the last call.
     * Stores the new boot epoch so subsequent calls return false
     * until the next reboot.
     */
    fun checkAndRecord(): Boolean {
        val bootEpoch = bootEpochMs()
        val stored    = prefs.getLong(KEY_LAST_BOOT, -1L)
        val isNew     = stored == -1L || abs(bootEpoch - stored) > JITTER_MS
        if (isNew) prefs.edit().putLong(KEY_LAST_BOOT, bootEpoch).apply()
        return isNew
    }

    /** Seconds the device has been running since the last boot. */
    fun uptimeSeconds(): Long = SystemClock.elapsedRealtime() / 1_000L

    /** Approximate epoch timestamp of the most recent boot. */
    private fun bootEpochMs() = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    companion object {
        private const val PREFS_NAME    = "monitor_prefs"
        private const val KEY_LAST_BOOT = "last_boot_epoch"
        private const val JITTER_MS     = 5_000L // tolerate ±5 s clock jitter
    }
}
