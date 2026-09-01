package dev.pantherale0.mc40.ha

import android.os.Build
import android.provider.Settings
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import dev.pantherale0.mc40.Mc40App
import dev.pantherale0.mc40.net.HaUrls
import dev.pantherale0.mc40.net.HttpClients
import dev.pantherale0.mc40.prefs.AppPrefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class RegistrationResponse(
    @SerializedName("webhook_id") val webhookId: String?,
    @SerializedName("cloudhook_url") val cloudhookUrl: String?,
    @SerializedName("remote_ui_url") val remoteUiUrl: String?,
    @SerializedName("secret") val secret: String?
)

class HaApi(private val prefs: AppPrefs) {
    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun validate(): Result<Unit> {
        val url = HaUrls.normalize(prefs.instanceUrl)
            ?: return Result.failure(IllegalArgumentException("URL must be http:// or https://"))
        val token = prefs.accessToken
        if (url.isEmpty() || token.isEmpty()) {
            return Result.failure(IllegalArgumentException("URL and token are required"))
        }
        val request = Request.Builder()
            .url("$url/api/config")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        HttpClients.okHttp.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                return Result.failure(IllegalArgumentException("Access token was rejected"))
            }
            if (!response.isSuccessful) {
                return Result.failure(IllegalStateException("Home Assistant returned HTTP ${response.code}"))
            }
        }
        return Result.success(Unit)
    }

    fun register(): Result<RegistrationResponse> {
        val body = JsonObject().apply {
            addProperty("device_id", deviceId())
            addProperty("app_id", "dev.pantherale0.mc40")
            addProperty("app_name", "MC40 Companion")
            addProperty("app_version", "1.0.0")
            addProperty("device_name", prefs.deviceName)
            addProperty("manufacturer", Build.MANUFACTURER ?: "Zebra Technologies")
            addProperty("model", Build.MODEL ?: "MC40N0")
            addProperty("os_name", "Android")
            addProperty("os_version", Build.VERSION.RELEASE ?: "5.1.1")
            addProperty("supports_encryption", false)
            add("app_data", JsonObject().apply {
                addProperty("push_websocket_channel", true)
            })
        }
        val request = Request.Builder()
            .url("${prefs.instanceUrl}/api/mobile_app/registrations")
            .header("Authorization", "Bearer ${prefs.accessToken}")
            .post(gson.toJson(body).toRequestBody(jsonType))
            .build()
        HttpClients.okHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 404) {
                return Result.failure(
                    IllegalStateException("mobile_app is not loaded. Add it to configuration.yaml")
                )
            }
            if (!response.isSuccessful) {
                return Result.failure(IllegalStateException("Registration failed HTTP ${response.code}: $text"))
            }
            val parsed = gson.fromJson(text, RegistrationResponse::class.java)
            if (parsed.webhookId.isNullOrEmpty()) {
                return Result.failure(IllegalStateException("Registration response missing webhook_id"))
            }
            return Result.success(parsed)
        }
    }

    fun webhook(type: String, data: Any?): Result<String> {
        val payload = JsonObject().apply {
            addProperty("type", type)
            when (data) {
                null -> add("data", JsonObject())
                is JsonElement -> add("data", data)
                else -> add("data", gson.toJsonTree(data))
            }
        }
        val request = Request.Builder()
            .url(prefs.webhookUrl())
            .post(gson.toJson(payload).toRequestBody(jsonType))
            .build()
        HttpClients.okHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 410) {
                return Result.failure(IllegalStateException("Registration was deleted in Home Assistant"))
            }
            if (!response.isSuccessful && response.code != 201) {
                return Result.failure(IllegalStateException("Webhook $type failed HTTP ${response.code}: $text"))
            }
            return Result.success(text)
        }
    }

    private fun deviceId(): String {
        val id = Settings.Secure.getString(
            Mc40App.instance.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        return if (id.isNullOrEmpty()) "mc40n0" else id
    }
}
