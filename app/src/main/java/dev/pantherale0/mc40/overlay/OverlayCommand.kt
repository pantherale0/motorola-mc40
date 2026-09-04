package dev.pantherale0.mc40.overlay

enum class OverlayAction {
    OVERLAY,
    SET_MODE,
    DISMISS,
    FEEDBACK,
    TTS,
    TTS_STOP,
    UI_CONFIG,
    REINIT,
    TOAST,
    FORM,
    LIST,
    SEARCH,
    SEARCH_RESULTS,
    SET_PAGE
}

enum class Measure {
    WEIGHT,
    COUNT
}

data class ToastPayload(
    val message: String,
    val level: String = LEVEL_INFO,
    val durationLong: Boolean = false
) {
    companion object {
        const val LEVEL_INFO = "info"
        const val LEVEL_OK = "ok"
        const val LEVEL_ERROR = "error"
    }
}

data class FormOption(
    val id: String,
    val label: String
)

data class FormField(
    val id: String,
    val label: String,
    val type: String = TYPE_TEXT,
    val value: String = "",
    val placeholder: String = "",
    val options: List<FormOption> = emptyList()
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_NUMBER = "number"
        const val TYPE_TOGGLE = "toggle"
        const val TYPE_SELECT = "select"
        const val TYPE_BARCODE = "barcode"
    }
}

data class FormPayload(
    val id: String,
    val title: String,
    val fields: List<FormField>,
    val confirmLabel: String = "Confirm",
    val cancelLabel: String = "Dismiss",
    val timeoutSec: Int? = null
)

data class ListItem(
    val id: String,
    val label: String,
    val subtitle: String = ""
)

data class ListPayload(
    val id: String,
    val title: String,
    val items: List<ListItem>,
    val filter: Boolean = true,
    val timeoutSec: Int? = null
)

data class SearchPayload(
    val id: String,
    val title: String,
    val placeholder: String = "",
    val query: String = "",
    val timeoutSec: Int? = null
)

data class OverlayCommand(
    val action: OverlayAction,
    val mode: String? = null,
    val page: String? = null,
    val name: String = "",
    val barcode: String = "",
    val productId: String = "",
    val imageUrl: String = "",
    val measure: Measure = Measure.COUNT,
    val unit: String = "",
    val quantity: Double = 1.0,
    val step: Double = 1.0,
    val timeoutSec: Int? = null,
    val beep: String? = null,
    val vibrateMs: Int? = null,
    val ledColor: String? = null,
    val ledDurationSec: Int? = null,
    val ttsText: String? = null,
    val ttsVolume: Float? = null,
    val ttsStream: Int? = null,
    val ttsLanguage: String? = null,
    val uiConfig: UiConfig? = null,
    val toast: ToastPayload? = null,
    val form: FormPayload? = null,
    val list: ListPayload? = null,
    val search: SearchPayload? = null
) {
    val hasFeedback: Boolean
        get() = !beep.isNullOrBlank() || vibrateMs != null || !ledColor.isNullOrBlank()
}
