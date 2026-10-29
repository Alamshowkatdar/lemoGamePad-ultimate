package com.alamshowkat.lemogamepad

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/** Shared preference keys used by the activity, the view and this panel. */
internal object PrefsKeys {
    const val FILE = "vpad"
    const val HOST = "host"
    const val PORT = "port"
    const val DEADZONE = "deadzone"
    const val HAPTICS = "haptics"
    const val ONBOARDED = "onboarded"
    const val WIFI_GUIDE_SEEN = "wifi_guide_seen"
    const val THEME = "theme"
    const val FACES = "faces"
    const val LAYOUT = "layout"
    const val TRANSPORT = "transport"
    const val TOKEN = "token"
    const val SCREEN = "screen"
    const val SCREEN_SMALL = "screen_small"
    const val SCREEN_QUALITY = "screen_quality"
    const val SCREEN_X = "screen_x"
    const val SCREEN_Y = "screen_y"
}

internal fun transportById(id: String?): Transport = when (id) {
    "wifi" -> Transport.WIFI
    "bluetooth" -> Transport.BLUETOOTH
    "usb" -> Transport.USB
    else -> Transport.AUTO
}

/** Picks black or white text for a coloured surface. */
private fun onColor(background: Int): Int {
    val luminance = (
        0.299 * Color.red(background) +
            0.587 * Color.green(background) +
            0.114 * Color.blue(background)
        ) / 255.0
    return if (luminance > 0.62) 0xFF08110C.toInt() else 0xFFF7FFFB.toInt()
}

/**
 * Themed settings sheet that slides in from the right.
 *
 * The old settings dialog could not show live link state and locked the user
 * into "save everything to connect" behaviour. This panel applies every change
 * immediately, shows the real transport/RTT/interface state while it is open,
 * and gives the connection its own confirm/reconnect/disconnect controls.
 */
