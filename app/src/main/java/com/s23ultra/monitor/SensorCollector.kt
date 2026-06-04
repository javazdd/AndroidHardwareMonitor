package com.s23ultra.monitor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Registers lightweight SensorEventListeners for:
 *  - Linear acceleration  → fall / impact detection (fires callbacks)
 *  - Pressure             → hPa
 *  - Light                → lux
 *  - Step counter         → cumulative steps since reboot
 *  - Relative humidity    → % RH
 *
 * Sensors not present on the device are silently skipped.
 * Call start() once on service create, stop() on destroy.
 */
class SensorCollector(
    context: Context,
    private val onImpact: () -> Unit,
    private val onFall: () -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    // @Volatile ensures the IO coroutine in MonitoringService always reads the value
    // written by the sensor callback thread, not a stale register-cached copy.
    @Volatile var pressureHpa: Float = Float.NaN; private set
    @Volatile var lightLux: Float    = Float.NaN; private set
    @Volatile var stepCount: Float   = Float.NaN; private set
    @Volatile var humidityPct: Float = Float.NaN; private set

    // Fall detection state machine
    private var inFreeFall = false
    private var freeFallStartMs = 0L
    // Suppress duplicate events within 2 s
    private var lastImpactMs = 0L
    private var lastFallMs   = 0L

    fun start() {
        // SENSOR_DELAY_UI (~60 Hz) is sufficient for fall/impact detection and uses
        // ~4× less CPU than SENSOR_DELAY_GAME (~200 Hz). The minimum detectable
        // free-fall window (FREE_FALL_MIN_MS = 80 ms) spans ~5 samples at 60 Hz,
        // which is enough to reliably distinguish a drop from a rapid tilt.
        register(Sensor.TYPE_LINEAR_ACCELERATION, SensorManager.SENSOR_DELAY_UI)
        register(Sensor.TYPE_PRESSURE,            SensorManager.SENSOR_DELAY_NORMAL)
        register(Sensor.TYPE_LIGHT,               SensorManager.SENSOR_DELAY_NORMAL)
        register(Sensor.TYPE_STEP_COUNTER,        SensorManager.SENSOR_DELAY_NORMAL)
        register(Sensor.TYPE_RELATIVE_HUMIDITY,   SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> processAccel(event)
            Sensor.TYPE_PRESSURE            -> pressureHpa = event.values[0]
            Sensor.TYPE_LIGHT               -> lightLux    = event.values[0]
            Sensor.TYPE_STEP_COUNTER        -> stepCount   = event.values[0]
            Sensor.TYPE_RELATIVE_HUMIDITY   -> humidityPct = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun processAccel(event: SensorEvent) {
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        // Convert m/s² magnitude to g-force (1 g = 9.81 m/s²)
        val gForce = sqrt(x * x + y * y + z * z) / 9.81f
        val now = System.currentTimeMillis()

        when {
            gForce < FREE_FALL_G_THRESHOLD -> {
                if (!inFreeFall) {
                    inFreeFall = true
                    freeFallStartMs = now
                }
            }
            gForce > IMPACT_G_THRESHOLD -> {
                val freeFallDuration = now - freeFallStartMs
                if (inFreeFall && freeFallDuration >= FREE_FALL_MIN_MS) {
                    // Sustained free-fall → impact = drop/fall event
                    if (now - lastFallMs > EVENT_COOLDOWN_MS) {
                        lastFallMs = now
                        onFall()
                    }
                } else if (gForce > HARD_IMPACT_G_THRESHOLD) {
                    // Sudden spike without free-fall = hard knock/drop
                    if (now - lastImpactMs > EVENT_COOLDOWN_MS) {
                        lastImpactMs = now
                        onImpact()
                    }
                }
                inFreeFall = false
            }
            else -> inFreeFall = false
        }
    }

    private fun register(type: Int, rate: Int) {
        sensorManager.getDefaultSensor(type)?.let {
            sensorManager.registerListener(this, it, rate)
        }
    }

    companion object {
        private const val FREE_FALL_G_THRESHOLD   = 0.5f   // g — below this = near-weightless
        private const val FREE_FALL_MIN_MS         = 80L    // ms — minimum freefall window
        private const val IMPACT_G_THRESHOLD       = 3.0f   // g — spike after freefall
        private const val HARD_IMPACT_G_THRESHOLD  = 4.5f   // g — direct knock without freefall
        private const val EVENT_COOLDOWN_MS        = 2_000L // prevent duplicate events
    }
}
