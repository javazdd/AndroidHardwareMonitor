package com.s23ultra.monitor

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.telephony.*
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvBatteryPct: TextView
    private lateinit var tvBatteryTemp: TextView
    private lateinit var tvCpuTemp: TextView
    private lateinit var tvSignal: TextView
    private lateinit var tvConnectivity: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvStorage: TextView
    private lateinit var tvStatus: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        tvStatus.text = if (allGranted) "Monitoring active" else "Some permissions denied — signal may be unavailable"
        startMonitoringService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupLog.step(this, "MainActivity.onCreate start")

        // ── Show crash dialog from previous run before doing anything else ────
        showCrashDialogIfPending()

        try {
            setContentView(R.layout.activity_main)
            StartupLog.step(this, "setContentView OK")

            tvBatteryPct   = findViewById(R.id.tvBatteryPct)
            tvBatteryTemp  = findViewById(R.id.tvBatteryTemp)
            tvCpuTemp      = findViewById(R.id.tvCpuTemp)
            tvSignal       = findViewById(R.id.tvSignal)
            tvConnectivity = findViewById(R.id.tvConnectivity)
            tvRam          = findViewById(R.id.tvRam)
            tvStorage      = findViewById(R.id.tvStorage)
            tvStatus       = findViewById(R.id.tvStatus)
            StartupLog.step(this, "Views bound OK")

            if (!AppConfig.isConfigured(this)) {
                StartupLog.step(this, "Not configured — opening SettingsActivity")
                startActivity(Intent(this, SettingsActivity::class.java))
            } else {
                StartupLog.step(this, "Configured — requesting permissions")
                checkPermissionsAndStart()
            }

            startPollingUI()
            StartupLog.step(this, "MainActivity.onCreate complete")

        } catch (e: Exception) {
            StartupLog.step(this, "EXCEPTION in MainActivity.onCreate: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            // Show the error directly on the status text if views were bound, otherwise
            // fall through to the UncaughtExceptionHandler which will write the crash file.
            try {
                tvStatus.text = "Startup error — see startup.log\n${e.javaClass.simpleName}: ${e.message?.take(120)}"
            } catch (_: Exception) {}
        }
    }

    // ── Crash dialog ──────────────────────────────────────────────────────────

    private fun showCrashDialogIfPending() {
        val crash = StartupLog.readCrash(this) ?: return
        StartupLog.step(this, "Showing crash dialog from previous session")
        // Don't delete yet — MonitorApp.sendPendingCrashReport() will send + delete
        // once an API key is configured. Here we just display it.
        AlertDialog.Builder(this)
            .setTitle("Crash detected (previous session)")
            .setMessage(crash.take(1_200))
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy path") { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("log path", StartupLog.logPath(this))
                )
            }
            .show()
    }

    // ── Resume: re-evaluate config + permissions each time we come to foreground

    override fun onResume() {
        super.onResume()
        // If the user just came back from SettingsActivity having saved a config for
        // the first time, request permissions and start the service from here rather
        // than from SettingsActivity (which doesn't have the permission launcher and
        // would crash on Android 14 trying to start a location foreground service
        // without the location permission).
        if (AppConfig.isConfigured(this)) {
            checkPermissionsAndStart()
        }
    }

    // ── Action bar ────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ── Permissions & service ─────────────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        val required = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startMonitoringService() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startMonitoringService() {
        val intent = Intent(this, MonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        tvStatus.text = "Monitoring active"
    }

    // ── UI polling (every 5 s) ────────────────────────────────────────────────

    private fun startPollingUI() {
        lifecycleScope.launch {
            while (true) {
                try { refreshUI() } catch (_: Exception) {}
                delay(5_000L)
            }
        }
    }

    private fun refreshUI() {
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level    = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale    = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct      = if (level >= 0) level * 100 / scale else -1
        val status   = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        tvBatteryPct.text = if (pct >= 0) "Battery: $pct%${if (charging) " ⚡" else ""}" else "Battery: —"

        val tenths = bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        tvBatteryTemp.text = "Battery Temp: ${tenths / 10.0}°C"

        try {
            val raw = File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toInt()
            tvCpuTemp.text = "CPU Temp: ${if (raw > 1000) raw / 1000.0 else raw.toDouble()}°C"
        } catch (_: Exception) { tvCpuTemp.text = "CPU Temp: unavailable" }

        tvSignal.text = if (hasSignalPermission()) {
            val dbm = readSignalDbm()
            if (dbm.isNaN()) "Signal: unavailable" else "Signal: ${dbm.toInt()} dBm"
        } else "Signal: permission required"

        val cm = getSystemService(ConnectivityManager::class.java)
        val online = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        tvConnectivity.text = if (online) "Connectivity: Online ✓" else "Connectivity: OFFLINE ✗"
        tvConnectivity.setTextColor(
            if (online) getColor(android.R.color.holo_green_dark) else getColor(android.R.color.holo_red_dark)
        )

        val memInfo = android.app.ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memInfo)
        tvRam.text = "RAM: ${memInfo.availMem / 1_048_576} MB free / ${memInfo.totalMem / 1_048_576} MB"

        val sf = StatFs(Environment.getDataDirectory().path)
        val freeGb = sf.availableBlocksLong * sf.blockSizeLong / 1_073_741_824.0
        tvStorage.text = "Storage: ${"%.1f".format(freeGb)} GB free"
    }

    // ── Signal helpers ────────────────────────────────────────────────────────

    private fun hasSignalPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun readSignalDbm(): Double {
        val tm = getSystemService(TelephonyManager::class.java)
        for (cell in try { tm.allCellInfo } catch (_: Exception) { emptyList() }) {
            val dbm = when (cell) {
                is CellInfoNr    -> (cell.cellSignalStrength as CellSignalStrengthNr).dbm
                is CellInfoLte   -> cell.cellSignalStrength.dbm
                is CellInfoWcdma -> cell.cellSignalStrength.dbm
                is CellInfoGsm   -> cell.cellSignalStrength.dbm
                else             -> Int.MIN_VALUE
            }
            if (dbm != Int.MIN_VALUE) return dbm.toDouble()
        }
        return Double.NaN
    }
}
