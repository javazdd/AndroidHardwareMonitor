package com.s23ultra.monitor

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

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
        setContentView(R.layout.activity_settings)
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

        setupSiteSpinner()
        setupIntervalSpinner()
        populateHardwareTags()
        loadSavedValues()

        btnSave.setOnClickListener { onSave() }
        btnDiscard.setOnClickListener { finish() }
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

        // Log config change before restarting the service so the log entry
        // goes out under the current (outgoing) config.
        DiagLogger.log(
            ctx     = this,
            level   = DiagLogger.Level.INFO,
            message = "Config saved | interval=${intervalSec}s | debug=$debugOn | site=$siteHost",
        )

        val svc = Intent(this, MonitoringService::class.java)
        stopService(svc)
        startForegroundService(svc)

        Toast.makeText(this, "Saved — monitoring restarted (${intervalSec}s interval)", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
