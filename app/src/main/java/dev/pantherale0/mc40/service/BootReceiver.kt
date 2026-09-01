package dev.pantherale0.mc40.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.pantherale0.mc40.Mc40App

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Mc40App.instance.prefs.isRegistered) return
        context.startService(Intent(context, CompanionService::class.java))
    }
}
