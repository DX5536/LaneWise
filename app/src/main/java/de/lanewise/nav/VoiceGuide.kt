package de.lanewise.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import de.lanewise.settings.AppLanguage
import java.io.File
import java.util.Locale

/**
 * Speaks announcements. With an OmniVoice voice pack for the current language (see [VoicePack]) the
 * pre-rendered clips of an announcement's segments are chained gaplessly; if any segment (e.g. a street outside
 * the pack's region) is missing, the whole sentence is spoken by Android TextToSpeech instead, so one sentence
 * never mixes two voices.
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
    private val queue = ArrayDeque<List<MediaPlayer>>()
    private var playing: List<MediaPlayer> = emptyList()

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.setAudioAttributes(attrs)
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
        val clips = packs[VoiceInventory.code(language)]?.clips(a.parts)
        val players = clips?.let(::prepare)
        if (players != null) {
            if (flush) { stopClips(); tts.stop() }
            queue.addLast(players)
            if (playing.isEmpty()) playNext()
            return
        }
        if (!ready) return
        if (flush) stopClips()
        tts.speak(a.text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, a.key)
    }

    /** Prepared players for all clips, or null (→ Android TTS for the whole sentence) if any clip fails. */
    private fun prepare(files: List<File>): List<MediaPlayer>? {
        if (files.isEmpty()) return null
        val players = ArrayList<MediaPlayer>(files.size)
        for (f in files) {
            val p = runCatching { MediaPlayer().apply { setAudioAttributes(attrs); setDataSource(f.path); prepare() } }.getOrNull()
            if (p == null) { players.forEach { it.release() }; return null }
            players += p
        }
        return players
    }

    /** Chains one utterance's clips with setNextMediaPlayer (gapless), then continues with the queue. */
    private fun playNext() {
        val players = queue.removeFirstOrNull()
        if (players == null) { audio.abandonAudioFocusRequest(focus); return }
        for (i in 0 until players.size - 1) players[i].setNextMediaPlayer(players[i + 1])
        players.forEach { p -> p.setOnCompletionListener { it.release() } }
        players.last().setOnCompletionListener { it.release(); playing = emptyList(); playNext() }
        playing = players
        audio.requestAudioFocus(focus)
        players.first().start()
    }

    private fun stopClips() {
        queue.forEach { u -> u.forEach { runCatching { it.release() } } }
        queue.clear()
        playing.forEach { runCatching { it.stop() }; runCatching { it.release() } }
        playing = emptyList()
    }

    fun beep() { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 150) }

    fun shutdown() {
        stopClips(); audio.abandonAudioFocusRequest(focus)
        tts.stop(); tts.shutdown(); tone?.release()
    }
}
