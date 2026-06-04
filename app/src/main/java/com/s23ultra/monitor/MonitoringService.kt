package com.s23ultra.monitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.*
import android.net.wifi.WifiManager
import android.os.*
import android.telephony.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

class MonitoringService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var client: DatadogClient

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var sensorCollector: SensorCollector
    private lateinit var rebootTracker: RebootTracker

    private var wasOnline = true
    private var lastRxBytes = TrafficStats.getMobileRxBytes()
    private var lastTxBytes = TrafficStats.getMobileTxBytes()

    // Bounded queue: events from async callbacks (network, sensors, reboot) are queued
    // here and flushed at the start of each poll cycle. Cap at 50 entries so rapid
    // accelerometer spikes or connectivity flaps cannot grow memory without bound.
    private data class QueuedEvent(val title: String, val text: String, val alertType: String)
    private val eventQueue = LinkedBlockingQueue<QueuedEvent>(MAX_QUEUED_EVENTS)

    private fun queueEvent(title: String, text: String, alertType: String) {
        // offer() is non-blocking and silently drops the event when the queue is full,
        // which is the correct behaviour (an overfull queue signals a sensor runaway).
        eventQueue.offer(QueuedEvent(title, text, alertType))
    }

    // Single poll coroutine — cancelled and replaced whenever the service is re-started
    // (e.g. after settings change), preventing duplicate poll loops.
    private var pollJob: Job? = null

    // ── Network callback ──────────────────────────────────────────────────────

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!wasOnline) {
                wasOnline = true
                queueEvent(
                    title     = "Connectivity Restored",
                    text      = "Device is back online.",
                    alertType = "success",
                )
            }
        }
        override fun onLost(network: Network) {
            if (wasOnline) {
                wasOnline = false
                queueEvent(
                    title     = "Connectivity Lost",
                    text      = "Internet connection lost — cellular provider may be down.",
                    alertType = "error",
                )
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        client = DatadogClient(this)
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        rebootTracker = RebootTracker(this)
        if (rebootTracker.checkAndRecord()) {
            queueEvent(
                title     = "Device Rebooted",
                text      = "Device came back online after a reboot.",
                alertType = "info",
            )
        }

        sensorCollector = SensorCollector(
            context  = this,
            onImpact = {
                queueEvent(
                    title     = "Impact Detected",
                    text      = "Hard knock detected — sudden g-force spike (>4.5 g).",
                    alertType = "warning",
                )
            },
            onFall = {
                queueEvent(
                    title     = "Drop / Fall Detected",
                    text      = "Free-fall phase (>80 ms) followed by impact detected.",
                    alertType = "error",
                )
            },
        )
        sensorCollector.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Cancel any previous poll loop so a settings-triggered restart doesn't
        // accumulate multiple concurrent loops.
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val intervalMs = AppConfig.pollIntervalSeconds(this@MonitoringService) * 1_000L
                poll()
                delay(intervalMs)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        sensorCollector.stop()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Poll cycle ────────────────────────────────────────────────────────────

    private fun poll() {
        // Top-level guard: any hardware read can throw (bad fd, null service, etc.).
        // Catching here keeps the coroutine loop alive even if one cycle fails entirely.
        try {
            doPoll()
        } catch (_: Exception) { }
    }

    private fun doPoll() {
        // 1. Drain the event queue — send everything captured since the last cycle.
        var event = eventQueue.poll()
        while (event != null) {
            client.sendEvent(event.title, event.text, event.alertType)
            event = eventQueue.poll()
        }

        // 2. Read all hardware values at this instant.
        val batteryIntent  = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryManager = getSystemService(BatteryManager::class.java)

        // Battery
        val level     = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale     = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct       = if (level >= 0) level * 100.0 / scale else Double.NaN
        val tempC     = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val status    = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging  = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.toDouble() ?: Double.NaN
        val currentUa = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toDouble()
        val capUah    = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toDouble()

        // System
        val memInfo = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memInfo)
        val ramAvailMb    = memInfo.availMem / 1_048_576.0
        val ramTotalMb    = memInfo.totalMem / 1_048_576.0
        val storageFreeGb = try {
            StatFs(Environment.getDataDirectory().path).run {
                availableBlocksLong * blockSizeLong / 1_073_741_824.0
            }
        } catch (_: Exception) { Double.NaN }
        val thermalHeadroom = (getSystemService(POWER_SERVICE) as PowerManager)
            .getThermalHeadroom(30).toDouble()
        val uptimeSec = rebootTracker.uptimeSeconds().toDouble()
        val cpuTempC  = readCpuTemp()

        // Network
        val curRx   = TrafficStats.getMobileRxBytes()
        val curTx   = TrafficStats.getMobileTxBytes()
        val rxDelta = if (lastRxBytes >= 0 && curRx >= lastRxBytes) (curRx - lastRxBytes).toDouble() else Double.NaN
        val txDelta = if (lastTxBytes >= 0 && curTx >= lastTxBytes) (curTx - lastTxBytes).toDouble() else Double.NaN
        lastRxBytes = curRx
        lastTxBytes = curTx

        val signalDbm = signalStrengthDbm()
        val wifiDbm   = wifiRssiDbm()
        val online    = isOnline()

        // 3. Build and send metric list.
        val metrics = buildList {
            // Battery
            add(DeviceMetric("device.battery.percent",       pct))
            add(DeviceMetric("device.battery.charging",      if (charging) 1.0 else 0.0))
            add(DeviceMetric("device.battery.voltage_mv",    voltageMv))
            if (currentUa > Long.MIN_VALUE.toDouble() + 1) add(DeviceMetric("device.battery.current_ua", currentUa))
            if (capUah > 0) add(DeviceMetric("device.battery.capacity_uah", capUah))
            add(DeviceMetric("device.temperature.battery",  tempC))

            // System
            add(DeviceMetric("device.temperature.cpu",       cpuTempC))
            add(DeviceMetric("device.memory.available_mb",   ramAvailMb))
            add(DeviceMetric("device.memory.total_mb",       ramTotalMb))
            add(DeviceMetric("device.storage.free_gb",       storageFreeGb))
            if (!thermalHeadroom.isNaN()) add(DeviceMetric("device.thermal.headroom", thermalHeadroom))
            add(DeviceMetric("device.uptime.seconds",        uptimeSec))

            // Network
            if (!rxDelta.isNaN()) add(DeviceMetric("device.network.mobile_rx_bytes", rxDelta))
            if (!txDelta.isNaN()) add(DeviceMetric("device.network.mobile_tx_bytes", txDelta))
            if (!signalDbm.isNaN()) add(DeviceMetric("device.signal.strength_dbm",  signalDbm))
            if (!wifiDbm.isNaN())   add(DeviceMetric("device.wifi.rssi_dbm",        wifiDbm))
            add(DeviceMetric("device.connectivity.online",   if (online) 1.0 else 0.0))

            // Passive sensors (updated continuously by SensorCollector)
            with(sensorCollector) {
                if (!pressureHpa.isNaN()) add(DeviceMetric("device.sensor.pressure_hpa",  pressureHpa.toDouble()))
                if (!lightLux.isNaN())    add(DeviceMetric("device.sensor.light_lux",     lightLux.toDouble()))
                if (!stepCount.isNaN())   add(DeviceMetric("device.sensor.steps",         stepCount.toDouble()))
                if (!humidityPct.isNaN()) add(DeviceMetric("device.sensor.humidity_pct",  humidityPct.toDouble()))
            }
        }

        client.sendMetrics(metrics)

        val pctStr = if (pct.isNaN()) "?" else "${pct.toInt()}%"
        val sigStr = if (signalDbm.isNaN()) "N/A" else "${signalDbm.toInt()} dBm"
        val ivlStr = "${AppConfig.pollIntervalSeconds(this)}s"
        updateNotification("Bat $pctStr ${tempC}°C · CPU ${cpuTempC}°C · $sigStr · every $ivlStr")
    }

    // ── Sensor / hardware reads ───────────────────────────────────────────────

    private fun readCpuTemp(): Double = try {
        val raw = File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toInt()
        if (raw > 1_000) raw / 1_000.0 else raw.toDouble()
    } catch (_: Exception) { -1.0 }

    @Suppress("MissingPermission")
    private fun signalStrengthDbm(): Double {
        val tm = getSystemService(TelephonyManager::class.java)
        val cells = try { tm.allCellInfo } catch (_: Exception) { emptyList() }
        for (cell in cells) {
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

    @Suppress("MissingPermission", "DEPRECATION")
    private fun wifiRssiDbm(): Double = try {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        val rssi = wm.connectionInfo?.rssi ?: Int.MIN_VALUE
        if (rssi != Int.MIN_VALUE) rssi.toDouble() else Double.NaN
    } catch (_: Exception) { Double.NaN }

    private fun isOnline(): Boolean {
        val caps = connectivityManager.activeNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        ensureNotificationChannel()
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hardware Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) =
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Device Monitoring", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Live device telemetry monitoring" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    companion object {
        private const val CHANNEL_ID        = "monitor_channel"
        private const val NOTIFICATION_ID   = 1
        private const val MAX_QUEUED_EVENTS = 50
    }
}
