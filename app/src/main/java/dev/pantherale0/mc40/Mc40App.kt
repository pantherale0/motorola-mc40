package dev.pantherale0.mc40

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import dev.pantherale0.mc40.prefs.AppPrefs
import dev.pantherale0.mc40.scan.DataWedgeManager
import dev.pantherale0.mc40.scan.ScanReceiver

class Mc40App : Application() {
    lateinit var prefs: AppPrefs
        private set
    private val scanReceiver = ScanReceiver()
    private val scanReceiverNoCat = ScanReceiver()

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = AppPrefs(this)
        val withCategory = IntentFilter().apply {
            addAction(DataWedgeManager.SCAN_ACTION)
            addAction(DataWedgeManager.RESULT_ACTION)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val scansOnly = IntentFilter(DataWedgeManager.SCAN_ACTION)
        registerReceiver(scanReceiver, withCategory)
        registerReceiver(scanReceiverNoCat, scansOnly)
        DataWedgeManager.applyProfile(this)
        Log.i(TAG, "MC40 Companion started sdk=${Build.VERSION.SDK_INT}")
    }

    companion object {
        const val TAG = "Mc40Ha"
        lateinit var instance: Mc40App
            private set
    }
}
