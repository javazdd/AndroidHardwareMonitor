package com.s23ultra.monitor

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DeviceMetric(val name: String, val value: Double)

class DatadogClient(private val context: Context) {

    private val mediaType = "application/json".toMediaType()

    fun sendMetrics(metrics: List<DeviceMetric>) {
        val apiKey = AppConfig.apiKey(context)
        if (apiKey.isBlank()) return

        val nowSeconds = System.currentTimeMillis() / 1000L
        val tags = buildTagArray(context)
        val series = JSONArray()

        metrics.filter { !it.value.isNaN() && !it.value.isInfinite() }.forEach { m ->
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

    fun sendEvent(title: String, text: String, alertType: String = "info") {
        val apiKey = AppConfig.apiKey(context)
        if (apiKey.isBlank()) return
        val body = JSONObject().apply {
            put("title", title)
            put("text",  text)
            put("alert_type", alertType)
            put("tags", buildTagArray(context))
        }.toString()
        post(apiKey, "https://api.${AppConfig.site(context)}/api/v1/events", body)
    }

    private fun buildTagArray(ctx: Context): JSONArray =
        JSONArray().also { arr -> AppConfig.allTags(ctx).forEach { arr.put(it) } }

    private fun post(apiKey: String, url: String, body: String) {
        val request = Request.Builder()
            .url(url)
            .addHeader("DD-API-KEY", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(mediaType))
            .build()
        try {
            http.newCall(request).execute().use { }
        } catch (_: Exception) {
            // Network or protocol error — tolerated, next poll cycle will retry.
        }
    }

    companion object {
        /**
         * Shared across all DatadogClient instances and service restarts.
         * OkHttpClient owns a thread pool + connection pool; creating a new one
         * on every service start would leave dangling threads until GC collects them.
         */
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // explicit — don't retry; next poll will
            .build()
    }
}
