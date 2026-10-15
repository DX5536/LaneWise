package de.lanewise.core.net

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * How busy the online servers of search and stops are (the dot in "Add stop"): green free, yellow moderate, red busy,
 * grey unreachable. The Overpass `/api/status` pages proved unreliable (502 / 500 while queries worked), so each server
 * gets a tiny real request and its answer time counts.
 */
enum class Health { FREE, MODERATE, BUSY, DOWN }

object ServerHealth {

    /** Request timeout of a probe (s): no answer within it counts as unreachable. */
    private const val PROBE_TIMEOUT_S = 8L
    private val probeClient: OkHttpClient by lazy {
        Http.client.newBuilder().callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS).build()
    }

    /** Health from an HTTP answer: [code] null = no answer; [ms] = time to the full answer. */
    fun classify(code: Int?, ms: Long): Health = when {
        code == null -> Health.DOWN
        code == 429 || code == 503 || code == 504 -> Health.BUSY
        code !in 200..299 -> Health.DOWN
        ms < 1_500 -> Health.FREE
        ms < 5_000 -> Health.MODERATE
        else -> Health.BUSY
    }

    /** The worse of two (DOWN is worst). */
    fun worse(a: Health, b: Health): Health = if (a.ordinal >= b.ordinal) a else b

    /** Result of a probe round: overall health, and the Overpass mirrors healthiest first. */
    data class Report(val overall: Health, val overpass: Health, val routing: Health, val overpassOrder: List<String>)

    /**
     * Probes all Overpass mirrors and the routing server ([osrmUrl]) in parallel. The app fails over between the
     * mirrors, so Overpass counts with its best mirror; overall is the worse of Overpass and routing. Also puts the
     * healthiest mirror first for the next searches ([Overpass.preferred]).
     */
    suspend fun probe(osrmUrl: String): Report = coroutineScope {
        val mirrors = Overpass.ENDPOINTS.map { url ->
            // Same headers as real searches (Overpass answers 406 without Accept).
            async { url to timed { Request.Builder().url(url).header("Accept", "application/json")
                .post(FormBody.Builder().add("data", PROBE_QUERY).build()).build() } }
        }
        val osrm = async { timed { Request.Builder().url("$osrmUrl/nearest/v1/driving/9.7320,52.3759").build() } }
        val results = mirrors.map { it.await() }
        val order = results.sortedWith(compareBy({ it.second.first.ordinal }, { it.second.second })).map { it.first }
        val op = results.minOf { it.second.first.ordinal }.let { Health.entries[it] }
        val rt = osrm.await().first
        Overpass.preferred = order
        Report(worse(op, rt), op, rt, order)
    }

    /** Smallest valid Overpass query (one node id), answered in ~0.2 s by a free server. */
    private const val PROBE_QUERY = "[out:json][timeout:5];node(1);out ids;"

    private suspend fun timed(build: () -> Request): Pair<Health, Long> {
        val t0 = System.nanoTime()
        val code = try {
            Http.fetch(build(), probeClient).code
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        return classify(code, ms) to ms
    }
}
