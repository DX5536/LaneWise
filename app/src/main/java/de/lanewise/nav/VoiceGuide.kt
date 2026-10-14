package de.lanewise.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import de.lanewise.settings.AppLanguage
import java.util.Locale

/** Android TextToSpeech wrapper; works offline when the device has the German/English voice installed. */
class VoiceGuide(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var language: AppLanguage = AppLanguage.GERMAN
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }.getOrNull()

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            applyLanguage()
        }
    }

    fun setLanguage(lang: AppLanguage) {
        if (lang != language) { language = lang; applyLanguage() }
    }

    private fun applyLanguage() {
        if (!ready) return
        tts.language = when (language) {
            AppLanguage.GERMAN -> Locale.GERMANY
            AppLanguage.ENGLISH -> Locale.UK
            AppLanguage.VIETNAMESE -> Locale.forLanguageTag("vi-VN")
        }
    }

    fun speak(a: Announcement) {
        if (!ready) return
        val mode = if (a.priority >= 2) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(a.text, mode, null, a.key)
    }

    fun beep() { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 150) }

    fun shutdown() {
        tts.stop(); tts.shutdown(); tone?.release()
    }
}
