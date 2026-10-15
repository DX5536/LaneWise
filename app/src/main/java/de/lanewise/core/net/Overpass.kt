package de.lanewise.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Overpass API client with mirror fallback and retries. Public instances are frequently overloaded
 * ("Dispatcher_Client::request_read_and_idx::timeout", HTTP 504/429, or an HTML error page with 200),
 * so every mirror is tried, and the whole round is repeated with backoff.
 */
object Overpass {
    val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )
    private val BACKOFF_MS = longArrayOf(0, 3_000, 8_000)

    private val longClient: OkHttpClient by lazy {
        Http.client.newBuilder().readTimeout(15, TimeUnit.MINUTES).build()
    }

    /** Small queries: returns the parsed JSON. Asynchronous and cancellable (see [Http.fetch]). */
    suspend fun json(query: String): JsonElement {
        var last: Exception? = null
        for (wait in BACKOFF_MS) {
            if (wait > 0) delay(wait)
            for (url in ENDPOINTS) {
                try {
                    val req = Request.Builder().url(url).header("Accept", "application/json")
                        .post(FormBody.Builder().add("data", query).build()).build()
                    val r = Http.fetch(req)
                    if (!r.ok) throw IOException("Overpass HTTP ${r.code} at $url")
                    if (!r.contentType.contains("json") || !r.body.trimStart().startsWith("{")) {
                        throw IOException("Overpass returned no JSON (busy?) at $url")
                    }
                    return withContext(kotlinx.coroutines.Dispatchers.Default) { Http.json.parseToJsonElement(r.body) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                }
            }
        }
        throw last ?: IOException("No Overpass endpoint reachable")
    }

    /** Large downloads: hands the open response to [read] (stream it, don't buffer). */
    suspend fun <T> stream(query: String, read: (Response) -> T): T = withRetries(query, longClient, read)

    private suspend fun <T> withRetries(query: String, client: OkHttpClient, read: (Response) -> T): T {
        var last: Exception? = null
        for (wait in BACKOFF_MS) {
            if (wait > 0) delay(wait)
            for (url in ENDPOINTS) {
                try {
                    return withContext(Dispatchers.IO) { execute(url, query, client, read) }
                } catch (e: Exception) {
                    last = e
                }
            }
        }
        throw last ?: IOException("No Overpass endpoint reachable")
    }

    private fun <T> execute(url: String, query: String, client: OkHttpClient, read: (Response) -> T): T {
        val req = Request.Builder().url(url).header("Accept", "application/json")
            .post(FormBody.Builder().add("data", query).build()).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Overpass HTTP ${resp.code} at $url")
            val type = resp.header("Content-Type").orEmpty()
            if (!type.contains("json")) throw IOException("Overpass returned $type at $url")
            return read(resp)
        }
    }
}
