package com.s23ultra.monitor

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {

    private lateinit var spinnerSite: Spinner
    private lateinit var tilApiKey: TextInputLayout
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etDeviceId: EditText
    private lateinit var tvHardwareTags: TextView
    private lateinit var btnSave: Button
    private lateinit var btnDiscard: Button

    // Five custom tag rows  (key EditText, value EditText)
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

        spinnerSite   = findViewById(R.id.spinnerSite)
        tilApiKey     = findViewById(R.id.tilApiKey)
        etApiKey      = findViewById(R.id.etApiKey)
        etDeviceId    = findViewById(R.id.etDeviceId)
        tvHardwareTags = findViewById(R.id.tvHardwareTags)
        btnSave       = findViewById(R.id.btnSave)
        btnDiscard    = findViewById(R.id.btnDiscard)

        setupSiteSpinner()
        populateHardwareTags()
        loadSavedValues()

        btnSave.setOnClickListener { onSave() }
        btnDiscard.setOnClickListener { finish() }
    }

    // ── Site spinner ──────────────────────────────────────────────────────────

    private fun setupSiteSpinner() {
        val labels = AppConfig.Site.entries.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSite.adapter = adapter

        val currentHost = AppConfig.site(this)
        val idx = AppConfig.Site.entries.indexOfFirst { it.host == currentHost }.coerceAtLeast(0)
        spinnerSite.setSelection(idx)
    }

    // ── Hardware tags (read-only display) ─────────────────────────────────────

    private fun populateHardwareTags() {
        tvHardwareTags.text = AppConfig.hardwareTags().joinToString("\n")
    }

    // ── Pre-fill saved values ─────────────────────────────────────────────────

    private fun loadSavedValues() {
        // API key is masked by default (inputType=textPassword in XML);
        // the eye icon in TextInputLayout lets the user reveal it on demand.
        etApiKey.setText(AppConfig.apiKey(this))

        val savedId = AppConfig.deviceTag(this).removePrefix("device:")
        etDeviceId.setText(savedId)

        val savedPairs = AppConfig.loadCustomTagPairs(this)
        tagRows.forEachIndexed { i, (keyView, valView) ->
            keyView.setText(savedPairs[i].first)
            valView.setText(savedPairs[i].second)
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun onSave() {
        val apiKey   = etApiKey.text.toString().trim()
        val siteHost = AppConfig.Site.entries[spinnerSite.selectedItemPosition].host
        val deviceId = etDeviceId.text.toString().trim()

        if (apiKey.isBlank()) {
            tilApiKey.error = "API key is required"
            return
        }
        tilApiKey.error = null

        AppConfig.save(this, apiKey, siteHost, deviceId)
        AppConfig.saveCustomTags(this, tagRows.map { (k, v) ->
            Pair(k.text.toString(), v.text.toString())
        })

        val svc = Intent(this, MonitoringService::class.java)
        stopService(svc)
        startForegroundService(svc)

        Toast.makeText(this, "Saved — monitoring restarted", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
