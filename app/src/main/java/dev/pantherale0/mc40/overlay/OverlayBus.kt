package dev.pantherale0.mc40.overlay

import android.os.Handler
import android.os.Looper

object OverlayBus {
    fun interface Listener {
        fun onCommand(command: OverlayCommand)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()

    @Synchronized
    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun post(command: OverlayCommand) {
        val snapshot: List<Listener>
        synchronized(this) {
            snapshot = listeners.toList()
        }
        main.post {
            for (listener in snapshot) {
                listener.onCommand(command)
            }
        }
    }
}
