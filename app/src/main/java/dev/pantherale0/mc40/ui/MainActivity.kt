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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
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
import dev.pantherale0.mc40.overlay.FormField
import dev.pantherale0.mc40.overlay.FormPayload
import dev.pantherale0.mc40.overlay.FormScanGate
import dev.pantherale0.mc40.overlay.ImageLoader
import dev.pantherale0.mc40.overlay.ListItem
import dev.pantherale0.mc40.overlay.ListPayload
import dev.pantherale0.mc40.overlay.Measure
import dev.pantherale0.mc40.overlay.ModeBus
import dev.pantherale0.mc40.overlay.OverlayAction
import dev.pantherale0.mc40.overlay.OverlayBus
import dev.pantherale0.mc40.overlay.OverlayCommand
import dev.pantherale0.mc40.overlay.SearchPayload
import dev.pantherale0.mc40.overlay.ToastPayload
import dev.pantherale0.mc40.overlay.UiAction
import dev.pantherale0.mc40.overlay.UiConfig
import dev.pantherale0.mc40.overlay.UiConfigBus
import dev.pantherale0.mc40.overlay.UiConfigState
import dev.pantherale0.mc40.overlay.UiInitStage
import dev.pantherale0.mc40.overlay.UiWidget
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
    private val formViews = mutableMapOf<String, View>()
    private val formBarcodeInputs = mutableListOf<EditText>()
    private var overlay: OverlayCommand? = null
    private var activeForm: FormPayload? = null
    private var activeList: ListPayload? = null
    private var activeSearch: SearchPayload? = null
    private var currentPageId: String = ""
    private var overlayQty = 1.0
    private var qtyUpdating = false
    private val overlayTimeout = Runnable { hideOverlay() }
    private val formTimeout = Runnable { hideForm(reason = "timeout") }
    private val listTimeout = Runnable { hideList(reason = "timeout") }
    private val searchTimeout = Runnable { hideSearch(reason = "timeout") }
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
        binding.formConfirmButton.setOnClickListener { confirmForm() }
        binding.formCancelButton.setOnClickListener { hideForm(reason = "dismiss") }
        binding.formOverlay.setOnClickListener { hideForm(reason = "dismiss") }
        binding.formCard.setOnClickListener { /* keep dim-tap from dismissing */ }
        binding.listCancelButton.setOnClickListener { hideList(reason = "dismiss") }
        binding.listOverlay.setOnClickListener { hideList(reason = "dismiss") }
        binding.listCard.setOnClickListener { /* keep dim-tap from dismissing */ }
        binding.searchSubmitButton.setOnClickListener { submitSearch() }
        binding.searchCancelButton.setOnClickListener { hideSearch(reason = "dismiss") }
        binding.searchOverlay.setOnClickListener { hideSearch(reason = "dismiss") }
        binding.searchCard.setOnClickListener { /* keep dim-tap from dismissing */ }
        wireWedgeCapture()
        wireQtyField()
        wireListFilter()
        wireSearchQuery()
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
        refreshFormScanGate()
        if (Mc40App.instance.prefs.isRegistered && !FormScanGate.consumeScans) {
            binding.wedgeCapture.requestFocus()
        }
    }

    override fun onPause() {
        FormScanGate.consumeScans = false
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
        when {
            binding.searchOverlay.visibility == View.VISIBLE -> hideSearch(reason = "dismiss")
            binding.formOverlay.visibility == View.VISIBLE -> hideForm(reason = "dismiss")
            binding.listOverlay.visibility == View.VISIBLE -> hideList(reason = "dismiss")
            binding.productOverlay.visibility == View.VISIBLE -> hideOverlay()
            else -> {
                val config = UiConfigBus.state.config
                if (config != null && config.hasPages &&
                    currentPageId.isNotEmpty() &&
                    currentPageId != config.defaultPage
                ) {
                    setPage(config.defaultPage, notify = true)
                }
            }
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
        if (FormScanGate.consumeScans && activeForm != null && formBarcodeInputs.isNotEmpty()) {
            val target = formBarcodeInputs.firstOrNull { it.hasFocus() }
                ?: formBarcodeInputs.first()
            target.setText(result.data)
            target.setSelection(result.data.length)
            binding.lastBarcode.text = result.data
            binding.lastSymbology.text = result.labelType
            status("Captured", error = false)
            return
        }
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
            state.config?.let(::renderHomeButtons)
            binding.wedgeCapture.requestFocus()
            return
        }
        if (binding.productOverlay.visibility == View.VISIBLE ||
            binding.formOverlay.visibility == View.VISIBLE ||
            binding.listOverlay.visibility == View.VISIBLE ||
            binding.searchOverlay.visibility == View.VISIBLE
        ) {
            hideAllModals()
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

    private fun renderHomeButtons(config: UiConfig) {
        renderModeButtons(config)
        if (config.hasPages) {
            binding.actionContainer.visibility = View.GONE
            binding.actionContainer.removeAllViews()
            val initial = when {
                currentPageId.isNotEmpty() && config.page(currentPageId) != null -> currentPageId
                else -> config.defaultPage
            }
            setPage(initial, notify = false)
        } else {
            binding.pageHeader.visibility = View.GONE
            binding.pageContainer.visibility = View.GONE
            binding.pageContainer.removeAllViews()
            currentPageId = ""
            renderActionButtons(config.actions)
        }
    }

    private fun setPage(pageId: String, notify: Boolean) {
        val config = UiConfigBus.state.config ?: return
        val page = config.page(pageId) ?: return
        val changed = currentPageId != page.id
        currentPageId = page.id
        binding.pageHeader.text = page.label
        binding.pageHeader.visibility = View.VISIBLE
        binding.pageContainer.visibility = View.VISIBLE
        renderPageWidgets(page.widgets)
        if (notify && changed) {
            val id = page.id
            val label = page.label
            io.execute {
                SensorPublisher(HaApi(Mc40App.instance.prefs), Mc40App.instance.prefs)
                    .publishPageChanged(id, label)
            }
            status("Page: ${page.label}", error = false)
        }
        binding.wedgeCapture.requestFocus()
    }

    private fun renderPageWidgets(widgets: List<UiWidget>) {
        binding.pageContainer.removeAllViews()
        val interactive = mutableListOf<UiWidget>()
        for (widget in widgets) {
            when (widget.type) {
                UiWidget.TYPE_TEXT -> {
                    flushInteractiveRow(interactive)
                    binding.pageContainer.addView(
                        TextView(this).apply {
                            text = widget.label
                            setTextColor(Color.parseColor("#B3B3B3"))
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = dp(8) }
                        }
                    )
                }
                UiWidget.TYPE_BUTTON, UiWidget.TYPE_NAV -> interactive.add(widget)
            }
        }
        flushInteractiveRow(interactive)
    }

    private fun flushInteractiveRow(pending: MutableList<UiWidget>) {
        if (pending.isEmpty()) return
        for (chunk in pending.toList().chunked(2)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            for ((index, widget) in chunk.withIndex()) {
                row.addView(pageWidgetButton(widget, index))
            }
            if (chunk.size == 1) {
                row.addView(
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                            leftMargin = dp(4)
                        }
                    }
                )
            }
            binding.pageContainer.addView(row)
        }
        pending.clear()
    }

    private fun pageWidgetButton(widget: UiWidget, index: Int): Button {
        return Button(this).apply {
            text = widget.label
            contentDescription = widget.label
            textSize = 15f
            isAllCaps = false
            maxLines = 2
            setBackgroundResource(R.drawable.btn_mode_off)
            setTextColor(Color.parseColor("#F2F2F2"))
            setOnClickListener { handlePageWidget(widget) }
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                if (index == 0) rightMargin = dp(4) else leftMargin = dp(4)
            }
        }
    }

    private fun handlePageWidget(widget: UiWidget) {
        when (widget.type) {
            UiWidget.TYPE_NAV -> {
                val config = UiConfigBus.state.config ?: return
                if (config.page(widget.page) == null) {
                    status("Unknown page: ${widget.page}", error = true)
                    return
                }
                setPage(widget.page, notify = true)
            }
            UiWidget.TYPE_BUTTON -> fireHomeAction(
                UiAction(widget.id, widget.label, widget.kind)
            )
        }
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

    private fun renderActionButtons(actions: List<UiAction>) {
        binding.actionContainer.removeAllViews()
        if (actions.isEmpty()) {
            binding.actionContainer.visibility = View.GONE
            return
        }
        binding.actionContainer.visibility = View.VISIBLE
        for (chunk in actions.chunked(2)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            }
            for ((index, action) in chunk.withIndex()) {
                val button = Button(this).apply {
                    text = action.label
                    contentDescription = action.label
                    textSize = 15f
                    isAllCaps = false
                    maxLines = 2
                    setBackgroundResource(R.drawable.btn_mode_off)
                    setTextColor(Color.parseColor("#F2F2F2"))
                    setOnClickListener { fireHomeAction(action) }
                    layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                        if (index == 0) rightMargin = dp(4) else leftMargin = dp(4)
                    }
                }
                row.addView(button)
            }
            if (chunk.size == 1) {
                row.addView(
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                            leftMargin = dp(4)
                        }
                    }
                )
            }
            binding.actionContainer.addView(row)
        }
    }

    private fun fireHomeAction(action: UiAction) {
        if (action.kind == UiAction.KIND_SEARCH) {
            showSearch(
                SearchPayload(
                    id = action.id,
                    title = action.label,
                    placeholder = getString(R.string.hint_search)
                )
            )
            return
        }
        val actionId = action.id
        val label = action.label
        io.execute {
            SensorPublisher(HaApi(Mc40App.instance.prefs), Mc40App.instance.prefs)
                .publishHomeAction(actionId, label)
        }
        status("Action: ${action.label}", error = false)
        binding.wedgeCapture.requestFocus()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun handleOverlay(command: OverlayCommand) {
        command.mode?.let { setMode(it) }
        showNotifyLed(command)
        if (!UiConfigBus.isReady) return
        when (command.action) {
            OverlayAction.DISMISS -> hideAllModals()
            OverlayAction.SET_MODE -> { }
            OverlayAction.OVERLAY -> showOverlay(command)
            OverlayAction.TOAST -> command.toast?.let(::showToast)
            OverlayAction.FORM -> command.form?.let(::showForm)
            OverlayAction.LIST -> command.list?.let(::handleListOrSearchResults)
            OverlayAction.SEARCH -> command.search?.let(::showSearch)
            OverlayAction.SEARCH_RESULTS -> command.list?.let(::applySearchResults)
            OverlayAction.SET_PAGE -> command.page?.let { setPage(it, notify = true) }
            OverlayAction.FEEDBACK -> { }
            OverlayAction.TTS, OverlayAction.TTS_STOP -> { }
            OverlayAction.UI_CONFIG, OverlayAction.REINIT -> { }
        }
    }

    private fun handleListOrSearchResults(payload: ListPayload) {
        val search = activeSearch
        if (search != null && search.id == payload.id) {
            applySearchResults(payload)
        } else if (activeList?.id == payload.id) {
            applyListItems(payload)
        } else {
            showList(payload)
        }
    }

    private fun showToast(payload: ToastPayload) {
        val length = if (payload.durationLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Toast.makeText(this, payload.message, length).show()
        val error = payload.level == ToastPayload.LEVEL_ERROR
        status(payload.message, error = error)
        if (payload.level == ToastPayload.LEVEL_OK) {
            binding.statusMessage.setTextColor(Color.parseColor("#81C784"))
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
        hideForm(reason = null)
        hideList(reason = null)
        hideSearch(reason = null)
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

    private fun showForm(payload: FormPayload) {
        hideOverlay()
        hideList(reason = null)
        hideSearch(reason = null)
        activeForm = payload
        binding.formTitle.text = payload.title
        binding.formConfirmButton.text = payload.confirmLabel
        binding.formCancelButton.text = payload.cancelLabel
        binding.formFields.removeAllViews()
        formViews.clear()
        formBarcodeInputs.clear()
        var firstFocus: View? = null
        for (field in payload.fields) {
            when (field.type) {
                FormField.TYPE_TOGGLE -> {
                    val check = CheckBox(this).apply {
                        text = field.label
                        isChecked = field.value == "true"
                        setTextColor(Color.parseColor("#F2F2F2"))
                        textSize = 16f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(8) }
                    }
                    formViews[field.id] = check
                    binding.formFields.addView(check)
                    if (firstFocus == null) firstFocus = check
                }
                FormField.TYPE_SELECT -> {
                    val label = formFieldLabel(field.label)
                    val spinner = Spinner(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(4) }
                        adapter = ArrayAdapter(
                            this@MainActivity,
                            android.R.layout.simple_spinner_dropdown_item,
                            field.options.map { it.label }
                        )
                        val selected = field.options.indexOfFirst { it.id == field.value }
                            .takeIf { it >= 0 } ?: 0
                        setSelection(selected.coerceIn(0, (field.options.size - 1).coerceAtLeast(0)))
                    }
                    formViews[field.id] = spinner
                    binding.formFields.addView(label)
                    binding.formFields.addView(spinner)
                    if (firstFocus == null) firstFocus = spinner
                }
                else -> {
                    val label = formFieldLabel(field.label)
                    val input = EditText(this).apply {
                        setText(field.value)
                        hint = when {
                            field.placeholder.isNotBlank() -> field.placeholder
                            field.type == FormField.TYPE_BARCODE -> "Scan barcode"
                            else -> field.label
                        }
                        setTextColor(Color.parseColor("#F2F2F2"))
                        setHintTextColor(Color.parseColor("#808080"))
                        setBackgroundResource(R.drawable.edit_bg)
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        maxLines = 1
                        imeOptions = EditorInfo.IME_ACTION_DONE
                        inputType = when (field.type) {
                            FormField.TYPE_NUMBER ->
                                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                            else -> InputType.TYPE_CLASS_TEXT
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(4) }
                    }
                    formViews[field.id] = input
                    if (field.type == FormField.TYPE_BARCODE) {
                        formBarcodeInputs.add(input)
                    }
                    binding.formFields.addView(label)
                    binding.formFields.addView(input)
                    if (firstFocus == null) firstFocus = input
                }
            }
        }
        binding.formOverlay.visibility = View.VISIBLE
        refreshFormScanGate()
        main.removeCallbacks(formTimeout)
        payload.timeoutSec?.let { sec ->
            main.postDelayed(formTimeout, sec * 1000L)
        }
        status(payload.title, error = false)
        val focusBarcode = formBarcodeInputs.firstOrNull()
        (focusBarcode ?: firstFocus)?.requestFocus()
    }

    private fun formFieldLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#B3B3B3"))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
    }

    private fun refreshFormScanGate() {
        FormScanGate.consumeScans = activeForm?.fields?.any {
            it.type == FormField.TYPE_BARCODE
        } == true && binding.formOverlay.visibility == View.VISIBLE
    }

    private fun confirmForm() {
        val form = activeForm ?: return
        val values = LinkedHashMap<String, String>()
        for (field in form.fields) {
            values[field.id] = when (val view = formViews[field.id]) {
                is CheckBox -> if (view.isChecked) "true" else "false"
                is Spinner -> {
                    val index = view.selectedItemPosition
                    field.options.getOrNull(index)?.id.orEmpty()
                }
                is EditText -> view.text?.toString()?.trim().orEmpty()
                else -> ""
            }
        }
        val formId = form.id
        io.execute {
            SensorPublisher(HaApi(Mc40App.instance.prefs), Mc40App.instance.prefs)
                .publishFormSubmit(formId, values)
        }
        hideForm(reason = null)
        status("Form sent", error = false)
    }

    private fun hideForm(reason: String?) {
        val form = activeForm
        main.removeCallbacks(formTimeout)
        activeForm = null
        formViews.clear()
        formBarcodeInputs.clear()
        FormScanGate.consumeScans = false
        binding.formFields.removeAllViews()
        binding.formOverlay.visibility = View.GONE
        hideKeyboard()
        if (reason != null && form != null) {
            val formId = form.id
            io.execute {
                SensorPublisher(HaApi(Mc40App.instance.prefs), Mc40App.instance.prefs)
                    .publishFormCancel(formId, reason)
            }
        }
        binding.wedgeCapture.requestFocus()
    }

    private fun showList(payload: ListPayload) {
        hideOverlay()
        hideForm(reason = null)
        hideSearch(reason = null)
        activeList = payload
        binding.listTitle.text = payload.title
        binding.listFilter.setText("")
        binding.listFilter.visibility = if (payload.filter) View.VISIBLE else View.GONE
        binding.listOverlay.visibility = View.VISIBLE
        renderListItems(payload.items)
        main.removeCallbacks(listTimeout)
        payload.timeoutSec?.let { sec ->
            main.postDelayed(listTimeout, sec * 1000L)
        }
        status(payload.title, error = false)
        if (payload.filter) {
            binding.listFilter.requestFocus()
        } else {
            binding.wedgeCapture.requestFocus()
        }
        val listId = payload.id
        val title = payload.title
        io.execute {
            SensorPublisher(HaApi(Mc40App.instance.prefs), Mc40App.instance.prefs)
                .publishListShow(listId, title)
        }
    }

    private fun applyListItems(payload: ListPayload) {
        val list = activeList ?: return
        if (list.id != payload.id) return
        activeList = list.copy(
            title = if (payload.title.isNotBlank() && payload.title != payload.id) {
                payload.title
            } else {
                list.title
            },
            items = payload.items,
            filter = payload.filter,
            timeoutSec = payload.timeoutSec ?: list.timeoutSec
        )
        val updated = activeList!!
        if (updated.title.isNotBlank()) {
            binding.listTitle.text = updated.title
        }
        binding.listFilter.visibility = if (updated.filter) View.VISIBLE else View.GONE
        val query = binding.listFilter.text?.toString()?.trim().orEmpty()
        val visible = if (!updated.filter || query.isEmpty()) {
            updated.items
        } else {
            updated.items.filter { item ->
                item.label.contains(query, ignoreCase = true) ||
                    item.subtitle.contains(query, ignoreCase = true)
            }
        }
        renderListItems(visible)
        main.removeCallbacks(listTimeout)
        updated.timeoutSec?.let { sec ->
            main.postDelayed(listTimeout, sec * 1000L)
        }
        status(updated.title, error = false)
    }

    private fun wireListFilter() {
        binding.listFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val list = activeList ?: return
                val query = s?.toString()?.trim().orEmpty()
                val filtered = if (query.isEmpty()) {
                    list.items
                } else {
                    list.items.filter { item ->
                        item.label.contains(query, ignoreCase = true) ||
                            item.subtitle.contains(query, ignoreCase = true)
                    }
                }
                renderListItems(filtered)
            }
        })
    }

    private fun renderListItems(items: List<ListItem>) {
        binding.listItems.removeAllViews()
        binding.listEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.btn_mode_off)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                isClickable = true
                isFocusable = true
                setOnClickListener { selectListItem(item) }
            }
            row.addView(
                TextView(this).apply {
                    text = item.label
                    setTextColor(Color.parseColor("#F2F2F2"))
                    textSize = 16f
                    maxLines = 2
                }
            )
            if (item.subtitle.isNotBlank()) {
                row.addView(
                    TextView(this).apply {
                        text = item.subtitle
                        setTextColor(Color.parseColor("#B3B3B3"))
                        textSize = 13f
                        maxLines = 2
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(2) }
                    }
                )
            }
            binding.listItems.addView(row)
        }
    }

    private fun selectListItem(item: ListItem) {
        val list = activeList ?: return
        val listId = list.id
        io.execute {
            SensorPublisher(HaApi(Mc40App.instance.prefs), Mc40App.instance.prefs)
                .publishListSelect(listId, item.id, item.label)
        }
        hideList(reason = null)
        status("Selected ${item.label}", error = false)
    }

    private fun hideList(reason: String?) {
        val list = activeList
        main.removeCallbacks(listTimeout)
        activeList = null
        binding.listItems.removeAllViews()
        binding.listOverlay.visibility = View.GONE
        hideKeyboard()
        if (reason != null && list != null) {
            val listId = list.id
            io.execute {
                SensorPublisher(HaApi(Mc40App.instance.prefs), Mc40App.instance.prefs)
                    .publishListCancel(listId, reason)
            }
        }
        binding.wedgeCapture.requestFocus()
    }

    private fun showSearch(payload: SearchPayload) {
        hideOverlay()
        hideForm(reason = null)
        hideList(reason = null)
        activeSearch = payload
        binding.searchTitle.text = payload.title
        binding.searchQuery.hint = payload.placeholder.ifBlank { getString(R.string.hint_search) }
        binding.searchQuery.setText(payload.query)
        binding.searchStatus.setText(R.string.search_waiting)
        binding.searchItems.removeAllViews()
        binding.searchOverlay.visibility = View.VISIBLE
        main.removeCallbacks(searchTimeout)
        payload.timeoutSec?.let { sec ->
            main.postDelayed(searchTimeout, sec * 1000L)
        }
        status(payload.title, error = false)
        binding.searchQuery.requestFocus()
    }

    private fun wireSearchQuery() {
        binding.searchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                submitSearch()
                true
            } else {
                false
            }
        }
    }

    private fun submitSearch() {
        val search = activeSearch ?: return
        val query = binding.searchQuery.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            binding.searchStatus.setText(R.string.search_empty_query)
            status(getString(R.string.search_empty_query), error = true)
            return
        }
        binding.searchStatus.setText(R.string.search_loading)
        binding.searchItems.removeAllViews()
        hideKeyboard()
        val searchId = search.id
        io.execute {
            SensorPublisher(HaApi(Mc40App.instance.prefs), Mc40App.instance.prefs)
                .publishSearch(searchId, query)
        }
        status("Searching…", error = false)
    }

    private fun applySearchResults(payload: ListPayload) {
        val search = activeSearch ?: return
        if (search.id != payload.id) return
        if (payload.title.isNotBlank() && payload.title != payload.id) {
            binding.searchTitle.text = payload.title
        }
        renderSearchItems(payload.items)
        binding.searchStatus.text = if (payload.items.isEmpty()) {
            getString(R.string.list_empty)
        } else {
            "${payload.items.size} result${if (payload.items.size == 1) "" else "s"}"
        }
        status(binding.searchStatus.text.toString(), error = false)
    }

    private fun renderSearchItems(items: List<ListItem>) {
        binding.searchItems.removeAllViews()
        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.btn_mode_off)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                isClickable = true
                isFocusable = true
                setOnClickListener { selectSearchItem(item) }
            }
            row.addView(
                TextView(this).apply {
                    text = item.label
                    setTextColor(Color.parseColor("#F2F2F2"))
                    textSize = 16f
                    maxLines = 2
                }
            )
            if (item.subtitle.isNotBlank()) {
                row.addView(
                    TextView(this).apply {
                        text = item.subtitle
                        setTextColor(Color.parseColor("#B3B3B3"))
                        textSize = 13f
                        maxLines = 2
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(2) }
                    }
                )
            }
            binding.searchItems.addView(row)
        }
    }

    private fun selectSearchItem(item: ListItem) {
        val search = activeSearch ?: return
        val listId = search.id
        io.execute {
            SensorPublisher(HaApi(Mc40App.instance.prefs), Mc40App.instance.prefs)
                .publishListSelect(listId, item.id, item.label)
        }
        hideSearch(reason = null)
        status("Selected ${item.label}", error = false)
    }

    private fun hideSearch(reason: String?) {
        val search = activeSearch
        main.removeCallbacks(searchTimeout)
        activeSearch = null
        binding.searchItems.removeAllViews()
        binding.searchOverlay.visibility = View.GONE
        hideKeyboard()
        if (reason != null && search != null) {
            val listId = search.id
            io.execute {
                SensorPublisher(HaApi(Mc40App.instance.prefs), Mc40App.instance.prefs)
                    .publishListCancel(listId, reason)
            }
        }
        binding.wedgeCapture.requestFocus()
    }

    private fun hideAllModals() {
        hideOverlay()
        hideForm(reason = null)
        hideList(reason = null)
        hideSearch(reason = null)
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
        val view = currentFocus ?: binding.wedgeCapture
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
        val productId = card.productId
        val name = card.name
        val qty = overlayQty
        val unit = card.unit
        io.execute {
            SensorPublisher(HaApi(Mc40App.instance.prefs), Mc40App.instance.prefs)
                .publishModeConfirm(barcode, name, qty, measure, unit, productId)
        }
        hideOverlay()
        status("Sent ${formatQty(qty)} ${unit}", error = false)
    }
}
