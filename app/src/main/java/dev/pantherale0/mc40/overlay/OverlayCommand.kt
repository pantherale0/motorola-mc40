package dev.pantherale0.mc40.overlay

enum class OverlayAction {
    OVERLAY,
    SET_MODE,
    DISMISS,
    FEEDBACK,
    TTS,
    TTS_STOP
}

enum class Measure {
    WEIGHT,
    COUNT
}

enum class ScannerMode {
    USE,
    SHOPPING;

    val wire: String
        get() = if (this == USE) "use" else "shopping"

    companion object {
        fun from(raw: String?): ScannerMode? {
            return when (raw?.trim()?.lowercase()) {
                "use", "consume" -> USE
                "shopping", "shop", "list" -> SHOPPING
                else -> null
            }
        }
    }
}

data class OverlayCommand(
    val action: OverlayAction,
    val mode: ScannerMode? = null,
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
    val ttsLanguage: String? = null
) {
    val hasFeedback: Boolean
        get() = !beep.isNullOrBlank() || vibrateMs != null || !ledColor.isNullOrBlank()
}
