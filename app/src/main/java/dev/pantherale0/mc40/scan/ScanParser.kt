package dev.pantherale0.mc40.scan

import android.content.Intent
import android.os.Bundle
import android.util.Log
import dev.pantherale0.mc40.Mc40App

object ScanParser {
    private val DATA_KEYS = arrayOf(
        "com.symbol.datawedge.data_string",
        "com.motorolasolutions.emdk.datawedge.data_string",
        "com.symbol.datawedge.decoded_data",
        "com.dw.datawedge.data_string",
        "barcode_string",
        "com.symbol.datawedge.data"
    )
    private val TYPE_KEYS = arrayOf(
        "com.symbol.datawedge.label_type",
        "com.motorolasolutions.emdk.datawedge.label_type",
        "com.symbol.datawedge.data_type"
    )
    private val SOURCE_KEYS = arrayOf(
        "com.symbol.datawedge.source_profile_name",
        "com.symbol.datawedge.source"
    )

    fun fromIntent(intent: Intent?): ScanResult? {
        if (intent == null) return null
        val extras = intent.extras
        if (extras != null) {
            logExtras(extras)
        }
        val data = firstString(intent, extras, *DATA_KEYS)
            ?: decodeData(extras)
            ?: firstNonEmptyString(extras)
            ?: return null
        val label = firstString(intent, extras, *TYPE_KEYS) ?: "unknown"
        val source = firstString(intent, extras, *SOURCE_KEYS) ?: "scanner"
        return ScanResult(data.trim(), label, source)
    }

    private fun firstString(intent: Intent, extras: Bundle?, vararg keys: String): String? {
        for (key in keys) {
            val value = intent.getStringExtra(key)
            if (!value.isNullOrEmpty()) return value
        }
        if (extras == null) return null
        for (key in keys) {
            val text = stringify(extras.get(key))
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeData(extras: Bundle?): String? {
        if (extras == null) return null
        val raw = extras.get("com.symbol.datawedge.decode_data")
            ?: extras.get("com.motorolasolutions.emdk.datawedge.decode_data")
            ?: return null
        val arrays: List<ByteArray> = when (raw) {
            is ByteArray -> listOf(raw)
            is ArrayList<*> -> raw.mapNotNull { it as? ByteArray }
            is Array<*> -> raw.mapNotNull { it as? ByteArray }
            else -> emptyList()
        }
        if (arrays.isEmpty()) return null
        return arrays.joinToString("") { String(it) }.trim().ifEmpty { null }
    }

    private fun firstNonEmptyString(extras: Bundle?): String? {
        if (extras == null) return null
        val keySet = extras.keySet() ?: return null
        for (key in keySet) {
            if (key.contains("data", ignoreCase = true) || key.contains("barcode", ignoreCase = true)) {
                val text = stringify(extras.get(key))
                if (!text.isNullOrEmpty() && text.length < 4096) return text
            }
        }
        return null
    }

    private fun stringify(raw: Any?): String? {
        return when (raw) {
            null -> null
            is String -> raw.trim().ifEmpty { null }
            is CharSequence -> raw.toString().trim().ifEmpty { null }
            is ByteArray -> String(raw).trim().ifEmpty { null }
            else -> null
        }
    }

    private fun logExtras(extras: Bundle) {
        val keys = extras.keySet() ?: return
        val summary = keys.joinToString(",") { key ->
            val value = extras.get(key)
            val shown = when (value) {
                is ByteArray -> "bytes[${value.size}]"
                is ArrayList<*> -> "list[${value.size}]"
                else -> value?.javaClass?.simpleName ?: "null"
            }
            "$key=$shown"
        }
        Log.i(Mc40App.TAG, "Scan extras: $summary")
    }
}
