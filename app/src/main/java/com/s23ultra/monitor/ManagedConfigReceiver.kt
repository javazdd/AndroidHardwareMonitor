package com.s23ultra.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Receives ACTION_APPLICATION_RESTRICTIONS_CHANGED from the MDM (SOTI MobiControl).
 * Re-applies managed config to SharedPreferences and restarts the monitoring service
 * so the new values take effect immediately without a reboot.
 */
class ManagedConfigReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED) return

        AppConfig.applyManagedConfig(context)
        DiagLogger.log(context, DiagLogger.Level.INFO, "MDM config updated — restarting service")

        val svc = Intent(context, MonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }
}
