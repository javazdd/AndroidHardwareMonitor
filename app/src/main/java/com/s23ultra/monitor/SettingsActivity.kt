package com.s23ultra.monitor

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var spinnerSite: Spinner
    private lateinit var etApiKey: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var btnSave: Button
    private lateinit var btnDiscard: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.title = "Monitoring Configuration"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        spinnerSite  = findViewById(R.id.spinnerSite)
        etApiKey     = findViewById(R.id.etApiKey)
        etDeviceId   = findViewById(R.id.etDeviceId)
        btnSave      = findViewById(R.id.btnSave)
        btnDiscard   = findViewById(R.id.btnDiscard)

        // Populate site dropdown
        val labels = AppConfig.Site.entries.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSite.adapter = adapter

        // Pre-fill saved values
        val currentHost = AppConfig.site(this)
        val idx = AppConfig.Site.entries.indexOfFirst { it.host == currentHost }.coerceAtLeast(0)
        spinnerSite.setSelection(idx)
        etApiKey.setText(AppConfig.apiKey(this))
        // Strip the "device:" prefix for display
        val savedTag = AppConfig.deviceTag(this).removePrefix("device:")
        etDeviceId.setText(savedTag)

        btnSave.setOnClickListener {
            val apiKey   = etApiKey.text.toString().trim()
            val siteHost = AppConfig.Site.entries[spinnerSite.selectedItemPosition].host
            val deviceId = etDeviceId.text.toString().trim()

            if (apiKey.isBlank()) {
                etApiKey.error = "API key is required"
                return@setOnClickListener
            }

            AppConfig.save(this, apiKey, siteHost, deviceId)

            // Restart MonitoringService so it picks up the new config immediately
            val svc = Intent(this, MonitoringService::class.java)
            stopService(svc)
            startForegroundService(svc)

            Toast.makeText(this, "Saved — monitoring restarted", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnDiscard.setOnClickListener { finish() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
