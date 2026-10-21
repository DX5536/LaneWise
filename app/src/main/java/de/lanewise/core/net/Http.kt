package de.lanewise.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    const val USER_AGENT = "LaneWise/0.1 (Android; +https://github.com/DX5536/LaneWise)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The network changed (Wi-Fi gone, mobile data on): pooled connections belong to the old network and would only
     * fail or hang on the next request. Drop them, and cancel calls still waiting on the old network.
     */
    fun onNetworkChanged() {
        client.connectionPool.evictAll()
        client.dispatcher.runningCalls().forEach { it.cancel() }
    }

    /** Status, content type and body of a finished request. */
    data class Reply(val code: Int, val contentType: String, val body: String) {
        val ok get() = code in 200..299
    }

    /**
     * Runs [req] on OkHttp's own threads and reads the whole body there; cancelling the coroutine cancels the call.
     * Never a blocking execute() inside withContext(Dispatchers.IO): it keeps its IO thread until the server answers or
     * the read timeout (60 s, Overpass downloads up to 15 min) passes, even when nobody waits for it any more. A few
     * route searches (about 20 traffic requests each) filled the shared IO pool, and Settings could not save for
     * minutes after a demo run.
     */
    suspend fun fetch(req: Request, c: OkHttpClient = client): Reply =
        call(req, c) { Reply(it.code, it.header("Content-Type").orEmpty(), it.body?.string().orEmpty()) }

    /** Like [fetch], but the body as bytes (gzip files, binary feeds). */
    suspend fun fetchBytes(req: Request, c: OkHttpClient = client): Pair<Int, ByteArray> =
        call(req, c) { it.code to (it.body?.bytes() ?: ByteArray(0)) }

    private suspend fun <T> call(req: Request, c: OkHttpClient, read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
        val call = c.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val reply = try {
                    response.use(read)
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                    return
                }
                if (cont.isActive) cont.resume(reply)
            }
        })
    }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val r = fetch(req)
        if (!r.ok) throw IOException("HTTP ${r.code} for ${url.substringBefore('?')}: ${r.body.take(200)}")
        return r.body
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        getText(url, headers).let { withContext(Dispatchers.Default) { json.parseToJsonElement(it) } }

    suspend fun postJson(url: String, body: String): JsonElement {
        val req = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()
        val r = fetch(req)
        if (!r.ok) throw IOException("HTTP ${r.code} for ${url.substringBefore('?')}: ${r.body.take(200)}")
        return withContext(Dispatchers.Default) { json.parseToJsonElement(r.body) }
    }

    suspend fun postForm(url: String, form: String): String {
        val req = Request.Builder().url(url)
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val r = fetch(req)
        if (!r.ok) throw IOException("HTTP ${r.code}: ${r.body.take(200)}")
        return r.body
    }
}

// --- tiny helpers for navigating JsonElement trees -------------------------------------------

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: JsonArray? get() = this as? JsonArray
val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
val JsonElement?.dbl: Double? get() = (this as? JsonPrimitive)?.doubleOrNull
val JsonElement?.int: Int? get() = (this as? JsonPrimitive)?.intOrNull
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)
