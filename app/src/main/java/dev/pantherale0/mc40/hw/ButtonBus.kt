package dev.pantherale0.mc40.hw

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

data class ButtonPress(
    val button: String,
    val keyCode: Int,
    val scanCode: Int
)

object ButtonBus {
    fun interface Listener {
        fun onPress(press: ButtonPress)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()
    private var lastButton: String? = null
    private var lastAt: Long = 0

    @Synchronized
    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun post(press: ButtonPress) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (press.button == lastButton && now - lastAt < 400) return
            lastButton = press.button
            lastAt = now
        }
        val snapshot: List<Listener>
        synchronized(this) {
            snapshot = listeners.toList()
        }
        main.post {
            for (listener in snapshot) listener.onPress(press)
        }
    }
}

object HardwareButtons {
    private const val KEYCODE_PTT = 226
    private const val SCAN_PTT = 739
    private const val SCAN_TRIGGER_4 = 723

    fun from(event: KeyEvent): ButtonPress? {
        val button = when {
            event.scanCode == SCAN_PTT || event.scanCode == SCAN_TRIGGER_4 -> "ptt"
            event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KEYCODE_PTT -> "ptt"
            event.keyCode == KeyEvent.KEYCODE_BUTTON_R2 -> "headset"
            else -> null
        } ?: return null
        return ButtonPress(button, event.keyCode, event.scanCode)
    }
}
