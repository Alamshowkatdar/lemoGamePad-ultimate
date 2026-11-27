package com.alamshowkat.lemogamepad

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
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
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var view: GamepadView
    private lateinit var link: Link
    private lateinit var prefs: SharedPreferences
    private lateinit var layouts: LayoutStore
    private lateinit var profiles: GameProfileStore
    private lateinit var root: FrameLayout
    private var editBar: LinearLayout? = null
    private var settingsPanel: SettingsPanel? = null
    private var wifiGuide: View? = null
    private var setupGuide: View? = null
    private var setupCardHolder: FrameLayout? = null
    private var setupStep = 0
    private var setupChoice = "wifi"
    private var setupStartup = false
    private var connectionDoctor: View? = null
    private var doctorCardHolder: FrameLayout? = null
    private var doctorStartup = false
    private var doctorStep = 0
    @Volatile private var doctorCheckRunning = false
    @Volatile private var updateCheckRunning = false
    private var pendingUpdate: ReleaseChecker.Release? = null
    private var doctorCheckResult = ""
    private var startupOverlaysChecked = false

    /** One-tap sensitivity card shown after an aim calibration run. */
    private var aimRecommendation: View? = null

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
    private var adaptiveEnabled = false
    private lateinit var adaptiveQuality: AdaptiveQualityController
    private var lastAdaptiveNoticeAt = 0L
    private var trainerPreviewWasVisible = false
    private lateinit var phoneGamepad: BluetoothHidGamepad
    private var phoneGamepadActive = false
    private var lastPhoneVersion = -1L
    private var btWasConnected = false
    private var btPermissionAsked = false
    /** Host address the user picked, kept across the Nearby-devices permission prompt. */
    private var pendingConnectAddress: String? = null
    /** True only while the permission prompt opened by SELECT HOST is on screen. */
    private var hostPermissionPending = false
    @Volatile private var btStatus = "Bluetooth gamepad is off — tap SELECT HOST to connect"

    private val handler = Handler(Looper.getMainLooper())
    private var batteryReceiver: BroadcastReceiver? = null
    private var batteryLevel = -1

    @Volatile private var rumbleAmp = 0
    @Volatile private var hapticStrength = 1f
    @Volatile private var gameRumbleEnabled = true
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
                // The host may have connected after the last state change;
                // force one report so it does not start with a stale pad.
                if (!btWasConnected) {
                    btWasConnected = true
                    lastPhoneVersion = -1L
                    // Remembered for picker order only; never used to reconnect.
                    phoneGamepad.connectedAddress?.let { address ->
                        if (prefs.getString(PrefsKeys.BT_LAST_HOST, null) != address) {
                            prefs.edit().putString(PrefsKeys.BT_LAST_HOST, address).apply()
                        }
                    }
                }
                val version = view.state.version
                if (version != lastPhoneVersion) {
                    lastPhoneVersion = version
                    phoneGamepad.send(view.state)
                }
            } else {
                btWasConnected = false
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

    /** Samples stream/link health slowly; each quality change reconnects TCP. */
    private val adaptiveTicker = object : Runnable {
        override fun run() {
            if (resumed && adaptiveEnabled && screenEnabled) {
                val sv = screenView
                if (sv != null) {
                    val next = adaptiveQuality.update(
                        screenQuality,
                        AdaptiveSignal(
                            linkConnected = link.connected,
                            streamConnected = sv.isStreamConnected,
                            streamFps = sv.currentStreamFps,
                            rttMs = link.rttMs,
                            jitterMs = link.jitterMs,
                            lossPermille = link.lossPermille,
                        ),
                    )
                    if (next != null) applyQuality(next, automatic = true)
                }
            }
            handler.postDelayed(this, ADAPTIVE_SAMPLE_MS)
        }
    }

    /** Safety stop if the PC's rumble refresh packets disappear. */
    private val stopRumble = Runnable { stopRumbleInternal() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences(PrefsKeys.FILE, MODE_PRIVATE)
        // Repair invalid or outdated values before any screen reads them.
        SettingsGuard.normalize(prefs)
        // One Gamepad layout owns the live arrangement and all saved presets.
        layouts = LayoutStore(prefs)
        if (layouts.activeName() == null) layouts.clearActive()
        profiles = GameProfileStore(prefs)
        adaptiveQuality = AdaptiveQualityController { SystemClock.uptimeMillis() }
        phoneGamepad = BluetoothHidGamepad(applicationContext) { message ->
            val error = message.startsWith("!")
            btStatus = message.removePrefix("!")
            if (error) handler.post { if (!isFinishing) toast(message.removePrefix("!")) }
            handler.post {
                // A failed start/connect must not leave a phantom gamepad ticking.
                if (error && !phoneGamepad.isConnected && phoneGamepadActive) {
                    phoneGamepadActive = false
                    handler.removeCallbacks(phoneGamepadTicker)
                    lastPhoneVersion = -1L
                    btWasConnected = false
                }
                settingsPanel?.refreshBluetoothGamepad()
            }
        }
        val btStart = BluetoothHidSupport.status(this)
        btStatus = when {
            !btStart.supported -> "No Bluetooth adapter on this phone"
            !btStart.enabled -> "Turn on Bluetooth to use the Bluetooth gamepad"
            !btStart.permitted -> "Nearby devices access is off — grant it, then pick a host."
            else -> "Bluetooth gamepad is off — tap SELECT HOST to connect"
        }

        view = GamepadView(this)
        view.theme = Themes.byId(SettingsGuard.string(prefs, PrefsKeys.THEME, Themes.MIDNIGHT.id))
        view.faceLabels = if (SettingsGuard.string(prefs, PrefsKeys.FACES, "ps") == "xbox") {
            FaceLabels.XBOX
        } else {
            FaceLabels.PLAYSTATION
        }
        view.touchpadEnabled = SettingsGuard.bool(prefs, PrefsKeys.TOUCHPAD_ENABLED, false)
        view.touchpadMouseMode = SettingsGuard.bool(prefs, PrefsKeys.TOUCHPAD_MOUSE, false)
        view.gyroSteering = SettingsGuard.bool(prefs, PrefsKeys.GYRO_STEER, false)
        view.gyroAim = SettingsGuard.bool(prefs, PrefsKeys.GYRO_AIM, false)
        view.gyroRevertX = SettingsGuard.bool(prefs, PrefsKeys.GYRO_REVERT_X, false)
        view.gyroRevertY = SettingsGuard.bool(prefs, PrefsKeys.GYRO_REVERT_Y, false)
        view.steerSensitivity = SettingsGuard.int(prefs, PrefsKeys.STEER_SENS, 55) / 100f
        view.steerDeadzone = SettingsGuard.int(prefs, PrefsKeys.STEER_DEADZONE, 5) / 100f
        view.steerCurve = SettingsGuard.int(prefs, PrefsKeys.STEER_CURVE, 25) / 100f
        view.aimSensitivity = SettingsGuard.int(prefs, PrefsKeys.AIM_SENS, 55) / 100f
        view.touchpadSensitivity = SettingsGuard.int(prefs, PrefsKeys.TOUCHPAD_SENS, 50) / 100f
        view.controlAlpha = SettingsGuard.int(prefs, PrefsKeys.PAD_ALPHA, 100) / 100f
        view.faceSpacing = SettingsGuard.int(prefs, PrefsKeys.FACE_SPACING, 100) / 100f
        view.applyLayout(layouts.activeData())
        view.snapEnabled = SettingsGuard.bool(prefs, PrefsKeys.LAYOUT_SNAP, false)
        view.guidesEnabled = SettingsGuard.bool(prefs, PrefsKeys.LAYOUT_GUIDES, true)
        view.safeAreaEnabled = SettingsGuard.bool(prefs, PrefsKeys.LAYOUT_SAFE_AREA, false)
        view.multiSelectEnabled = SettingsGuard.bool(prefs, PrefsKeys.LAYOUT_MULTI, false)
        view.deadzone = SettingsGuard.int(prefs, PrefsKeys.DEADZONE, 12) / 100f
        view.stickCurve = SettingsGuard.int(prefs, PrefsKeys.STICK_CURVE, 0) / 100f
        view.hapticsEnabled = SettingsGuard.bool(prefs, PrefsKeys.HAPTICS, true)
        view.motionEnabled = SettingsGuard.bool(prefs, PrefsKeys.MOTION, true)
        view.onOpenSettings = { showSettings() }
        view.onHaptic = { ms -> tapFeedback(ms) }
        view.onLayoutChanged = { persistLayout() }
        view.onToast = { msg -> toast(msg) }
        view.onAimTrainerChanged = { active -> onAimTrainerChanged(active) }
        view.onAimTrainerFinished = { summary -> toast(summary) }
        view.onAimTrainerResult = { result ->
            recommendAim(result)?.let { showAimRecommendation(it) }
        }

        root = FrameLayout(this)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
        installSafeAreaInsets()

        link = Link(view.state, applicationContext)
        link.port = SettingsGuard.int(prefs, PrefsKeys.PORT, Proto.PORT)
        link.transport = transportById(SettingsGuard.string(prefs, PrefsKeys.TRANSPORT, ""))
        link.inputRate = InputRate.fromId(SettingsGuard.string(prefs, PrefsKeys.INPUT_RATE, InputRate.STANDARD.id))
        link.wantSlot = SettingsGuard.int(prefs, PrefsKeys.PLAYER_SLOT, 0)
        link.onRumble = { small, large -> handleRumble(small, large) }
        link.onSlotAsk = { ask -> handler.post { showSwapPrompt(ask) } }
        link.onSlotResult = { result -> handler.post { handleSlotResult(result) } }
        link.onPartyState = { locked, _ ->
            handler.post {
                refreshStatus()
                settingsPanel?.refreshLink()
                // A lock cancels pending swaps; the target must not keep an
                // Accept card for a request the PC has already discarded.
                if (locked) dismissSwapPrompt()
            }
        }

        screenEnabled = SettingsGuard.bool(prefs, PrefsKeys.SCREEN, false)
        screenSmall = SettingsGuard.bool(prefs, PrefsKeys.SCREEN_SMALL, true)
        hapticStrength = SettingsGuard.int(prefs, PrefsKeys.HAPTIC_STRENGTH, 100) / 100f
        gameRumbleEnabled = SettingsGuard.bool(prefs, PrefsKeys.GAME_RUMBLE, true)
        screenQuality = ScreenQuality.fromId(
            SettingsGuard.int(prefs, PrefsKeys.SCREEN_QUALITY, ScreenQuality.BALANCED.wireId),
        )
        adaptiveEnabled = SettingsGuard.bool(prefs, PrefsKeys.ADAPTIVE_QUALITY, false)

        when {
            !SettingsGuard.bool(prefs, PrefsKeys.ONBOARDED, false) -> {
                setupStartup = true
                showSetupGuide()
            }
            !SettingsGuard.bool(prefs, PrefsKeys.WIFI_GUIDE_SEEN, false) -> showWifiGuide()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (!startupOverlaysChecked && wifiGuide == null && setupGuide == null && connectionDoctor == null) {
            startupOverlaysChecked = true
            continueStartupOverlays()
        }
        enterImmersive()
        link.host = SettingsGuard.string(prefs, PrefsKeys.HOST, "").takeIf { it.isNotBlank() }
        link.start()
        registerBattery()
        handler.post(ticker)
        handler.removeCallbacks(adaptiveTicker)
        handler.post(adaptiveTicker)
        view.startMotion()
        if (screenEnabled) showScreenPreview(true)
        pendingUpdate?.let { release ->
            if (wifiGuide == null && setupGuide == null && connectionDoctor == null &&
                settingsPanel?.isShowing != true
            ) {
                pendingUpdate = null
                showUpdate(release)
            }
        }
        // Bluetooth stays off until the user picks a host in the settings card.
        // If a gamepad was already started explicitly, resume its report ticker.
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
        // requestingPermissions() pauses this Activity while its dialog is up.
        // Keep the pick only for that prompt; any stale pick is forgotten.
        if (!hostPermissionPending) pendingConnectAddress = null
        LayoutDialogs.dismissAll(this)
        dismissSwapPrompt()
        dismissAimRecommendation()
        dismissSetupGuide(markSeen = false, advanceStartup = false)
        dismissConnectionDoctor(markSeen = false, advanceStartup = false)
        settingsPanel?.dismiss(animate = false)
        if (view.editMode) exitEditing(save = true)
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(adaptiveTicker)
        rumbleAmp = 0
        handler.removeCallbacks(stopRumble)
        vibrator()?.cancel()
        view.resetInputs()
        view.stopMotion()
        link.stop()
        // Release every button on the selected host before parking the ticker.
        // The profile stays registered only because the user started it; a new
        // app launch starts with it off and does not connect by itself.
        handler.removeCallbacks(phoneGamepadTicker)
        lastPhoneVersion = -1L
        btWasConnected = false
        if (phoneGamepadActive) phoneGamepad.sendNeutral()
        // Keep the little window on screen, but drop the TCP link while the
        // app is in the background so it does not burn battery.
        screenView?.stopStreaming()
        unregisterBattery()
    }

    override fun onDestroy() {
        LayoutDialogs.dismissAll(this)
        phoneGamepadActive = false
        hostPermissionPending = false
        pendingConnectAddress = null
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
        if (dismissSetupGuide(markSeen = true, advanceStartup = setupStartup)) return
        wifiGuide?.let {
            root.removeView(it)
            wifiGuide = null
            startupOverlaysChecked = true
            prefs.edit().putBoolean(PrefsKeys.WIFI_GUIDE_SEEN, true).apply()
            continueStartupOverlays()
            return
        }
        if (dismissConnectionDoctor(markSeen = true, advanceStartup = doctorStartup)) return
        if (dismissAimRecommendation()) return
        if (view.aimTrainerActive) {
            view.aimTrainerActive = false
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
        if (requestCode == REQ_LAYOUT_QR) {
            if (resultCode != RESULT_OK || data == null) return
            val payload = data.getStringExtra(ScanActivity.EXTRA_LAYOUT_PAYLOAD) ?: return
            val shared = LayoutQr.decode(payload) ?: return toast("That is not a layout QR")
            val replaced = layouts.save(shared.name, shared.data)
            view.applyLayout(shared.data)
            toast(
                if (replaced) "Layout \"${shared.name}\" updated from QR"
                else "Layout \"${shared.name}\" imported from QR",
            )
            return
        }
        if (requestCode != REQ_QR || resultCode != RESULT_OK || data == null) return
        val host = data.getStringExtra(ScanActivity.EXTRA_HOST) ?: return
        val port = data.getIntExtra(ScanActivity.EXTRA_PORT, Proto.PORT).coerceIn(1, 65535)
        val name = data.getStringExtra(ScanActivity.EXTRA_NAME) ?: ""
        val auto = data.getBooleanExtra(ScanActivity.EXTRA_AUTO, false)

        val editor = prefs.edit().putString(PrefsKeys.HOST, host).putInt(PrefsKeys.PORT, port)
        if (auto) editor.putInt(PrefsKeys.PLAYER_SLOT, 0)
        editor.apply()
        link.transport = transportById(prefs.getString(PrefsKeys.TRANSPORT, null))
        if (auto) {
            // Guest codes put this phone in AUTO; the PC fills the first free
            // pad on the next input packet and reports it back through PONG.
            link.cancelSwap()
            link.wantSlot = 0
        }
        link.retarget(host, port)
        link.serverName = name
        attachScreenStream()
        toast(
            when {
                auto && name.isBlank() -> "Joining next free pad on $host"
                auto -> "Joining next free pad on $name"
                name.isBlank() -> "Paired with $host"
                else -> "Paired with $name"
            }
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BT && requestCode != REQ_BT_HOST) return
        val granted = !BluetoothHidSupport.permissionsMissing(this)
        btPermissionAsked = false
        if (requestCode == REQ_BT_HOST) {
            // Only a permission prompt opened by SELECT HOST may finish a pick.
            // Other buttons (discoverable, etc.) must never reuse the address.
            hostPermissionPending = false
            val address = pendingConnectAddress
            pendingConnectAddress = null
            if (granted && address != null) {
                connectBluetoothHost(address)
            } else if (!granted) {
                btStatus = "Nearby devices access denied — allow it, then pick a host again"
            }
        }
        settingsPanel?.onBluetoothPermissionResult(granted)
    }

    // ------------------------------------------------------------------ status
    private fun refreshStatus() {
        link.refreshConnectionState()
        val host = link.host
        when {
            host == null && phoneGamepadActive && phoneGamepad.isConnected -> {
                val name = phoneGamepad.connectedName?.uppercase() ?: "HOST"
                view.setStatus("BLUETOOTH GAMEPAD  ·  $name", GamepadView.STATUS_OK)
            }
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
                val party = when {
                    link.isHost && link.partyLocked -> "  ·  HOST · LOCK"
                    link.isHost -> "  ·  HOST"
                    link.partyLocked -> "  ·  LOCK"
                    else -> ""
                }
                view.setStatus("$name$via$pad$party$rtt$jitter$loss$batt", GamepadView.STATUS_OK)
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

        fun dismissGuide(advance: Boolean) {
            root.removeView(scrim)
            wifiGuide = null
            startupOverlaysChecked = true
            prefs.edit().putBoolean(PrefsKeys.WIFI_GUIDE_SEEN, true).apply()
            if (advance) continueStartupOverlays()
        }
        addButton("GOT IT", primary = false) { dismissGuide(advance = true) }
        addButton("OPEN WI-FI SETUP", primary = true) {
            link.transport = Transport.WIFI
            prefs.edit().putString(PrefsKeys.TRANSPORT, "wifi").apply()
            dismissGuide(advance = false)
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

    // --------------------------------------------------- onboarding / what's new
    private fun continueStartupOverlays() {
        if (isFinishing || !resumed) return
        if (!SettingsGuard.bool(prefs, PrefsKeys.ONBOARDED, false)) {
            setupStartup = true
            showSetupGuide()
            return
        }
        if (!SettingsGuard.bool(prefs, PrefsKeys.CONNECTION_DOCTOR_SEEN, false)) {
            doctorStartup = true
            showConnectionDoctor()
        } else if (!maybeShowWhatsNew()) {
            checkForUpdate()
        }
    }

    // ------------------------------------------------------- first-time setup
    private fun showSetupGuide() {
        if (setupGuide != null || isFinishing) return
        setupStep = 0
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(224, 0, 0, 0))
            isClickable = true
            isFocusable = true
            elevation = px(29).toFloat()
        }
        val holder = FrameLayout(this)
        scrim.addView(
            holder,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply { marginStart = px(20); marginEnd = px(20) },
        )
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setupGuide = scrim
        setupCardHolder = holder
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(200L).start()
        renderSetupGuide()
    }

    private fun renderSetupGuide() {
        val holder = setupCardHolder ?: return
        holder.removeAllViews()
        val t = view.theme
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(px(20), px(18), px(20), px(17))
            background = GradientDrawable().apply {
                cornerRadius = px(20).toFloat()
                setColor(t.surface)
                setStroke(px(1), withAlpha(t.accent, 145))
            }
            elevation = px(30).toFloat()
        }

        fun addCopy(text: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) {
            card.addView(TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                setLineSpacing(px(3).toFloat(), 1f)
                if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                if (top > 0) setPadding(0, px(top), 0, 0)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        fun choose(value: String) {
            setupChoice = value
            setupStep = 1
            renderSetupGuide()
        }

        if (setupStep == 0) {
            addCopy("FIRST-TIME SETUP  ·  1 / 2", 10f, t.accent, bold = true)
            addCopy("How do you want to play?", 20f, t.text, bold = true, top = 6)
            addCopy(
                "Pick one path. Only the steps you need are shown, and nothing connects until you choose a host or scan a code.",
                12f, t.textDim, top = 9,
            )
            val first = rowOfButtons()
            first.addView(swapButton("WI-FI", t, primary = true) { choose("wifi") }, weightParams(1f, end = px(6)))
            first.addView(swapButton("USB", t, primary = false) { choose("usb") }, weightParams(1f))
            val second = rowOfButtons()
            second.addView(swapButton("BLUETOOTH", t, primary = false) { choose("bluetooth") }, weightParams(1f, end = px(6)))
            second.addView(swapButton("WEB PAD", t, primary = false) { choose("web") }, weightParams(1f))
            card.addView(first, topParams(px(14)))
            card.addView(second, topParams(px(7)))
        } else {
            addCopy("FIRST-TIME SETUP  ·  2 / 2", 10f, t.accent, bold = true)
            when (setupChoice) {
                "usb" -> {
                    addCopy("USB tether", 20f, t.text, bold = true, top = 6)
                    addCopy(
                        "1. Connect the phone to the PC with a USB cable.\n" +
                            "2. Enable USB tethering / mobile data passthrough on the phone.\n" +
                            "3. Start the PC server, then use transport USB and enter the IP it prints.",
                        12f, t.textDim, top = 10,
                    )
                }
                "bluetooth" -> {
                    addCopy("Bluetooth HID gamepad", 20f, t.text, bold = true, top = 6)
                    addCopy(
                        "1. Pair 'Lemo GamePad' from the PC or TV's Bluetooth settings.\n" +
                            "2. In this app open Settings > Bluetooth gamepad and start it.\n" +
                            "3. Tap SELECT HOST and pick that device. It never auto-connects.",
                        12f, t.textDim, top = 10,
                    )
                }
                "web" -> {
                    addCopy("Browser pad (also works on iPhone)", 19f, t.text, bold = true, top = 6)
                    addCopy(
                        "1. Start the PC server and note the LAN address it prints.\n" +
                            "2. Open http://PC-IP:9878 in the phone's browser on the same Wi-Fi.\n" +
                            "3. On iPhone, use Safari > Share > Add to Home Screen.\n" +
                            "The page must come from the PC; a public site cannot reach the local link.",
                        12f, t.textDim, top = 10,
                    )
                }
                else -> {
                    addCopy("Wi-Fi gamepad", 20f, t.text, bold = true, top = 6)
                    addCopy(
                        "1. Put the phone and PC on the same Wi-Fi network.\n" +
                            "2. Start lemoGamePad-server on the PC and leave it running.\n" +
                            "3. Scan its QR code here, or enter the IP it prints.\n" +
                            "Guest Wi-Fi that blocks devices and the PC firewall are the usual causes of trouble.",
                        12f, t.textDim, top = 10,
                    )
                }
            }
            val paths = rowOfButtons()
            when (setupChoice) {
                "wifi" -> {
                    paths.addView(swapButton("SCAN QR", t, primary = true) {
                        dismissSetupGuide(markSeen = true, advanceStartup = false)
                        startActivityForResult(Intent(this@MainActivity, ScanActivity::class.java), REQ_QR)
                    }, weightParams(1f, end = px(6)))
                    paths.addView(swapButton("USE WI-FI", t, primary = false) {
                        link.transport = Transport.WIFI
                        prefs.edit().putString(PrefsKeys.TRANSPORT, "wifi").apply()
                        dismissSetupGuide(markSeen = true, advanceStartup = false)
                        toast("Transport: Wi-Fi")
                    }, weightParams(1f))
                }
                "usb" -> {
                    paths.addView(swapButton("USE USB", t, primary = true) {
                        link.transport = Transport.USB
                        prefs.edit().putString(PrefsKeys.TRANSPORT, "usb").apply()
                        dismissSetupGuide(markSeen = true, advanceStartup = false)
                        toast("Transport: USB")
                    }, weightParams(1f, end = px(6)))
                    paths.addView(swapButton("SETTINGS", t, primary = false) {
                        dismissSetupGuide(markSeen = true, advanceStartup = false)
                        showSettings()
                    }, weightParams(1f))
                }
                "bluetooth" -> {
                    paths.addView(swapButton("OPEN SETTINGS", t, primary = true) {
                        dismissSetupGuide(markSeen = true, advanceStartup = false)
                        showSettings()
                    }, weightParams(1f))
                }
                else -> {
                    paths.addView(swapButton("ONLINE GUIDE", t, primary = true) { openUrl(SETUP_URL) }, weightParams(1f))
                }
            }
            card.addView(paths, topParams(px(13)))
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, px(14), 0, 0)
        }
        if (setupStep > 0) {
            actions.addView(
                swapButton("BACK", t, primary = false) {
                    setupStep--
                    renderSetupGuide()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = px(8) },
            )
        } else {
            actions.addView(
                swapButton("SKIP", t, primary = false) {
                    dismissSetupGuide(markSeen = true, advanceStartup = setupStartup)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = px(8) },
            )
        }
        actions.addView(
            swapButton(if (setupStep == 0) "NEXT" else "FINISH", t, primary = true) {
                if (setupStep == 0) {
                    setupStep = 1
                    renderSetupGuide()
                } else {
                    dismissSetupGuide(markSeen = true, advanceStartup = setupStartup)
                }
            },
        )
        card.addView(actions)
        holder.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(150L).start()
    }

    private fun rowOfButtons(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun weightParams(weight: Float, end: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            marginEnd = end
        }

    private fun topParams(top: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }

    private fun dismissSetupGuide(markSeen: Boolean, advanceStartup: Boolean): Boolean {
        val overlay = setupGuide ?: return false
        setupGuide = null
        setupCardHolder = null
        if (markSeen) {
            prefs.edit()
                .putBoolean(PrefsKeys.ONBOARDED, true)
                .putBoolean(PrefsKeys.WIFI_GUIDE_SEEN, true)
                .putBoolean(PrefsKeys.CONNECTION_DOCTOR_SEEN, true)
                .apply()
        }
        overlay.animate().alpha(0f).setDuration(140L)
            .withEndAction { root.removeView(overlay) }
            .start()
        setupStartup = false
        if (advanceStartup) continueStartupOverlays()
        return true
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): Pair<Long, String> = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        code to (info.versionName ?: Brand.VERSION)
    } catch (_: Exception) {
        0L to Brand.VERSION
    }

    private fun maybeShowWhatsNew(): Boolean {
        val (current, name) = appVersion()
        val previous = prefs.getLong(PrefsKeys.LAST_WHATSNEW_CODE, 0L)
        if (current == 0L || previous == current) return false
        // A fresh install has nothing to compare against; only updates get the card.
        if (previous == 0L) {
            prefs.edit().putLong(PrefsKeys.LAST_WHATSNEW_CODE, current).apply()
            return false
        }
        prefs.edit().putLong(PrefsKeys.LAST_WHATSNEW_CODE, current).apply()
        LayoutDialogs.info(
            this,
            view.theme,
            "WHAT'S NEW IN $name",
            "• Aim calibration now uses a free reticle: drag to place it, tap to fire.\n" +
                "• Calibration can suggest concrete sensitivity and gyro-revert values.\n" +
                "• Manual game profiles, adaptive quality and one merged live connection card.\n" +
                "• Connection Doctor links to the website for downloads and guides; battery saver pauses live stats.\n" +
                "• Layout editor adds undo/redo, snap/grid, alignment guides, safe-area guides, multi-select, copy/paste, lock and mirror.",
        )
        return true
    }

    /** Checks GitHub at most twice a day; failures are silent and offline-safe. */
    private fun checkForUpdate() {
        if (updateCheckRunning || isFinishing) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong(PrefsKeys.UPDATE_LAST_CHECK, 0L)
        if (now - last < UPDATE_CHECK_INTERVAL_MS) return
        updateCheckRunning = true
        prefs.edit().putLong(PrefsKeys.UPDATE_LAST_CHECK, now).apply()

        thread(name = "update-check") {
            val release = ReleaseChecker.latest(Brand.VERSION)
            updateCheckRunning = false
            if (release == null || release.tag == prefs.getString(PrefsKeys.UPDATE_SEEN_TAG, null)) {
                return@thread
            }
            handler.post {
                if (isFinishing) return@post
                if (!resumed) {
                    pendingUpdate = release
                } else {
                    showUpdate(release)
                }
            }
        }
    }

    private fun showUpdate(release: ReleaseChecker.Release) {
        // Remember the tag even when the user chooses Later, so one release is
        // only announced once per install.
        prefs.edit().putString(PrefsKeys.UPDATE_SEEN_TAG, release.tag).apply()
        LayoutDialogs.confirm(
            activity = this,
            theme = view.theme,
            title = "UPDATE AVAILABLE",
            message = "lemoGamePad ultimate ${release.version} is available on GitHub. " +
                "Open the release page to download the APK and server binaries. " +
                "Nothing is installed automatically.",
            positive = "DOWNLOAD",
            onConfirm = { openUrl(release.url) },
            negative = "LATER",
        )
    }

    /**
     * Best-effort live probe. TCP 9877 and 9878 are opened by the PC server
     * whenever it is running, so a successful connect proves the phone can
     * reach the host on the same route the input link uses. A closed port is
     * reported as information, not as a failure of UDP input.
     */
    private fun runDoctorChecks() {
        if (doctorCheckRunning) return
        val host = link.host ?: SettingsGuard.string(prefs, PrefsKeys.HOST, "").takeIf { it.isNotBlank() }
        val readiness = link.readiness()
        val local = link.boundLocalAddress()
        val localSummary = link.localSummary().ifBlank { "none detected" }
        val videoPort = Proto.videoPort(link.port)
        doctorCheckRunning = true
        doctorCheckResult = "Running checks…"
        renderConnectionDoctor()

        thread(name = "doctor-check") {
            val lines = ArrayList<String>()
            lines += "Wi-Fi path   " + if (readiness[Transport.WIFI] == true) "READY" else "not ready"
            lines += "USB path     " + if (readiness[Transport.USB] == true) "READY" else "not ready"
            lines += "This device  $localSummary"
            if (host == null) {
                lines += "PC probe     pair a host first"
            } else {
                fun tcp(port: Int): String = try {
                    val socket = Socket()
                    try {
                        if (local != null) socket.bind(InetSocketAddress(local, 0))
                        socket.connect(InetSocketAddress(host, port), 500)
                        "reachable"
                    } finally {
                        socket.close()
                    }
                } catch (_: Exception) {
                    "not reachable"
                }
                lines += "Paired host  $host"
                lines += "Video TCP $videoPort  ${tcp(videoPort)}"
                lines += "Web TCP 9878  ${tcp(9878)}"
            }
            val result = lines.joinToString("\n")
            runOnUiThread {
                doctorCheckRunning = false
                doctorCheckResult = result
                if (connectionDoctor != null) renderConnectionDoctor()
            }
        }
    }

    private fun showConnectionDoctor() {
        if (connectionDoctor != null || isFinishing) return
        doctorStep = 0
        doctorCheckResult = ""
        val t = view.theme
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(222, 0, 0, 0))
            isClickable = true
            isFocusable = true
            elevation = px(28).toFloat()
        }
        val holder = FrameLayout(this)
        scrim.addView(
            holder,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply { marginStart = px(20); marginEnd = px(20) },
        )
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        connectionDoctor = scrim
        doctorCardHolder = holder
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(200L).start()
        renderConnectionDoctor()
    }

    private fun renderConnectionDoctor() {
        val holder = doctorCardHolder ?: return
        holder.removeAllViews()
        val t = view.theme
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(px(20), px(17), px(20), px(16))
            background = GradientDrawable().apply {
                cornerRadius = px(19).toFloat()
                setColor(t.surface)
                setStroke(px(1), withAlpha(t.accent, 140))
            }
            elevation = px(30).toFloat()
        }

        fun addCopy(text: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) {
            card.addView(TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                setLineSpacing(px(3).toFloat(), 1f)
                if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                if (top > 0) setPadding(0, px(top), 0, 0)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        when (doctorStep) {
            0 -> {
                addCopy("CONNECTION DOCTOR  ·  1 / 3", 10f, t.accent, bold = true)
                addCopy("Open the website, then connect", 19f, t.text, bold = true, top = 6)
                addCopy(
                    "1. Open the website for the latest downloads and the full setup guide.\n" +
                        "2. Start lemoGamePad-server on the PC and leave the console open.\n" +
                        "3. Scan its QR code here, or enter the PC IP it prints.",
                    12f, t.textDim, top = 10,
                )
                addCopy(
                    "Bluetooth is separate: it never auto-connects and always needs a host you pick.",
                    10.5f, t.muted, top = 8,
                )
                val links = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setPadding(0, px(12), 0, 0)
                }
                links.addView(
                    swapButton("OPEN WEBSITE", t, primary = false) {
                        openUrl(SETUP_URL)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                links.addView(
                    swapButton("HELP", t, primary = false) {
                        HelpCenter.show(this@MainActivity, view.theme)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = px(8) },
                )
                card.addView(links)
            }
            1 -> {
                val readiness = link.readiness()
                addCopy("CONNECTION DOCTOR  ·  2 / 3", 10f, t.accent, bold = true)
                addCopy("Transport check", 19f, t.text, bold = true, top = 6)
                addCopy(
                    buildString {
                        append("Wi-Fi path   ").append(
                            if (readiness[Transport.WIFI] == true) "READY" else "not ready",
                        )
                        append("\nUSB path     ").append(
                            if (readiness[Transport.USB] == true) "READY" else "not ready",
                        )
                        append("\nSelected     ").append(
                            link.transportName().ifBlank { link.transport.label },
                        )
                        append("\nInterface    ").append(
                            link.localSummary().ifBlank { "none detected" },
                        )
                    },
                    12f, t.textDim, top = 10,
                )
                addCopy(
                    "If both paths say not ready, start the PC server and check guest Wi-Fi isolation or the USB cable.",
                    10.5f, t.muted, top = 8,
                )
                if (doctorCheckResult.isNotBlank()) {
                    addCopy("LIVE CHECK\n$doctorCheckResult", 11f, t.textDim, top = 10)
                }
                val checks = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setPadding(0, px(12), 0, 0)
                }
                checks.addView(
                    swapButton(if (doctorCheckRunning) "CHECKING…" else "RUN CHECK", t, primary = true) {
                        runDoctorChecks()
                    },
                )
                card.addView(checks)
            }
            else -> {
                addCopy("CONNECTION DOCTOR  ·  3 / 3", 10f, t.accent, bold = true)
                addCopy("Link check", 19f, t.text, bold = true, top = 6)
                addCopy(
                    buildString {
                        append("Paired host   ").append(link.host ?: "none")
                        append("\nStatus        ").append(
                            if (link.connected) "connected" else if (link.host != null) "linking" else "idle",
                        )
                        append("\nRound trip    ").append(
                            if (link.connected && link.rttMs >= 0) "${link.rttMs} ms" else "—",
                        )
                        append("\nPacket loss   ").append(
                            if (link.lossPermille < 0) "—" else "${(link.lossPermille + 5) / 10}%",
                        )
                        append("\nVideo stream  ").append(
                            if (screenView?.isStreamConnected == true) {
                                "${screenView?.currentStreamFps ?: 0} fps"
                            } else {
                                "off"
                            },
                        )
                    },
                    12f, t.textDim, top = 10,
                )
                addCopy(
                    "Still not connected? Open Settings and use SCAN or the QR code. Keep the server window running on the PC.",
                    10.5f, t.muted, top = 8,
                )
                if (doctorCheckResult.isNotBlank()) {
                    addCopy("LIVE CHECK\n$doctorCheckResult", 11f, t.textDim, top = 10)
                }
            }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, px(14), 0, 0)
        }
        if (doctorStep > 0) {
            actions.addView(
                swapButton("BACK", t, primary = false) {
                    doctorStep--
                    renderConnectionDoctor()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = px(8) },
            )
        } else {
            actions.addView(
                swapButton("SKIP", t, primary = false) {
                    dismissConnectionDoctor(markSeen = true, advanceStartup = doctorStartup)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = px(8) },
            )
        }
        if (doctorStep < 2) {
            actions.addView(
                swapButton("NEXT", t, primary = true) {
                    doctorStep++
                    renderConnectionDoctor()
                },
            )
        } else {
            actions.addView(
                swapButton("SETTINGS", t, primary = false) {
                    dismissConnectionDoctor(markSeen = true, advanceStartup = false)
                    showSettings()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = px(8) },
            )
            actions.addView(
                swapButton("DONE", t, primary = true) {
                    dismissConnectionDoctor(markSeen = true, advanceStartup = doctorStartup)
                },
            )
        }
        card.addView(actions)
        holder.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(140L).start()
    }

    private fun dismissConnectionDoctor(markSeen: Boolean, advanceStartup: Boolean): Boolean {
        val overlay = connectionDoctor ?: return false
        connectionDoctor = null
        doctorCardHolder = null
        if (markSeen) {
            prefs.edit().putBoolean(PrefsKeys.CONNECTION_DOCTOR_SEEN, true).apply()
        }
        overlay.animate().alpha(0f).setDuration(140L)
            .withEndAction { root.removeView(overlay) }
            .start()
        if (advanceStartup) continueStartupOverlays()
        return true
    }

    // ------------------------------------------------------------------ screen
    private fun showScreenPreview(show: Boolean) {
        screenEnabled = show
        // A new stream has no meaningful FPS history; restart sampling.
        adaptiveQuality.reset()
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

    /** Swaps the right stick for the swipe pad and reloads the saved layout. */
    private fun setTouchpadEnabled(enabled: Boolean) {
        if (view.touchpadEnabled == enabled) return
        if (view.editMode) exitEditing(save = true)
        persistLayout()
        prefs.edit().putBoolean(PrefsKeys.TOUCHPAD_ENABLED, enabled).apply()
        view.touchpadEnabled = enabled
        view.applyLayout(layouts.activeData())
        refreshStatus()
    }

    /** Keeps the screen PiP out of the trainer while still owning its state. */
    private fun onAimTrainerChanged(active: Boolean) {
        if (active) {
            dismissAimRecommendation()
            trainerPreviewWasVisible = screenView?.visibility == View.VISIBLE
            screenView?.visibility = View.GONE
        } else if (trainerPreviewWasVisible) {
            screenView?.visibility = if (view.editMode) View.GONE else View.VISIBLE
            trainerPreviewWasVisible = false
        }
        refreshStatus()
    }

    // ---------------------------------------------------------------- profiles
    private fun captureGameProfile(): GameProfileData = GameProfileData(
        touchpadEnabled = view.touchpadEnabled,
        touchpadMouse = view.touchpadMouseMode,
        touchpadSensitivity = (view.touchpadSensitivity * 100f).roundToInt(),
        gyroSteer = view.gyroSteering,
        gyroAim = view.gyroAim,
        gyroRevertX = view.gyroRevertX,
        gyroRevertY = view.gyroRevertY,
        steerSensitivity = (view.steerSensitivity * 100f).roundToInt(),
        steerDeadzone = (view.steerDeadzone * 100f).roundToInt(),
        steerCurve = (view.steerCurve * 100f).roundToInt(),
        aimSensitivity = (view.aimSensitivity * 100f).roundToInt(),
        deadzone = (view.deadzone * 100f).roundToInt(),
        stickCurve = (view.stickCurve * 100f).roundToInt(),
        haptics = view.hapticsEnabled,
        gameRumble = gameRumbleEnabled,
        hapticStrength = (hapticStrength * 100f).roundToInt(),
        inputRate = link.inputRate.id,
        faceLabels = view.faceLabels.name,
        padAlpha = (view.controlAlpha * 100f).roundToInt(),
        faceSpacing = (view.faceSpacing * 100f).roundToInt(),
        adaptiveQuality = adaptiveEnabled,
        layout = view.layoutSnapshot(),
    )

    private fun saveGameProfile(name: String) {
        profiles.save(name, GameProfileCodec.encode(captureGameProfile()))
    }

    private fun applyGameProfile(name: String) {
        val entry = profiles.find(name)
        val data = entry?.let { GameProfileCodec.decode(it.data) }
        if (entry == null || data == null) {
            toast("That profile could not be read")
            return
        }
        if (view.editMode) exitEditing(save = true)

        val labels = if (data.faceLabels == FaceLabels.XBOX.name) FaceLabels.XBOX else FaceLabels.PLAYSTATION
        val rate = InputRate.fromId(data.inputRate)
        view.faceLabels = labels
        view.touchpadEnabled = data.touchpadEnabled
        view.touchpadMouseMode = data.touchpadMouse
        view.gyroSteering = data.gyroSteer
        view.gyroAim = data.gyroAim
        view.gyroRevertX = data.gyroRevertX
        view.gyroRevertY = data.gyroRevertY
        view.steerSensitivity = data.steerSensitivity / 100f
        view.steerDeadzone = data.steerDeadzone / 100f
        view.steerCurve = data.steerCurve / 100f
        view.aimSensitivity = data.aimSensitivity / 100f
        view.touchpadSensitivity = data.touchpadSensitivity / 100f
        view.deadzone = data.deadzone / 100f
        view.stickCurve = data.stickCurve / 100f
        view.hapticsEnabled = data.haptics
        gameRumbleEnabled = data.gameRumble
        if (!gameRumbleEnabled) handler.post { stopRumbleInternal() }
        view.controlAlpha = data.padAlpha / 100f
        view.faceSpacing = data.faceSpacing / 100f
        link.inputRate = rate
        hapticStrength = data.hapticStrength.coerceIn(0, 100) / 100f
        adaptiveEnabled = data.adaptiveQuality

        val editor = prefs.edit()
            .putString(PrefsKeys.FACES, labels.name)
            .putBoolean(PrefsKeys.TOUCHPAD_ENABLED, data.touchpadEnabled)
            .putBoolean(PrefsKeys.TOUCHPAD_MOUSE, data.touchpadMouse)
            .putBoolean(PrefsKeys.GYRO_STEER, data.gyroSteer)
            .putBoolean(PrefsKeys.GYRO_AIM, data.gyroAim)
            .putBoolean(PrefsKeys.GYRO_REVERT_X, data.gyroRevertX)
            .putBoolean(PrefsKeys.GYRO_REVERT_Y, data.gyroRevertY)
            .putInt(PrefsKeys.STEER_SENS, data.steerSensitivity)
            .putInt(PrefsKeys.STEER_DEADZONE, data.steerDeadzone)
            .putInt(PrefsKeys.STEER_CURVE, data.steerCurve)
            .putInt(PrefsKeys.AIM_SENS, data.aimSensitivity)
            .putInt(PrefsKeys.TOUCHPAD_SENS, data.touchpadSensitivity)
            .putInt(PrefsKeys.DEADZONE, data.deadzone)
            .putInt(PrefsKeys.STICK_CURVE, data.stickCurve)
            .putBoolean(PrefsKeys.HAPTICS, data.haptics)
            .putBoolean(PrefsKeys.GAME_RUMBLE, data.gameRumble)
            .putInt(PrefsKeys.HAPTIC_STRENGTH, data.hapticStrength.coerceIn(0, 100))
            .putInt(PrefsKeys.PAD_ALPHA, data.padAlpha)
            .putInt(PrefsKeys.FACE_SPACING, data.faceSpacing)
            .putString(PrefsKeys.INPUT_RATE, rate.id)
            .putBoolean(PrefsKeys.ADAPTIVE_QUALITY, adaptiveEnabled)

        if (data.layout != null) {
            view.applyLayout(data.layout)
            editor.putString(PrefsKeys.LAYOUT, data.layout)
        } else {
            view.resetLayout()
            editor.putString(PrefsKeys.LAYOUT, view.layoutSnapshot())
        }
        editor.apply()
        // A profile's layout is its own snapshot, not a named layout preset.
        layouts.clearActive()
        profiles.activate(entry.name)
        adaptiveQuality.reset()
        settingsPanel?.refreshTouchpadMode()
        refreshStatus()
    }

    private fun deleteGameProfile(name: String) {
        profiles.remove(name)
    }

    // ----------------------------------------------------------- video quality
    private fun applyQuality(quality: ScreenQuality, automatic: Boolean) {
        val previous = screenQuality
        screenQuality = quality
        prefs.edit().putInt(PrefsKeys.SCREEN_QUALITY, quality.wireId).apply()
        screenView?.setQuality(quality)
        settingsPanel?.syncScreenQuality(quality)
        if (automatic) {
            if (previous != quality) {
                val now = SystemClock.uptimeMillis()
                if (now - lastAdaptiveNoticeAt >= ADAPTIVE_NOTICE_MS) {
                    lastAdaptiveNoticeAt = now
                    val message = if (quality.wireId < previous.wireId) {
                        "Stream sharpness lowered to ${quality.label} to keep 60 fps"
                    } else {
                        "Stream sharpness raised to ${quality.label} — the stream is smooth"
                    }
                    toast(message)
                }
            }
        } else {
            adaptiveQuality.noteManual()
        }
    }

    private fun showEditBar() {
        hideEditBar()
        val t = view.theme

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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

        fun editRow(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            bar.addView(this)
        }

        fun addButton(row: LinearLayout, text: String, primary: Boolean, action: () -> Unit): TextView {
            val b = TextView(this).apply {
                this.text = text
                textSize = 10f
                letterSpacing = 0.06f
                gravity = Gravity.CENTER
                setTextColor(if (primary) t.bgBottom else t.text)
                setPadding(px(8), px(7), px(8), px(7))
                background = GradientDrawable().apply {
                    cornerRadius = px(13).toFloat()
                    setColor(if (primary) t.accent else withAlpha(t.surfaceAlt, 210))
                    if (!primary) setStroke(px(1), withAlpha(t.text, 45))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            row.addView(
                b,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = px(2)
                    marginEnd = px(2)
                },
            )
            return b
        }

        val actions = editRow()
        val tools = editRow()
        val clipboardRow = editRow()

        addButton(actions, "DONE", true) { exitEditing(save = true) }
        addButton(actions, "UNDO", false) {
            if (!view.undoLayout()) toast("Nothing to undo")
        }
        addButton(actions, "REDO", false) {
            if (!view.redoLayout()) toast("Nothing to redo")
        }
        addButton(actions, "SAVE AS", false) {
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

        fun toggleLabel(base: String, on: Boolean) = if (on) "$base ON" else "$base OFF"
        val snapButton = addButton(tools, toggleLabel("SNAP", view.snapEnabled), false) {}
        snapButton.setOnClickListener {
            view.snapEnabled = !view.snapEnabled
            snapButton.text = toggleLabel("SNAP", view.snapEnabled)
            persistEditorOptions()
        }
        val guidesButton = addButton(tools, toggleLabel("GUIDES", view.guidesEnabled), false) {}
        guidesButton.setOnClickListener {
            view.guidesEnabled = !view.guidesEnabled
            guidesButton.text = toggleLabel("GUIDES", view.guidesEnabled)
            persistEditorOptions()
        }
        val safeButton = addButton(tools, toggleLabel("SAFE", view.safeAreaEnabled), false) {}
        safeButton.setOnClickListener {
            view.safeAreaEnabled = !view.safeAreaEnabled
            safeButton.text = toggleLabel("SAFE", view.safeAreaEnabled)
            persistEditorOptions()
            if (view.safeArea.isEmpty) toast("No notch/bars inset reported on this device")
        }
        val multiButton = addButton(tools, toggleLabel("MULTI", view.multiSelectEnabled), false) {}
        multiButton.setOnClickListener {
            view.multiSelectEnabled = !view.multiSelectEnabled
            multiButton.text = toggleLabel("MULTI", view.multiSelectEnabled)
            persistEditorOptions()
            toast(if (view.multiSelectEnabled) "Tap controls to add them to the selection" else "Single-select mode")
        }

        addButton(clipboardRow, "COPY", false) { view.copySelected() }
        addButton(clipboardRow, "PASTE", false) { view.pasteSelected() }
        addButton(clipboardRow, "LOCK", false) { view.toggleLockSelected() }
        addButton(clipboardRow, "MIRROR", false) { view.mirrorSelected() }
        addButton(clipboardRow, "RESET", false) {
            view.resetLayout()
            layouts.clearActive()
            persistLayout()
            toast("Default layout restored")
        }
        addButton(clipboardRow, "SHOW ALL", false) { view.showAll() }

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

    private fun persistEditorOptions() {
        prefs.edit()
            .putBoolean(PrefsKeys.LAYOUT_SNAP, view.snapEnabled)
            .putBoolean(PrefsKeys.LAYOUT_GUIDES, view.guidesEnabled)
            .putBoolean(PrefsKeys.LAYOUT_SAFE_AREA, view.safeAreaEnabled)
            .putBoolean(PrefsKeys.LAYOUT_MULTI, view.multiSelectEnabled)
            .apply()
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

                override fun onScanLayoutQr() {
                    settingsPanel?.dismiss()
                    startActivityForResult(
                        Intent(this@MainActivity, ScanActivity::class.java)
                            .putExtra(ScanActivity.EXTRA_LAYOUT_MODE, true),
                        REQ_LAYOUT_QR,
                    )
                }

                override fun onEditLayout() {
                    settingsPanel?.dismiss()
                    handler.postDelayed({ startEditing() }, 260L)
                }

                override fun onLayoutSaved() = persistLayout()

                override fun onAimTrainer() {
                    if (view.editMode) exitEditing(save = true)
                    settingsPanel?.dismiss()
                    handler.postDelayed({ view.aimTrainerActive = true }, 260L)
                }

                override fun saveGameProfile(name: String) {
                    this@MainActivity.saveGameProfile(name)
                }

                override fun applyGameProfile(name: String) {
                    this@MainActivity.applyGameProfile(name)
                }

                override fun deleteGameProfile(name: String) {
                    this@MainActivity.deleteGameProfile(name)
                }

                override fun onTouchpadChanged(enabled: Boolean) {
                    setTouchpadEnabled(enabled)
                    settingsPanel?.refreshTouchpadMode()
                }

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
                    applyQuality(quality, automatic = false)
                }

                override fun onAdaptiveQuality(enabled: Boolean) {
                    adaptiveEnabled = enabled
                    prefs.edit().putBoolean(PrefsKeys.ADAPTIVE_QUALITY, enabled).apply()
                    adaptiveQuality.reset()
                }

                override fun onHapticsChanged(strength: Float) {
                    hapticStrength = strength.coerceIn(0f, 1f)
                    if (rumbleAmp > 0) handler.post { startRumble(rumbleAmp) }
                }

                override fun onGameRumbleChanged(enabled: Boolean) {
                    gameRumbleEnabled = enabled
                    if (!enabled) handler.post { stopRumbleInternal() }
                }

                override fun attachScreenStream() {
                    this@MainActivity.attachScreenStream()
                }

                override fun streamFps(): Int = screenView?.currentStreamFps ?: 0

                override fun streamConnected(): Boolean =
                    screenView?.isStreamConnected ?: false

                override fun onOpenConnectionDoctor() {
                    doctorStartup = false
                    showConnectionDoctor()
                }

                override fun onOpenSetupGuide() {
                    setupStartup = false
                    showSetupGuide()
                }

                override fun onOpenHelp() {
                    HelpCenter.show(this@MainActivity, view.theme)
                }

                override fun onClosed() {
                    settingsPanel = null
                }

                override fun toast(message: String) {
                    this@MainActivity.toast(message)
                }

                override fun bluetoothPermissionsMissing(): Boolean =
                    BluetoothHidSupport.permissionsMissing(this@MainActivity)

                override fun bluetoothSupported(): Boolean =
                    BluetoothHidSupport.status(this@MainActivity).supported

                override fun bluetoothEnabled(): Boolean =
                    BluetoothHidSupport.status(this@MainActivity).enabled

                override fun requestBluetoothPermissions() {
                    btPermissionAsked = true
                    BluetoothHidSupport.requestPermissions(this@MainActivity, REQ_BT)
                }

                override fun onBluetoothPair() {
                    if (!BluetoothHidSupport.openSettings(this@MainActivity)) {
                        toast("Open Settings › Connected devices › Pair new device")
                    }
                }

                override fun onBluetoothDiscoverable() {
                    makeDiscoverable(manual = true)
                }

                override fun onBluetoothHostAudioSettings() {
                    val address = if (phoneGamepadActive) {
                        phoneGamepad.connectedAddress.orEmpty()
                    } else {
                        ""
                    }
                    val opened = BluetoothHidSupport.openHostSettings(this@MainActivity, address)
                    toast(
                        if (opened) {
                            "Turn off Media audio and Phone audio for this host"
                        } else {
                            "Open Connected devices and turn off Media audio and Phone audio"
                        },
                    )
                }

                override fun bluetoothHosts(): List<BluetoothHost> = phoneGamepad.hosts()

                override fun onBluetoothConnect(address: String) {
                    connectBluetoothHost(address)
                }

                override fun onBluetoothDisconnect() {
                    disconnectBluetoothHost()
                }

                override fun bluetoothGamepadRunning(): Boolean =
                    phoneGamepadActive && phoneGamepad.isRunning

                override fun bluetoothGamepadConnected(): Boolean =
                    phoneGamepadActive && phoneGamepad.isConnected

                override fun bluetoothGamepadName(): String =
                    if (phoneGamepadActive) phoneGamepad.connectedName ?: "" else ""

                override fun bluetoothGamepadStatus(): String = btStatus
            },
        )
        settingsPanel = panel
        panel.show(root)
    }

    // ------------------------------------------------------------- bluetooth
    /**
     * Starts the HID profile (if needed) and connects to one host the user
     * picked. Nothing is registered or connected on app launch. Permission is
     * requested once; the address survives the prompt so the user does not have
     * to pick twice.
     */
    private fun connectBluetoothHost(address: String) {
        if (!::phoneGamepad.isInitialized) return
        val status = BluetoothHidSupport.status(this)
        if (!status.supported) {
            btStatus = "No Bluetooth adapter on this phone"
            settingsPanel?.refreshBluetoothGamepad()
            return
        }
        if (!status.permitted) {
            btStatus = "Nearby devices access is off — granting access…"
            pendingConnectAddress = address
            if (!btPermissionAsked) {
                btPermissionAsked = true
                hostPermissionPending = true
                BluetoothHidSupport.requestPermissions(this, REQ_BT_HOST)
            }
            settingsPanel?.refreshBluetoothGamepad()
            return
        }
        if (!status.enabled) {
            btStatus = "Turn on Bluetooth to use the Bluetooth gamepad"
            settingsPanel?.refreshBluetoothGamepad()
            return
        }
        pendingConnectAddress = null
        // connect() now reports synchronous start/host failures. Do not mark the
        // gamepad active (or keep a 250 ms polling ticker) on a failed pick.
        val started = phoneGamepad.connect(address)
        if (started) {
            if (!phoneGamepadActive) {
                phoneGamepadActive = true
                lastPhoneVersion = -1L
                btWasConnected = false
            }
            handler.removeCallbacks(phoneGamepadTicker)
            handler.post(phoneGamepadTicker)
        } else {
            phoneGamepadActive = false
            handler.removeCallbacks(phoneGamepadTicker)
            lastPhoneVersion = -1L
            btWasConnected = false
        }
        settingsPanel?.refreshBluetoothGamepad()
    }

    /** Drops the picked host only; the profile stays ready for another pick. */
    private fun disconnectBluetoothHost() {
        pendingConnectAddress = null
        hostPermissionPending = false
        phoneGamepad.disconnect()
        btStatus = "Bluetooth gamepad disconnected — tap SELECT HOST to connect"
        settingsPanel?.refreshBluetoothGamepad()
    }

    private fun makeDiscoverable(manual: Boolean) {
        if (BluetoothHidSupport.permissionsMissing(this)) {
            btPermissionAsked = true
            BluetoothHidSupport.requestPermissions(this, REQ_BT)
            if (manual) toast("Allow Nearby devices, then tap MAKE DISCOVERABLE again")
            return
        }
        if (BluetoothHidSupport.requestDiscoverable(this)) {
            if (manual) {
                toast("Visible as “Lemo GamePad” for 5 minutes — pair, then pick the host here")
            }
        } else if (manual) {
            toast("Bluetooth is not available")
        }
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
        // Callbacks arrive on the link's receiver thread. After onPause the
        // vibrator is cancelled and resumed is false, so a packet already in
        // flight cannot restart it in the background.
        if (!resumed) return
        val amp = rumbleAmplitude(small, large)
        if (amp == 0 || !view.hapticsEnabled || !gameRumbleEnabled) {
            handler.post { stopRumbleInternal() }
            return
        }
        // Keep the game's level rather than turning it into fixed pulses. The
        // server repeats a live level every 500 ms, which only refreshes the
        // watchdog here; the motor itself stays on continuously.
        handler.post {
            if (!resumed || !view.hapticsEnabled || !gameRumbleEnabled) return@post
            if (rumbleAmp != amp) {
                rumbleAmp = amp
                startRumble(amp)
            }
            handler.removeCallbacks(stopRumble)
            handler.postDelayed(stopRumble, RUMBLE_TIMEOUT_MS)
        }
    }

    /** XInput drives two motors; a phone has one, so keep both contributions. */
    private fun rumbleAmplitude(small: Int, large: Int): Int {
        val s = small.coerceIn(0, 255)
        val l = large.coerceIn(0, 255)
        return minOf(255, maxOf(s, l) + minOf(s, l) / 2)
    }

    private fun startRumble(amp: Int) {
        val v = vibrator() ?: return
        val strength = (amp * hapticStrength).toInt().coerceIn(0, 255)
        v.cancel()
        if (strength == 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // A single repeating segment is a smooth, uninterrupted buzz whose
            // amplitude tracks the game instead of a 130 ms on/off pattern.
            v.vibrate(
                VibrationEffect.createWaveform(longArrayOf(40L), intArrayOf(strength), 0),
            )
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(longArrayOf(40L, 10L), 0)
        }
    }

    private fun stopRumbleInternal() {
        handler.removeCallbacks(stopRumble)
        if (rumbleAmp == 0) return
        rumbleAmp = 0
        vibrator()?.cancel()
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }

    // ------------------------------------------------------------------ window
    /** Feeds notch/system-bar bounds to the editor's optional safe-area guide. */
    @Suppress("DEPRECATION")
    private fun installSafeAreaInsets() {
        root.setOnApplyWindowInsetsListener { v, insets ->
            var left = insets.systemWindowInsetLeft
            var top = insets.systemWindowInsetTop
            var right = insets.systemWindowInsetRight
            var bottom = insets.systemWindowInsetBottom
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                insets.displayCutout?.let { cutout ->
                    left = maxOf(left, cutout.safeInsetLeft)
                    top = maxOf(top, cutout.safeInsetTop)
                    right = maxOf(right, cutout.safeInsetRight)
                    bottom = maxOf(bottom, cutout.safeInsetBottom)
                }
            }
            v.post {
                val w = root.width
                val h = root.height
                if (w > 0 && h > 0) {
                    view.safeArea = RectF(
                        left.coerceIn(0, w).toFloat(),
                        top.coerceIn(0, h).toFloat(),
                        (w - right).coerceIn(0, w).toFloat(),
                        (h - bottom).coerceIn(0, h).toFloat(),
                    )
                }
            }
            insets
        }
    }

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

    // ---------------------------------------------------------- aim suggestion
    private fun showAimRecommendation(rec: AimRecommendation) {
        if (isFinishing || !resumed) return
        dismissAimRecommendation()
        val t = view.theme

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(15), px(18), px(14))
            background = GradientDrawable().apply {
                cornerRadius = px(17).toFloat()
                setColor(withAlpha(t.surface, 250))
                setStroke(px(1), withAlpha(t.accent, 200))
            }
            elevation = px(40).toFloat()
        }
        card.addView(TextView(this).apply {
            text = "AIM RECOMMENDATION"
            textSize = 9.5f
            letterSpacing = 0.14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(t.accent)
        })
        card.addView(TextView(this).apply {
            text = rec.title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(t.text)
            setPadding(0, px(7), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = rec.detail
            textSize = 11.5f
            setTextColor(t.textDim)
            setPadding(0, px(5), 0, 0)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, px(12), 0, 0)
        }
        if (rec.hasChanges) {
            row.addView(
                swapButton("NOT NOW", t, primary = false) { dismissAimRecommendation() },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = px(8) },
            )
            row.addView(
                swapButton("APPLY", t, primary = true) { applyAimRecommendation(rec) },
            )
        } else {
            row.addView(
                swapButton("GOT IT", t, primary = true) { dismissAimRecommendation() },
            )
        }
        card.addView(row)

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(withAlpha(t.bgBottom, 175))
            isClickable = true
            setOnClickListener { dismissAimRecommendation() }
            addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ).apply { marginStart = px(20); marginEnd = px(20) },
            )
            alpha = 0f
        }
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        aimRecommendation = scrim
        scrim.animate().alpha(1f).setDuration(160).start()
    }

    private fun applyAimRecommendation(rec: AimRecommendation) {
        val editor = prefs.edit()
        rec.touchpadSensitivity?.let { value ->
            view.touchpadSensitivity = value / 100f
            editor.putInt(PrefsKeys.TOUCHPAD_SENS, value)
        }
        rec.aimSensitivity?.let { value ->
            view.aimSensitivity = value / 100f
            editor.putInt(PrefsKeys.AIM_SENS, value)
        }
        rec.gyroRevertX?.let { value ->
            view.gyroRevertX = value
            editor.putBoolean(PrefsKeys.GYRO_REVERT_X, value)
        }
        rec.gyroRevertY?.let { value ->
            view.gyroRevertY = value
            editor.putBoolean(PrefsKeys.GYRO_REVERT_Y, value)
        }
        editor.apply()
        dismissAimRecommendation()
        toast("Aim settings updated")
    }

    private fun dismissAimRecommendation(): Boolean {
        val card = aimRecommendation ?: return false
        aimRecommendation = null
        card.animate().alpha(0f).setDuration(120)
            .withEndAction { root.removeView(card) }
            .start()
        return true
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

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            toast("No browser installed")
        }
    }

    companion object {
        private const val SETUP_URL =
            "https://alamshowkatdar.github.io/lemoGamePad-ultimate/"

        private const val REQ_QR = 77
        private const val REQ_BT = 78
        /** Permission prompt opened by SELECT HOST; only it may consume a pick. */
        private const val REQ_BT_HOST = 79
        private const val REQ_LAYOUT_QR = 80

        // Rumble follows the game's motor levels. The server repeats a live
        // level every 500 ms; this timeout only covers lost refreshes.
        private const val RUMBLE_TIMEOUT_MS = 1600L

        // Adaptive quality: 2 s windows, with the controller's own hysteresis
        // and cooldown. Notices are throttled separately so the badge can change
        // quietly without a toast storm.
        private const val ADAPTIVE_SAMPLE_MS = 2_000L
        private const val ADAPTIVE_NOTICE_MS = 60_000L

        // Release checks are best-effort and cheap, but still rate limited so
        // repeated launches do not hammer the public GitHub API.
        private const val UPDATE_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L
    }
}
