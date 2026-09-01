package dev.pantherale0.mc40.overlay

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.util.Log
import dev.pantherale0.mc40.Mc40App
import dev.pantherale0.mc40.R

class FeedbackPlayer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var tone: ToneGenerator? = null
    private val stopLed = Runnable { notifications.cancel(LED_ID) }

    fun play(command: OverlayCommand) {
        if (!command.hasFeedback) return
        command.beep?.let { beep(it) }
        command.vibrateMs?.let { vibrate(it) }
        command.ledColor?.let { led(it, command.ledDurationSec) }
    }

    fun stop() {
        main.removeCallbacks(stopLed)
        notifications.cancel(LED_ID)
        runCatching { vibrator.cancel() }
        releaseTone()
    }

    private fun beep(kind: String) {
        val toneType = when (kind.lowercase()) {
            "error", "nack", "fail", "bad" -> ToneGenerator.TONE_PROP_NACK
            "scan", "click" -> ToneGenerator.TONE_PROP_BEEP
            "ok", "ack", "success", "good", "true" -> ToneGenerator.TONE_PROP_ACK
            "off", "false", "none" -> return
            else -> ToneGenerator.TONE_PROP_BEEP
        }
        val duration = if (toneType == ToneGenerator.TONE_PROP_NACK) 400 else 180
        try {
            if (tone == null) {
                tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            }
            tone?.startTone(toneType, duration)
        } catch (e: Exception) {
            Log.w(Mc40App.TAG, "Beep failed: ${e.message}")
        }
    }

    private fun vibrate(ms: Int) {
        try {
            if (ms <= 0) {
                vibrator.cancel()
                return
            }
            @Suppress("DEPRECATION")
            vibrator.vibrate(ms.toLong().coerceAtMost(2000L))
        } catch (e: Exception) {
            Log.w(Mc40App.TAG, "Vibrate failed: ${e.message}")
        }
    }

    private fun led(colorName: String, durationSec: Int?) {
        main.removeCallbacks(stopLed)
        val name = colorName.lowercase()
        if (name == "off" || name == "false" || name == "none") {
            notifications.cancel(LED_ID)
            return
        }
        val color = argb(name)
        if (color == Color.TRANSPARENT) {
            notifications.cancel(LED_ID)
            return
        }
        @Suppress("DEPRECATION")
        val notification = Notification.Builder(context)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("LED")
            .setDefaults(0)
            .setLights(color, 300, 300)
            .setSound(null)
            .setVibrate(null)
            .setAutoCancel(true)
            .build()
        notification.ledARGB = color
        notification.ledOnMS = 300
        notification.ledOffMS = 300
        notification.flags = notification.flags or Notification.FLAG_SHOW_LIGHTS
        notifications.notify(LED_ID, notification)
        val seconds = durationSec ?: 3
        if (seconds > 0) {
            main.postDelayed(stopLed, seconds * 1000L)
        }
    }

    private fun releaseTone() {
        runCatching { tone?.release() }
        tone = null
    }

    companion object {
        private const val LED_ID = 41

        fun argb(name: String): Int {
            val raw = name.trim().lowercase()
            if (raw.startsWith("#")) {
                return runCatching { Color.parseColor(raw) }.getOrElse { Color.RED }
            }
            return when (raw) {
                "green" -> Color.GREEN
                "blue" -> Color.BLUE
                "amber", "yellow", "orange" -> Color.parseColor("#FFC107")
                "white" -> Color.WHITE
                "cyan" -> Color.CYAN
                "magenta" -> Color.MAGENTA
                "off", "false", "none" -> Color.TRANSPARENT
                else -> Color.RED
            }
        }
    }
}
