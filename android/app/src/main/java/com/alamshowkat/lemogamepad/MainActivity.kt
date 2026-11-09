package com.alamshowkat.lemogamepad

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.graphics.Color
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var view: GamepadView
    private lateinit var link: Link
    private lateinit var prefs: SharedPreferences
    private lateinit var layouts: LayoutStore
    private lateinit var root: FrameLayout
    private var editBar: LinearLayout? = null
    private var settingsPanel: SettingsPanel? = null
    private var wifiGuide: View? = null

    /** Small non-blocking Accept/Decline card shown when another player asks to swap. */
    private var swapPrompt: View? = null
    private var swapPromptId = 0
    private var answeredSwapId = 0
    private val swapPromptTimeout = Runnable { dismissSwapPrompt() }

    /** Small draggable window that mirrors the PC screen. */
    private var screenView: ScreenView? = null
    private var screenEnabled = false
    private var screenSmall = true
    private var screenQuality = ScreenQuality.BALANCED
    private lateinit var phoneGamepad: BluetoothHidGamepad
    private var phoneGamepadActive = false
    private var lastPhoneVersion = -1L

    private val handler = Handler(Looper.getMainLooper())
    private var batteryReceiver: BroadcastReceiver? = null
    private var batteryLevel = -1

    @Volatile private var rumbleAmp = 0
    @Volatile private var lastRumbleAt = 0L
    @Volatile private var hapticStrength = 1f
    /** True between onResume and onPause; read by the link's rumble callback. */
    @Volatile private var resumed = false

    private val phoneGamepadTicker = object : Runnable {
        override fun run() {
            if (!phoneGamepadActive) return
            // Only push a report when something moved, and only while a host
            // is connected. Idle ticks get slower so a paired-but-paused host
            // does not cost a wakeup every frame.
            val connected = phoneGamepad.isConnected
            if (connected) {
                val version = view.state.version
                if (version != lastPhoneVersion) {
                    lastPhoneVersion = version
                    phoneGamepad.send(view.state)
                }
            }
            handler.postDelayed(this, if (connected) 16L else 250L)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 350)
        }
    }

    /** Repeats short pulses while the PC keeps asking for rumble. */
    private val rumbleTicker = object : Runnable {
        override fun run() {
            if (rumbleAmp <= 0) return
            if (SystemClock.uptimeMillis() - lastRumbleAt > RUMBLE_HOLD_MS) {
                rumbleAmp = 0
                return
            }
            pulse(rumbleAmp)
            handler.postDelayed(this, RUMBLE_PERIOD_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences(PrefsKeys.FILE, MODE_PRIVATE)
        layouts = LayoutStore(prefs)
        if (layouts.activeName() == null) layouts.clearActive()
        phoneGamepad = BluetoothHidGamepad(applicationContext) { message ->
            handler.post { if (!isFinishing) toast(message) }
        }

        view = GamepadView(this)
        view.theme = Themes.byId(prefs.getString(PrefsKeys.THEME, null))
        view.faceLabels = if (prefs.getString(PrefsKeys.FACES, "ps") == "xbox") {
            FaceLabels.XBOX
        } else {
            FaceLabels.PLAYSTATION
        }
        view.applyLayout(layouts.activeData())
        view.deadzone = prefs.getInt(PrefsKeys.DEADZONE, 12) / 100f
        view.stickCurve = prefs.getInt(PrefsKeys.STICK_CURVE, 0) / 100f
        view.hapticsEnabled = prefs.getBoolean(PrefsKeys.HAPTICS, true)
        view.onOpenSettings = { showSettings() }
        view.onHaptic = { ms -> tapFeedback(ms) }
        view.onLayoutChanged = { persistLayout() }
        view.onToast = { msg -> toast(msg) }

        root = FrameLayout(this)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)

        link = Link(view.state, applicationContext)
        link.port = prefs.getInt(PrefsKeys.PORT, Proto.PORT)
        link.transport = transportById(prefs.getString(PrefsKeys.TRANSPORT, null))
        link.inputRate = InputRate.fromId(prefs.getString(PrefsKeys.INPUT_RATE, null))
        link.wantSlot = prefs.getInt(PrefsKeys.PLAYER_SLOT, 0)
        link.onRumble = { small, large -> handleRumble(small, large) }
        link.onSlotAsk = { ask -> handler.post { showSwapPrompt(ask) } }
        link.onSlotResult = { result -> handler.post { handleSlotResult(result) } }

        screenEnabled = prefs.getBoolean(PrefsKeys.SCREEN, false)
        screenSmall = prefs.getBoolean(PrefsKeys.SCREEN_SMALL, true)
        hapticStrength = prefs.getInt(PrefsKeys.HAPTIC_STRENGTH, 100) / 100f
        screenQuality = ScreenQuality.fromId(
            prefs.getInt(PrefsKeys.SCREEN_QUALITY, ScreenQuality.BALANCED.wireId),
        )

        if (!prefs.getBoolean(PrefsKeys.WIFI_GUIDE_SEEN, false)) {
            showWifiGuide()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        enterImmersive()
        link.host = prefs.getString(PrefsKeys.HOST, null)?.takeIf { it.isNotBlank() }
        link.start()
        registerBattery()
        handler.post(ticker)
        if (screenEnabled) showScreenPreview(true)
        // The HID ticker is stopped while backgrounded; restart it here.
        if (phoneGamepadActive) {
            handler.removeCallbacks(phoneGamepadTicker)
            handler.post(phoneGamepadTicker)
        }
    }

    override fun onPause() {
        super.onPause()
        // A rumble callback can still be in flight on the receiver thread; make
        // sure it cannot restart the ticker after this method clears it.
        resumed = false
        LayoutDialogs.dismissAll(this)
        dismissSwapPrompt()
        settingsPanel?.dismiss(animate = false)
        if (view.editMode) exitEditing(save = true)
        handler.removeCallbacks(ticker)
        rumbleAmp = 0
        handler.removeCallbacks(rumbleTicker)
        vibrator()?.cancel()
        view.resetInputs()
        link.stop()
        // Release every button on the game host before parking the ticker.
        handler.removeCallbacks(phoneGamepadTicker)
        lastPhoneVersion = -1L
        if (phoneGamepadActive) phoneGamepad.send(view.state)
        // Keep the little window on screen, but drop the TCP link while the
        // app is in the background so it does not burn battery.
        screenView?.stopStreaming()
        unregisterBattery()
    }

    override fun onDestroy() {
        LayoutDialogs.dismissAll(this)
        phoneGamepadActive = false
        handler.removeCallbacks(phoneGamepadTicker)
        if (::phoneGamepad.isInitialized) phoneGamepad.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        wifiGuide?.let {
            root.removeView(it)
            wifiGuide = null
            prefs.edit().putBoolean(PrefsKeys.WIFI_GUIDE_SEEN, true).apply()
            return
        }
        val panel = settingsPanel
        if (panel != null && panel.isShowing) {
            panel.dismiss()
            return
        }
        if (view.editMode) {
            exitEditing(save = true)
            return
        }
        super.onBackPressed()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_QR || resultCode != RESULT_OK || data == null) return
        val host = data.getStringExtra(ScanActivity.EXTRA_HOST) ?: return
        val port = data.getIntExtra(ScanActivity.EXTRA_PORT, Proto.PORT).coerceIn(1, 65535)
        val name = data.getStringExtra(ScanActivity.EXTRA_NAME) ?: ""

        prefs.edit().putString(PrefsKeys.HOST, host).putInt(PrefsKeys.PORT, port).apply()
        link.transport = transportById(prefs.getString(PrefsKeys.TRANSPORT, null))
        link.retarget(host, port)
        link.serverName = name
        attachScreenStream()
        toast(if (name.isBlank()) "Paired with $host" else "Paired with $name")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BT) return
        settingsPanel?.onBluetoothPermissionResult(!BluetoothPairing.permissionsMissing(this))
    }

    // ------------------------------------------------------------------ status
    private fun refreshStatus() {
        link.refreshConnectionState()
        val host = link.host
        when {
            host == null -> view.setStatus("TAP TO CONNECT", GamepadView.STATUS_IDLE)
            link.connected -> {
                val name = link.serverName.ifBlank { host }
                val linkName = link.transportName()
                val rtt = if (link.rttMs >= 0) "  ·  ${link.rttMs} ms" else ""
                val jitter = if (link.jitterMs > 0) " ±${link.jitterMs}" else ""
                val loss = if (link.lossPermille >= 5) {
                    "  ·  ${(link.lossPermille + 5) / 10}% loss"
                } else {
                    ""
                }
                val batt = if (batteryLevel in 0..100) "  ·  $batteryLevel%" else ""
                val via = if (linkName.isBlank()) "" else "  ·  $linkName"
                val pad = if (link.assignedSlot > 0) "  ·  P${link.assignedSlot}" else ""
                view.setStatus("$name$via$pad$rtt$jitter$loss$batt", GamepadView.STATUS_OK)
            }
            else -> {
                val kind = link.transport
                val linkName = if (kind == Transport.AUTO) "" else "  ·  ${kind.label}"
                view.setStatus("CONNECTING$linkName  ·  $host", GamepadView.STATUS_WARN)
            }
        }
        screenView?.accent = view.theme.accent
    }

    private fun showWifiGuide() {
        if (wifiGuide != null) return
        val t = view.theme
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(218, 0, 0, 0))
            isClickable = true
            isFocusable = true
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(18), px(22), px(18))
            background = GradientDrawable().apply {
                cornerRadius = px(22).toFloat()
                setColor(t.surface)
                setStroke(px(1), withAlpha(t.accent, 120))
            }
            elevation = px(16).toFloat()
        }

        fun addCopy(text: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) {
            val label = TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setLineSpacing(px(3).toFloat(), 1f)
                if (top > 0) setPadding(0, px(top), 0, 0)
            }
            card.addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        addCopy("QUICK START  ·  WI-FI", 10f, t.accent, bold = true)
        addCopy("Use your phone as a gamepad", 21f, t.text, bold = true, top = 5)
        addCopy(
            "1. Connect your phone and PC to the same Wi-Fi network.\n" +
                "2. Start lemoGamePad-server.exe on the PC and leave it running.\n" +
                "3. Scan the network, then connect to your PC.",
            13f,
            t.textDim,
            top = 10,
        )
        addCopy(
            "Guest Wi-Fi can block devices from seeing each other. If the PC is not found, check the Windows Firewall line in the server window.",
            11f,
            t.muted,
            top = 8,
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, px(14), 0, 0)
        }
        fun addButton(title: String, primary: Boolean, onClick: () -> Unit) {
            val button = TextView(this).apply {
                text = title
                textSize = 11f
                letterSpacing = 0.06f
                gravity = Gravity.CENTER
                setTextColor(if (primary) t.bgBottom else t.textDim)
                setPadding(px(14), px(11), px(14), px(11))
                background = GradientDrawable().apply {
                    cornerRadius = px(13).toFloat()
                    setColor(if (primary) t.accent else withAlpha(t.text, 18))
                    if (!primary) setStroke(px(1), withAlpha(t.text, 38))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            actions.addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = px(8) })
        }

        fun dismissGuide() {
            root.removeView(scrim)
            wifiGuide = null
            prefs.edit().putBoolean(PrefsKeys.WIFI_GUIDE_SEEN, true).apply()
        }
        addButton("GOT IT", primary = false) { dismissGuide() }
        addButton("OPEN WI-FI SETUP", primary = true) {
            link.transport = Transport.WIFI
            prefs.edit().putString(PrefsKeys.TRANSPORT, "wifi").apply()
            dismissGuide()
            showSettings()
        }
        card.addView(actions)
        scrim.addView(
            card,
            FrameLayout.LayoutParams(
                minOf(px(520), (resources.displayMetrics.widthPixels * 0.92f).toInt()),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        wifiGuide = scrim
        root.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(220L).start()
    }

    // ------------------------------------------------------------------ screen
    private fun showScreenPreview(show: Boolean) {
        screenEnabled = show
        val existing = screenView
        if (!show) {
            existing?.let {
                it.stopStreaming()
                root.removeView(it)
            }
            screenView = null
            return
        }

        val sv = existing ?: createScreenView()
        sv.visibility = if (view.editMode) View.GONE else View.VISIBLE
        sv.accent = view.theme.accent
        sv.setSmall(screenSmall, animate = false)
        attachScreenStream()
        placeScreenView(sv, animate = false)
        if (existing == null) {
            sv.alpha = 0f
            sv.scaleX = 0.8f
            sv.scaleY = 0.8f
            sv.post {
                sv.animate()
                    .alpha(1f)
                    .scaleX(if (screenSmall) ScreenView.SMALL_SCALE else 1f)
                    .scaleY(if (screenSmall) ScreenView.SMALL_SCALE else 1f)
                    .setDuration(320L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun createScreenView(): ScreenView {
        val sv = ScreenView(this)
        sv.setQuality(screenQuality)
        sv.onQualityUnsupported = {
            if (screenEnabled) toast("Update the PC server to use stream quality controls")
        }
        sv.onClose = {
            prefs.edit().putBoolean(PrefsKeys.SCREEN, false).apply()
            screenEnabled = false
            // stopStreaming() closes the socket and stops the receiver thread;
            // removing the view alone left both alive until process death.
            sv.stopStreaming()
            root.removeView(sv)
            screenView = null
            toast("Screen preview off - re-enable it in settings")
        }
        sv.onToggleSize = {
            screenSmall = !screenSmall
            prefs.edit().putBoolean(PrefsKeys.SCREEN_SMALL, screenSmall).apply()
            // Re-clamp once the scale animation has settled.
            handler.postDelayed({
                screenView?.let { placeScreenView(it, animate = true) }
            }, 280L)
        }
        sv.onMoved = { fx, fy ->
            prefs.edit().putFloat(PrefsKeys.SCREEN_X, fx).putFloat(PrefsKeys.SCREEN_Y, fy).apply()
        }
        // The PiP TCP stream must leave through the same network as the pad,
        // otherwise BT/USB tethers silently swallow it.
        sv.networkProvider = { link.boundNetwork() }
        sv.localAddressProvider = { link.boundLocalAddress()?.hostAddress }
        screenView = sv

        val params = FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.36f).toInt().coerceAtLeast(px(120)),
            (resources.displayMetrics.widthPixels * 0.36f * 9f / 16f).toInt().coerceAtLeast(px(70)),
        )
        root.addView(sv, params)
        attachScreenStream()
        placeScreenView(sv, animate = false)
        return sv
    }

    private fun attachScreenStream() {
        val sv = screenView ?: return
        val host = link.host
        if (host == null || !screenEnabled) {
            sv.stopStreaming()
            return
        }
        sv.setEndpoint(host, Proto.videoPort(link.port))
    }

    /** Restores the saved centre position (normalised) and clamps to the screen. */
    private fun placeScreenView(sv: ScreenView, animate: Boolean) {
        val pw = root.width
        val ph = root.height
        if (pw <= 0 || ph <= 0 || sv.width <= 0 || sv.height <= 0) {
            root.post { placeScreenView(sv, animate) }
            return
        }
        val vw = sv.width * sv.scaleX
        val vh = sv.height * sv.scaleY

        var fx = prefs.getFloat(PrefsKeys.SCREEN_X, 0.5f)
        var fy = prefs.getFloat(PrefsKeys.SCREEN_Y, 0.30f)
        if (fx <= 0f && fy <= 0f) {
            fx = 0.5f
            fy = 0.30f
        }

        val targetX = (fx * pw - sv.width / 2f)
            .coerceIn(-(sv.width - vw) / 2f, (pw - vw - (sv.width - vw) / 2f).coerceAtLeast(0f))
        val targetY = (fy * ph - sv.height / 2f)
            .coerceIn(-(sv.height - vh) / 2f, (ph - vh - (sv.height - vh) / 2f).coerceAtLeast(0f))

        if (animate && sv.isAttachedToWindow) {
            sv.animate().x(targetX).y(targetY).setDuration(240L)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            sv.x = targetX
            sv.y = targetY
        }
    }

    // ---------------------------------------------------------------- editing
    private fun startEditing() {
        if (view.editMode) return
        view.editMode = true
        screenView?.visibility = View.GONE
        showEditBar()
        toast("Drag to move · pinch to resize · hold to hide")
    }

    private fun exitEditing(save: Boolean) {
        if (save) persistLayout()
        view.editMode = false
        screenView?.visibility = View.VISIBLE
        screenView?.let { placeScreenView(it, animate = true) }
        hideEditBar()
    }

    private fun persistLayout() {
        val snapshot = view.layoutSnapshot()
        prefs.edit().putString(PrefsKeys.LAYOUT, snapshot).apply()
        // Keep the named preset in sync when the user tweaks an active layout.
        layouts.updateActive(snapshot)
    }

    private fun showEditBar() {
        hideEditBar()
        val t = view.theme

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(px(5), px(4), px(5), px(4))
            background = GradientDrawable().apply {
                cornerRadius = px(18).toFloat()
                setColor(withAlpha(t.surface, 238))
                setStroke(px(1), withAlpha(t.accent, 130))
            }
            alpha = 0f
            translationY = -px(22).toFloat()
        }

        fun addButton(text: String, primary: Boolean, action: () -> Unit) {
            val b = TextView(this).apply {
                this.text = text
                textSize = 11f
                letterSpacing = 0.08f
                gravity = Gravity.CENTER
                setTextColor(if (primary) t.bgBottom else t.text)
                setPadding(px(12), px(9), px(12), px(9))
                background = GradientDrawable().apply {
                    cornerRadius = px(14).toFloat()
                    setColor(if (primary) t.accent else withAlpha(t.surfaceAlt, 210))
                    if (!primary) setStroke(px(1), withAlpha(t.text, 45))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            bar.addView(
                b,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = px(3)
                    marginEnd = px(3)
                },
            )
        }

        addButton("DONE", true) { exitEditing(save = true) }
        addButton("SAVE AS", false) {
            LayoutDialogs.promptName(
                this@MainActivity,
                view.theme,
                title = "SAVE LAYOUT",
                initial = "",
                existing = layouts.saved().map { it.name },
            ) { name ->
                val replaced = layouts.save(name, view.layoutSnapshot())
                toast(if (replaced) "Layout \"$name\" updated" else "Layout \"$name\" saved")
            }
        }
        addButton("RESET", false) {
            view.resetLayout()
            layouts.clearActive()
            persistLayout()
            toast("Default layout restored")
        }
        addButton("SHOW ALL", false) { view.showAll() }

        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = px(6)
            },
        )
        editBar = bar
        bar.animate().alpha(1f).translationY(0f)
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hideEditBar() {
        val bar = editBar ?: return
        editBar = null
        bar.animate().alpha(0f).translationY(-px(16).toFloat())
            .setDuration(160L)
            .withEndAction {
                root.removeView(bar)
            }
            .start()
    }

    // ---------------------------------------------------------------- settings
    private fun showSettings() {
        if (view.editMode) exitEditing(save = true)
        if (settingsPanel != null) return

        val panel = SettingsPanel(
            this,
            link,
            view,
            prefs,
            object : SettingsPanel.Callbacks {
                override fun onScanQr() {
                    settingsPanel?.dismiss()
                    startActivityForResult(
                        Intent(this@MainActivity, ScanActivity::class.java),
                        REQ_QR,
                    )
                }

                override fun onEditLayout() {
                    settingsPanel?.dismiss()
                    handler.postDelayed({ startEditing() }, 260L)
                }

                override fun onLayoutSaved() = persistLayout()

                override fun onThemeChanged(theme: PadTheme) {
                    screenView?.accent = theme.accent
                    refreshStatus()
                }

                override fun onScreenPreview(enabled: Boolean) = showScreenPreview(enabled)

                override fun onScreenSize(small: Boolean) {
                    screenSmall = small
                    prefs.edit().putBoolean(PrefsKeys.SCREEN_SMALL, small).apply()
                    screenView?.let { sv ->
                        sv.setSmall(small, animate = true)
                        handler.postDelayed({ placeScreenView(sv, animate = true) }, 280L)
                    }
                }

                override fun onScreenQuality(quality: ScreenQuality) {
                    screenQuality = quality
                    prefs.edit().putInt(PrefsKeys.SCREEN_QUALITY, quality.wireId).apply()
                    screenView?.setQuality(quality)
                }

                override fun onHapticsChanged(strength: Float) {
                    hapticStrength = strength.coerceIn(0f, 1f)
                }

                override fun attachScreenStream() {
                    this@MainActivity.attachScreenStream()
                }

                override fun onClosed() {
                    settingsPanel = null
                }

                override fun toast(message: String) {
                    this@MainActivity.toast(message)
                }

                override fun bluetoothPermissionsMissing(): Boolean =
                    BluetoothPairing.permissionsMissing(this@MainActivity)

                override fun requestBluetoothPermissions() {
                    BluetoothPairing.requestPermissions(this@MainActivity, REQ_BT)
                }

                override fun onStartPhoneGamepad() {
                    if (phoneGamepadActive) {
                        toast("Phone gamepad is already running")
                        return
                    }
                    if (BluetoothPairing.permissionsMissing(this@MainActivity)) {
                        BluetoothPairing.requestPermissions(this@MainActivity, REQ_BT)
                        toast("Allow Nearby devices, then tap START GAMEPAD again")
                        return
                    }
                    if (!BluetoothPairing.status(this@MainActivity).enabled) {
                        toast("Turn on Bluetooth, then start the phone gamepad")
                        return
                    }
                    if (!phoneGamepad.start()) return
                    phoneGamepadActive = true
                    lastPhoneVersion = -1L
                    handler.removeCallbacks(phoneGamepadTicker)
                    handler.post(phoneGamepadTicker)
                    if (BluetoothPairing.requestDiscoverable(this@MainActivity)) {
                        toast("Accept the Bluetooth prompt, then pair Lemo GamePad from the game phone")
                    } else {
                        toast("Open Bluetooth settings on the game phone and pair Lemo GamePad")
                    }
                }

                override fun onStopPhoneGamepad() {
                    phoneGamepadActive = false
                    handler.removeCallbacks(phoneGamepadTicker)
                    phoneGamepad.stop()
                    toast("Phone gamepad stopped")
                }

                override fun phoneGamepadRunning(): Boolean = phoneGamepadActive

                override fun phoneGamepadConnected(): Boolean =
                    phoneGamepadActive && phoneGamepad.isConnected
            },
        )
        settingsPanel = panel
        panel.show(root)
    }

    // ---------------------------------------------------------------- battery
    private fun registerBattery() {
        if (batteryReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra("level", -1) ?: -1
                val scale = intent?.getIntExtra("scale", 100) ?: 100
                if (level >= 0 && scale > 0) {
                    batteryLevel = (level * 100 / scale).coerceIn(0, 100)
                    link.battery = batteryLevel
                }
            }
        }
        batteryReceiver = receiver
        try {
            registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            batteryReceiver = null
        }
    }

    private fun unregisterBattery() {
        batteryReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        batteryReceiver = null
    }

    // --------------------------------------------------------------- feedback
    private fun tapFeedback(ms: Int) {
        val v = vibrator() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms.toLong(), 90))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(ms.toLong())
        }
    }

    private fun handleRumble(small: Int, large: Int) {
        // Rumble callbacks arrive on the link's receiver thread. After onPause
        // the ticker has been removed and the vibrator cancelled, so ignore
        // anything that was already in flight instead of re-arming it in the
        // background.
        if (!resumed) return
        val now = SystemClock.uptimeMillis()
        val amp = maxOf(small, large).coerceIn(0, 255)
        if (amp == 0) {
            // ViGEm reports the current motor state about once a second, so a
            // "stop" right next to a live value is usually a stale datagram.
            if (now - lastRumbleAt < RUMBLE_STALE_MS) return
            lastRumbleAt = now
            if (rumbleAmp != 0) {
                rumbleAmp = 0
                vibrator()?.cancel()
            }
            return
        }

        lastRumbleAt = now
        if (!view.hapticsEnabled) {
            if (rumbleAmp != 0) vibrator()?.cancel()
            rumbleAmp = 0
            return
        }
        if (rumbleAmp == 0) {
            pulse(amp)
            handler.removeCallbacks(rumbleTicker)
            handler.postDelayed(rumbleTicker, RUMBLE_PERIOD_MS)
        }
        rumbleAmp = amp
    }

    private fun pulse(amp: Int) {
        val v = vibrator() ?: return
        val strength = (amp * hapticStrength).toInt().coerceIn(0, 255)
        if (strength == 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(RUMBLE_PULSE_MS, strength))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(RUMBLE_PULSE_MS)
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }

    // ------------------------------------------------------------------ window
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    // --------------------------------------------------------------- pad swap
    /**
     * Another player wants this pad. The card is deliberately small and has no
     * scrim: it sits above the pad without pausing input or blocking the rest
     * of the screen, so a game in progress keeps running underneath it.
     */
    private fun showSwapPrompt(ask: SlotAsk) {
        if (isFinishing || !resumed) return
        // The PC resends the ask until it is answered. Do not rebuild the
        // card (or reopen it after a decision) for the same request.
        if (ask.id == answeredSwapId) return
        // A repeated ask must not rebuild the card or reopen it after a
        // decision; ordering across reconnects is handled per socket in Link.
        if (swapPrompt != null && ask.id == swapPromptId) return
        dismissSwapPrompt()
        swapPromptId = ask.id
        val t = view.theme

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(12))
            background = GradientDrawable().apply {
                cornerRadius = px(16).toFloat()
                setColor(withAlpha(t.surface, 248))
                setStroke(px(1), withAlpha(t.accent, 190))
            }
            elevation = px(12).toFloat()
        }
        card.addView(
            TextView(this).apply {
                text = "PLAYER SWAP"
                textSize = 9.5f
                letterSpacing = 0.14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(t.accent)
            },
        )
        card.addView(
            TextView(this).apply {
                text = "P${ask.fromSlot} wants to swap pads with you."
                textSize = 13.5f
                setTextColor(t.text)
                setPadding(0, px(5), 0, px(2))
            },
        )
        card.addView(
            TextView(this).apply {
                text = "Your game keeps running. Accept to trade seats."
                textSize = 10.5f
                setTextColor(t.textDim)
            },
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, px(10), 0, 0)
        }
        row.addView(
            swapButton("DECLINE", t, primary = false) { answerSwap(false) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = px(8) },
        )
        row.addView(
            swapButton("ACCEPT", t, primary = true) { answerSwap(true) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.addView(row)

        val wrap = FrameLayout(this).apply {
            // No scrim and not clickable: touches outside the card fall through
            // to the pad, so the other player can keep playing while deciding.
            isClickable = false
            isFocusable = false
            addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            alpha = 0f
            translationY = -px(10).toFloat()
        }
        root.addView(
            wrap,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = px(60) },
        )
        swapPrompt = wrap
        wrap.animate().alpha(1f).translationY(0f).setDuration(160).start()
        handler.postDelayed(swapPromptTimeout, 11_000L)
    }

    private fun swapButton(
        label: String,
        theme: PadTheme,
        primary: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        letterSpacing = 0.06f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) onColor(theme.accent) else theme.text)
        setPadding(px(14), px(8), px(14), px(8))
        background = GradientDrawable().apply {
            cornerRadius = px(10).toFloat()
            setColor(if (primary) theme.accent else withAlpha(theme.surfaceAlt, 240))
            setStroke(
                px(1),
                withAlpha(if (primary) theme.accent else theme.text, if (primary) 255 else 50),
            )
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun answerSwap(accept: Boolean) {
        val id = swapPromptId
        dismissSwapPrompt()
        if (id == 0) return
        answeredSwapId = id
        link.respondSwap(id, accept)
        toast(if (accept) "Swap accepted" else "Swap declined")
    }

    private fun handleSlotResult(result: SlotResult) {
        dismissSwapPrompt()
        when (result.code) {
            Proto.SLOT_ACCEPTED -> {
                if (result.slot > 0) {
                    prefs.edit().putInt(PrefsKeys.PLAYER_SLOT, result.slot).apply()
                    toast("Swapped — now P${result.slot}")
                }
            }
            Proto.SLOT_DECLINED ->
                toast("Swap declined — staying on P${link.assignedSlot}")
            Proto.SLOT_TIMEOUT ->
                toast("No answer — staying on P${link.assignedSlot}")
            Proto.SLOT_UNAVAILABLE ->
                toast("That player left — staying on P${link.assignedSlot}")
            else -> Unit
        }
    }

    private fun dismissSwapPrompt() {
        handler.removeCallbacks(swapPromptTimeout)
        val view = swapPrompt ?: return
        swapPrompt = null
        swapPromptId = 0
        view.animate().alpha(0f).translationY(-px(8).toFloat()).setDuration(120)
            .withEndAction { root.removeView(view) }
            .start()
    }

    // ------------------------------------------------------------------- misc
    private fun px(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_QR = 77
        private const val REQ_BT = 78

        // Rumble: short pulses repeated while the PC keeps reporting a motor
        // speed; the hold timeout covers a dropped "stop" packet.
        private const val RUMBLE_PULSE_MS = 130L
        private const val RUMBLE_PERIOD_MS = 160L
        private const val RUMBLE_HOLD_MS = 1600L
        private const val RUMBLE_STALE_MS = 250L
    }
}
