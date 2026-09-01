package dev.pantherale0.mc40.ha

import android.net.Uri
import org.json.JSONObject

data class SetupQr(
    val url: String?,
    val token: String?
)

object SetupQrParser {
    fun parse(raw: String): SetupQr? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("{")) {
            return try {
                val json = JSONObject(value)
                val url = firstString(json, "url", "instance_url", "base_url")
                val token = firstString(json, "token", "access_token")
                if (url == null && token == null) null else SetupQr(url, token)
            } catch (_: Exception) {
                null
            }
        }
        if (value.startsWith("mc40ha://", ignoreCase = true) ||
            value.startsWith("homeassistant://", ignoreCase = true)
        ) {
            val uri = Uri.parse(value)
            val url = uri.getQueryParameter("url") ?: uri.getQueryParameter("instance_url")
            val token = uri.getQueryParameter("token") ?: uri.getQueryParameter("access_token")
            if (url == null && token == null) return null
            return SetupQr(url, token)
        }
        if (looksLikeJwt(value) || value.length >= 40) {
            return SetupQr(url = null, token = value)
        }
        return null
    }

    private fun firstString(json: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (json.has(key)) {
                val text = json.optString(key, "").trim()
                if (text.isNotEmpty()) return text
            }
        }
        return null
    }

    private fun looksLikeJwt(value: String): Boolean {
        return value.startsWith("eyJ") && value.count { it == '.' } >= 2
    }
}
