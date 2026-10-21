package de.lanewise.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import de.lanewise.settings.AppLanguage
import java.io.File
import java.nio.ByteOrder
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Speaks announcements. With an OmniVoice voice pack for the current language (see [VoicePack]) the
 * pre-rendered clips of an announcement's segments are decoded, trimmed and joined into one sound with a short natural
 * gap ([PcmJoin]; file after file left an audible pause before the street name); if any segment (e.g. a street outside
 * the pack's region) is missing, the pack says the sentence without it ([VoiceInventory.sayable]: "In 300 metres turn
 * right"); only when a fixed phrase is missing does Android TextToSpeech say the whole sentence - one sentence never
 * mixes two voices. Pack clips and TTS sentences share one queue ([worker]), so two sentences never overlap (they did
 * when a turn and a lane hint came in the same update: the clip and the TTS voice talked at once).
 */
class VoiceGuide(context: Context) : TextToSpeech.OnInitListener {
    private val app = context.applicationContext
    private val tts = TextToSpeech(app, this)
    private var ready = false
    private var language: AppLanguage = AppLanguage.GERMAN
    private var englishAccent = "en-gb"
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }.getOrNull()

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()

    /** Selected pack per language code ("de", "en", "vi"); null entry = Android TTS. */
    private var packs: Map<String, VoicePack> = emptyMap()
    /** Plays utterances one after another (the queue); [generation] changes on a flush to drop what is pending. */
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null

    private class Pcm(val rate: Int, val channels: Int, val samples: ShortArray)
    /** Decoded, trimmed clips by file (the same phrases come back on every route). */
    private val cache = object : LinkedHashMap<String, Pcm>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pcm>?) = size > 120
    }

    /** TTS utterances the worker waits for (utterance id → done). */
    private val ttsDone = ConcurrentHashMap<String, CountDownLatch>()

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.setAudioAttributes(attrs)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { id?.let { ttsDone.remove(it)?.countDown() } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { id?.let { ttsDone.remove(it)?.countDown() } }
                override fun onError(id: String?, code: Int) { id?.let { ttsDone.remove(it)?.countDown() } }
                override fun onStop(id: String?, interrupted: Boolean) { id?.let { ttsDone.remove(it)?.countDown() } }
            })
            applyLanguage()
        }
    }

    fun setLanguage(lang: AppLanguage) {
        if (lang != language) { language = lang; applyLanguage() }
    }

    /** Installed packs the user selected (ids); packs for other languages are kept for when the language changes. */
    fun setPacks(selected: List<VoicePack>) {
        packs = selected.associateBy { it.language }
        englishAccent = if (packs["en"]?.id?.startsWith("en-us") == true) "en-us" else "en-gb"
        applyLanguage()
    }

    private fun applyLanguage() {
        if (!ready) return
        tts.language = when (language) {
            AppLanguage.GERMAN -> Locale.GERMANY
            AppLanguage.ENGLISH -> if (englishAccent == "en-us") Locale.US else Locale.UK
            AppLanguage.VIETNAMESE -> Locale.forLanguageTag("vi-VN")
        }
    }

    fun speak(a: Announcement) {
        val flush = a.priority >= 2
        val pack = packs[VoiceInventory.code(language)]
        val clips = pack?.let { p -> VoiceInventory.sayable(a.parts, p::has)?.let { p.clips(it) } }
        if (flush) { stopClips(); tts.stop() }
        val gen = generation
        worker.execute {
            if (gen != generation) return@execute
            val pcm = clips?.takeIf { it.isNotEmpty() }?.let { utterance(it) }
            // No pack, a fixed phrase missing, or a clip that cannot be decoded: the whole sentence by Android TTS.
            if (pcm == null) sayTts(a, gen) else play(pcm, gen)
        }
    }

    /** Speaks [a] with Android TTS and waits until it is done (on the worker), so nothing else talks over it. */
    private fun sayTts(a: Announcement, gen: Int) {
        if (!ready || gen != generation) return
        val id = "${a.key}#${System.nanoTime()}"
        val done = CountDownLatch(1).also { ttsDone[id] = it }
        audio.requestAudioFocus(focus)
        try {
            if (tts.speak(a.text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) return
            // Wait for the end (a flush stops TTS and ends the wait too); never longer than a long sentence takes.
            while (gen == generation && !done.await(100, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() - id.substringAfter('#').toLong() > 20_000_000_000L) break
            }
        } finally {
            ttsDone.remove(id)
            audio.abandonAudioFocusRequest(focus)
        }
    }

    /** All clips of one announcement, trimmed and joined; null if one cannot be decoded or formats differ. */
    private fun utterance(files: List<File>): Pcm? {
        val parts = files.map { f -> synchronized(cache) { cache[f.path] } ?: decode(f)?.also { synchronized(cache) { cache[f.path] = it } } ?: return null }
        val rate = parts.first().rate; val ch = parts.first().channels
        if (parts.any { it.rate != rate || it.channels != ch }) return null
        return Pcm(rate, ch, PcmJoin.join(parts.map { it.samples }, ch, rate))
    }

    /** Decodes a clip (OGG Vorbis / Opus …) to 16-bit PCM with the platform decoder and trims its silence. */
    private fun decode(f: File): Pcm? = runCatching {
        val ex = MediaExtractor()
        ex.setDataSource(f.path)
        val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val chunks = ArrayList<ShortArray>()
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val b = codec.getOutputBuffer(o)!!
                    b.position(info.offset); b.limit(info.offset + info.size)
                    val sb = b.order(ByteOrder.nativeOrder()).asShortBuffer()
                    chunks += ShortArray(sb.remaining()).also { sb.get(it) }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
        val all = ShortArray(chunks.sumOf { it.size }).also { out -> var p = 0; chunks.forEach { it.copyInto(out, p); p += it.size } }
        Pcm(rate, ch, PcmJoin.trim(all, ch, rate))
    }.getOrNull()

    /** Plays one utterance to its end (blocking the worker), unless a flush ([generation]) stops it. */
    private fun play(pcm: Pcm, gen: Int) {
        val chMask = if (pcm.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val t = runCatching {
            AudioTrack.Builder().setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(pcm.rate)
                    .setChannelMask(chMask).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(pcm.rate, chMask, AudioFormat.ENCODING_PCM_16BIT) * 2)
                .build()
        }.getOrNull() ?: return
        track = t
        audio.requestAudioFocus(focus)
        try {
            t.play()
            var pos = 0
            val chunk = pcm.rate / 10 * pcm.channels
            while (pos < pcm.samples.size && gen == generation) {
                val n = t.write(pcm.samples, pos, minOf(chunk, pcm.samples.size - pos))
                if (n <= 0) break
                pos += n
            }
            // Wait for the last buffer to be heard before the next utterance / releasing focus.
            val frames = pcm.samples.size / pcm.channels
            while (gen == generation && t.playState == AudioTrack.PLAYSTATE_PLAYING && t.playbackHeadPosition < frames) Thread.sleep(20)
        } finally {
            runCatching { t.stop() }; t.release()
            track = null
            audio.abandonAudioFocusRequest(focus)
        }
    }

    /** Drops pending utterances and stops the one playing. */
    private fun stopClips() {
        generation++
        ttsDone.values.forEach { it.countDown() }
        runCatching { track?.pause(); track?.flush() }
    }

    fun beep() { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 150) }

    fun shutdown() {
        stopClips(); worker.shutdownNow(); audio.abandonAudioFocusRequest(focus)
        tts.stop(); tts.shutdown(); tone?.release()
    }
}