class SettingsPanel(
    private val activity: Activity,
    private val link: Link,
    private val pad: GamepadView,
    private val prefs: SharedPreferences,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onScanQr()
        fun onEditLayout()
        fun onLayoutSaved()
        fun onThemeChanged(theme: PadTheme)
        fun onScreenPreview(enabled: Boolean)
        fun onScreenSize(small: Boolean)
        fun onScreenQuality(quality: ScreenQuality)
        fun attachScreenStream()
        fun onClosed()
        fun toast(message: String)
        fun bluetoothPermissionsMissing(): Boolean
        fun requestBluetoothPermissions()
        fun onStartPhoneGamepad()
        fun onStopPhoneGamepad()
    }

    val view = FrameLayout(activity)
    val isShowing: Boolean get() = showing

    private val tinters = ArrayList<() -> Unit>()
    private val transportViews = ArrayList<Pair<Transport, TextView>>()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var scrim: View
    private lateinit var sheet: LinearLayout
    private lateinit var statusDot: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var detailLine: TextView
    private lateinit var hintLine: TextView
    private lateinit var connectBtn: TextView
    private lateinit var disconnectBtn: TextView
    private lateinit var scanBtn: TextView
    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var tetherBtn: TextView
    private lateinit var scanLine: TextView
    /** Set when a scan only succeeded on a link the user did not pick. */
    private var scanFallback: String? = null
    private lateinit var bluetoothStatus: TextView
    private lateinit var panLine: TextView
    private lateinit var bluetoothGrantBtn: TextView
    private lateinit var discoverBtn: TextView
    private lateinit var pairBtn: TextView

    private var selectedTransport: Transport = link.transport
    private var scanning = false
    private var showing = false
    private var closed = false
    private var pendingBluetooth: (() -> Unit)? = null
    private var lastBluetoothRefresh = 0L
    private var lastChipSignature = Int.MIN_VALUE

    private val ticker = object : Runnable {
        override fun run() {
            if (!showing) return
            refreshLink()
            handler.postDelayed(this, 500L)
        }
    }

    init {
        view.isClickable = true
        build()
    }

    // ------------------------------------------------------------------ shell
    private fun build() {
        val metrics = activity.resources.displayMetrics
        val landscape = metrics.widthPixels >= metrics.heightPixels
        val sheetWidth = if (landscape) {
            min(px(460), (metrics.widthPixels * 0.47f).toInt())
        } else {
            (metrics.widthPixels * 0.92f).toInt()
        }

        scrim = View(activity).apply {
            isClickable = true
            setOnClickListener { dismiss() }
        }
        view.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusableInTouchMode = true
            elevation = px(18).toFloat()
        }
        view.addView(
            sheet,
            FrameLayout.LayoutParams(sheetWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END),
        )

        tinters += {
            val t = pad.selectedTheme
            scrim.setBackgroundColor(withAlpha(0x000000, if (t.dark) 175 else 100))
            sheet.background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    px(24).toFloat(), px(24).toFloat(),
                    0f, 0f,
                    0f, 0f,
                    px(24).toFloat(), px(24).toFloat(),
                )
                setColor(withAlpha(t.surface, if (t.dark) 252 else 254))
            }
        }

        sheet.addView(buildHeader())
        sheet.addView(buildDivider())
        sheet.addView(
            buildScroll(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        refreshTints()
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(14), px(10))
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val eyebrow = TextView(activity).apply {
            text = "CONTROL CENTRE"
            textSize = 10f
            letterSpacing = 0.2f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        eyebrow.setTextColor(pad.selectedTheme.accent)
        val title = TextView(activity).apply {
            text = Brand.NAME
            textSize = 17f
            letterSpacing = 0.01f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, px(2), 0, px(1))
        }
        val author = TextView(activity).apply {
            text = "${Brand.AUTHOR.uppercase()}  ·  v${Brand.VERSION}"
            textSize = 10f
            letterSpacing = 0.14f
        }
        titles.addView(eyebrow)
        titles.addView(title)
        titles.addView(author)
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val close = TextView(activity).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "Close settings"
            setOnClickListener { dismiss() }
        }
        row.addView(
            close,
            LinearLayout.LayoutParams(px(36), px(36)).apply { marginStart = px(8) },
        )

        header.addView(row)
        tinters += {
            val t = pad.selectedTheme
            eyebrow.setTextColor(t.accent)
            title.setTextColor(t.text)
            author.setTextColor(t.muted)
            close.setTextColor(t.textDim)
            close.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(t.text, 16))
                setStroke(px(1), withAlpha(t.text, 42))
            }
        }
        header.alpha = 0f
        header.translationY = -px(8).toFloat()
        header.animate().alpha(1f).translationY(0f).setDuration(280L).start()
        return header
    }

    private fun buildDivider(): View {
        val line = View(activity)
        tinters += {
            line.setBackgroundColor(withAlpha(pad.selectedTheme.text, 22))
        }
        line.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            px(1),
        ).apply {
            marginStart = px(18)
            marginEnd = px(16)
        }
        return line
    }

    private fun buildScroll(): ScrollView {
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(40))
        }

        content.addView(statusCard(), cardParams())
        content.addView(connectionCard(), cardParams())
        content.addView(bluetoothCard(), cardParams())
        content.addView(phoneGamepadCard(), cardParams())
        content.addView(appearanceCard(), cardParams())
        content.addView(controlsCard(), cardParams())
        content.addView(screenCard(), cardParams())
        content.addView(footer())

        scroll.addView(content)
        scroll.alpha = 0f
        scroll.animate().alpha(1f).setDuration(260L).setStartDelay(70L).start()
        return scroll
    }

    // ------------------------------------------------------------------ cards
    private fun card(): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(13), px(14), px(14))
        }
        tinters += {
            val t = pad.selectedTheme
            card.background = GradientDrawable().apply {
                cornerRadius = px(16).toFloat()
                setColor(withAlpha(t.surfaceAlt, if (t.dark) 200 else 238))
                setStroke(px(1), withAlpha(t.accent, if (t.dark) 24 else 34))
            }
        }
        return card
    }

    private fun cardParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = px(10) }

    private fun statusCard(): LinearLayout {
        val card = card()

        val top = row()
        statusDot = TextView(activity).apply {
            text = "●"
            textSize = 13f
            includeFontPadding = false
        }
        statusTitle = TextView(activity).apply {
            text = "NOT PAIRED"
            textSize = 12f
            letterSpacing = 0.13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        statusSub = TextView(activity).apply {
            textSize = 10.5f
            letterSpacing = 0.04f
            gravity = Gravity.END
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        top.addView(statusDot, wrapParams().apply { marginEnd = px(6) })
        top.addView(statusTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(statusSub, wrapParams())
        card.addView(top)

        detailLine = TextView(activity).apply {
            textSize = 11f
            setLineSpacing(px(3).toFloat(), 1f)
            setPadding(px(1), px(6), 0, px(10))
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(detailLine)

        val actions = row()
        connectBtn = action("CONNECT", primary = true) { connect() }
        val reconnectBtn = action("RECONNECT", primary = false) { reconnect() }
        disconnectBtn = action("DISCONNECT", primary = false) { disconnect() }
        actions.addView(connectBtn, weightParams(1.15f, end = px(5)))
        actions.addView(reconnectBtn, weightParams(1f, end = px(5)))
        actions.addView(disconnectBtn, weightParams(1f))
        card.addView(actions)

        tinters += {
            val t = pad.selectedTheme
            statusSub.setTextColor(t.muted)
            detailLine.setTextColor(t.textDim)
        }
        return card
    }

    private fun connectionCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("CONNECTION"))

        val chips = row()
        for (tr in Transport.values()) {
            val label = if (tr == Transport.BLUETOOTH) "BT" else tr.label
            val chipView = chip(label) {
                if (selectedTransport == tr && !scanning) {
                    scan(autoConnect = true)
                    return@chip
                }
                selectedTransport = tr
                link.transport = tr
                prefs.edit().putString(PrefsKeys.TRANSPORT, tr.name.lowercase()).apply()
                refreshTransportChips(link.readiness())
                scan(autoConnect = true)
            }
            transportViews += tr to chipView
            chips.addView(chipView, weightParams(1f, end = px(5)))
        }
        card.addView(chips)

        hintLine = TextView(activity).apply {
            textSize = 11f
            setLineSpacing(px(2).toFloat(), 1f)
            setPadding(px(2), px(9), px(2), px(9))
        }
        card.addView(hintLine)

        // Bluetooth PAN and USB tethering are off by default on most phones and
        // the toggle is buried several menus deep, so offer a shortcut.
        tetherBtn = action("OPEN TETHERING SETTINGS", primary = false) { openTetherSettings() }
        tetherBtn.visibility = View.GONE
        card.addView(tetherBtn, topParams(px(2)))

        val fieldLabels = row()
        fieldLabels.addView(tiny("PC ADDRESS"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fieldLabels.addView(tiny("PORT").apply { gravity = Gravity.END }, wrapParams())
        card.addView(fieldLabels)

        val fields = row()
        hostEdit = field("192.168.1.10", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        hostEdit.setText(prefs.getString(PrefsKeys.HOST, "") ?: "")
        portEdit = field("9876", InputType.TYPE_CLASS_NUMBER)
        portEdit.setText(prefs.getInt(PrefsKeys.PORT, Proto.PORT).toString())
        fields.addView(hostEdit, weightParams(2.6f, end = px(8)))
        fields.addView(portEdit, weightParams(1f))
        card.addView(fields)

        val scanRow = row()
        scanBtn = action("SCAN NETWORK", primary = false) { scan(autoConnect = true) }
        val qrBtn = action("SCAN QR", primary = false) { callbacks.onScanQr() }
        scanRow.addView(scanBtn, weightParams(1f, end = px(6)))
        scanRow.addView(qrBtn, weightParams(1f))
        card.addView(scanRow, topParams(px(8)))

        scanLine = TextView(activity).apply {
            textSize = 10f
            letterSpacing = 0.03f
            setLineSpacing(px(2).toFloat(), 1f)
            setPadding(px(2), px(7), px(2), 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        card.addView(scanLine)

        tinters += {
            val t = pad.selectedTheme
            hintLine.setTextColor(t.muted)
            scanLine.setTextColor(t.muted)
        }
        return card
    }

    private fun bluetoothCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("BLUETOOTH PAIRING"))

        bluetoothStatus = TextView(activity).apply {
            textSize = 11f
            setLineSpacing(px(3).toFloat(), 1f)
            setPadding(px(2), 0, px(2), px(9))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(bluetoothStatus)

        val actions = row()
        pairBtn = action("PAIR WITH PC", primary = false) {
            if (!BluetoothPairing.openSettings(activity)) {
                callbacks.toast("Open Settings › Connected devices › Pair new device")
            }
        }
        discoverBtn = action("DISCOVERABLE", primary = false) {
            runBluetooth {
                if (BluetoothPairing.requestDiscoverable(activity)) {
                    callbacks.toast("Phone visible to the PC for 2 minutes")
                } else {
                    callbacks.toast("Bluetooth is not available")
                }
            }
        }
        actions.addView(pairBtn, weightParams(1f, end = px(5)))
        actions.addView(discoverBtn, weightParams(1f))
        card.addView(actions)

        bluetoothGrantBtn = action("ALLOW NEARBY DEVICES", primary = true) {
            callbacks.requestBluetoothPermissions()
        }
        bluetoothGrantBtn.visibility = View.GONE
        card.addView(bluetoothGrantBtn, topParams(px(6)))

        panLine = TextView(activity).apply {
            textSize = 10.5f
            setLineSpacing(px(3).toFloat(), 1f)
            setPadding(px(2), px(9), px(2), px(9))
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(panLine)

        val steps = tiny(
            "1 · Make the phone discoverable, then add it in Windows › Bluetooth.\n" +
                "2 · On the PC: Bluetooth devices › Join a Personal Area Network.\n" +
                "3 · Turn on Bluetooth tethering below, then SCAN NETWORK.",
            bottom = 0,
        )
        steps.setLineSpacing(px(3).toFloat(), 1f)
        steps.maxLines = 4
        card.addView(steps)

        tinters += {
            val t = pad.selectedTheme
            bluetoothStatus.setTextColor(t.textDim)
            panLine.setTextColor(t.muted)
        }
        return card
    }

    private fun phoneGamepadCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("PHONE-TO-PHONE GAMEPAD"))
        card.addView(
            tiny(
                "Bluetooth makes this phone act like a real game controller. Start it here, " +
                    "then pair “Lemo GamePad” from Bluetooth settings on the phone running your game. " +
                    "Android 9+ is required; Wi-Fi-to-PC remains available separately.",
                bottom = px(8),
            ),
        )
        val actions = row()
        actions.addView(
            action("START GAMEPAD", primary = true) { callbacks.onStartPhoneGamepad() },
            weightParams(1f, end = px(5)),
        )
        actions.addView(
            action("STOP", primary = false) { callbacks.onStopPhoneGamepad() },
            weightParams(1f),
        )
        card.addView(actions)
        return card
    }

    private fun appearanceCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("APPEARANCE"))
        card.addView(tiny("THEME"))

        val swatches = ArrayList<Pair<PadTheme, TextView>>()
        fun styleSwatches() {
            val selected = pad.selectedTheme
            for ((th, chipView) in swatches) {
                val on = th.id == selected.id
                chipView.setTextColor(if (on) onColor(selected.accent) else selected.textDim)
                chipView.background = GradientDrawable().apply {
                    cornerRadius = px(11).toFloat()
                    setColor(if (on) selected.accent else withAlpha(selected.surfaceAlt, 235))
                    setStroke(
                        px(1),
                        withAlpha(if (on) selected.accent else selected.text, if (on) 255 else 34),
                    )
                }
            }
        }

        for (rowIndex in 0 until 2) {
            val rowBox = row()
            for (col in 0 until 3) {
                val th = Themes.all.getOrNull(rowIndex * 3 + col)
                if (th == null) {
                    rowBox.addView(View(activity), weightParams(1f))
                    continue
                }
                val chip = TextView(activity).apply {
                    text = th.name
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(px(4), px(10), px(4), px(10))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        pad.theme = th
                        prefs.edit().putString(PrefsKeys.THEME, th.id).apply()
                        callbacks.onThemeChanged(th)
                        refreshTints()
                    }
                }
                swatches += th to chip
                rowBox.addView(chip, weightParams(1f, end = px(5), bottom = px(5)))
            }
            card.addView(rowBox)
        }
        tinters += { styleSwatches() }
        styleSwatches()

        card.addView(tiny("FACE BUTTON LABELS", top = px(9)))
        val faceRow = row()
        val psChip = choiceChip("PLAYSTATION") {
            pad.faceLabels = FaceLabels.PLAYSTATION
            prefs.edit().putString(PrefsKeys.FACES, "ps").apply()
            refreshTints()
        }
        val xboxChip = choiceChip("XBOX") {
            pad.faceLabels = FaceLabels.XBOX
            prefs.edit().putString(PrefsKeys.FACES, "xbox").apply()
            refreshTints()
        }
        faceRow.addView(psChip, weightParams(1f, end = px(5)))
        faceRow.addView(xboxChip, weightParams(1f))
        card.addView(faceRow)

        fun styleFaces() {
            val t = pad.selectedTheme
            val playstation = pad.faceLabels == FaceLabels.PLAYSTATION
            for ((chip, on) in listOf(psChip to playstation, xboxChip to !playstation)) {
                chip.setTextColor(if (on) onColor(t.accent) else t.textDim)
                chip.background = GradientDrawable().apply {
                    cornerRadius = px(10).toFloat()
                    setColor(if (on) t.accent else withAlpha(t.surfaceAlt, 235))
                    setStroke(px(1), withAlpha(if (on) t.accent else t.text, if (on) 255 else 34))
                }
            }
        }
        tinters += { styleFaces() }
        styleFaces()

        return card
    }

    private fun controlsCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("CONTROLS"))

        val layoutRow = row()
        layoutRow.addView(
            action("CUSTOMIZE LAYOUT", primary = false) { callbacks.onEditLayout() },
            weightParams(1f, end = px(6)),
        )
        layoutRow.addView(
            action("RESET LAYOUT", primary = false) {
                pad.resetLayout()
                callbacks.onLayoutSaved()
                callbacks.toast("Layout reset")
            },
            weightParams(1f),
        )
        card.addView(layoutRow)

        val deadzoneLabel = tiny(
            "STICK DEADZONE  ·  ${(pad.deadzone * 100f).roundToInt()}%",
            top = px(12),
            bottom = px(2),
        )
        card.addView(deadzoneLabel)

        val bar = SeekBar(activity).apply {
            max = 40
            progress = (pad.deadzone * 100f).roundToInt().coerceIn(0, 40)
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    pad.deadzone = value / 100f
                    deadzoneLabel.text = "STICK DEADZONE  ·  $value%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    prefs.edit()
                        .putInt(PrefsKeys.DEADZONE, (pad.deadzone * 100f).roundToInt())
                        .apply()
                }
            })
        }
        tinters += {
            val t = pad.selectedTheme
            bar.progressTintList = ColorStateList.valueOf(t.accent)
            bar.thumbTintList = ColorStateList.valueOf(t.accent)
            bar.progressBackgroundTintList = ColorStateList.valueOf(withAlpha(t.text, 30))
        }
        card.addView(bar)

        card.addView(
            toggle("Vibration feedback", prefs.getBoolean(PrefsKeys.HAPTICS, true)) { on ->
                pad.hapticsEnabled = on
                prefs.edit().putBoolean(PrefsKeys.HAPTICS, on).apply()
            },
            topParams(px(8)),
        )
        return card
    }

    private fun screenCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("PC SCREEN"))

        var enabled = prefs.getBoolean(PrefsKeys.SCREEN, false)
        var small = prefs.getBoolean(PrefsKeys.SCREEN_SMALL, true)
        var quality = ScreenQuality.fromId(
            prefs.getInt(PrefsKeys.SCREEN_QUALITY, ScreenQuality.BALANCED.wireId),
        )

        val sizeRow = row()
        val smallChip = chip("SMALL") {}
        val largeChip = chip("LARGE") {}

        val qualityRow = row()
        val lowChip = chip("LOW") {}
        val balancedChip = chip("BALANCED") {}
        val highChip = chip("HIGH") {}

        fun refreshChips() {
            val t = pad.selectedTheme
            for ((chipView, on) in listOf(
                smallChip to (small && enabled),
                largeChip to (!small && enabled),
            )) {
                chipView.alpha = if (enabled) 1f else 0.38f
                chipView.setTextColor(if (on) onColor(t.accent) else t.textDim)
                chipView.background = GradientDrawable().apply {
                    cornerRadius = px(10).toFloat()
                    setColor(if (on) t.accent else withAlpha(t.surfaceAlt, 235))
                    setStroke(px(1), withAlpha(if (on) t.accent else t.text, if (on) 255 else 34))
                }
            }
            for ((chipView, selected) in listOf(
                lowChip to (quality == ScreenQuality.LOW),
                balancedChip to (quality == ScreenQuality.BALANCED),
                highChip to (quality == ScreenQuality.HIGH),
            )) {
                chipView.alpha = if (enabled) 1f else 0.38f
                chipView.setTextColor(if (selected) onColor(t.accent) else t.textDim)
                chipView.background = GradientDrawable().apply {
                    cornerRadius = px(10).toFloat()
                    setColor(if (selected) t.accent else withAlpha(t.surfaceAlt, 235))
                    setStroke(px(1), withAlpha(if (selected) t.accent else t.text, if (selected) 255 else 34))
                }
            }
        }

        smallChip.setOnClickListener {
            if (!enabled) return@setOnClickListener
            small = true
            prefs.edit().putBoolean(PrefsKeys.SCREEN_SMALL, true).apply()
            callbacks.onScreenSize(true)
            refreshChips()
        }
        largeChip.setOnClickListener {
            if (!enabled) return@setOnClickListener
            small = false
            prefs.edit().putBoolean(PrefsKeys.SCREEN_SMALL, false).apply()
            callbacks.onScreenSize(false)
            refreshChips()
        }
        sizeRow.addView(smallChip, weightParams(1f, end = px(5)))
        sizeRow.addView(largeChip, weightParams(1f))

        fun selectQuality(value: ScreenQuality) {
            if (!enabled || quality == value) return
            quality = value
            prefs.edit().putInt(PrefsKeys.SCREEN_QUALITY, value.wireId).apply()
            callbacks.onScreenQuality(value)
            refreshChips()
        }
        lowChip.setOnClickListener { selectQuality(ScreenQuality.LOW) }
        balancedChip.setOnClickListener { selectQuality(ScreenQuality.BALANCED) }
        highChip.setOnClickListener { selectQuality(ScreenQuality.HIGH) }
        qualityRow.addView(lowChip, weightParams(1f, end = px(4)))
        qualityRow.addView(balancedChip, weightParams(1f, end = px(4)))
        qualityRow.addView(highChip, weightParams(1f))

        val previewToggle = toggle("Screen preview window", enabled) { on ->
            enabled = on
            prefs.edit().putBoolean(PrefsKeys.SCREEN, on).apply()
            callbacks.onScreenPreview(on)
            refreshChips()
        }

        card.addView(previewToggle)
        card.addView(tiny("PREVIEW SIZE", top = px(10), bottom = px(5)))
        card.addView(sizeRow)
        card.addView(tiny("STREAM QUALITY", top = px(10), bottom = px(5)))
        card.addView(qualityRow)
        card.addView(
            tiny("LOW sends less data · HIGH is sharper · live FPS appears on the preview", top = px(5)),
        )
        tinters += { refreshChips() }
        refreshChips()
        return card
    }

    private fun footer(): TextView {
        val text = TextView(activity).apply {
            text = "${Brand.NAME}™\nby ${Brand.AUTHOR}  ·  v${Brand.VERSION}"
            textSize = 10.5f
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setLineSpacing(px(3).toFloat(), 1f)
            setPadding(px(2), px(18), px(2), px(8))
        }
        tinters += { text.setTextColor(withAlpha(pad.selectedTheme.muted, 220)) }
        return text
    }

    // ------------------------------------------------------------- small views
    private fun row(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun wrapParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun weightParams(weight: Float, end: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            weight,
        ).apply {
            marginEnd = end
            bottomMargin = bottom
        }

    private fun topParams(top: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }

    private fun sectionTitle(text: String): TextView {
        val label = TextView(activity).apply {
            this.text = text
            textSize = 10f
            letterSpacing = 0.17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(px(2), 0, 0, px(9))
        }
        tinters += { label.setTextColor(pad.selectedTheme.accent) }
        return label
    }

    private fun tiny(text: String, top: Int = 0, bottom: Int = px(6)): TextView {
        val label = TextView(activity).apply {
            this.text = text
            textSize = 10f
            letterSpacing = 0.11f
            setPadding(px(2), top, 0, bottom)
        }
        tinters += { label.setTextColor(pad.selectedTheme.muted) }
        return label
    }

    private fun action(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val button = TextView(activity).apply {
            text = label
            textSize = 11f
            letterSpacing = 0.07f
            gravity = Gravity.CENTER
            setPadding(px(8), px(10), px(8), px(10))
            minHeight = px(44)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        tinters += {
            val t = pad.selectedTheme
            button.setTextColor(if (primary) onColor(t.accent) else t.text)
            button.background = GradientDrawable().apply {
                cornerRadius = px(11).toFloat()
                setColor(if (primary) t.accent else withAlpha(t.surfaceAlt, 240))
                setStroke(
                    px(1),
                    withAlpha(if (primary) t.accent else t.text, if (primary) 255 else 40),
                )
            }
        }
        return button
    }

    private fun chip(label: String, onClick: () -> Unit): TextView {
        return TextView(activity).apply {
            text = label
            textSize = 10.5f
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
            setPadding(px(4), px(9), px(4), px(9))
            minHeight = px(40)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun choiceChip(label: String, onClick: () -> Unit): TextView =
        chip(label, onClick).apply {
            textSize = 11f
            setPadding(px(6), px(10), px(6), px(10))
        }

    private fun field(hintText: String, type: Int): EditText {
        val edit = EditText(activity).apply {
            hint = hintText
            inputType = type
            textSize = 13f
            setSingleLine()
            setPadding(px(10), px(8), px(10), px(8))
            includeFontPadding = false
        }
        fun style() {
            val t = pad.selectedTheme
            val focused = edit.hasFocus()
            edit.setTextColor(t.text)
            edit.setHintTextColor(withAlpha(t.muted, 190))
            edit.background = GradientDrawable().apply {
                cornerRadius = px(10).toFloat()
                setColor(withAlpha(t.surfaceAlt, if (t.dark) 215 else 245))
                setStroke(
                    px(1),
                    withAlpha(
                        if (focused) t.accent else t.text,
                        if (focused) 220 else 36,
                    ),
                )
            }
        }
        edit.setOnFocusChangeListener { _, _ -> style() }
        tinters += { style() }
        style()
        return edit
    }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(2), px(6), px(2), px(6))
            isClickable = true
            isFocusable = true
        }
        val text = TextView(activity).apply {
            this.text = label
            textSize = 12.5f
        }
        val pill = TextView(activity).apply {
            textSize = 10f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setPadding(px(12), px(5), px(12), px(5))
        }
        container.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(pill, wrapParams())

        var on = initial
        fun style() {
            val t = pad.selectedTheme
            text.setTextColor(t.text)
            pill.text = if (on) "ON" else "OFF"
            pill.setTextColor(if (on) onColor(t.accent) else t.muted)
            pill.background = GradientDrawable().apply {
                cornerRadius = px(20).toFloat()
                setColor(if (on) t.accent else withAlpha(t.surfaceAlt, 240))
                setStroke(px(1), withAlpha(if (on) t.accent else t.text, if (on) 255 else 34))
            }
        }
        container.setOnClickListener {
            on = !on
            style()
            onChange(on)
        }
        tinters += { style() }
        style()
        return container
    }

    // -------------------------------------------------------------- connection
    private fun connect() {
        if (applyFields()) {
            callbacks.toast("Connecting to ${link.host}")
        } else {
            scan(autoConnect = true)
        }
    }

    private fun reconnect() {
        if (!applyFields()) {
            scan(autoConnect = true)
            return
        }
        link.reconnect()
        callbacks.attachScreenStream()
        callbacks.toast("Rebuilding ${selectedTransport.label} link")
    }

    private fun disconnect() {
        prefs.edit().putString(PrefsKeys.HOST, "").apply()
        scanFallback = null
        link.retarget(null, link.port)
        link.serverName = ""
        hostEdit.setText("")
        callbacks.attachScreenStream()
        callbacks.toast("Disconnected")
    }

    /** Saves the address fields and points the link at them. */
    private fun applyFields(): Boolean {
        val host = hostEdit.text.toString().trim()
        val port = portEdit.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: Proto.PORT
        if (portEdit.text.toString() != port.toString()) portEdit.setText(port.toString())

        val changed = link.host != host.ifBlank { null } || link.port != port
        prefs.edit()
            .putString(PrefsKeys.HOST, host)
            .putInt(PrefsKeys.PORT, port)
            .putString(PrefsKeys.TRANSPORT, selectedTransport.name.lowercase())
            .apply()

        link.port = port
        link.transport = selectedTransport
        if (host.isBlank()) {
            link.retarget(null, port)
            return false
        }
        if (changed) link.retarget(host, port)
        callbacks.attachScreenStream()
        return true
    }

    private fun scan(autoConnect: Boolean) {
        if (scanning) return
        scanning = true
        // Remember the user's choice: the callback below may re-point the chip
        // at the link that actually answered.
        val want = selectedTransport
        val port = portEdit.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: Proto.PORT

        link.port = port
        link.transport = selectedTransport
        prefs.edit()
            .putInt(PrefsKeys.PORT, port)
            .putString(PrefsKeys.TRANSPORT, selectedTransport.name.lowercase())
            .apply()

        scanBtn.isEnabled = false
        scanBtn.text = "SCANNING…"
        hintLine.text = "Searching for the PC on ${selectedTransport.label}…"
        scanLine.visibility = View.VISIBLE
        scanLine.text = "SCAN · probing ${selectedTransport.label.lowercase()}…"

        link.discover(3000) { ip, name ->
            activity.runOnUiThread {
                scanning = false
                scanBtn.isEnabled = true
                scanBtn.text = "SCAN NETWORK"
                scanLine.visibility = View.VISIBLE
                scanLine.text = "SCAN · ${link.lastScan.ifBlank { "no reply" }}"
                if (!showing) return@runOnUiThread

                if (ip == null) {
                    scanFallback = null
                    // A link that is up but silent almost always means the PC is
                    // running and Windows Firewall is dropping the datagrams.
                    val linkUp = link.readiness()[selectedTransport] == true
                    hintLine.text = if (linkUp) {
                        "${selectedTransport.label} link is up but the PC stayed silent — " +
                            "allow lemoGamePad-server through Windows Firewall."
                    } else {
                        "No PC answered on ${selectedTransport.label}. " +
                            "Start lemoGamePad-server and enable tethering for BT/USB."
                    }
                    callbacks.toast("No PC found on ${selectedTransport.label}")
                    return@runOnUiThread
                }

                // Follow the link the PC actually answered on: the chip may
                // say BLUETOOTH while the PC is reachable over Wi-Fi.
                val foundTransport = link.lastScanTransport
                scanFallback = if (
                    foundTransport != null && want != Transport.AUTO && foundTransport != want
                ) {
                    "${want.label} found no PC — linked over " +
                        "${foundTransport.label} instead"
                } else {
                    null
                }
                if (foundTransport != null && foundTransport != selectedTransport) {
                    selectedTransport = foundTransport
                    prefs.edit()
                        .putString(PrefsKeys.TRANSPORT, foundTransport.name.lowercase())
                        .apply()
                    refreshTransportChips(link.readiness())
                }

                hostEdit.setText(ip)
                portEdit.setText(port.toString())
                link.serverName = name ?: ""
                val label = if (name.isNullOrBlank()) ip else "$name ($ip)"
                if (autoConnect) {
                    applyFields()
                    callbacks.toast("Found $label · connecting")
                } else {
                    callbacks.toast("Found $label")
                }
            }
        }
    }

    // ------------------------------------------------------------------- state
    private fun refreshLink() {
        link.refreshConnectionState()
        val t = pad.selectedTheme
        val host = link.host
        val connected = link.connected
        val readiness = link.readiness()

        statusDot.setTextColor(
            when {
                connected -> t.ok
                host != null -> t.warn
                else -> t.muted
            },
        )
        statusTitle.text = when {
            connected -> "CONNECTED"
            host != null -> "LINKING…"
            else -> "NOT PAIRED"
        }
        statusTitle.setTextColor(if (host != null) t.text else t.textDim)

        val via = link.transportName().ifBlank {
            if (selectedTransport == Transport.AUTO) "" else selectedTransport.label
        }
        statusSub.text = buildString {
            if (via.isNotBlank()) append(via) else append("—")
            if (connected && link.rttMs >= 0) append("  ·  ${link.rttMs} ms")
            if (host != null) append("  ·  $host")
        }

        val interfaceList = link.localSummary()
        detailLine.text = buildString {
            append(link.serverName.ifBlank { if (host == null) "No PC paired yet" else host })
            append("\n")
            if (interfaceList.isBlank()) {
                append("No network interface")
            } else {
                append(interfaceList)
            }
        }

        // The primary button always dials the address typed above; RECONNECT
        // next to it rebuilds the current link. Do not relabel this one, or the
        // row shows RECONNECT twice.
        connectBtn.text = "CONNECT"
        disconnectBtn.isEnabled = host != null
        disconnectBtn.alpha = if (host == null) 0.4f else 1f

        refreshTransportChips(readiness)
        refreshBluetooth()
        tetherBtn.visibility = if (
            selectedTransport != Transport.AUTO && readiness[selectedTransport] != true
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        updateHint(readiness[selectedTransport] == true, connected)
    }

    private fun refreshTransportChips(readiness: Map<Transport, Boolean>) {
        val t = pad.selectedTheme
        var signature = t.accent
        for ((tr, _) in transportViews) {
            val on = tr == selectedTransport
            val ready = readiness[tr] == true
            signature = signature * 31 + (if (on) 2 else if (ready) 1 else 0)
        }
        if (signature == lastChipSignature) return
        lastChipSignature = signature

        for ((tr, chipView) in transportViews) {
            val on = tr == selectedTransport
            val ready = readiness[tr] == true
            chipView.alpha = if (ready || on) 1f else 0.5f
            chipView.setTextColor(if (on) onColor(t.accent) else t.textDim)
            chipView.background = GradientDrawable().apply {
                cornerRadius = px(10).toFloat()
                setColor(if (on) t.accent else withAlpha(t.surfaceAlt, 235))
                setStroke(px(1), withAlpha(if (on) t.accent else t.text, if (on) 255 else 34))
            }
            // Readiness dot: solid green for an up link, dim when missing.
            val dotSize = px(6)
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    when {
                        !ready -> withAlpha(t.muted, 110)
                        on -> onColor(t.accent)
                        else -> t.ok
                    },
                )
                setSize(dotSize, dotSize)
                setBounds(0, 0, dotSize, dotSize)
            }
            chipView.setCompoundDrawables(dot, null, null, null)
            chipView.compoundDrawablePadding = px(4)
        }
    }

    private fun updateHint(ready: Boolean, connected: Boolean) {
        if (scanning) return
        val tr = selectedTransport
        // With AUTO the chip says nothing useful about the live link, so report
        // the transport the socket actually went out on.
        val via = link.transportName().ifBlank { tr.label }.lowercase()
        hintLine.text = when {
            // The instruction is done — say so instead of repeating it.
            connected -> buildString {
                append("● $via link up and paired")
                // A fallback that happens silently looks like the app ignored
                // the chip the user just tapped, so spell it out.
                scanFallback?.let { append("\n$it") }
            }
            tr == Transport.AUTO -> tr.hint
            ready -> "● ${tr.label.lowercase()} link is up — SCAN NETWORK to find the PC"
            else -> buildString {
                append(tr.hint)
                append("\n● no ")
                append(if (tr == Transport.BLUETOOTH) "Bluetooth" else tr.label.lowercase())
                append(" interface yet — enable tethering")
            }
        }
    }

    /** Runs a Bluetooth action, asking for Android 12+ permissions first. */
    private fun runBluetooth(action: () -> Unit) {
        if (callbacks.bluetoothPermissionsMissing()) {
            pendingBluetooth = action
            callbacks.requestBluetoothPermissions()
            return
        }
        action()
    }

    /** Called by the activity after the Bluetooth permission dialog closes. */
    fun onBluetoothPermissionResult(granted: Boolean) {
        val action = pendingBluetooth
        pendingBluetooth = null
        if (granted) {
            action?.invoke()
        } else {
            callbacks.toast("Bluetooth access denied")
        }
        lastBluetoothRefresh = 0L
        refreshBluetooth(force = true)
    }

    private fun refreshBluetooth(force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastBluetoothRefresh < 1500L) return
        lastBluetoothRefresh = now

        val status = BluetoothPairing.status(activity)
        bluetoothStatus.text = when {
            !status.supported -> "No Bluetooth adapter on this phone."
            !status.permitted -> "Bluetooth access is off — allow nearby devices to pair."
            !status.enabled -> "Bluetooth is off. Turn it on in system settings."
            else -> if (status.phoneName.isBlank()) "This phone" else status.phoneName
        }
        bluetoothGrantBtn.visibility =
            if (status.supported && !status.permitted) View.VISIBLE else View.GONE
        discoverBtn.alpha = if (status.enabled && status.permitted) 1f else 0.45f
        panLine.text = when {
            status.panAddress != null -> "PAN link up  ·  ${status.panAddress}"
            !status.permitted -> "PAN link: allow Bluetooth access to read it"
            status.bonded.isEmpty() -> "No paired PC yet — pair from Windows Bluetooth settings."
            else -> "Paired: " + summarizeBonded(status.bonded)
        }
    }

    /**
     * Lists the Windows host first and caps the rest, so a phone with a dozen
     * bonded headsets/keyboards does not flood the card.
     */
    private fun summarizeBonded(bonded: List<String>): String {
        val ordered = bonded.sortedBy { name ->
            if (name.contains("DESKTOP", true) || name.contains("LAPTOP", true)) 0 else 1
        }
        val shown = ordered.take(5)
        val hidden = ordered.size - shown.size
        return shown.joinToString(", ") + if (hidden > 0) "  +$hidden more" else ""
    }

    /** Jumps to the system tethering page (Bluetooth PAN / USB). */
    private fun openTetherSettings() {
        val intents = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Settings.ACTION_TETHER_SETTINGS is not in every compile SDK, so
            // use the literal action name.
            intents.add(Intent("android.settings.TETHER_SETTINGS"))
        }
        intents.add(Intent("android.settings.WIRELESS_SETTINGS"))
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        callbacks.toast("Open Settings › Network › Hotspot & tethering")
    }

    private fun refreshTints() {
        for (tint in tinters) tint()
    }

    // ---------------------------------------------------------------- lifecycle
    fun show(parent: ViewGroup) {
        if (view.parent != null) return
        parent.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        showing = true
        closed = false
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(220L).start()
        sheet.post {
            sheet.translationX = sheet.width.toFloat()
            sheet.alpha = 0.35f
            sheet.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(320L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        refreshTints()
        refreshLink()
        lastBluetoothRefresh = 0L
        refreshBluetooth(force = true)
        // Do not pop the keyboard just because the sheet opened: the sheet
        // takes focus, so the address fields only focus when tapped.
        sheet.requestFocus()
        hideKeyboard()
        handler.post(ticker)
    }

    fun dismiss(animate: Boolean = true) {
        if (closed) return
        if (!showing || !animate || !view.isAttachedToWindow) {
            closeNow()
            return
        }
        showing = false
        handler.removeCallbacks(ticker)
        hideKeyboard()
        scrim.animate().alpha(0f).setDuration(180L).start()
        sheet.animate()
            .translationX(sheet.width.toFloat())
            .alpha(0.4f)
            .setDuration(240L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { closeNow() }
            .start()
    }

    private fun closeNow() {
        if (closed) return
        closed = true
        showing = false
        handler.removeCallbacks(ticker)
        hideKeyboard()
        (view.parent as? ViewGroup)?.removeView(view)
        callbacks.onClosed()
    }

    private fun hideKeyboard() {
        try {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        } catch (_: Exception) {
        }
    }

    // --------------------------------------------------------------------- misc
    private fun px(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}

