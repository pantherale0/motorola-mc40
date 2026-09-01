package dev.pantherale0.mc40.scan

import android.os.Handler
import android.os.Looper

object ScanBus {
    fun interface Listener {
        fun onScan(result: ScanResult)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()
    private var lastData: String? = null
    private var lastAt: Long = 0

    @Synchronized
    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    @Synchronized
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun post(result: ScanResult) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (result.data == lastData && now - lastAt < 750) return
            lastData = result.data
            lastAt = now
        }
        val snapshot: List<Listener>
        synchronized(this) {
            snapshot = listeners.toList()
        }
        main.post {
            for (listener in snapshot) {
                listener.onScan(result)
            }
        }
    }
}
