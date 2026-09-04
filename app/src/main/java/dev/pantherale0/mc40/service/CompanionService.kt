package dev.pantherale0.mc40.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.pantherale0.mc40.Mc40App
import dev.pantherale0.mc40.R
import dev.pantherale0.mc40.device.ProximityMonitor
import dev.pantherale0.mc40.hw.ButtonBus
import dev.pantherale0.mc40.overlay.FeedbackPlayer
import dev.pantherale0.mc40.overlay.ModeBus
import dev.pantherale0.mc40.overlay.OverlayAction
import dev.pantherale0.mc40.overlay.OverlayBus
import dev.pantherale0.mc40.overlay.TtsPlayer
import dev.pantherale0.mc40.overlay.UiConfig
import dev.pantherale0.mc40.overlay.UiConfigBus
import dev.pantherale0.mc40.overlay.UiInitStage
import dev.pantherale0.mc40.ha.HaApi
import dev.pantherale0.mc40.ha.NotifySocket
import dev.pantherale0.mc40.ha.SensorPublisher
import dev.pantherale0.mc40.scan.DataWedgeManager
import dev.pantherale0.mc40.scan.ScanBus
import dev.pantherale0.mc40.scan.ScanResult
import dev.pantherale0.mc40.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class CompanionService : Service() {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var api: HaApi
    private lateinit var sensors: SensorPublisher
    private lateinit var notify: NotifySocket
    private lateinit var feedback: FeedbackPlayer
    private lateinit var tts: TtsPlayer
    private lateinit var proximity: ProximityMonitor
    private lateinit var idle: IdlePowerController
    private var poll: ScheduledFuture<*>? = null
    private var initRetry: ScheduledFuture<*>? = null

    private val dropNotify = Runnable {
        if (!idle.interactive && UiConfigBus.isReady) {
            notify.disconnect()
            Log.i(Mc40App.TAG, "Notify socket dropped after idle grace")
        }
    }

    private val scanListener = ScanBus.Listener { result -> onScan(result) }
    private val buttonListener = ButtonBus.Listener { press ->
        if (!Mc40App.instance.prefs.isRegistered) return@Listener
        wakeNotifyBriefly()
        runIo {
            runCatching { sensors.publishButton(press.button, press.keyCode, press.scanCode) }
                .onFailure { Log.w(Mc40App.TAG, "Button publish failed: ${it.message}") }
        }
    }
    private val modeListener = ModeBus.Listener { _ ->
        executor.execute {
            runCatching { sensors.publishMode() }
                .onFailure { Log.w(Mc40App.TAG, "Mode publish failed: ${it.message}") }
        }
    }
    private val overlayListener = OverlayBus.Listener { command ->
        if (command.action == OverlayAction.UI_CONFIG) {
            val config = command.uiConfig
            if (config == null) {
                Log.w(Mc40App.TAG, "Ignored invalid ui_config")
            } else {
                applyUiConfig(config)
            }
            return@Listener
        }
        if (command.action == OverlayAction.REINIT) {
            restartUiInit()
            return@Listener
        }
        if (command.action == OverlayAction.TTS_STOP) {
            tts.stopSpeaking()
            return@Listener
        }
        if (command.hasFeedback) feedback.play(command)
        val spoken = command.ttsText
        if (!spoken.isNullOrBlank()) {
            tts.speak(spoken, command.ttsVolume, command.ttsStream, command.ttsLanguage)
        }
    }

    override fun onCreate() {
        super.onCreate()
        api = HaApi(Mc40App.instance.prefs)
        sensors = SensorPublisher(api, Mc40App.instance.prefs)
        notify = NotifySocket(Mc40App.instance.prefs) { subscribed ->
            if (subscribed && !UiConfigBus.isReady) {
                UiConfigBus.updateStage(UiInitStage.NOTIFY_CONNECTED)
                executor.execute { startUiHandshake() }
            }
        }
        feedback = FeedbackPlayer(this)
        TtsPlayer.onReady = { ready ->
            if (Mc40App.instance.prefs.isRegistered) {
                executor.execute {
                    runCatching { sensors.publishTtsReady(ready) }
                        .onFailure { Log.w(Mc40App.TAG, "TTS sensor failed: ${it.message}") }
                }
            }
        }
        tts = TtsPlayer(this)
        proximity = ProximityMonitor(this) { state ->
            if (!Mc40App.instance.prefs.isRegistered) return@ProximityMonitor
            executor.execute {
                runCatching { sensors.publishProximity(state) }
                    .onFailure { Log.w(Mc40App.TAG, "Proximity publish failed: ${it.message}") }
            }
        }
        idle = IdlePowerController(this, object : IdlePowerController.Listener {
            override fun onInteractiveChanged(interactive: Boolean) {
                if (interactive) enterAwake() else enterSleep()
            }
        })
        ScanBus.addListener(scanListener)
        ButtonBus.addListener(buttonListener)
        ModeBus.addListener(modeListener)
        OverlayBus.addListener(overlayListener)
        startForeground(NOTIF_ID, notification())
        idle.start()
        if (Mc40App.instance.prefs.isRegistered) {
            UiConfigBus.begin()
        }
        if (idle.interactive) {
            proximity.start()
            startAwakePoll()
        }
        if (Mc40App.instance.prefs.isRegistered && (idle.interactive || !UiConfigBus.isReady)) {
            notify.connect()
        }
        executor.execute { DataWedgeManager.switchToProfile(this) }
        if (Mc40App.instance.prefs.isRegistered) {
            publishAsync("service_start")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == IdlePowerController.ACTION_SLEEP_TICK) {
            publishSleepTick()
            return START_STICKY
        }
        if (Mc40App.instance.prefs.isRegistered && (idle.interactive || !UiConfigBus.isReady)) {
            notify.ensureConnected()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(dropNotify)
        stopAwakePoll()
        stopUiHandshake()
        idle.stop()
        ScanBus.removeListener(scanListener)
        ButtonBus.removeListener(buttonListener)
        ModeBus.removeListener(modeListener)
        OverlayBus.removeListener(overlayListener)
        proximity.stop()
        feedback.stop()
        tts.shutdown()
        TtsPlayer.onReady = null
        notify.disconnect()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterSleep() {
        main.removeCallbacks(dropNotify)
        proximity.stop()
        stopAwakePoll()
        if (UiConfigBus.isReady) {
            notify.disconnect()
        } else {
            notify.ensureConnected()
        }
        publishAsync("sleep")
    }

    private fun enterAwake() {
        main.removeCallbacks(dropNotify)
        proximity.start()
        startAwakePoll()
        if (Mc40App.instance.prefs.isRegistered) {
            notify.connect()
            publishAsync("screen_on")
        }
    }

    private fun wakeNotifyBriefly() {
        if (!Mc40App.instance.prefs.isRegistered) return
        notify.connect()
        main.removeCallbacks(dropNotify)
        if (!idle.interactive) {
            main.postDelayed(dropNotify, NOTIFY_GRACE_MS)
        }
    }

    private fun publishSleepTick() {
        if (idle.interactive || !Mc40App.instance.prefs.isRegistered) return
        publishAsync("sleep")
    }

    private fun publishAsync(trigger: String) {
        runIo {
            runCatching { sensors.publishDevice(trigger) }
                .onFailure { Log.w(Mc40App.TAG, "Sensor publish failed ($trigger): ${it.message}") }
        }
    }

    private fun runIo(block: () -> Unit) {
        idle.acquireWorkLock()
        executor.execute {
            try {
                block()
            } finally {
                idle.releaseWorkLock()
            }
        }
    }

    private fun startAwakePoll() {
        stopAwakePoll()
        poll = executor.scheduleAtFixedRate({
            if (Mc40App.instance.prefs.isRegistered && idle.interactive) {
                notify.ensureConnected()
                runCatching { sensors.publishDevice("periodic") }
                    .onFailure { Log.w(Mc40App.TAG, "Periodic sensors failed: ${it.message}") }
            }
        }, AWAKE_POLL_SEC, AWAKE_POLL_SEC, TimeUnit.SECONDS)
    }

    private fun stopAwakePoll() {
        poll?.cancel(false)
        poll = null
    }

    private fun onScan(result: ScanResult) {
        val prefs = Mc40App.instance.prefs
        if (!prefs.isRegistered || prefs.setupScanMode || !UiConfigBus.isReady) return
        wakeNotifyBriefly()
        runIo {
            runCatching {
                sensors.publishScan(result)
                if (currentBehavior() == UiConfig.BEHAVIOR_SHOPPING) {
                    sensors.publishShoppingAdd(result.data, "", 1.0, "count", "pcs")
                }
            }.onFailure { Log.w(Mc40App.TAG, "Scan publish failed: ${it.message}") }
        }
    }

    private fun startUiHandshake() {
        if (!notify.isSubscribed || UiConfigBus.isReady) return
        stopUiHandshake()
        sensors.publishBoot("start")
        UiConfigBus.updateStage(UiInitStage.WAITING_FOR_BLUEPRINT)
        initRetry = executor.scheduleAtFixedRate({
            if (!UiConfigBus.isReady && notify.isSubscribed) {
                sensors.publishBoot("timeout")
            }
        }, UI_INIT_RETRY_SEC, UI_INIT_RETRY_SEC, TimeUnit.SECONDS)
    }

    private fun stopUiHandshake() {
        initRetry?.cancel(false)
        initRetry = null
    }

    private fun applyUiConfig(config: UiConfig) {
        UiConfigBus.updateStage(UiInitStage.APPLYING)
        val prefs = Mc40App.instance.prefs
        if (config.slots.none { it.id == prefs.scannerMode }) {
            prefs.scannerMode = config.defaultMode
        }
        stopUiHandshake()
        UiConfigBus.apply(config)
        if (!idle.interactive) {
            notify.disconnect()
        }
        executor.execute {
            sensors.publishMode()
            sensors.publishBoot("complete")
        }
    }

    private fun restartUiInit() {
        stopUiHandshake()
        UiConfigBus.begin()
        notify.ensureConnected()
        if (notify.isSubscribed) {
            UiConfigBus.updateStage(UiInitStage.NOTIFY_CONNECTED)
            executor.execute { startUiHandshake() }
        }
    }

    private fun currentBehavior(): String {
        return UiConfigBus.state.config
            ?.behaviorFor(Mc40App.instance.prefs.scannerMode)
            ?: UiConfig.BEHAVIOR_USE
    }

    private fun notification(): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 40
        private const val AWAKE_POLL_SEC = 60L
        private const val UI_INIT_RETRY_SEC = 10L
        private val NOTIFY_GRACE_MS = TimeUnit.SECONDS.toMillis(45)
    }
}
