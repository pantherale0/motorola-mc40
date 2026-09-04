package dev.pantherale0.mc40.overlay

import android.os.Handler
import android.os.Looper
import com.google.gson.JsonObject

data class UiSlot(
    val id: String,
    val label: String,
    val behavior: String
)

data class UiConfig(
    val schema: Int,
    val defaultMode: String,
    val slots: List<UiSlot>
) {
    fun behaviorFor(mode: String): String {
        return slots.firstOrNull { it.id == mode }?.behavior ?: BEHAVIOR_USE
    }

    companion object {
        const val BEHAVIOR_USE = "use"
        const val BEHAVIOR_SHOPPING = "shopping"
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

object UiConfigParser {
    fun parse(data: JsonObject): UiConfig? {
        val schema = integer(data, "schema") ?: 1
        if (schema != 1) return null
        val slots = nestedSlots(data).ifEmpty { flattenedSlots(data) }
            .distinctBy { it.id }
            .take(MAX_SLOTS)
        if (slots.isEmpty()) return null
        val requestedDefault = text(data, "default", "default_mode")?.let(::normalizeId)
        val defaultMode = requestedDefault?.takeIf { value -> slots.any { it.id == value } }
            ?: slots.first().id
        return UiConfig(schema, defaultMode, slots)
    }

    private fun nestedSlots(data: JsonObject): List<UiSlot> {
        val array = data.get("slots")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let(::slot)
        }
    }

    private fun flattenedSlots(data: JsonObject): List<UiSlot> {
        return (1..MAX_SLOTS).mapNotNull { index ->
            val id = text(data, "slot_${index}_id") ?: return@mapNotNull null
            val label = text(data, "slot_${index}_label") ?: return@mapNotNull null
            UiSlot(normalizeId(id), label.take(MAX_LABEL_LENGTH), behavior(data, "slot_${index}_behavior"))
        }
    }

    private fun slot(data: JsonObject): UiSlot? {
        val id = text(data, "id") ?: return null
        val label = text(data, "label") ?: return null
        return UiSlot(normalizeId(id), label.take(MAX_LABEL_LENGTH), behavior(data, "behavior"))
    }

    private fun behavior(data: JsonObject, key: String): String {
        return if (text(data, key)?.lowercase() == UiConfig.BEHAVIOR_SHOPPING) {
            UiConfig.BEHAVIOR_SHOPPING
        } else {
            UiConfig.BEHAVIOR_USE
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
        return runCatching { value.asInt }.getOrNull()
    }

    private fun normalizeId(value: String): String {
        return value.lowercase().take(MAX_ID_LENGTH)
    }

    private const val MAX_SLOTS = 4
    private const val MAX_ID_LENGTH = 32
    private const val MAX_LABEL_LENGTH = 40
}
