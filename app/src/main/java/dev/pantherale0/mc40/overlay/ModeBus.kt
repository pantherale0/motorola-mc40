package dev.pantherale0.mc40.overlay

import android.os.Handler
import android.os.Looper

object ModeBus {
    fun interface Listener {
        fun onMode(mode: String)
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

    fun post(mode: String) {
        val snapshot: List<Listener>
        synchronized(this) {
            snapshot = listeners.toList()
        }
        main.post {
            for (listener in snapshot) listener.onMode(mode)
        }
    }
}
