package dev.pantherale0.mc40.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.pantherale0.mc40.Mc40App
import java.util.concurrent.TimeUnit

class IdlePowerController(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onInteractiveChanged(interactive: Boolean)
    }

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)

    @Volatile
    var interactive: Boolean = power.isInteractive
        private set

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> setInteractive(true)
                Intent.ACTION_SCREEN_OFF -> setInteractive(false)
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(screenReceiver, filter)
        interactive = power.isInteractive
        if (!interactive) {
            scheduleSleepAlarm()
        }
    }

    fun stop() {
        cancelSleepAlarm()
        runCatching { context.unregisterReceiver(screenReceiver) }
        releaseWorkLock()
    }

    @Synchronized
    fun acquireWorkLock() {
        wakeLock.acquire(WORK_LOCK_MS)
    }

    @Synchronized
    fun releaseWorkLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun setInteractive(now: Boolean) {
        if (now == interactive) return
        interactive = now
        Log.i(Mc40App.TAG, if (now) "Screen on" else "Screen off: pausing notify socket")
        if (now) {
            cancelSleepAlarm()
        } else {
            scheduleSleepAlarm()
        }
        listener.onInteractiveChanged(now)
    }

    private fun scheduleSleepAlarm() {
        alarm.setRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + SLEEP_UPDATE_MS,
            SLEEP_UPDATE_MS,
            sleepPending()
        )
    }

    private fun cancelSleepAlarm() {
        alarm.cancel(sleepPending())
    }

    private fun sleepPending(): PendingIntent {
        val intent = Intent(context, CompanionService::class.java).setAction(ACTION_SLEEP_TICK)
        val flags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getService(context, REQ_SLEEP, intent, flags)
    }

    companion object {
        const val ACTION_SLEEP_TICK = "dev.pantherale0.mc40.SLEEP_TICK"
        private const val REQ_SLEEP = 41
        private const val WAKELOCK_TAG = "mc40:sleep"
        private val SLEEP_UPDATE_MS = TimeUnit.MINUTES.toMillis(10)
        private val WORK_LOCK_MS = TimeUnit.SECONDS.toMillis(30)
    }
}
