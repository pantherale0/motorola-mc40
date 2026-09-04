package dev.pantherale0.mc40.prefs

import android.content.Context
import android.content.SharedPreferences

class AppPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mc40_ha", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context, prefs)

    var instanceUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()

    var accessToken: String
        get() = secrets.get(KEY_TOKEN)
        set(value) = secrets.set(KEY_TOKEN, value)

    var webhookId: String
        get() = secrets.get(KEY_WEBHOOK)
        set(value) = secrets.set(KEY_WEBHOOK, value)

    var cloudhookUrl: String
        get() = secrets.get(KEY_CLOUDHOOK)
        set(value) = secrets.set(KEY_CLOUDHOOK, value)

    var remoteUiUrl: String
        get() = prefs.getString(KEY_REMOTE_UI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REMOTE_UI, value).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "MC40N0") ?: "MC40N0"
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var sensorsRegistered: Boolean
        get() = prefs.getBoolean(KEY_SENSORS, false)
        set(value) = prefs.edit().putBoolean(KEY_SENSORS, value).apply()

    var scannerReady: Boolean
        get() = prefs.getBoolean(KEY_SCANNER, false)
        set(value) = prefs.edit().putBoolean(KEY_SCANNER, value).apply()

    var setupScanMode: Boolean
        get() = prefs.getBoolean(KEY_SETUP_SCAN, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_SCAN, value).apply()

    var scannerMode: String
        get() = prefs.getString(KEY_MODE, "use") ?: "use"
        set(value) = prefs.edit().putString(KEY_MODE, value).apply()

    var uiConfigJson: String
        get() = prefs.getString(KEY_UI_CONFIG, "") ?: ""
        set(value) = prefs.edit().putString(KEY_UI_CONFIG, value).apply()

    val isRegistered: Boolean
        get() = webhookId.isNotEmpty() && instanceUrl.isNotEmpty() && accessToken.isNotEmpty()

    fun webhookUrl(): String {
        if (cloudhookUrl.isNotEmpty()) return cloudhookUrl
        val base = if (remoteUiUrl.isNotEmpty()) remoteUiUrl.trimEnd('/') else instanceUrl
        return "$base/api/webhook/$webhookId"
    }

    fun clearUiConfig() {
        prefs.edit().remove(KEY_UI_CONFIG).apply()
    }

    fun clearRegistration() {
        secrets.remove(KEY_WEBHOOK)
        secrets.remove(KEY_CLOUDHOOK)
        prefs.edit()
            .remove(KEY_REMOTE_UI)
            .remove(KEY_SENSORS)
            .remove(KEY_UI_CONFIG)
            .apply()
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_TOKEN = "token"
        private const val KEY_WEBHOOK = "webhook_id"
        private const val KEY_CLOUDHOOK = "cloudhook_url"
        private const val KEY_REMOTE_UI = "remote_ui_url"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_SENSORS = "sensors_registered"
        private const val KEY_SCANNER = "scanner_ready"
        private const val KEY_SETUP_SCAN = "setup_scan_mode"
        private const val KEY_MODE = "scanner_mode"
        private const val KEY_UI_CONFIG = "ui_config_json"
    }
}
