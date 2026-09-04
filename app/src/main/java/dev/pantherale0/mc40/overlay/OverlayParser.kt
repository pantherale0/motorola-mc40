package dev.pantherale0.mc40.overlay

import com.google.gson.JsonElement
import com.google.gson.JsonObject

object OverlayParser {
    fun parse(event: JsonObject): OverlayCommand? {
        val data = payload(event) ?: return null
        val command = string(data, "command")
            ?: event.get("message")?.takeIf { it.isJsonPrimitive }?.asString
        val cmd = command?.trim()?.lowercase().orEmpty()
        val ttsText = ttsValue(event, data, cmd)
        val action = when (cmd) {
            "overlay", "show_product", "product" -> OverlayAction.OVERLAY
            "set_mode", "mode" -> OverlayAction.SET_MODE
            "dismiss", "hide", "clear" -> OverlayAction.DISMISS
            "feedback", "beep", "vibrate", "haptic", "led", "notify_led" -> OverlayAction.FEEDBACK
            "tts_stop", "stop_tts", "silence" -> OverlayAction.TTS_STOP
            "tts", "speak", "announce" -> OverlayAction.TTS
            "ui_config", "configure_ui" -> OverlayAction.UI_CONFIG
            "reinit", "reinitialize" -> OverlayAction.REINIT
            else -> if (!ttsText.isNullOrBlank()) OverlayAction.TTS else return null
        }
        val measure = when (string(data, "measure", "quantity_type")?.lowercase()) {
            "weight", "mass" -> Measure.WEIGHT
            else -> Measure.COUNT
        }
        val defaultStep = if (measure == Measure.WEIGHT) 50.0 else 1.0
        val defaultQty = if (measure == Measure.WEIGHT) {
            number(data, "quantity", "amount") ?: defaultStep
        } else {
            number(data, "quantity", "amount") ?: 1.0
        }
        val unit = string(data, "unit") ?: if (measure == Measure.WEIGHT) "g" else "pcs"
        return OverlayCommand(
            action = action,
            mode = modeValue(data),
            name = string(data, "name", "title", "product") ?: "",
            barcode = string(data, "barcode", "code") ?: "",
            imageUrl = string(data, "image_url", "image", "picture") ?: "",
            measure = measure,
            unit = unit,
            quantity = defaultQty.coerceAtLeast(0.0),
            step = (number(data, "step") ?: defaultStep).coerceAtLeast(0.01),
            timeoutSec = number(data, "timeout")?.toInt()?.takeIf { it > 0 },
            beep = beepValue(data, cmd),
            vibrateMs = vibrateValue(data, cmd),
            ledColor = ledValue(data, cmd),
            ledDurationSec = ledDurationValue(data, cmd),
            ttsText = ttsText,
            ttsVolume = volumeValue(data),
            ttsStream = TtsPlayer.streamFrom(string(data, "stream", "media_stream")),
            ttsLanguage = string(data, "language", "lang", "locale"),
            uiConfig = if (action == OverlayAction.UI_CONFIG) UiConfigParser.parse(data) else null
        )
    }

    private fun modeValue(data: JsonObject): String? {
        return when (val mode = string(data, "mode")?.lowercase()) {
            "consume" -> "use"
            "shop", "list" -> "shopping"
            else -> mode
        }
    }

    private fun ttsValue(event: JsonObject, data: JsonObject, command: String): String? {
        string(data, "tts_text", "tts", "speak")?.take(400)?.let { return it }
        if (command == "tts" || command == "speak" || command == "announce") {
            string(data, "text")?.take(400)?.let { return it }
            val message = event.get("message")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            if (message.isNotEmpty() && message.lowercase() !in setOf("tts", "speak", "announce", "overlay")) {
                return message.take(400)
            }
        }
        return null
    }

    private fun volumeValue(data: JsonObject): Float? {
        val raw = number(data, "tts_volume", "volume") ?: return null
        return if (raw > 1.0) (raw / 100.0).toFloat().coerceIn(0f, 1f) else raw.toFloat().coerceIn(0f, 1f)
    }

    private fun beepValue(data: JsonObject, command: String): String? {
        val named = string(data, "beep", "tone", "sound")?.lowercase()
        if (named != null) {
            return when (named) {
                "true", "yes", "on", "1" -> "ok"
                "false", "no", "off", "0" -> null
                else -> named
            }
        }
        if (flag(data, "beep") == true) return "ok"
        if (command == "beep") return string(data, "type")?.lowercase() ?: "ok"
        return null
    }

    private fun vibrateValue(data: JsonObject, command: String): Int? {
        number(data, "vibrate_ms")?.toInt()?.let { return it.coerceAtLeast(0) }
        val vibrateNum = number(data, "vibrate", "haptic")
        if (vibrateNum != null) {
            return if (vibrateNum <= 1.0) 250 else vibrateNum.toInt().coerceAtLeast(0)
        }
        if (flag(data, "vibrate", "haptic") == true) return 250
        if (command == "vibrate" || command == "haptic") {
            return number(data, "duration")?.toInt()?.coerceAtLeast(0) ?: 250
        }
        return null
    }

    private fun ledValue(data: JsonObject, command: String): String? {
        string(data, "led", "led_color", "color")?.lowercase()?.let { return it }
        if (command == "led" || command == "notify_led") return "red"
        return null
    }

    private fun ledDurationValue(data: JsonObject, command: String): Int? {
        number(data, "led_duration")?.toInt()?.let { return it.coerceAtLeast(0) }
        if (command == "led" || command == "notify_led") {
            return number(data, "duration")?.toInt()?.coerceAtLeast(0)
        }
        return null
    }

    private fun payload(event: JsonObject): JsonObject? {
        val data = event.get("data")
        if (data != null && data.isJsonObject) {
            val obj = data.asJsonObject
            val nested = obj.get("data")
            if (nested != null && nested.isJsonObject &&
                (obj.get("command") == null || !obj.get("command").isJsonPrimitive)
            ) {
                return nested.asJsonObject
            }
            return obj
        }
        return event
    }

    private fun string(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val el = obj.get(key) ?: continue
            if (el.isJsonPrimitive) {
                val text = el.asString.trim()
                if (text.isNotEmpty()) return text
            }
        }
        return null
    }

    private fun number(obj: JsonObject, vararg keys: String): Double? {
        for (key in keys) {
            val el: JsonElement = obj.get(key) ?: continue
            if (!el.isJsonPrimitive) continue
            val prim = el.asJsonPrimitive
            if (prim.isBoolean) continue
            if (prim.isNumber) return prim.asDouble
            prim.asString.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun flag(obj: JsonObject, vararg keys: String): Boolean? {
        for (key in keys) {
            val el = obj.get(key) ?: continue
            if (!el.isJsonPrimitive) continue
            val prim = el.asJsonPrimitive
            if (prim.isBoolean) return prim.asBoolean
            if (prim.isNumber) return prim.asDouble != 0.0
            when (prim.asString.trim().lowercase()) {
                "true", "yes", "on", "1" -> return true
                "false", "no", "off", "0" -> return false
            }
        }
        return null
    }
}
