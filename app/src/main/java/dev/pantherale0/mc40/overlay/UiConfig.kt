package dev.pantherale0.mc40.overlay

import android.os.Handler
import android.os.Looper
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class UiSlot(
    val id: String,
    val label: String,
    val behavior: String
)

data class UiAction(
    val id: String,
    val label: String,
    val kind: String = KIND_EVENT
) {
    companion object {
        const val KIND_EVENT = "event"
        const val KIND_SEARCH = "search"
    }
}

data class UiWidget(
    val id: String,
    val label: String,
    val type: String,
    val kind: String = UiAction.KIND_EVENT,
    val page: String = ""
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_BUTTON = "button"
        const val TYPE_NAV = "nav"
    }
}

data class UiPage(
    val id: String,
    val label: String,
    val widgets: List<UiWidget> = emptyList()
)

data class UiConfig(
    val schema: Int,
    val defaultMode: String,
    val slots: List<UiSlot>,
    val actions: List<UiAction> = emptyList(),
    val pages: List<UiPage> = emptyList(),
    val defaultPage: String = ""
) {
    fun behaviorFor(mode: String): String {
        return slots.firstOrNull { it.id == mode }?.behavior ?: BEHAVIOR_USE
    }

    fun page(id: String): UiPage? = pages.firstOrNull { it.id == id }

    val hasPages: Boolean
        get() = pages.isNotEmpty()

    companion object {
        const val BEHAVIOR_USE = "use"
        const val BEHAVIOR_SHOPPING = "shopping"
        const val BEHAVIOR_CUSTOM = "custom"
        const val MAX_SCHEMA = 3
    }
}

enum class UiInitStage(val progress: Int) {
    REGISTERING(20),
    NOTIFY_CONNECTED(45),
    WAITING_FOR_BLUEPRINT(65),
    APPLYING(90),
    READY(100)
}

data class UiConfigState(
    val stage: UiInitStage,
    val config: UiConfig? = null
) {
    val isReady: Boolean
        get() = stage == UiInitStage.READY && config != null
}

