package com.s23ultra.monitor

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.Manifest

class SettingsActivity : AppCompatActivity() {

    private lateinit var spinnerSite: Spinner
    private lateinit var spinnerInterval: Spinner
    private lateinit var tilApiKey: TextInputLayout
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etDeviceId: EditText
    private lateinit var tvHardwareTags: TextView
    private lateinit var switchDebugLogging: SwitchMaterial
    private lateinit var btnSave: Button
    private lateinit var btnDiscard: Button

    private val tagRows: List<Pair<EditText, EditText>> by lazy {
        listOf(
            Pair(findViewById(R.id.etTagKey0), findViewById(R.id.etTagValue0)),
            Pair(findViewById(R.id.etTagKey1), findViewById(R.id.etTagValue1)),
            Pair(findViewById(R.id.etTagKey2), findViewById(R.id.etTagValue2)),
            Pair(findViewById(R.id.etTagKey3), findViewById(R.id.etTagValue3)),
            Pair(findViewById(R.id.etTagKey4), findViewById(R.id.etTagValue4))
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupLog.step(this, "SettingsActivity.onCreate start")
        try {
            setContentView(R.layout.activity_settings)
            StartupLog.step(this, "SettingsActivity setContentView OK")
            supportActionBar?.title = "Monitoring Configuration"
            supportActionBar?.setDisplayHomeAsUpEnabled(true)

            spinnerSite        = findViewById(R.id.spinnerSite)
            spinnerInterval    = findViewById(R.id.spinnerInterval)
            tilApiKey          = findViewById(R.id.tilApiKey)
            etApiKey           = findViewById(R.id.etApiKey)
            etDeviceId         = findViewById(R.id.etDeviceId)
            tvHardwareTags     = findViewById(R.id.tvHardwareTags)
            switchDebugLogging = findViewById(R.id.switchDebugLogging)
            btnSave            = findViewById(R.id.btnSave)
            btnDiscard         = findViewById(R.id.btnDiscard)
            StartupLog.step(this, "SettingsActivity views bound OK")

            setupSiteSpinner()
            StartupLog.step(this, "SettingsActivity setupSiteSpinner OK")
            setupIntervalSpinner()
            StartupLog.step(this, "SettingsActivity setupIntervalSpinner OK")
            populateHardwareTags()
            StartupLog.step(this, "SettingsActivity populateHardwareTags OK")
            loadSavedValues()
            StartupLog.step(this, "SettingsActivity loadSavedValues OK")

            applyMdmState()
            StartupLog.step(this, "SettingsActivity applyMdmState OK")

            btnSave.setOnClickListener { onSave() }
            btnDiscard.setOnClickListener { finish() }
            StartupLog.step(this, "SettingsActivity.onCreate complete")
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message?.take(300)}"
            StartupLog.step(this, "EXCEPTION in SettingsActivity.onCreate: $msg")
            Toast.makeText(this, "Settings error: $msg", Toast.LENGTH_LONG).show()
        }
    }

    // ── MDM lock state ────────────────────────────────────────────────────────

    private fun applyMdmState() {
        if (!AppConfig.isMdmManaged(this)) return

        // Show a banner at the top of the screen
        findViewById<TextView>(R.id.tvMdmBanner).also {
            it.visibility = android.view.View.VISIBLE
        }

        // Disable all editable fields — MDM is the source of truth
        val fieldsToLock = listOf(
            spinnerSite, spinnerInterval, etApiKey, etDeviceId, switchDebugLogging
        )
        fieldsToLock.forEach { v -> v.isEnabled = false }
        tagRows.forEach { (k, v) -> k.isEnabled = false; v.isEnabled = false }
        btnSave.isEnabled = false
        btnSave.alpha = 0.4f
    }

    // ── Site spinner ──────────────────────────────────────────────────────────

    private fun setupSiteSpinner() {
        val labels = AppConfig.Site.entries.map { it.label }
        spinnerSite.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val currentHost = AppConfig.site(this)
        spinnerSite.setSelection(
            AppConfig.Site.entries.indexOfFirst { it.host == currentHost }.coerceAtLeast(0)
        )
    }

    // ── Interval spinner ──────────────────────────────────────────────────────

    private fun setupIntervalSpinner() {
        val labels = AppConfig.INTERVAL_OPTIONS.map { sec ->
            when {
                sec < 60   -> "Every ${sec}s"
                sec == 60L -> "Every 60s (1 min)"
                sec < 300  -> "Every ${sec}s (${sec / 60} min)"
                else       -> "Every ${sec}s (5 min)"
            }
        }
        spinnerInterval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val saved = AppConfig.pollIntervalSeconds(this)
        spinnerInterval.setSelection(
            AppConfig.INTERVAL_OPTIONS.indexOf(saved).coerceAtLeast(1)
        )
    }

    // ── Hardware tags (read-only) ─────────────────────────────────────────────

    private fun populateHardwareTags() {
        tvHardwareTags.text = AppConfig.hardwareTags().joinToString("\n")
    }

    // ── Pre-fill saved values ─────────────────────────────────────────────────

    private fun loadSavedValues() {
        etApiKey.setText(AppConfig.apiKey(this))
        etDeviceId.setText(AppConfig.deviceTag(this).removePrefix("device:"))

        val savedPairs = AppConfig.loadCustomTagPairs(this)
        tagRows.forEachIndexed { i, (keyView, valView) ->
            keyView.setText(savedPairs[i].first)
            valView.setText(savedPairs[i].second)
        }

        switchDebugLogging.isChecked = AppConfig.isDebugLoggingEnabled(this)
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun onSave() {
        try {
            val apiKey      = etApiKey.text.toString().trim()
            val siteHost    = AppConfig.Site.entries[spinnerSite.selectedItemPosition].host
            val deviceId    = etDeviceId.text.toString().trim()
            val intervalSec = AppConfig.INTERVAL_OPTIONS[spinnerInterval.selectedItemPosition]
            val debugOn     = switchDebugLogging.isChecked

            if (apiKey.isBlank()) {
                tilApiKey.error = "API key is required"
                return
            }
            tilApiKey.error = null

            AppConfig.save(this, apiKey, siteHost, deviceId, intervalSec)
            AppConfig.saveCustomTags(this, tagRows.map { (k, v) ->
                Pair(k.text.toString(), v.text.toString())
            })
            AppConfig.saveDebugLogging(this, debugOn)

            DiagLogger.log(
                ctx     = this,
                level   = DiagLogger.Level.INFO,
                message = "Config saved | interval=${intervalSec}s | debug=$debugOn | site=$siteHost",
            )

            // Only restart the service if location permission is already granted.
            // On first-ever launch permissions haven't been requested yet — starting a
            // foreground service with foregroundServiceType="location" without the
            // permission throws a SecurityException on Android 14. MainActivity.onResume()
            // will request permissions and start the service once this screen closes.
            val locationGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (locationGranted) {
                val svc = Intent(this, MonitoringService::class.java)
                stopService(svc)
                startForegroundService(svc)
                Toast.makeText(this, "Saved — monitoring restarted (${intervalSec}s)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Saved — grant permissions to start monitoring", Toast.LENGTH_SHORT).show()
            }

            StartupLog.step(this, "onSave complete — locationGranted=$locationGranted")
            finish()
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message?.take(200)}"
            StartupLog.step(this, "EXCEPTION in onSave: $msg")
            Toast.makeText(this, "Save error: $msg", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
