package com.s23ultra.monitor

import android.content.Context
import okhttp3.ConnectionPool
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
        val tags   = buildTagArray(context)
        val series = JSONArray()

        metrics.filter { !it.value.isNaN() && !it.value.isInfinite() }.forEach { m ->
            series.put(JSONObject().apply {
                put("metric", m.name)
                put("type",   3) // gauge
                put("points", JSONArray().put(JSONObject().apply {
                    put("timestamp", nowSeconds)
                    put("value",     m.value)
                }))
                put("tags", tags)
            })
        }

        if (series.length() == 0) return
        val url = "https://api.${AppConfig.site(context)}/api/v2/series"
        post(apiKey, url, JSONObject().put("series", series).toString(), "metrics")
    }

    fun sendEndpointResults(results: List<EndpointResult>, transitions: List<Pair<EndpointResult, Boolean>>) {
        val apiKey = AppConfig.apiKey(context)
        if (apiKey.isBlank()) return

        val nowSeconds = System.currentTimeMillis() / 1000L
        val baseTags   = buildTagArray(context)
        val series     = JSONArray()

        results.forEach { r ->
            val endpointTag = JSONArray(baseTags.toString()).also { it.put("endpoint:${r.name}") }

            series.put(JSONObject().apply {
                put("metric", "springshot.endpoint.up")
                put("type",   3)
                put("points", JSONArray().put(JSONObject().apply {
                    put("timestamp", nowSeconds)
                    put("value",     if (r.isUp) 1.0 else 0.0)
                }))
                put("tags", endpointTag)
            })

            if (r.latencyMs > 0 && r.isUp) {
                series.put(JSONObject().apply {
                    put("metric", "springshot.endpoint.latency_ms")
                    put("type",   3)
                    put("points", JSONArray().put(JSONObject().apply {
                        put("timestamp", nowSeconds)
                        put("value",     r.latencyMs.toDouble())
                    }))
                    put("tags", endpointTag)
                })
            }
        }

        if (series.length() > 0) {
            val url = "https://api.${AppConfig.site(context)}/api/v2/series"
            post(apiKey, url, JSONObject().put("series", series).toString(), "endpoint-metrics")
        }

        // Fire events only for state transitions
        transitions.forEach { (r, changed) ->
            if (!changed) return@forEach
            if (r.isUp) {
                sendEvent("Springshot ${r.name} recovered", "${r.host} is reachable again (${r.label})", "success")
            } else {
                sendEvent("Springshot ${r.name} unreachable", "${r.host} failed: ${r.label}", "error")
            }
        }
    }

    fun sendEvent(title: String, text: String, alertType: String = "info") {
        val apiKey = AppConfig.apiKey(context)
        if (apiKey.isBlank()) return
        val body = JSONObject().apply {
            put("title",            title)
            put("text",             text)
            put("alert_type",       alertType)
            put("source_type_name", SOURCE_TYPE)
            put("tags",             buildTagArray(context))
        }.toString()
        val url = "https://api.${AppConfig.site(context)}/api/v1/events"
        post(apiKey, url, body, "event[$title]")
    }

    private fun buildTagArray(ctx: Context): JSONArray =
        JSONArray().also { arr -> AppConfig.allTags(ctx).forEach { arr.put(it) } }

    private fun post(apiKey: String, url: String, body: String, label: String) {
        val host = url.substringAfter("//").substringBefore("/")
        val request = Request.Builder()
            .url(url)
            .addHeader("DD-API-KEY", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(mediaType))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    lastSendOk      = true
                    lastSendMessage = "Sent OK"
                    lastSendTimeMs  = System.currentTimeMillis()
                } else {
                    lastSendOk      = false
                    lastSendMessage = "HTTP ${response.code} — check API key / site"
                    lastSendTimeMs  = System.currentTimeMillis()
                    DiagLogger.logCrash(context, "Send failed HTTP ${response.code} → $host ($label)")
                }
            }
        } catch (e: Exception) {
            lastSendOk      = false
            lastSendMessage = "Network error: ${e.javaClass.simpleName}"
            lastSendTimeMs  = System.currentTimeMillis()
            DiagLogger.logCrash(context, "Send exception → $host: ${e.message?.take(200)}")
        }
    }

    companion object {
        /** Unique source name stamped on every event — used to filter the dashboard widget. */
        const val SOURCE_TYPE = "android-hardware-monitor"

        internal val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            // Keep at most 1 idle connection for 20 s. The default (5 connections / 5 min)
            // holds the radio active far longer than needed between 60-second metric sends.
            .connectionPool(ConnectionPool(1, 20, TimeUnit.SECONDS))
            .build()

        /** Send status — written by background thread, read by UI thread. */
        @Volatile var lastSendOk: Boolean? = null      // null = never attempted
        @Volatile var lastSendMessage: String = "Waiting for first send…"
        @Volatile var lastSendTimeMs: Long = 0L
    }
}
