package dev.pantherale0.mc40.ha

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.pantherale0.mc40.Mc40App
import dev.pantherale0.mc40.device.DeviceSensors
import dev.pantherale0.mc40.overlay.TtsPlayer
import dev.pantherale0.mc40.prefs.AppPrefs
import dev.pantherale0.mc40.scan.ScanResult

class SensorPublisher(
    private val api: HaApi,
    private val prefs: AppPrefs
) {
    @Volatile
    private var registered = prefs.sensorsRegistered

    fun ensureRegistered() {
        if (registered) return
        val snap = DeviceSensors.snapshot(Mc40App.instance)
        val specs = sensorSpecs(snap, lastBarcode = "", lastType = "", scannerReady = prefs.scannerReady)
        for (spec in specs) {
            val result = api.webhook("register_sensor", spec)
            if (result.isFailure) {
                Log.w(Mc40App.TAG, "register_sensor ${spec.get("unique_id")} failed: ${result.exceptionOrNull()?.message}")
            }
        }
        registered = true
        prefs.sensorsRegistered = true
    }

    fun publishDevice(trigger: String) {
        ensureRegistered()
        val snap = DeviceSensors.snapshot(Mc40App.instance)
        val updates = JsonArray().apply {
            add(update("sensor", "battery_level", snap.batteryLevel, "mdi:battery"))
            add(update("sensor", "battery_state", snap.batteryState, "mdi:battery-charging"))
            add(update("sensor", "battery_temperature", snap.batteryTemperature, "mdi:thermometer"))
            add(update("binary_sensor", "is_charging", snap.charging, "mdi:power-plug"))
            add(update("sensor", "wifi_connection", snap.wifiSsid, "mdi:wifi"))
            add(update("sensor", "wifi_ip_address", snap.wifiIp, "mdi:ip"))
            add(update("sensor", "wifi_signal_strength", snap.wifiRssi, "mdi:wifi-strength-3"))
            add(update("sensor", "last_update_trigger", trigger, "mdi:update"))
            add(update("sensor", "last_reboot", snap.lastReboot, "mdi:restart"))
            add(update("binary_sensor", "scanner_ready", prefs.scannerReady, "mdi:barcode-scan"))
            add(update("sensor", "scanner_mode", prefs.scannerMode, "mdi:swap-horizontal"))
            add(update("sensor", "proximity", snap.proximity, "mdi:distribute-horizontal-center"))
            add(update("binary_sensor", "tts_ready", TtsPlayer.ready, "mdi:text-to-speech"))
        }
        postUpdates(updates)
    }

    fun publishScan(scan: ScanResult) {
        ensureRegistered()
        val scannedAt = DeviceSensors.nowIso()
        val barcodeUpdate = update("sensor", "last_barcode", scan.data, "mdi:barcode").apply {
            add("attributes", JsonObject().apply {
                addProperty("symbology", scan.labelType)
                addProperty("source", scan.source)
                addProperty("scanned_at", scannedAt)
            })
        }
        val updates = JsonArray().apply {
            add(barcodeUpdate)
            add(update("sensor", "last_update_trigger", "barcode_scan", "mdi:update"))
        }
        postUpdates(updates)
        val event = JsonObject().apply {
            addProperty("event_type", "mc40_barcode_scanned")
            add("event_data", JsonObject().apply {
                addProperty("barcode", scan.data)
                addProperty("symbology", scan.labelType)
                addProperty("source", scan.source)
                addProperty("device_id", prefs.deviceName)
                addProperty("scanned_at", scannedAt)
                addProperty("mode", prefs.scannerMode)
            })
        }
        val fired = api.webhook("fire_event", event)
        if (fired.isFailure) {
            Log.w(Mc40App.TAG, "fire_event failed: ${fired.exceptionOrNull()?.message}")
        }
    }

    fun publishMode() {
        ensureRegistered()
        api.webhook(
            "register_sensor",
            register(
                type = "sensor",
                uniqueId = "scanner_mode",
                name = "Scanner Mode",
                state = prefs.scannerMode,
                icon = "mdi:swap-horizontal",
                category = "diagnostic"
            )
        )
        postUpdates(JsonArray().apply {
            add(update("sensor", "scanner_mode", prefs.scannerMode, "mdi:swap-horizontal"))
        })
    }

    fun publishProximity(state: String) {
        ensureRegistered()
        api.webhook(
            "register_sensor",
            register(
                type = "sensor",
                uniqueId = "proximity",
                name = "Proximity",
                state = state,
                icon = "mdi:distribute-horizontal-center",
                category = "diagnostic"
            )
        )
        postUpdates(JsonArray().apply {
            add(update("sensor", "proximity", state, "mdi:distribute-horizontal-center"))
        })
    }

    fun publishTtsReady(ready: Boolean) {
        ensureRegistered()
        api.webhook(
            "register_sensor",
            register(
                type = "binary_sensor",
                uniqueId = "tts_ready",
                name = "TTS Ready",
                state = ready,
                icon = "mdi:text-to-speech",
                category = "diagnostic"
            )
        )
        postUpdates(JsonArray().apply {
            add(update("binary_sensor", "tts_ready", ready, "mdi:text-to-speech"))
        })
    }

    fun publishStockAdjust(
        barcode: String,
        name: String,
        quantity: Double,
        measure: String,
        unit: String
    ) {
        fireNamed("mc40_stock_adjust", barcode, name, quantity, measure, unit, "use")
    }

    fun publishShoppingAdd(
        barcode: String,
        name: String,
        quantity: Double,
        measure: String,
        unit: String
    ) {
        fireNamed("mc40_shopping_add", barcode, name, quantity, measure, unit, "shopping")
    }

    private fun fireNamed(
        eventType: String,
        barcode: String,
        name: String,
        quantity: Double,
        measure: String,
        unit: String,
        mode: String
    ) {
        val event = JsonObject().apply {
            addProperty("event_type", eventType)
            add("event_data", JsonObject().apply {
                addProperty("barcode", barcode)
                addProperty("name", name)
                addProperty("quantity", quantity)
                addProperty("measure", measure)
                addProperty("unit", unit)
                addProperty("mode", mode)
                addProperty("device_id", prefs.deviceName)
                addProperty("scanned_at", DeviceSensors.nowIso())
            })
        }
        val fired = api.webhook("fire_event", event)
        if (fired.isFailure) {
            Log.w(Mc40App.TAG, "$eventType failed: ${fired.exceptionOrNull()?.message}")
        }
    }

    fun publishButton(button: String, keyCode: Int, scanCode: Int) {
        val event = JsonObject().apply {
            addProperty("event_type", "mc40_button_pressed")
            add("event_data", JsonObject().apply {
                addProperty("button", button)
                addProperty("keycode", keyCode)
                addProperty("scancode", scanCode)
                addProperty("mode", prefs.scannerMode)
                addProperty("device_id", prefs.deviceName)
                addProperty("pressed_at", DeviceSensors.nowIso())
            })
        }
        val fired = api.webhook("fire_event", event)
        if (fired.isFailure) {
            Log.w(Mc40App.TAG, "mc40_button_pressed failed: ${fired.exceptionOrNull()?.message}")
        }
    }

    private fun postUpdates(updates: JsonArray) {
        val result = api.webhook("update_sensor_states", updates)
        val body = result.getOrNull() ?: run {
            Log.w(Mc40App.TAG, "update_sensor_states failed: ${result.exceptionOrNull()?.message}")
            return
        }
        try {
            val parsed = JsonParser.parseString(body).asJsonObject
            for ((_, value) in parsed.entrySet()) {
                if (value.isJsonObject && value.asJsonObject.get("success")?.asBoolean == false) {
                    val code = value.asJsonObject.getAsJsonObject("error")?.get("code")?.asString
                    if (code == "not_registered") {
                        registered = false
                        prefs.sensorsRegistered = false
                    }
                }
            }
        } catch (_: Exception) {
            // Non-JSON webhook replies are ignored.
        }
    }

    private fun sensorSpecs(
        snap: dev.pantherale0.mc40.device.DeviceSnapshot,
        lastBarcode: String,
        lastType: String,
        scannerReady: Boolean
    ): List<JsonObject> {
        return listOf(
            register(
                type = "sensor",
                uniqueId = "battery_level",
                name = "Battery Level",
                state = snap.batteryLevel,
                icon = "mdi:battery",
                deviceClass = "battery",
                unit = "%",
                stateClass = "measurement",
                category = "diagnostic"
            ),
            register("sensor", "battery_state", "Battery State", snap.batteryState, "mdi:battery-charging", category = "diagnostic"),
            register(
                type = "sensor",
                uniqueId = "battery_temperature",
                name = "Battery Temperature",
                state = snap.batteryTemperature,
                icon = "mdi:thermometer",
                deviceClass = "temperature",
                unit = "°C",
                stateClass = "measurement",
                category = "diagnostic"
            ),
            register("binary_sensor", "is_charging", "Charging", snap.charging, "mdi:power-plug", deviceClass = "battery_charging", category = "diagnostic"),
            register("sensor", "wifi_connection", "Wi-Fi Connection", snap.wifiSsid, "mdi:wifi", category = "diagnostic"),
            register("sensor", "wifi_ip_address", "Wi-Fi IP Address", snap.wifiIp, "mdi:ip", category = "diagnostic"),
            register(
                type = "sensor",
                uniqueId = "wifi_signal_strength",
                name = "Wi-Fi Signal Strength",
                state = snap.wifiRssi,
                icon = "mdi:wifi-strength-3",
                unit = "dBm",
                stateClass = "measurement",
                category = "diagnostic"
            ),
            register("sensor", "last_update_trigger", "Last Update Trigger", "registration", "mdi:update", category = "diagnostic"),
            register("sensor", "last_reboot", "Last Reboot", snap.lastReboot, "mdi:restart", category = "diagnostic"),
            register(
                type = "sensor",
                uniqueId = "last_barcode",
                name = "Last Barcode",
                state = lastBarcode.ifEmpty { "unknown" },
                icon = "mdi:barcode",
                attributes = JsonObject().apply { addProperty("symbology", lastType) }
            ),
            register("binary_sensor", "scanner_ready", "Scanner Ready", scannerReady, "mdi:barcode-scan"),
            register(
                type = "sensor",
                uniqueId = "scanner_mode",
                name = "Scanner Mode",
                state = prefs.scannerMode,
                icon = "mdi:swap-horizontal",
                category = "diagnostic"
            ),
            register(
                type = "sensor",
                uniqueId = "proximity",
                name = "Proximity",
                state = snap.proximity,
                icon = "mdi:distribute-horizontal-center",
                category = "diagnostic"
            ),
            register(
                type = "binary_sensor",
                uniqueId = "tts_ready",
                name = "TTS Ready",
                state = TtsPlayer.ready,
                icon = "mdi:text-to-speech",
                category = "diagnostic"
            )
        )
    }

    private fun register(
        type: String,
        uniqueId: String,
        name: String,
        state: Any,
        icon: String,
        deviceClass: String? = null,
        unit: String? = null,
        stateClass: String? = null,
        category: String? = null,
        attributes: JsonObject? = null
    ): JsonObject {
        return JsonObject().apply {
            addProperty("type", type)
            addProperty("unique_id", uniqueId)
            addProperty("name", name)
            addProperty("icon", icon)
            addState(this, state)
            if (deviceClass != null) addProperty("device_class", deviceClass)
            if (unit != null) addProperty("unit_of_measurement", unit)
            if (stateClass != null) addProperty("state_class", stateClass)
            if (category != null) addProperty("entity_category", category)
            if (attributes != null) add("attributes", attributes)
        }
    }

    private fun update(type: String, uniqueId: String, state: Any, icon: String): JsonObject {
        return JsonObject().apply {
            addProperty("type", type)
            addProperty("unique_id", uniqueId)
            addProperty("icon", icon)
            addState(this, state)
        }
    }

    private fun addState(obj: JsonObject, state: Any) {
        when (state) {
            is Boolean -> obj.addProperty("state", state)
            is Int -> obj.addProperty("state", state)
            is Long -> obj.addProperty("state", state)
            is Float -> obj.addProperty("state", state)
            is Double -> obj.addProperty("state", state)
            else -> obj.addProperty("state", state.toString())
        }
    }
}
