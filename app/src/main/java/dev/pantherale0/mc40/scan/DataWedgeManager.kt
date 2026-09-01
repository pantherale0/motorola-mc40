package dev.pantherale0.mc40.scan

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.pantherale0.mc40.Mc40App

object DataWedgeManager {
    const val SCAN_ACTION = "dev.pantherale0.mc40.SCAN"
    const val PROFILE_NAME = "MC40HA"
    const val DW_ACTION = "com.symbol.datawedge.api.ACTION"
    const val RESULT_ACTION = "com.symbol.datawedge.api.RESULT_ACTION"
    const val CATEGORY_DEFAULT = "android.intent.category.DEFAULT"

    private const val EXTRA_SET_CONFIG = "com.symbol.datawedge.api.SET_CONFIG"
    private const val EXTRA_CREATE_PROFILE = "com.symbol.datawedge.api.CREATE_PROFILE"
    private const val EXTRA_SWITCH_PROFILE = "com.symbol.datawedge.api.SWITCH_TO_PROFILE"
    private const val EXTRA_SOFT_SCAN = "com.symbol.datawedge.api.SOFT_SCAN_TRIGGER"
    private const val EXTRA_GET_ACTIVE = "com.symbol.datawedge.api.GET_ACTIVE_PROFILE"
    private const val LEGACY_SOFT_ACTION =
        "com.motorolasolutions.emdk.datawedge.api.ACTION_SOFTSCANTRIGGER"
    private const val LEGACY_SOFT_EXTRA =
        "com.motorolasolutions.emdk.datawedge.api.EXTRA_PARAMETER"

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var applying = false

    fun applyProfile(context: Context) {
        val app = context.applicationContext
        if (applying) return
        applying = true
        Log.i(Mc40App.TAG, "Applying DataWedge profile $PROFILE_NAME")
        send(app, Intent(DW_ACTION).apply {
            putExtra(EXTRA_CREATE_PROFILE, PROFILE_NAME)
            requestResult(this, "mc40_create")
        })
        handler.postDelayed({ setPlugin(app, barcodePlugin(), "mc40_barcode") }, 400)
        handler.postDelayed({ setPlugin(app, intentPlugin(), "mc40_intent") }, 800)
        handler.postDelayed({ setPlugin(app, keystrokePlugin(), "mc40_keystroke") }, 1200)
        handler.postDelayed({ associateApp(app) }, 1600)
        handler.postDelayed({
            switchToProfile(app)
            queryActiveProfile(app)
            applying = false
            Mc40App.instance.prefs.scannerReady = true
        }, 2000)
    }

    fun switchToProfile(context: Context) {
        send(context, Intent(DW_ACTION).apply {
            putExtra(EXTRA_SWITCH_PROFILE, PROFILE_NAME)
            requestResult(this, "mc40_switch")
        })
    }

    fun queryActiveProfile(context: Context) {
        send(context, Intent(DW_ACTION).apply {
            putExtra(EXTRA_GET_ACTIVE, "")
            requestResult(this, "mc40_active")
        })
    }

    fun toggleScan(context: Context) {
        switchToProfile(context)
        send(context, Intent(DW_ACTION).apply { putExtra(EXTRA_SOFT_SCAN, "TOGGLE_SCANNING") })
        send(context, Intent(LEGACY_SOFT_ACTION).apply { putExtra(LEGACY_SOFT_EXTRA, "TOGGLE_SCANNING") })
    }

    fun startScan(context: Context) {
        switchToProfile(context)
        send(context, Intent(DW_ACTION).apply { putExtra(EXTRA_SOFT_SCAN, "START_SCANNING") })
        send(context, Intent(LEGACY_SOFT_ACTION).apply { putExtra(LEGACY_SOFT_EXTRA, "START_SCANNING") })
    }

    private fun setPlugin(context: Context, plugin: Bundle, commandId: String) {
        val main = Bundle().apply {
            putString("PROFILE_NAME", PROFILE_NAME)
            putString("PROFILE_ENABLED", "true")
            putString("CONFIG_MODE", "UPDATE")
            putBundle("PLUGIN_CONFIG", plugin)
        }
        send(context, Intent(DW_ACTION).apply {
            putExtra(EXTRA_SET_CONFIG, main)
            requestResult(this, commandId)
        })
    }

    private fun associateApp(context: Context) {
        val appConfig = Bundle().apply {
            putString("PACKAGE_NAME", context.packageName)
            putStringArray(
                "ACTIVITY_LIST",
                arrayOf("${context.packageName}.ui.MainActivity", "*")
            )
        }
        val main = Bundle().apply {
            putString("PROFILE_NAME", PROFILE_NAME)
            putString("PROFILE_ENABLED", "true")
            putString("CONFIG_MODE", "UPDATE")
            putParcelableArray("APP_LIST", arrayOf(appConfig))
        }
        send(context, Intent(DW_ACTION).apply {
            putExtra(EXTRA_SET_CONFIG, main)
            requestResult(this, "mc40_applist")
        })
    }

    private fun barcodePlugin(): Bundle {
        val params = Bundle().apply {
            putString("scanner_selection", "auto")
            putString("scanner_input_enabled", "true")
            putString("decoder_ean8", "true")
            putString("decoder_ean13", "true")
            putString("decoder_upca", "true")
            putString("decoder_upce0", "true")
            putString("decoder_code128", "true")
            putString("decoder_code39", "true")
            putString("decoder_qrcode", "true")
            putString("decoder_datamatrix", "true")
            putString("decoder_pdf417", "true")
        }
        return Bundle().apply {
            putString("PLUGIN_NAME", "BARCODE")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", params)
        }
    }

    private fun intentPlugin(): Bundle {
        val params = Bundle().apply {
            putString("intent_output_enabled", "true")
            putString("intent_action", SCAN_ACTION)
            putString("intent_category", CATEGORY_DEFAULT)
            putInt("intent_delivery", 2)
        }
        return Bundle().apply {
            putString("PLUGIN_NAME", "INTENT")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", params)
        }
    }

    private fun keystrokePlugin(): Bundle {
        val params = Bundle().apply {
            putString("keystroke_output_enabled", "false")
        }
        return Bundle().apply {
            putString("PLUGIN_NAME", "KEYSTROKE")
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", params)
        }
    }

    private fun requestResult(intent: Intent, commandId: String) {
        intent.putExtra("SEND_RESULT", "TRUE")
        intent.putExtra("COMMAND_IDENTIFIER", commandId)
        intent.putExtra("com.symbol.datawedge.api.RESULT_CATEGORY", CATEGORY_DEFAULT)
    }

    private fun send(context: Context, intent: Intent) {
        Log.i(Mc40App.TAG, "DW send ${intent.extras?.keySet()}")
        context.applicationContext.sendBroadcast(intent)
    }
}
