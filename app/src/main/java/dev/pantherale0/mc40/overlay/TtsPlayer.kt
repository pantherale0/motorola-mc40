package dev.pantherale0.mc40.overlay

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import dev.pantherale0.mc40.Mc40App
import java.util.Locale

class TtsPlayer(context: Context) : TextToSpeech.OnInitListener {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val tts = TextToSpeech(context.applicationContext, this)
    private var pending: Triple<String, Float, Int>? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.US
            Log.i(Mc40App.TAG, "TTS ready (Pico)")
            pending?.let { (text, volume, stream) ->
                pending = null
                speak(text, volume, stream, null)
            }
        } else {
            Log.w(Mc40App.TAG, "TTS init failed status=$status")
        }
        onReady?.invoke(ready)
    }

    fun speak(text: String, volume: Float?, stream: Int?, language: String?) {
        val clipped = text.trim().take(MAX_CHARS)
        if (clipped.isEmpty()) return
        if (!ready) {
            pending = Triple(clipped, volume ?: 1f, stream ?: AudioManager.STREAM_MUSIC)
            return
        }
        language?.let { applyLanguage(it) }
        val useStream = stream ?: AudioManager.STREAM_MUSIC
        @Suppress("DEPRECATION")
        audio.requestAudioFocus(null, useStream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, useStream)
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, (volume ?: 1f).coerceIn(0f, 1f))
        val result = tts.speak(clipped, TextToSpeech.QUEUE_FLUSH, params, "mc40")
        if (result != TextToSpeech.SUCCESS) {
            Log.w(Mc40App.TAG, "TTS speak failed $result")
        }
    }

    fun stopSpeaking() {
        pending = null
        runCatching { tts.stop() }
        @Suppress("DEPRECATION")
        audio.abandonAudioFocus(null)
    }

    fun shutdown() {
        stopSpeaking()
        runCatching { tts.shutdown() }
        ready = false
    }

    private fun applyLanguage(tag: String) {
        val parts = tag.trim().replace('_', '-').split('-')
        val locale = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(parts[0])
        val check = tts.isLanguageAvailable(locale)
        if (check >= TextToSpeech.LANG_AVAILABLE) {
            tts.language = locale
        } else {
            tts.language = Locale.US
        }
    }

    companion object {
        private const val MAX_CHARS = 400
        @Volatile var ready: Boolean = false
        var onReady: ((Boolean) -> Unit)? = null

        fun streamFrom(name: String?): Int? {
            return when (name?.trim()?.lowercase()) {
                "alarm", "alarm_stream", "alarm_stream_max" -> AudioManager.STREAM_ALARM
                "notification", "ring" -> AudioManager.STREAM_NOTIFICATION
                "music", "media", "call" -> AudioManager.STREAM_MUSIC
                else -> null
            }
        }
    }
}
