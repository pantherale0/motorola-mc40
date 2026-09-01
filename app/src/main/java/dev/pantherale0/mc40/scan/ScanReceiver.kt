package dev.pantherale0.mc40.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.pantherale0.mc40.Mc40App

class ScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action.orEmpty()
        if (action == DataWedgeManager.RESULT_ACTION) {
            val ident = intent.getStringExtra("COMMAND_IDENTIFIER")
            val result = intent.getStringExtra("RESULT")
            val command = intent.getStringExtra("COMMAND")
            val info = intent.getBundleExtra("RESULT_INFO")
            val infoText = info?.keySet()?.joinToString(",") { key ->
                "$key=${info.get(key)}"
            }
            val active = intent.getStringExtra("com.symbol.datawedge.api.RESULT_GET_ACTIVE_PROFILE")
            Log.i(Mc40App.TAG, "DataWedge result cmd=$command id=$ident result=$result info=$infoText active=$active")
            if (result == "SUCCESS") {
                Mc40App.instance.prefs.scannerReady = true
            }
            return
        }
        Log.i(Mc40App.TAG, "Scan intent action=$action")
        val parsed = ScanParser.fromIntent(intent)
        if (parsed == null) {
            Log.w(Mc40App.TAG, "Scan intent had no barcode payload")
            return
        }
        Log.i(Mc40App.TAG, "Scan ${parsed.labelType}: ${preview(parsed.data)}")
        ScanBus.post(parsed)
    }

    private fun preview(data: String): String {
        if (data.startsWith("eyJ") && data.count { it == '.' } >= 2) return "[token]"
        return data.take(48)
    }
}
