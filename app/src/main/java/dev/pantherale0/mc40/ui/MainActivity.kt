package dev.pantherale0.mc40.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.pantherale0.mc40.Mc40App
import dev.pantherale0.mc40.R
import dev.pantherale0.mc40.databinding.ActivityMainBinding
import dev.pantherale0.mc40.ha.HaApi
import dev.pantherale0.mc40.ha.SensorPublisher
import dev.pantherale0.mc40.ha.SetupQrParser
import dev.pantherale0.mc40.hw.ButtonBus
import dev.pantherale0.mc40.hw.HardwareButtons
import dev.pantherale0.mc40.net.HaUrls
import dev.pantherale0.mc40.overlay.FeedbackPlayer
import dev.pantherale0.mc40.overlay.ImageLoader
import dev.pantherale0.mc40.overlay.Measure
import dev.pantherale0.mc40.overlay.ModeBus
import dev.pantherale0.mc40.overlay.OverlayAction
import dev.pantherale0.mc40.overlay.OverlayBus
import dev.pantherale0.mc40.overlay.OverlayCommand
import dev.pantherale0.mc40.overlay.UiConfig
import dev.pantherale0.mc40.overlay.UiConfigBus
import dev.pantherale0.mc40.overlay.UiConfigState
import dev.pantherale0.mc40.overlay.UiInitStage
import dev.pantherale0.mc40.scan.DataWedgeManager
import dev.pantherale0.mc40.scan.ScanBus
import dev.pantherale0.mc40.scan.ScanParser
import dev.pantherale0.mc40.scan.ScanResult
import dev.pantherale0.mc40.service.CompanionService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val scanListener = ScanBus.Listener { result -> handleScan(result) }
    private val overlayListener = OverlayBus.Listener { command -> handleOverlay(command) }
    private val uiConfigListener = UiConfigBus.Listener { state -> renderUiState(state) }
    private val modeButtons = mutableMapOf<String, Button>()
    private var overlay: OverlayCommand? = null
    private var overlayQty = 1.0
    private var qtyUpdating = false
    private val overlayTimeout = Runnable { hideOverlay() }
    private val ledOff = Runnable { binding.notifyLed.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideStatusBar()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        consumeScanIntent(intent)

        binding.urlInput.setText(Mc40App.instance.prefs.instanceUrl)
        refreshTokenHint()

        binding.scanQrButton.setOnClickListener {
            Mc40App.instance.prefs.setupScanMode = true
            status("Aim at the token QR and press the scan button", error = false)
            DataWedgeManager.startScan(this)
        }
        binding.connectButton.setOnClickListener { connect() }
        binding.scanButton.setOnClickListener { DataWedgeManager.toggleScan(this) }
        binding.minusButton.setOnClickListener { bumpQty(-1) }
        binding.plusButton.setOnClickListener { bumpQty(1) }
        binding.confirmButton.setOnClickListener { confirmOverlay() }
        binding.dismissButton.setOnClickListener { hideOverlay() }
        binding.productOverlay.setOnClickListener { hideOverlay() }
        binding.productCard.setOnClickListener { /* keep dim-tap from dismissing */ }
        wireWedgeCapture()
        wireQtyField()
        binding.changeServerButton.setOnClickListener {
            Mc40App.instance.prefs.clearRegistration()
            UiConfigBus.begin()
            stopService(Intent(this, CompanionService::class.java))
            showSetup(true)
            status("Enter a new URL and token", error = false)
        }

        showSetup(!Mc40App.instance.prefs.isRegistered)
        if (Mc40App.instance.prefs.isRegistered) {
            startService(Intent(this, CompanionService::class.java))
            status("Registered with Home Assistant", error = false)
        }
        renderUiState(UiConfigBus.state)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeScanIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
        ScanBus.addListener(scanListener)
        OverlayBus.addListener(overlayListener)
        UiConfigBus.addListener(uiConfigListener)
        renderUiState(UiConfigBus.state)
        DataWedgeManager.switchToProfile(this)
        binding.scannerStatus.setText(
            if (Mc40App.instance.prefs.scannerReady) R.string.scanner_ready else R.string.scanner_not_ready
        )
        if (Mc40App.instance.prefs.isRegistered) {
            binding.wedgeCapture.requestFocus()
        }
    }

    override fun onPause() {
        ScanBus.removeListener(scanListener)
        OverlayBus.removeListener(overlayListener)
        UiConfigBus.removeListener(uiConfigListener)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onBackPressed() {
        if (binding.productOverlay.visibility == View.VISIBLE) {
            hideOverlay()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val press = HardwareButtons.from(event)
            if (press != null) {
                Log.i(Mc40App.TAG, "Hardware ${press.button} key=${press.keyCode} scan=${press.scanCode}")
                ButtonBus.post(press)
                status("${press.button} sent to Home Assistant", error = false)
                return true
            }
            if (event.scanCode >= 700 || event.keyCode >= KeyEvent.KEYCODE_BUTTON_A) {
                Log.i(Mc40App.TAG, "Unmapped hardware key=${event.keyCode} scan=${event.scanCode}")
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    private fun hideStatusBar() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private val wedgeFlush = Runnable { flushWedgeCapture() }

    private fun wireWedgeCapture() {
        binding.wedgeCapture.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                flushWedgeCapture()
                true
            } else {
                false
            }
        }
        binding.wedgeCapture.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_TAB)
            ) {
                flushWedgeCapture()
                true
            } else {
                false
            }
        }
        binding.wedgeCapture.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrEmpty()) return
                main.removeCallbacks(wedgeFlush)
                main.postDelayed(wedgeFlush, 250)
            }
        })
    }

    private fun wireQtyField() {
        binding.productQuantity.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitQtyFromField()
                refreshQtyLabel()
                hideKeyboard()
                binding.wedgeCapture.requestFocus()
                true
            } else {
                false
            }
        }
        binding.productQuantity.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                main.removeCallbacks(overlayTimeout)
            } else {
                commitQtyFromField()
                refreshQtyLabel()
            }
        }
        binding.productQuantity.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (qtyUpdating) return
                commitQtyFromField()
            }
        })
    }

    private fun flushWedgeCapture() {
        val data = binding.wedgeCapture.text?.toString()?.trim().orEmpty()
        if (data.isEmpty()) return
        binding.wedgeCapture.setText("")
        ScanBus.post(ScanResult(data, "keystroke", "datawedge"))
        binding.wedgeCapture.requestFocus()
    }

    private fun consumeScanIntent(intent: Intent?) {
        if (intent?.action != DataWedgeManager.SCAN_ACTION) return
        val parsed = ScanParser.fromIntent(intent) ?: return
        ScanBus.post(parsed)
    }

    private fun handleScan(result: ScanResult) {
        val prefs = Mc40App.instance.prefs
        val setup = !prefs.isRegistered || prefs.setupScanMode
        if (setup) {
            val parsed = SetupQrParser.parse(result.data)
            if (parsed == null) {
                status("That QR is not a token. Try again.", error = true)
                return
            }
            parsed.url?.let {
                val normalized = HaUrls.normalize(it)
                if (normalized == null) {
                    status("QR URL must be http:// or https://", error = true)
                    return
                }
                prefs.instanceUrl = normalized
                binding.urlInput.setText(normalized)
            }
            parsed.token?.let {
                prefs.accessToken = it
                binding.tokenInput.setText("")
                refreshTokenHint()
            }
            prefs.setupScanMode = false
            status("Token captured. Tap Connect.", error = false)
            Toast.makeText(this, "Token captured", Toast.LENGTH_SHORT).show()
            return
        }
        if (!UiConfigBus.isReady) return
        binding.lastBarcode.text = result.data
        binding.lastSymbology.text = result.labelType
        val mode = Mc40App.instance.prefs.scannerMode
        status("Scanned ${result.labelType} ($mode)", error = false)
        binding.wedgeCapture.requestFocus()
    }

    private fun connect() {
        val url = HaUrls.normalize(binding.urlInput.text.toString())
        if (url == null) {
            status("URL must be http:// or https://", error = true)
            return
        }
        val typed = binding.tokenInput.text.toString().trim()
        val token = typed.ifEmpty { Mc40App.instance.prefs.accessToken }
        if (token.isEmpty()) {
            status("URL and token are required", error = true)
            return
        }
        Mc40App.instance.prefs.instanceUrl = url
        Mc40App.instance.prefs.accessToken = token
        Mc40App.instance.prefs.setupScanMode = false
        binding.tokenInput.setText("")
        refreshTokenHint()
        binding.connectButton.isEnabled = false
        status(getString(R.string.status_connecting), error = false)
        io.execute {
            val api = HaApi(Mc40App.instance.prefs)
            val validated = api.validate()
            if (validated.isFailure) {
                fail(validated.exceptionOrNull()?.message ?: "Validation failed")
                return@execute
            }
            val registered = api.register()
            val body = registered.getOrElse {
                fail(it.message ?: "Registration failed")
                return@execute
            }
            Mc40App.instance.prefs.webhookId = body.webhookId.orEmpty()
            Mc40App.instance.prefs.cloudhookUrl = body.cloudhookUrl.orEmpty()
            Mc40App.instance.prefs.remoteUiUrl = body.remoteUiUrl.orEmpty()
            Mc40App.instance.prefs.sensorsRegistered = false
            main.post {
                binding.connectButton.isEnabled = true
                UiConfigBus.begin()
                showSetup(false)
                status("Connected to Home Assistant", error = false)
                startService(Intent(this, CompanionService::class.java))
                binding.wedgeCapture.requestFocus()
            }
        }
    }

    private fun fail(message: String) {
        main.post {
            binding.connectButton.isEnabled = true
            status(message, error = true)
        }
    }

    private fun showSetup(setup: Boolean) {
        binding.setupPanel.visibility = if (setup) View.VISIBLE else View.GONE
        if (setup) {
            binding.initPanel.visibility = View.GONE
            binding.mainPanel.visibility = View.GONE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            binding.tokenInput.setText("")
            refreshTokenHint()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            binding.tokenInput.setText("")
            renderUiState(UiConfigBus.state)
        }
        binding.connectionStatus.setText(
            if (setup) R.string.status_disconnected else R.string.status_connected
        )
        binding.connectionStatus.setTextColor(
            if (setup) Color.parseColor("#CF6679") else Color.parseColor("#81C784")
        )
        if (!setup && UiConfigBus.isReady) {
            binding.wedgeCapture.requestFocus()
        }
    }

    private fun refreshTokenHint() {
        binding.tokenInput.hint = if (Mc40App.instance.prefs.accessToken.isNotEmpty()) {
            getString(R.string.hint_token_saved)
        } else {
            getString(R.string.hint_token)
        }
    }

    private fun status(message: String, error: Boolean) {
        binding.statusMessage.text = message
        binding.statusMessage.setTextColor(
            if (error) Color.parseColor("#CF6679") else Color.parseColor("#B3B3B3")
        )
    }

    private fun setMode(mode: String) {
        Mc40App.instance.prefs.scannerMode = mode
        applyModeButtons()
        ModeBus.post(mode)
        status("Mode: $mode", error = false)
        binding.wedgeCapture.requestFocus()
    }

    private fun applyModeButtons() {
        val selected = Mc40App.instance.prefs.scannerMode
        for ((mode, button) in modeButtons) {
            val active = mode == selected
            button.setBackgroundResource(if (active) R.drawable.btn_mode_on else R.drawable.btn_mode_off)
            button.setTextColor(Color.parseColor(if (active) "#121212" else "#F2F2F2"))
            button.isSelected = active
        }
    }

    private fun renderUiState(state: UiConfigState) {
        if (!Mc40App.instance.prefs.isRegistered) return
        binding.setupPanel.visibility = View.GONE
        binding.initProgress.progress = state.stage.progress
        if (state.isReady) {
            binding.initPanel.visibility = View.GONE
            binding.mainPanel.visibility = View.VISIBLE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            state.config?.let(::renderModeButtons)
            binding.wedgeCapture.requestFocus()
            return
        }
        if (binding.productOverlay.visibility == View.VISIBLE) {
            hideOverlay()
        }
        binding.initPanel.visibility = View.VISIBLE
        binding.mainPanel.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.initStatus.setText(
            when (state.stage) {
                UiInitStage.REGISTERING -> R.string.init_registering
                UiInitStage.NOTIFY_CONNECTED -> R.string.init_notify_connected
                UiInitStage.WAITING_FOR_BLUEPRINT -> R.string.init_waiting
                UiInitStage.APPLYING -> R.string.init_applying
                UiInitStage.READY -> R.string.init_waiting
            }
        )
    }

    private fun renderModeButtons(config: UiConfig) {
        binding.modeContainer.removeAllViews()
        modeButtons.clear()
        for (slots in config.slots.chunked(2)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            }
            for ((index, slot) in slots.withIndex()) {
                val button = Button(this).apply {
                    text = slot.label
                    contentDescription = slot.label
                    textSize = 16f
                    isAllCaps = false
                    maxLines = 2
                    setOnClickListener { setMode(slot.id) }
                    layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                        if (index == 0) rightMargin = dp(4) else leftMargin = dp(4)
                    }
                }
                modeButtons[slot.id] = button
                row.addView(button)
            }
            binding.modeContainer.addView(row)
        }
        applyModeButtons()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun handleOverlay(command: OverlayCommand) {
        command.mode?.let { setMode(it) }
        showNotifyLed(command)
        if (!UiConfigBus.isReady) return
        when (command.action) {
            OverlayAction.DISMISS -> hideOverlay()
            OverlayAction.SET_MODE -> { }
            OverlayAction.OVERLAY -> showOverlay(command)
            OverlayAction.FEEDBACK -> { }
            OverlayAction.TTS, OverlayAction.TTS_STOP -> { }
            OverlayAction.UI_CONFIG, OverlayAction.REINIT -> { }
        }
    }

    private fun showNotifyLed(command: OverlayCommand) {
        val colorName = command.ledColor ?: return
        main.removeCallbacks(ledOff)
        val color = FeedbackPlayer.argb(colorName)
        if (color == Color.TRANSPARENT) {
            binding.notifyLed.visibility = View.GONE
            return
        }
        binding.notifyLed.setBackgroundColor(color)
        binding.notifyLed.visibility = View.VISIBLE
        val seconds = command.ledDurationSec ?: 3
        if (seconds > 0) {
            main.postDelayed(ledOff, seconds * 1000L)
        }
    }

    private fun showOverlay(command: OverlayCommand) {
        overlay = command
        overlayQty = command.quantity
        binding.productOverlay.visibility = View.VISIBLE
        binding.productName.text = command.name.ifBlank { command.barcode.ifBlank { "Product" } }
        binding.productQuantity.inputType = if (command.measure == Measure.WEIGHT) {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        } else {
            InputType.TYPE_CLASS_NUMBER
        }
        refreshQtyLabel()
        binding.productImage.setImageBitmap(null)
        if (command.imageUrl.isBlank()) {
            binding.productImageHint.visibility = View.VISIBLE
        } else {
            binding.productImageHint.visibility = View.GONE
            ImageLoader.load(command.imageUrl) { bitmap ->
                if (overlay != command) return@load
                if (bitmap != null) {
                    binding.productImage.setImageBitmap(bitmap)
                    binding.productImageHint.visibility = View.GONE
                } else {
                    binding.productImageHint.visibility = View.VISIBLE
                }
            }
        }
        main.removeCallbacks(overlayTimeout)
        command.timeoutSec?.let { sec ->
            main.postDelayed(overlayTimeout, sec * 1000L)
        }
        status(command.name.ifBlank { "Product overlay" }, error = false)
        binding.wedgeCapture.requestFocus()
    }

    private fun hideOverlay() {
        main.removeCallbacks(overlayTimeout)
        ImageLoader.cancel()
        overlay = null
        binding.productOverlay.visibility = View.GONE
        binding.productImage.setImageBitmap(null)
        hideKeyboard()
        binding.wedgeCapture.requestFocus()
    }

    private fun bumpQty(direction: Int) {
        val card = overlay ?: return
        commitQtyFromField()
        overlayQty = (overlayQty + direction * card.step).coerceAtLeast(0.0)
        refreshQtyLabel()
        hideKeyboard()
        binding.wedgeCapture.requestFocus()
    }

    private fun refreshQtyLabel() {
        val card = overlay ?: return
        qtyUpdating = true
        binding.productQuantity.setText(formatQty(overlayQty))
        binding.productUnit.text = card.unit
        qtyUpdating = false
    }

    private fun commitQtyFromField() {
        val raw = binding.productQuantity.text?.toString()?.trim()?.replace(',', '.').orEmpty()
        if (raw.isEmpty() || raw == ".") return
        val parsed = raw.toDoubleOrNull() ?: return
        overlayQty = parsed.coerceAtLeast(0.0)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val view = currentFocus ?: binding.productQuantity
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun formatQty(value: Double): String {
        return if (kotlin.math.abs(value - value.toLong()) < 0.0001) {
            value.toLong().toString()
        } else {
            String.format("%.1f", value)
        }
    }

    private fun confirmOverlay() {
        val card = overlay ?: return
        commitQtyFromField()
        val measure = if (card.measure == Measure.WEIGHT) "weight" else "count"
        val barcode = card.barcode.ifBlank { binding.lastBarcode.text.toString() }
        val name = card.name
        val qty = overlayQty
        val unit = card.unit
        val shopping = UiConfigBus.state.config
            ?.behaviorFor(Mc40App.instance.prefs.scannerMode) == UiConfig.BEHAVIOR_SHOPPING
        io.execute {
            val publisher = SensorPublisher(HaApi(Mc40App.instance.prefs), Mc40App.instance.prefs)
            if (shopping) {
                publisher.publishShoppingAdd(barcode, name, qty, measure, unit)
            } else {
                publisher.publishStockAdjust(barcode, name, qty, measure, unit)
            }
        }
        hideOverlay()
        status("Sent ${formatQty(qty)} ${unit}", error = false)
    }
}
