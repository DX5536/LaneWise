package de.lanewise.nav

import android.content.Context
import de.lanewise.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * A pre-rendered OmniVoice voice pack (built on the PC by `tools/voice_pack`): `pack.json`, `index.tsv`
 * (normalised segment text → clip file) and Ogg Vorbis clips. Lives in `files/voices/<id>/`, or in
 * `Android/data/<app>/files/voices/<id>/` so packs can also be copied over USB (`adb push`).
 */
class VoicePack(val dir: File, val id: String, val language: String, val name: String) {

    private val index: Map<String, String> by lazy {
        File(dir, "index.tsv").takeIf { it.exists() }?.readLines(Charsets.UTF_8).orEmpty().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
        }.toMap()
    }

    val clipCount get() = index.size

    /** Clip files for all [parts], or null if any part is missing (then the whole sentence goes to Android TTS). */
    fun clips(parts: List<String>): List<File>? = parts.map { p ->
        index[VoiceInventory.normalize(p)]?.let { File(dir, it) }?.takeIf { it.exists() } ?: return null
    }

    /** A pack that can be downloaded (GitHub release asset `<id>.zip`). */
    data class Available(val id: String, val language: String, val name: String)

    companion object {
        /** Voice packs the owner renders and uploads to the GitHub release [DEFAULT_BASE_URL]. */
        val CATALOG = listOf(
            Available("de-omnivoice", "de", "Deutsch · OmniVoice"),
            Available("en-us-omnivoice", "en", "English (US) · OmniVoice"),
            Available("en-gb-omnivoice", "en", "English (UK) · OmniVoice"),
            Available("vi-omnivoice", "vi", "Tiếng Việt · OmniVoice"),
        )
        const val DEFAULT_BASE_URL = "https://github.com/DX5536/LaneWise/releases/download/voices-v1/"

        private fun roots(context: Context) = listOfNotNull(File(context.filesDir, "voices"), context.getExternalFilesDir("voices"))

        /** All installed packs (clips are Ogg Opus, which MediaPlayer plays from Android 10). */
        fun installed(context: Context): List<VoicePack> = if (android.os.Build.VERSION.SDK_INT < 29) emptyList() else roots(context).flatMap { root ->
            root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { load(it) }
        }.distinctBy { it.id }

        fun load(dir: File): VoicePack? = runCatching {
            val meta = Http.json.parseToJsonElement(File(dir, "pack.json").readText()).jsonObject
            VoicePack(dir, meta["id"]!!.jsonPrimitive.content, meta["language"]!!.jsonPrimitive.content,
                meta["name"]?.jsonPrimitive?.content ?: dir.name)
        }.getOrNull()

        /** Downloads and unpacks `<baseUrl><id>.zip` into `files/voices/<id>/`; [progress] gets 0..100. */
        suspend fun download(context: Context, id: String, baseUrl: String = DEFAULT_BASE_URL, progress: (Int) -> Unit = {}): VoicePack =
            withContext(Dispatchers.IO) {
                val target = File(File(context.filesDir, "voices"), id)
                val tmp = File(target.parentFile, "$id.part").also { it.deleteRecursively(); it.mkdirs() }
                val req = Request.Builder().url(baseUrl.trimEnd('/') + "/$id.zip").build()
                Http.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty response")
                    val total = body.contentLength()
                    var read = 0L
                    val counting = object : java.io.FilterInputStream(body.byteStream()) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int =
                            super.read(b, off, len).also { if (it > 0) { read += it; if (total > 0) progress((read * 100 / total).toInt()) } }
                    }
                    ZipInputStream(counting.buffered()).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: break
                            val out = File(tmp, e.name.substringAfterLast('/'))   // flat archive, no path traversal
                            if (!e.isDirectory && out.name.isNotEmpty()) out.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
                val pack = load(tmp) ?: throw IOException("not a LaneWise voice pack")
                target.deleteRecursively()
                if (!tmp.renameTo(target)) throw IOException("could not install voice pack")
                load(target) ?: pack
            }

        fun delete(pack: VoicePack) { pack.dir.deleteRecursively() }
    }
}
