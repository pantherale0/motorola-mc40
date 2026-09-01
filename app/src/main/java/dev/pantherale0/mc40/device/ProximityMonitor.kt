package dev.pantherale0.mc40.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import dev.pantherale0.mc40.Mc40App

class ProximityMonitor(
    context: Context,
    private val onChange: (String) -> Unit
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    fun start() {
        if (sensor == null) {
            Log.w(Mc40App.TAG, "No proximity sensor")
            return
        }
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val farRange = sensor?.maximumRange ?: event.values[0]
        val next = if (event.values[0] < farRange) "close" else "far"
        if (next == lastState) return
        lastState = next
        onChange(next)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        @Volatile
        var lastState: String = "far"
    }
}
