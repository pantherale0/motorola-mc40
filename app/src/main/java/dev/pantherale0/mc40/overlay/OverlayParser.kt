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
            "toast", "show_toast" -> OverlayAction.TOAST
            "form", "show_form" -> OverlayAction.FORM
            "list", "show_list", "picker" -> OverlayAction.LIST
            "search", "show_search" -> OverlayAction.SEARCH
            "search_results", "search_result" -> OverlayAction.SEARCH_RESULTS
            "set_page", "page", "show_page" -> OverlayAction.SET_PAGE
            else -> if (!ttsText.isNullOrBlank()) OverlayAction.TTS else return null
        }
        val toast = if (action == OverlayAction.TOAST) parseToast(event, data) else null
        if (action == OverlayAction.TOAST && toast == null) return null
        val form = if (action == OverlayAction.FORM) parseForm(data) else null
        if (action == OverlayAction.FORM && form == null) return null
        val list = when (action) {
            OverlayAction.LIST -> parseList(data, requireItems = false)
            OverlayAction.SEARCH_RESULTS -> parseList(data, requireItems = false)
            else -> null
        }
        if (action == OverlayAction.LIST && list == null) return null
        if (action == OverlayAction.SEARCH_RESULTS && list == null) return null
        val search = if (action == OverlayAction.SEARCH) parseSearch(data) else null
        if (action == OverlayAction.SEARCH && search == null) return null
        val page = if (action == OverlayAction.SET_PAGE) {
            string(data, "page", "id", "page_id")?.lowercase()?.take(32)
        } else {
            null
        }
        if (action == OverlayAction.SET_PAGE && page.isNullOrBlank()) return null

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
            page = page,
            name = string(data, "name", "title", "product") ?: "",
            barcode = string(data, "barcode", "code") ?: "",
            productId = string(data, "product_id", "item_id") ?: "",
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
            uiConfig = if (action == OverlayAction.UI_CONFIG) UiConfigParser.parse(data) else null,
            toast = toast,
            form = form,
            list = list,
            search = search
        )
    }

    private fun parseToast(event: JsonObject, data: JsonObject): ToastPayload? {
        val message = string(data, "message", "text")
            ?: run {
                val top = event.get("message")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                if (top.isNotEmpty() && top.lowercase() !in TOAST_COMMANDS) top else null
            }
            ?: return null
        val clipped = message.take(MAX_TOAST_LENGTH)
        if (clipped.isEmpty()) return null
        val level = when (string(data, "level", "severity")?.lowercase()) {
            ToastPayload.LEVEL_OK, "success", "green" -> ToastPayload.LEVEL_OK
            ToastPayload.LEVEL_ERROR, "fail", "failure", "red" -> ToastPayload.LEVEL_ERROR
            else -> ToastPayload.LEVEL_INFO
        }
        val durationLong = when (string(data, "duration")?.lowercase()) {
            "long" -> true
            "short" -> false
            else -> number(data, "duration")?.let { it >= 3.0 } ?: false
        }
        return ToastPayload(clipped, level, durationLong)
    }

    private fun parseForm(data: JsonObject): FormPayload? {
        val id = string(data, "id", "form_id")?.take(MAX_ID_LENGTH) ?: return null
        val title = string(data, "title", "name")?.take(MAX_TITLE_LENGTH) ?: id
        val fields = formFields(data).take(MAX_FORM_FIELDS)
        if (fields.isEmpty()) return null
        return FormPayload(
            id = id,
            title = title,
            fields = fields,
            confirmLabel = string(data, "confirm_label", "confirm")?.take(MAX_LABEL_LENGTH) ?: "Confirm",
            cancelLabel = string(data, "cancel_label", "cancel", "dismiss_label")?.take(MAX_LABEL_LENGTH)
                ?: "Dismiss",
            timeoutSec = number(data, "timeout")?.toInt()?.takeIf { it > 0 }
        )
    }

    private fun formFields(data: JsonObject): List<FormField> {
        val array = data.get("fields")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = string(obj, "id")?.take(MAX_ID_LENGTH) ?: return@mapNotNull null
            val label = string(obj, "label", "name")?.take(MAX_LABEL_LENGTH) ?: id
            val type = formFieldType(string(obj, "type"))
            val options = if (type == FormField.TYPE_SELECT) {
                formOptions(obj).take(MAX_SELECT_OPTIONS)
            } else {
                emptyList()
            }
            if (type == FormField.TYPE_SELECT && options.isEmpty()) return@mapNotNull null
            val value = when (type) {
                FormField.TYPE_TOGGLE -> if (flag(obj, "value", "default") == true) "true" else "false"
                else -> string(obj, "value", "default")?.take(MAX_FIELD_VALUE) ?: ""
            }
            FormField(
                id = id,
                label = label,
                type = type,
                value = value,
                placeholder = string(obj, "placeholder", "hint")?.take(MAX_LABEL_LENGTH) ?: "",
                options = options
            )
        }.distinctBy { it.id }
    }

    private fun formFieldType(raw: String?): String {
        return when (raw?.lowercase()) {
            FormField.TYPE_NUMBER, "int", "integer", "decimal", "float" -> FormField.TYPE_NUMBER
            FormField.TYPE_TOGGLE, "boolean", "switch", "checkbox", "bool" -> FormField.TYPE_TOGGLE
            FormField.TYPE_SELECT, "dropdown", "choice" -> FormField.TYPE_SELECT
            FormField.TYPE_BARCODE, "scan", "code" -> FormField.TYPE_BARCODE
            else -> FormField.TYPE_TEXT
        }
    }

    private fun formOptions(obj: JsonObject): List<FormOption> {
        val array = obj.get("options")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            when {
                element.isJsonObject -> {
                    val option = element.asJsonObject
                    val id = string(option, "id", "value")?.take(MAX_ID_LENGTH) ?: return@mapNotNull null
                    val label = string(option, "label", "name")?.take(MAX_LABEL_LENGTH) ?: id
                    FormOption(id, label)
                }
                element.isJsonPrimitive -> {
                    val text = element.asString.trim()
                    if (text.isEmpty()) return@mapNotNull null
                    val clipped = text.take(MAX_ID_LENGTH)
                    FormOption(clipped, clipped.take(MAX_LABEL_LENGTH))
                }
                else -> null
            }
        }.distinctBy { it.id }
    }

    private fun parseList(data: JsonObject, requireItems: Boolean): ListPayload? {
        val id = string(data, "id", "list_id", "search_id")?.take(MAX_ID_LENGTH) ?: return null
        val title = string(data, "title", "name")?.take(MAX_TITLE_LENGTH) ?: id
        val items = listItems(data).take(MAX_LIST_ITEMS)
        if (requireItems && items.isEmpty()) return null
        val filter = flag(data, "filter") ?: true
        return ListPayload(
            id = id,
            title = title,
            items = items,
            filter = filter,
            timeoutSec = number(data, "timeout")?.toInt()?.takeIf { it > 0 }
        )
    }

    private fun parseSearch(data: JsonObject): SearchPayload? {
        val id = string(data, "id", "search_id")?.take(MAX_ID_LENGTH) ?: return null
        val title = string(data, "title", "name")?.take(MAX_TITLE_LENGTH) ?: id
        return SearchPayload(
            id = id,
            title = title,
            placeholder = string(data, "placeholder", "hint")?.take(MAX_LABEL_LENGTH) ?: "",
            query = string(data, "query", "q", "text")?.take(MAX_FIELD_VALUE) ?: "",
            timeoutSec = number(data, "timeout")?.toInt()?.takeIf { it > 0 }
        )
    }

    private fun listItems(data: JsonObject): List<ListItem> {
        val array = jsonArray(data, "items") ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = string(obj, "id")?.take(MAX_ID_LENGTH) ?: return@mapNotNull null
            val label = string(obj, "label", "name", "title")?.take(MAX_LABEL_LENGTH) ?: id
            ListItem(
                id = id,
                label = label,
                subtitle = string(obj, "subtitle", "description", "detail")?.take(MAX_LABEL_LENGTH) ?: ""
            )
        }.distinctBy { it.id }
    }

    private fun jsonArray(data: JsonObject, key: String): com.google.gson.JsonArray? {
        val value = data.get(key) ?: return null
        if (value.isJsonArray) return value.asJsonArray
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            val raw = value.asString.trim()
            if (raw.startsWith("[")) {
                return runCatching {
                    com.google.gson.JsonParser.parseString(raw)
                        .takeIf { it.isJsonArray }
                        ?.asJsonArray
                }.getOrNull()
            }
        }
        return null
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

    private val TOAST_COMMANDS = setOf("toast", "show_toast")

    private const val MAX_TOAST_LENGTH = 120
    private const val MAX_ID_LENGTH = 64
    private const val MAX_TITLE_LENGTH = 80
    private const val MAX_LABEL_LENGTH = 40
    private const val MAX_FIELD_VALUE = 200
    private const val MAX_FORM_FIELDS = 4
    private const val MAX_SELECT_OPTIONS = 20
    private const val MAX_LIST_ITEMS = 40
}
