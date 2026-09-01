package dev.pantherale0.mc40.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object HaUrls {
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val parsed = trimmed.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "http" && parsed.scheme != "https") return null
        if (parsed.host.isBlank()) return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        return parsed.newBuilder()
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
    }

    fun isFetchable(raw: String): Boolean {
        val parsed = raw.trim().toHttpUrlOrNull() ?: return false
        return parsed.scheme == "http" || parsed.scheme == "https"
    }
}