object UiConfigBus {
    fun interface Listener {
        fun onState(state: UiConfigState)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()

    @Volatile
    var state = UiConfigState(UiInitStage.REGISTERING)
        private set

    val isReady: Boolean
        get() = state.isReady

    @Synchronized
    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun begin() {
        post(UiConfigState(UiInitStage.REGISTERING))
    }

    fun updateStage(stage: UiInitStage) {
        if (state.isReady || state.stage == stage) return
        post(UiConfigState(stage))
    }

    fun apply(config: UiConfig) {
        post(UiConfigState(UiInitStage.READY, config))
    }

    private fun post(next: UiConfigState) {
        val snapshot: List<Listener>
        synchronized(this) {
            state = next
            snapshot = listeners.toList()
        }
        main.post {
            for (listener in snapshot) listener.onState(next)
        }
    }
}

object UiConfigWriter {
    fun toJson(config: UiConfig): JsonObject {
        val root = JsonObject()
        root.addProperty("schema", config.schema)
        root.addProperty("default", config.defaultMode)
        if (config.defaultPage.isNotEmpty()) {
            root.addProperty("default_page", config.defaultPage)
        }
        val slots = JsonArray()
        for (slot in config.slots) {
            val obj = JsonObject()
            obj.addProperty("id", slot.id)
            obj.addProperty("label", slot.label)
            obj.addProperty("behavior", slot.behavior)
            slots.add(obj)
        }
        root.add("slots", slots)
        if (config.actions.isNotEmpty()) {
            val actions = JsonArray()
            for (action in config.actions) {
                val obj = JsonObject()
                obj.addProperty("id", action.id)
                obj.addProperty("label", action.label)
                obj.addProperty("kind", action.kind)
                actions.add(obj)
            }
            root.add("actions", actions)
        }
        if (config.pages.isNotEmpty()) {
            val pages = JsonArray()
            for (page in config.pages) {
                val pageObj = JsonObject()
                pageObj.addProperty("id", page.id)
                pageObj.addProperty("label", page.label)
                val widgets = JsonArray()
                for (widget in page.widgets) {
                    val widgetObj = JsonObject()
                    widgetObj.addProperty("type", widget.type)
                    widgetObj.addProperty("id", widget.id)
                    widgetObj.addProperty("label", widget.label)
                    when (widget.type) {
                        UiWidget.TYPE_BUTTON -> widgetObj.addProperty("kind", widget.kind)
                        UiWidget.TYPE_NAV -> widgetObj.addProperty("page", widget.page)
                    }
                    widgets.add(widgetObj)
                }
                pageObj.add("widgets", widgets)
                pages.add(pageObj)
            }
            root.add("pages", pages)
        }
        return root
    }
}

object UiConfigParser {
    fun parseJson(raw: String): UiConfig? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val element = JsonParser.parseString(trimmed)
            if (!element.isJsonObject) return@runCatching null
            parse(element.asJsonObject)
        }.getOrNull()
    }

    fun parse(data: JsonObject): UiConfig? {
        val requestedSchema = integer(data, "schema") ?: 1
        if (requestedSchema < 1) return null
        // Newer blueprints may advertise a higher schema; parse what we know.
        val schema = requestedSchema.coerceAtMost(UiConfig.MAX_SCHEMA)
        val slots = nestedSlots(data, schema).ifEmpty { flattenedSlots(data, schema) }
            .distinctBy { it.id }
            .take(MAX_SLOTS)
        if (slots.isEmpty()) return null

        val pages = if (schema >= 3) {
            parsePages(data)
        } else {
            emptyList()
        }

        val actions = if (schema >= 2 && pages.isEmpty()) {
            nestedActions(data).ifEmpty { flattenedActions(data) }
                .distinctBy { it.id }
                .take(MAX_ACTIONS)
        } else {
            emptyList()
        }

        val requestedDefault = text(data, "default", "default_mode")?.let(::normalizeId)
        val defaultMode = requestedDefault?.takeIf { value -> slots.any { it.id == value } }
            ?: slots.first().id
        val requestedPage = text(data, "default_page")?.let(::normalizeId)
        val defaultPage = when {
            pages.isEmpty() -> ""
            requestedPage != null && pages.any { it.id == requestedPage } -> requestedPage
            else -> pages.first().id
        }
        return UiConfig(schema, defaultMode, slots, actions, pages, defaultPage)
    }

    private fun nestedSlots(data: JsonObject, schema: Int): List<UiSlot> {
        val array = jsonArray(data, "slots") ?: return emptyList()
        return array.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let { slot(it, schema) }
        }
    }

    private fun flattenedSlots(data: JsonObject, schema: Int): List<UiSlot> {
        return (1..MAX_SLOTS).mapNotNull { index ->
            val id = text(data, "slot_${index}_id") ?: return@mapNotNull null
            val label = text(data, "slot_${index}_label") ?: return@mapNotNull null
            UiSlot(
                normalizeId(id),
                label.take(MAX_LABEL_LENGTH),
                behavior(data, "slot_${index}_behavior", schema)
            )
        }
    }

    private fun slot(data: JsonObject, schema: Int): UiSlot? {
        val id = text(data, "id") ?: return null
        val label = text(data, "label") ?: id
        return UiSlot(normalizeId(id), label.take(MAX_LABEL_LENGTH), behavior(data, "behavior", schema))
    }

    private fun nestedActions(data: JsonObject): List<UiAction> {
        val array = jsonArray(data, "actions") ?: return emptyList()
        return array.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let(::action)
        }
    }

    private fun flattenedActions(data: JsonObject): List<UiAction> {
        return (1..MAX_ACTIONS).mapNotNull { index ->
            val id = text(data, "action_${index}_id") ?: return@mapNotNull null
            val label = text(data, "action_${index}_label") ?: return@mapNotNull null
            UiAction(normalizeId(id), label.take(MAX_LABEL_LENGTH), actionKind(data, "action_${index}_kind"))
        }
    }

    private fun action(data: JsonObject): UiAction? {
        val id = text(data, "id") ?: return null
        val label = text(data, "label") ?: id
        return UiAction(normalizeId(id), label.take(MAX_LABEL_LENGTH), actionKind(data, "kind"))
    }

    private fun parsePages(data: JsonObject): List<UiPage> {
        val nested = nestedPages(data).distinctBy { it.id }.take(MAX_PAGES)
        val flatByPage = collectFlatWidgets(data)
        if (nested.isNotEmpty()) {
            return nested.map { page ->
                if (page.widgets.isNotEmpty()) {
                    page
                } else {
                    page.copy(widgets = flatByPage[page.id].orEmpty().take(MAX_WIDGETS))
                }
            }
        }
        return flattenedPages(data).distinctBy { it.id }.take(MAX_PAGES)
    }

    private fun collectFlatWidgets(data: JsonObject): Map<String, List<UiWidget>> {
        val widgetsByPage = mutableMapOf<String, MutableList<UiWidget>>()
        for (index in 1..MAX_FLAT_WIDGETS) {
            val pageId = text(data, "widget_${index}_page")?.let(::normalizeId) ?: continue
            val widget = flatWidget(data, index) ?: continue
            widgetsByPage.getOrPut(pageId) { mutableListOf() }.add(widget)
        }
        return widgetsByPage
    }

    private fun nestedPages(data: JsonObject): List<UiPage> {
        val array = jsonArray(data, "pages") ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = text(obj, "id") ?: return@mapNotNull null
            val label = text(obj, "label") ?: id
            val widgets = nestedWidgets(obj).take(MAX_WIDGETS)
            UiPage(normalizeId(id), label.take(MAX_LABEL_LENGTH), widgets)
        }
    }

    private fun flattenedPages(data: JsonObject): List<UiPage> {
        val pageDefs = (1..MAX_PAGES).mapNotNull { index ->
            val id = text(data, "page_${index}_id") ?: return@mapNotNull null
            val label = text(data, "page_${index}_label") ?: return@mapNotNull null
            normalizeId(id) to label.take(MAX_LABEL_LENGTH)
        }
        if (pageDefs.isEmpty()) return emptyList()
        val widgetsByPage = collectFlatWidgets(data)
        return pageDefs.map { (id, label) ->
            UiPage(id, label, widgetsByPage[id].orEmpty().take(MAX_WIDGETS))
        }
    }

    private fun nestedWidgets(page: JsonObject): List<UiWidget> {
        val array = page.get("widgets")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let(::widget)
        }.distinctBy { it.id }
    }

    private fun flatWidget(data: JsonObject, index: Int): UiWidget? {
        val id = text(data, "widget_${index}_id") ?: return null
        val label = text(data, "widget_${index}_label") ?: return null
        val type = widgetType(text(data, "widget_${index}_type")) ?: return null
        val kind = actionKind(data, "widget_${index}_kind")
        val target = text(data, "widget_${index}_target", "widget_${index}_page_target")
            ?.let(::normalizeId)
            .orEmpty()
        if (type == UiWidget.TYPE_NAV && target.isEmpty()) return null
        return UiWidget(
            id = normalizeId(id),
            label = label.take(MAX_LABEL_LENGTH),
            type = type,
            kind = kind,
            page = if (type == UiWidget.TYPE_NAV) target else ""
        )
    }

    private fun widget(data: JsonObject): UiWidget? {
        val id = text(data, "id") ?: return null
        val label = text(data, "label") ?: return null
        val type = widgetType(text(data, "type")) ?: return null
        val kind = actionKind(data, "kind")
        val target = text(data, "page", "target", "nav_page")?.let(::normalizeId).orEmpty()
        if (type == UiWidget.TYPE_NAV && target.isEmpty()) return null
        return UiWidget(
            id = normalizeId(id),
            label = label.take(MAX_LABEL_LENGTH),
            type = type,
            kind = kind,
            page = if (type == UiWidget.TYPE_NAV) target else ""
        )
    }

    private fun widgetType(raw: String?): String? {
        return when (raw?.lowercase()) {
            UiWidget.TYPE_TEXT, "label", "hint" -> UiWidget.TYPE_TEXT
            UiWidget.TYPE_BUTTON, "action", "event" -> UiWidget.TYPE_BUTTON
            UiWidget.TYPE_NAV, "navigate", "link" -> UiWidget.TYPE_NAV
            else -> null
        }
    }

    private fun actionKind(data: JsonObject, key: String): String {
        return when (text(data, key)?.lowercase()) {
            UiAction.KIND_SEARCH -> UiAction.KIND_SEARCH
            else -> UiAction.KIND_EVENT
        }
    }

    private fun behavior(data: JsonObject, key: String, schema: Int): String {
        return when (text(data, key)?.lowercase()) {
            UiConfig.BEHAVIOR_SHOPPING -> UiConfig.BEHAVIOR_SHOPPING
            UiConfig.BEHAVIOR_CUSTOM -> {
                if (schema >= 2) UiConfig.BEHAVIOR_CUSTOM else UiConfig.BEHAVIOR_USE
            }
            else -> UiConfig.BEHAVIOR_USE
        }
    }

    private fun text(data: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val value = data.get(key) ?: continue
            if (!value.isJsonPrimitive) continue
            val text = value.asString.trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    private fun integer(data: JsonObject, key: String): Int? {
        val value = data.get(key) ?: return null
        if (!value.isJsonPrimitive) return null
        val prim = value.asJsonPrimitive
        if (prim.isNumber) return runCatching { prim.asInt }.getOrNull()
        return prim.asString.trim().toIntOrNull()
    }

    private fun jsonArray(data: JsonObject, key: String): com.google.gson.JsonArray? {
        val value = data.get(key) ?: return null
        if (value.isJsonArray) return value.asJsonArray
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            val raw = value.asString.trim()
            if (raw.startsWith("[")) {
                return runCatching {
                    JsonParser.parseString(raw).takeIf { it.isJsonArray }?.asJsonArray
                }.getOrNull()
            }
        }
        return null
    }

    private fun normalizeId(value: String): String {
        return value.lowercase().take(MAX_ID_LENGTH)
    }

    private const val MAX_SLOTS = 4
    private const val MAX_ACTIONS = 4
    private const val MAX_PAGES = 3
    private const val MAX_WIDGETS = 6
    private const val MAX_FLAT_WIDGETS = 6
    private const val MAX_ID_LENGTH = 32
    private const val MAX_LABEL_LENGTH = 40
}
