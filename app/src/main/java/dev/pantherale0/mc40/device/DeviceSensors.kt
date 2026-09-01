package dev.pantherale0.mc40.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.SystemClock
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class DeviceSnapshot(
    val batteryLevel: Int,
    val batteryState: String,
    val batteryTemperature: Float,
    val charging: Boolean,
    val wifiSsid: String,
    val wifiIp: String,
    val wifiRssi: Int,
    val lastReboot: String,
    val proximity: String
)

object DeviceSensors {
    fun snapshot(context: Context): DeviceSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = batteryPercent(level, scale)
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val charging = plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val state = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> if (charging) "charging" else "discharging"
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifi.connectionInfo
        val ssid = info?.ssid?.replace("\"", "")?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" } ?: "not connected"
        val rssi = info?.rssi ?: 0
        val ip = formatIp(info?.ipAddress ?: 0)
        return DeviceSnapshot(
            batteryLevel = pct,
            batteryState = state,
            batteryTemperature = tempTenths / 10f,
            charging = charging,
            wifiSsid = ssid,
            wifiIp = ip,
            wifiRssi = rssi,
            lastReboot = iso(System.currentTimeMillis() - SystemClock.elapsedRealtime()),
            proximity = ProximityMonitor.lastState.ifBlank { "far" }
        )
    }

    fun nowIso(): String = iso(System.currentTimeMillis())

    private fun batteryPercent(level: Int, scale: Int): Int {
        if (level < 0) return 0
        val pct = when {
            scale in 1..100 && level <= scale -> (level * 100) / scale
            level <= 100 -> level
            else -> (level * 100) / 255
        }
        return pct.coerceIn(0, 100)
    }

    private fun iso(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(millis))
    }

    private fun formatIp(raw: Int): String {
        if (raw == 0) return ""
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw).array()
        return try {
            InetAddress.getByAddress(bytes).hostAddress ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
