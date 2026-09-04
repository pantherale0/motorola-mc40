package dev.pantherale0.mc40.overlay

enum class OverlayAction {
    OVERLAY,
    SET_MODE,
    DISMISS,
    FEEDBACK,
    TTS,
    TTS_STOP,
    UI_CONFIG,
    REINIT
}

enum class Measure {
    WEIGHT,
    COUNT
}

data class OverlayCommand(
    val action: OverlayAction,
    val mode: String? = null,
    val name: String = "",
    val barcode: String = "",
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
    val uiConfig: UiConfig? = null
) {
    val hasFeedback: Boolean
        get() = !beep.isNullOrBlank() || vibrateMs != null || !ledColor.isNullOrBlank()
}
