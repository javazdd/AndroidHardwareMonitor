package com.s23ultra.monitor

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class EndpointResult(
    val name: String,       // short label used as Datadog tag value
    val host: String,
    val latencyMs: Long,
    val httpCode: Int,      // actual HTTP code, or -1 = network error, -2 = timeout
    val isUp: Boolean
) {
    val label: String get() = when (httpCode) {
        -2   -> "Timeout"
        -1   -> "Unreachable"
        else -> "HTTP $httpCode"
    }
}

object EndpointChecker {

    /**
     * All nine Springshot endpoints discovered via active probing.
     * All are HTTPS/443. 404 on root is normal for authenticated APIs —
     * any HTTP response < 500 is treated as "reachable / healthy enough".
     */
    private val ENDPOINTS = listOf(
        "users"        to "users.springshot.com",
        "api"          to "api.springshot.com",
        "rt"           to "rt.springshot.com",
        "channels"     to "channels.springshot.com",
        "observations" to "observations-api.springshot.com",
        "workflow_gen" to "workflow-gen.springshot.com",
        "webapp"       to "webapp.springshot.com",
        "firebase"     to "firebase.springshot.com",
        "mattermost"   to "mattermost.springshot.com",
    )

    // Dedicated client — shorter timeout than the Datadog client, redirects allowed.
    // connectionPool(2, 20s): close idle connections quickly so the radio can sleep.
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(2, 20, TimeUnit.SECONDS))
        .build()

    /**
     * Previous up/down state per endpoint name — used to detect transitions
     * and fire Datadog events only on change.
     */
    private val previousState = mutableMapOf<String, Boolean>()

    /** Run all checks synchronously. Intended to be called from a background coroutine. */
    fun checkAll(): List<EndpointResult> = ENDPOINTS.map { (name, host) -> check(name, host) }

    private fun check(name: String, host: String): EndpointResult {
        val request = Request.Builder()
            .url("https://$host/")
            .get()
            .build()
        val start = System.currentTimeMillis()
        return try {
            http.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                val code = response.code
                EndpointResult(name, host, latency, code, code < 500)
            }
        } catch (e: IOException) {
            val latency = System.currentTimeMillis() - start
            val code = if (e is SocketTimeoutException) -2 else -1
            EndpointResult(name, host, latency, code, false)
        }
    }

    /**
     * Returns a list of (result, isNewTransition) pairs.
     * isNewTransition = true when the up/down state differs from the previous check.
     * Call this instead of checkAll() to get transition-aware results for event firing.
     */
    fun checkWithTransitions(): List<Pair<EndpointResult, Boolean>> {
        return checkAll().map { result ->
            val prev = previousState[result.name]
            val changed = prev != null && prev != result.isUp
            previousState[result.name] = result.isUp
            result to changed
        }
    }
}
