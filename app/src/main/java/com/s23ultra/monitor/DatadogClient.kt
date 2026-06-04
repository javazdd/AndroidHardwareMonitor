package com.s23ultra.monitor

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DeviceMetric(val name: String, val value: Double)

class DatadogClient(private val context: Context) {

    private val mediaType = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Sends gauge metrics to the Datadog v2 series endpoint.
     * Reads API key, site, and device tag fresh from SharedPreferences on each call
     * so config changes take effect without restarting the service.
     * Metrics with NaN values are silently skipped.
     */
    fun sendMetrics(metrics: List<DeviceMetric>) {
        val apiKey = AppConfig.apiKey(context)
        if (apiKey.isBlank()) return

        val series = JSONArray()
        val nowSeconds = System.currentTimeMillis() / 1000L

        val tags = JSONArray().also { arr ->
            AppConfig.allTags(context).forEach { arr.put(it) }
        }

        metrics.filter { !it.value.isNaN() }.forEach { m ->
            series.put(JSONObject().apply {
                put("metric", m.name)
                put("type", 3) // gauge
                put("points", JSONArray().put(JSONObject().apply {
                    put("timestamp", nowSeconds)
                    put("value", m.value)
                }))
                put("tags", tags)
            })
        }

        if (series.length() == 0) return
        post(apiKey, "https://api.${AppConfig.site(context)}/api/v2/series",
            JSONObject().put("series", series).toString())
    }

    /**
     * Fires a Datadog event (v1 events endpoint).
     * @param alertType one of: "error", "warning", "info", "success"
     */
    fun sendEvent(title: String, text: String, alertType: String = "info") {
        val apiKey = AppConfig.apiKey(context)
        if (apiKey.isBlank()) return
        val body = JSONObject().apply {
            put("title", title)
            put("text", text)
            put("alert_type", alertType)
            put("tags", JSONArray().also { arr ->
                AppConfig.allTags(context).forEach { arr.put(it) }
            })
        }.toString()
        post(apiKey, "https://api.${AppConfig.site(context)}/api/v1/events", body)
    }

    private fun post(apiKey: String, url: String, body: String) {
        val request = Request.Builder()
            .url(url)
            .addHeader("DD-API-KEY", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(mediaType))
            .build()
        try {
            http.newCall(request).execute().use { }
        } catch (_: IOException) {
            // Tolerated — next poll will retry.
        }
    }
}
