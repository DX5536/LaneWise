package de.lanewise.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Overpass API client with mirror fallback. The main instance (overpass-api.de) regularly answers
 * 504 "server too busy"; the next mirror is tried on any HTTP/network error.
 */
object Overpass {
    val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    private val longClient: OkHttpClient by lazy {
        Http.client.newBuilder().readTimeout(15, TimeUnit.MINUTES).build()
    }

    /** Small queries: returns the parsed JSON. */
    suspend fun json(query: String): JsonElement = withContext(Dispatchers.IO) {
        execute(query, Http.client) { resp -> Http.json.parseToJsonElement(resp.body?.string().orEmpty()) }
    }

    /** Large downloads: hands the open response to [read] (stream it, don't buffer). */
    suspend fun <T> stream(query: String, read: (Response) -> T): T = withContext(Dispatchers.IO) {
        execute(query, longClient, read)
    }

    private fun <T> execute(query: String, client: OkHttpClient, read: (Response) -> T): T {
        var last: Exception? = null
        for (url in ENDPOINTS) {
            try {
                val req = Request.Builder().url(url).header("Accept", "application/json")
                    .post(FormBody.Builder().add("data", query).build()).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Overpass HTTP ${resp.code} at $url")
                    val type = resp.header("Content-Type").orEmpty()
                    if (!type.contains("json")) throw IOException("Overpass returned $type at $url")
                    return read(resp)
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("No Overpass endpoint reachable")
    }
}
