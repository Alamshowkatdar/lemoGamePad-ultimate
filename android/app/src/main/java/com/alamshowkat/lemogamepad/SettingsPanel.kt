package com.alamshowkat.lemogamepad

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.MotionEvent
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
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt

/** Copy shown by the "?" button on each settings card. */
private val CARD_HELP: Map<String, Pair<String, String>> = mapOf(
    "status" to ("CONNECTION STATUS" to
        "The pill on the pad opens this panel. CONNECTED means the PC is answering pings; LINKING means an address is set but no reply has arrived yet. The graph and stats below show RTT, jitter, loss and video FPS. Battery saver pauses those live stats while connection state and the buttons keep working."),
    "connection" to ("CONNECTION" to
        "Wi-Fi and USB both use the same port 9876 for input and 9877 for video; the browser pad uses TCP 9878. Transport AUTO probes all available paths. Use SCAN or the QR code to pair a PC. Connection Doctor now runs a live port check as well as showing interface state."),
    "bluetooth" to ("BLUETOOTH GAMEPAD" to
        "This is a server-less HID gamepad for a phone or TV. You must start it and pick a host explicitly; the app never auto-connects, registers, calls, or changes audio profiles. Android may connect audio to the bonded host by itself — while connected, use HOST AUDIO SETTINGS to switch Media audio and Phone audio off for that device. The report stays the original 9 bytes."),
    "motion" to ("MOTION & AIM" to
        "Gyro adds aim movement on top of touch. Revert left/right or up/down flips one axis if aim drifts the wrong way. Run AIM CALIBRATION and apply the suggested values to tune sensitivity."),
    "appearance" to ("APPEARANCE" to
        "Theme changes are instant and persist. LOW/BALANCED/HIGH only change video sharpness; smoothness is always prioritised at 60 fps. Adaptive quality is off unless you enable it and can auto-adjust sharpness."),
    "controls" to ("CONTROLS" to
        "Deadzone and curve shape the analog sticks. Haptic strength scales touch and game vibration; vibration from games can be switched off separately. Face spacing changes the four face buttons together until you move one individually in the layout editor."),
    "studio" to ("CUSTOMIZATION STUDIO" to
        "Edit layout unlocks drag, pinch resize, long-press hide, snap/grid, alignment guides, multi-select, copy/paste, lock and mirror. Undo/redo works during the edit session; save a named layout to keep it."),
    "layouts" to ("LAYOUTS" to
        "Named layouts are manual quick-switches. SHARE QR / SCAN QR hand a layout to another phone; EXPORT / IMPORT and game profiles use the same compact text, so an old saved layout still loads. Safe-area guides show notch and system-bar margins while editing."),
    "profiles" to ("GAME PROFILES" to
        "Profiles store your control feel as a snapshot: aim, motion, haptics, rate, face labels and layout. They never detect which game is running — save, name, and apply them yourself. COPY duplicates a profile; EXPORT copies a JSON backup to the clipboard and IMPORT merges profiles whose names are not already used. Connections are never included."),
    "screen" to ("PC SCREEN" to
        "The small window streams TCP video on port 9877. Dragging moves it; the close button only hides it, it does not disconnect input. Smoothness is prioritised over sharpness."),
)

/** Shared preference keys used by the activity, the view and this panel. */
internal object PrefsKeys {
    const val FILE = "vpad"
    const val HOST = "host"
    const val PORT = "port"
    const val DEADZONE = "deadzone"
    const val STICK_CURVE = "stick_curve"
    const val HAPTICS = "haptics"
    const val GAME_RUMBLE = "game_rumble"
    const val HAPTIC_STRENGTH = "haptic_strength"
    const val INPUT_RATE = "input_rate"
    const val PLAYER_SLOT = "player_slot"
    const val ONBOARDED = "onboarded"
    const val WIFI_GUIDE_SEEN = "wifi_guide_seen"
    const val CONNECTION_DOCTOR_SEEN = "connection_doctor_seen_v1"
    const val BATTERY_SAVER = "battery_saver_live_stats"
    const val SETTINGS_SECTION = "settings_section"
    const val SETTINGS_DENSE = "settings_dense"
    const val BT_LAST_HOST = "bt_last_host"
    const val LAST_WHATSNEW_CODE = "last_whatsnew_code"
    const val UPDATE_LAST_CHECK = "update_last_check_ms"
    const val UPDATE_SEEN_TAG = "update_seen_tag"
    const val THEME = "theme"
    const val FACES = "faces"
    const val MOTION = "motion"
    const val LAYOUT = "layout"
    const val LAYOUT_ACTIVE = "layout_active"
    const val LAYOUTS = "layouts"
    const val LAYOUT_SNAP = "layout_snap"
    const val LAYOUT_GUIDES = "layout_guides"
    const val LAYOUT_SAFE_AREA = "layout_safe_area"
    const val LAYOUT_MULTI = "layout_multi"
    const val GAME_PROFILES = "game_profiles"
    const val GAME_PROFILE_ACTIVE = "game_profile_active"
    const val TOUCHPAD_ENABLED = "touchpad_enabled"
    const val TOUCHPAD_MOUSE = "touchpad_mouse"
    const val GYRO_STEER = "gyro_steer"
    const val GYRO_AIM = "gyro_aim"
    const val GYRO_REVERT_X = "gyro_revert_x"
    const val GYRO_REVERT_Y = "gyro_revert_y"
    const val STEER_SENS = "steer_sens"
    const val STEER_DEADZONE = "steer_deadzone"
    const val STEER_CURVE = "steer_curve"
    const val AIM_SENS = "aim_sens"
    const val TOUCHPAD_SENS = "touchpad_sens"
    const val PAD_ALPHA = "pad_alpha"
    const val FACE_SPACING = "face_spacing"
    const val TRANSPORT = "transport"
    const val TOKEN = "token"
    const val SCREEN = "screen"
    const val SCREEN_SMALL = "screen_small"
    const val SCREEN_QUALITY = "screen_quality"
    const val ADAPTIVE_QUALITY = "adaptive_quality"
    const val SCREEN_X = "screen_x"
    const val SCREEN_Y = "screen_y"
}

internal fun transportById(id: String?): Transport = when (id) {
    "wifi" -> Transport.WIFI
    "usb" -> Transport.USB
    else -> Transport.AUTO
}

/** Picks black or white text for a coloured surface. */
internal fun onColor(background: Int): Int {
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
        fun onScanLayoutQr()
        fun onEditLayout()
        fun onLayoutSaved()
        fun onAimTrainer()
        fun saveGameProfile(name: String)
        fun applyGameProfile(name: String)
        fun deleteGameProfile(name: String)
        fun onTouchpadChanged(enabled: Boolean)
        fun onThemeChanged(theme: PadTheme)
        fun onScreenPreview(enabled: Boolean)
        fun onScreenSize(small: Boolean)
        fun onScreenQuality(quality: ScreenQuality)
        fun onAdaptiveQuality(enabled: Boolean)
        fun onHapticsChanged(strength: Float)
        fun onGameRumbleChanged(enabled: Boolean)
        fun attachScreenStream()
        fun streamFps(): Int
        fun streamConnected(): Boolean
        fun onOpenConnectionDoctor()
        fun onOpenSetupGuide()
        fun onOpenHelp()
        fun onClosed()
        fun toast(message: String)
        fun bluetoothPermissionsMissing(): Boolean
        fun bluetoothSupported(): Boolean
        fun bluetoothEnabled(): Boolean
        fun requestBluetoothPermissions()
        fun onBluetoothPair()
        fun onBluetoothDiscoverable()
        fun onBluetoothHostAudioSettings()
        fun bluetoothHosts(): List<BluetoothHost>
        fun onBluetoothConnect(address: String)
        fun onBluetoothDisconnect()
        fun bluetoothGamepadRunning(): Boolean
        fun bluetoothGamepadConnected(): Boolean
        fun bluetoothGamepadName(): String
        fun bluetoothGamepadStatus(): String
    }

    private data class SectionViews(
        val body: View,
        val rail: View,
    )

    private data class Section(
        val title: String,
        val subtitle: String,
        val cards: List<View>,
    )

    val view = FrameLayout(activity)
    val isShowing: Boolean get() = showing

    private val tinters = ArrayList<() -> Unit>()
    /** Tints registered by views rebuilt *during* a tint pass; never retained. */
    private val pendingTinters = ArrayList<() -> Unit>()
    private var tintBatchDepth = 0
    private val transportViews = ArrayList<Pair<Transport, TextView>>()
    private val slotViews = ArrayList<Pair<Int, TextView>>()
    private var lastSlotSignature = Int.MIN_VALUE
    private var batterySaver = SettingsGuard.bool(prefs, PrefsKeys.BATTERY_SAVER, false)
    private val handler = Handler(Looper.getMainLooper())

    /** One open category at a time; -1 collapses them all. */
    private val sections = ArrayList<SectionViews>()
    private var openSection = SettingsGuard.int(prefs, PrefsKeys.SETTINGS_SECTION, 0).coerceIn(-1, 4)
    private var sectionStyleSignature = Int.MIN_VALUE
    /** Scroll offsets are per category so a jump never loses your place. */
    private val scrollMemory = IntArray(5)
    private var landscape = false
    private var dense = SettingsGuard.bool(prefs, PrefsKeys.SETTINGS_DENSE, false)

    private lateinit var scrim: View
    private lateinit var sheet: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var jumpRail: LinearLayout
    private lateinit var densityTile: TextView
    private lateinit var statusDot: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var detailLine: TextView
    private lateinit var latencyGraph: LatencyGraph
    private lateinit var latencyStats: TextView
    private lateinit var hintLine: TextView
    private lateinit var connectBtn: TextView
    private lateinit var disconnectBtn: TextView
    private lateinit var scanBtn: TextView
    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var slotLine: TextView
    private lateinit var partyLockBtn: TextView
    private lateinit var tetherBtn: TextView
    private lateinit var scanLine: TextView
    /** Set when a scan only succeeded on a link the user did not pick. */
    private var scanFallback: String? = null
    private lateinit var btStatus: TextView
    private lateinit var btGrantBtn: TextView
    private lateinit var btHostBtn: TextView
    private lateinit var btDisconnectBtn: TextView
    private lateinit var btPairBtn: TextView
    private lateinit var btDiscoverBtn: TextView
    private lateinit var btAudioHint: TextView

    private lateinit var sensorLine: TextView
    private var touchpadHint: TextView? = null
    private var touchpadMouseToggle: View? = null
    private var touchpadModeHint: TextView? = null
    private var screenQualitySync: ((ScreenQuality) -> Unit)? = null
    private var themePreview: View? = null
    /** Separate decor overlay for SELECT HOST; must die with the settings sheet. */
    private var hostPicker: View? = null

    private var selectedTransport: Transport = link.transport
    private var scanning = false
    private var showing = false
    private var closed = false
    private var lastChipSignature = Int.MIN_VALUE
    /** Bumped on close/new scan so an in-flight discover cannot touch a dead UI. */
    private var scanGeneration = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!showing) return
            refreshLink()
            handler.postDelayed(this, if (batterySaver) 1_200L else 500L)
        }
    }

    init {
        view.isClickable = true
        build()
    }

    // ------------------------------------------------------------------ shell
    private fun build() {
        val metrics = activity.resources.displayMetrics
        landscape = metrics.widthPixels >= metrics.heightPixels
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

        addTinter {
            val t = pad.selectedTheme
            scrim.setBackgroundColor(withAlpha(0x000000, if (t.dark) 175 else 100))
            sheet.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    withAlpha(t.surface, if (t.dark) 252 else 254),
                    withAlpha(t.surfaceAlt, if (t.dark) 246 else 250),
                ),
            ).apply {
                val corner = px(24).toFloat()
                cornerRadii = floatArrayOf(
                    corner, corner,
                    0f, 0f,
                    0f, 0f,
                    corner, corner,
                )
            }
        }

        sheet.addView(buildHeader())
        sheet.addView(buildDivider())
        sheet.addView(buildJumpRail())
        sheet.addView(
            buildScroll(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        applyDensity()

        refreshTints()
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(10), px(12), px(6))
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val eyebrow = TextView(activity).apply {
            text = "CONTROL CENTRE"
            textSize = 9f
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
        }
        val author = TextView(activity).apply {
            text = "${Brand.NAME}  ·  v${Brand.VERSION}"
            textSize = 9f
            letterSpacing = 0.08f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        titles.addView(eyebrow)
        titles.addView(author)
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val setup = headerAction("SETUP", "Replay first-time setup guide") {
            dismiss()
            callbacks.onOpenSetupGuide()
        }
        val help = headerAction("?", "Help and guides") { callbacks.onOpenHelp() }
        val close = headerAction("✕", "Close settings") { dismiss() }

        for (button in listOf(setup, help, close)) {
            row.addView(
                button,
                LinearLayout.LayoutParams(
                    if (button === setup) px(38) else px(28),
                    px(32),
                ).apply { marginStart = px(4) },
            )
        }

        header.addView(row)
        addTinter {
            val t = pad.selectedTheme
            eyebrow.setTextColor(t.accent)
            author.setTextColor(t.muted)
            for (button in listOf(setup, help, close)) {
                button.setTextColor(t.textDim)
                button.background = GradientDrawable().apply {
                    cornerRadius = px(10).toFloat()
                    setColor(withAlpha(t.text, 16))
                    setStroke(px(1), withAlpha(t.text, 42))
                }
            }
        }
        header.alpha = 0f
        header.translationY = -px(8).toFloat()
        header.animate().alpha(1f).translationY(0f).setDuration(280L).start()
        return header
    }

    private fun headerAction(label: String, description: String, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            textSize = if (label.length > 2) 8.5f else 12f
            letterSpacing = 0.05f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isSingleLine = true
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { onClick() }
        }

    private fun buildDivider(): View {
        val line = View(activity)
        addTinter {
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

    // ----------------------------------------------------------- navigation
    /** One tab row replaces the old dock and the per-section headers. */
    private fun buildJumpRail(): LinearLayout {
        jumpRail = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(12), px(8), px(12), px(10))
        }
        listOf("LINK", "PAD", "BT", "STYLE", "GAMES", "DENSE").forEachIndexed { index, label ->
            val chip = TextView(activity).apply {
                text = label
                textSize = 9f
                letterSpacing = 0.08f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isSingleLine = true
                isClickable = true
                isFocusable = true
                minHeight = px(36)
                setPadding(px(2), px(5), px(2), px(5))
                setOnClickListener {
                    if (index == 5) toggleDense() else selectSection(index)
                }
                pressable(this, 0.94f)
            }
            if (index == 5) densityTile = chip
            jumpRail.addView(
                chip,
                LinearLayout.LayoutParams(0, px(36), 1f).apply {
                    if (index > 0) marginStart = px(5)
                },
            )
        }
        return jumpRail
    }

    private fun toggleDense() {
        dense = !dense
        prefs.edit().putBoolean(PrefsKeys.SETTINGS_DENSE, dense).apply()
        applyDensity()
        styleSections(force = true)
    }

    private fun applyDensity() {
        if (!::densityTile.isInitialized) return
        densityTile.text = if (dense) "FULL" else "DENSE"
        for (section in sections) applyDensityTo(section.body)
    }

    private fun applyDensityTo(view: View) {
        when (view.tag) {
            "hint" -> view.visibility = if (dense) View.GONE else View.VISIBLE
            "density-card" -> view.setPadding(
                px(if (dense) 10 else 14),
                px(if (dense) 10 else 13),
                px(if (dense) 10 else 14),
                px(if (dense) 11 else 14),
            )
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyDensityTo(view.getChildAt(index))
        }
    }

    /**
     * Control Centre keeps one scroll per category. Landscape packs independent
     * cards side by side, portrait keeps them full width. All cards are built
     * once and just hidden, so live callbacks and the RTT graph stay intact.
     */
    private fun buildScroll(): ScrollView {
        scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(10), px(14), px(40))
        }

        val groups = listOf(
            Section(
                "LINK",
                "Status · transports · doctor",
                listOf(statusCard(), connectionCard()),
            ),
            Section(
                "PAD",
                "Motion, aim and stick feel",
                listOf(motionCard(), controlsCard()),
            ),
            Section(
                "BLUETOOTH",
                "Server-less HID gamepad",
                listOf(bluetoothGamepadCard()),
            ),
            Section(
                "STYLE",
                "Theme, layouts and editor",
                listOf(appearanceCard(), studioCard(), layoutsCard()),
            ),
            Section(
                "GAMES",
                "Profiles and PC screen",
                listOf(gameProfilesCard(), screenCard()),
            ),
        )

        groups.forEach { group ->
            val rail = View(activity)
            // No per-section header: the tab row already names the category.
            // Just one quiet caption, then the cards, starting near the top.
            val body = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(6), px(2), 0, px(4))
            }
            val caption = TextView(activity).apply {
                text = group.subtitle
                textSize = 10f
                letterSpacing = 0.08f
                isSingleLine = true
                setPadding(px(2), 0, 0, px(8))
            }
            addTinter { caption.setTextColor(pad.selectedTheme.muted) }
            body.addView(caption)
            group.cards.forEach { item -> body.addView(item, cardParams()) }
            val bodyWrap = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            bodyWrap.addView(rail, LinearLayout.LayoutParams(px(3), ViewGroup.LayoutParams.MATCH_PARENT))
            bodyWrap.addView(body, weightParams(1f))
            content.addView(bodyWrap)
            sections += SectionViews(bodyWrap, rail)
        }
        content.addView(footer())
        addTinter { styleSections() }
        selectSection(openSection)

        scroll.addView(content)
        scroll.alpha = 0f
        scroll.animate().alpha(1f).setDuration(260L).setStartDelay(70L).start()
        return scroll
    }

    private fun selectSection(index: Int) {
        if (::scroll.isInitialized && openSection in scrollMemory.indices) {
            scrollMemory[openSection] = scroll.scrollY
        }
        openSection = index.coerceIn(-1, (sections.size - 1).coerceAtLeast(0))
        for (i in sections.indices) {
            sections[i].body.visibility = if (i == openSection) View.VISIBLE else View.GONE
        }
        if (::scroll.isInitialized && openSection in sections.indices) {
            sections[openSection].body.apply {
                alpha = 0.55f
                translationY = px(7).toFloat()
                animate().alpha(1f).translationY(0f)
                    .setDuration(190L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
        styleSections()
        prefs.edit().putInt(PrefsKeys.SETTINGS_SECTION, openSection).apply()
        if (::scroll.isInitialized && openSection in scrollMemory.indices) {
            scroll.post { scroll.scrollTo(0, scrollMemory[openSection]) }
        }
    }

    private fun styleSections(force: Boolean = false) {
        val t = pad.selectedTheme
        val signature = t.accent xor t.text xor openSection xor (if (dense) 1 else 0)
        if (!force && signature == sectionStyleSignature) return
        sectionStyleSignature = signature

        for (i in sections.indices) {
            sections[i].rail.background = GradientDrawable().apply {
                cornerRadius = px(2).toFloat()
                setColor(withAlpha(t.accent, if (i == openSection) 220 else 0))
            }
        }
        if (::jumpRail.isInitialized) {
            for (i in 0 until jumpRail.childCount) {
                val chip = jumpRail.getChildAt(i) as TextView
                val on = if (i == 5) dense else i == openSection
                chip.setTextColor(if (on) t.accent else t.textDim)
                chip.background = GradientDrawable().apply {
                    cornerRadius = px(10).toFloat()
                    setColor(withAlpha(if (on) t.accent else t.text, if (on) 36 else 10))
                    setStroke(px(1), withAlpha(if (on) t.accent else t.text, if (on) 130 else 24))
                }
            }
        }
    }

    // ------------------------------------------------------------------ cards
    private fun card(): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(16))
            tag = "density-card"
        }
        addTinter {
            val t = pad.selectedTheme
            card.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    withAlpha(t.surfaceAlt, if (t.dark) 214 else 244),
                    withAlpha(t.surface, if (t.dark) 188 else 236),
                ),
            ).apply {
                cornerRadius = px(17).toFloat()
                setStroke(px(1), withAlpha(t.accent, if (t.dark) 34 else 44))
            }
        }
        return card
    }

    private fun cardParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = px(12) }

    private fun statusCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("CONNECTION STATUS", "status"))

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

        // One live RTT graph for the whole status card. The old dashboard card
        // drew the same history a second time; battery saver pauses it.
        latencyGraph = LatencyGraph(activity).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = px(6) }
        }
        card.addView(latencyGraph)

        latencyStats = TextView(activity).apply {
            textSize = 10.5f
            letterSpacing = 0.03f
            setLineSpacing(px(4).toFloat(), 1f)
            setPadding(px(1), px(2), px(1), px(9))
            visibility = View.GONE
        }
        card.addView(latencyStats)

        val actions = row()
        connectBtn = action("CONNECT", primary = true) { connect() }
        val reconnectBtn = action("RECONNECT", primary = false) { reconnect() }
        disconnectBtn = action("DISCONNECT", primary = false) { disconnect() }
        actions.addView(connectBtn, weightParams(1.15f, end = px(5)))
        actions.addView(reconnectBtn, weightParams(1f, end = px(5)))
        actions.addView(disconnectBtn, weightParams(1f))
        card.addView(actions)

        val utilities = row()
        utilities.addView(
            toggle("Battery saver · live stats", batterySaver) { on ->
                batterySaver = on
                prefs.edit().putBoolean(PrefsKeys.BATTERY_SAVER, on).apply()
                refreshLink()
            },
            weightParams(1f, end = px(5)),
        )
        utilities.addView(
            action("CONNECTION DOCTOR", primary = false) {
                dismiss()
                callbacks.onOpenConnectionDoctor()
            },
            wrapParams(),
        )
        card.addView(utilities)
        card.addView(
            hint(
                "Battery saver pauses the live graph; connection state and buttons keep working.",
                top = px(2),
                bottom = px(2),
            ),
        )

        addTinter {
            val t = pad.selectedTheme
            statusSub.setTextColor(t.muted)
            detailLine.setTextColor(t.textDim)
            latencyStats.setTextColor(t.textDim)
            if (!batterySaver) {
                latencyGraph.setSamples(link.rttHistory(), t.accent, t.muted)
            }
        }
        return card
    }

    private fun connectionCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("CONNECTION", "connection"))

        val chips = row()
        for (tr in Transport.values()) {
            val chipView = chip(tr.label) {
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

        // USB tethering is off by default on most phones and the toggle is
        // buried several menus deep, so offer a shortcut.
        tetherBtn = action("USB TETHERING SETTINGS", primary = false) { openTetherSettings() }
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

        // Player slot: ask the PC for a specific pad instead of taking the
        // next free one. AUTO keeps the old behaviour. The request is sent
        // with every input packet, so it also moves an already-connected phone.
        card.addView(tiny("PLAYER SLOT", top = px(10), bottom = px(5)))
        slotViews.clear()
        val slotsTop = row()
        val slotsBottom = row()

        fun addSlot(slot: Int, container: LinearLayout, weight: Float, end: Int) {
            val label = if (slot == 0) "AUTO" else "P$slot"
            val view = chip(label) { chooseSlot(slot) }
            slotViews += slot to view
            container.addView(view, weightParams(weight, end = end))
        }

        addSlot(0, slotsTop, 1.4f, px(4))
        addSlot(1, slotsTop, 1f, px(4))
        addSlot(2, slotsTop, 1f, px(4))
        addSlot(3, slotsTop, 1f, px(4))
        addSlot(4, slotsTop, 1f, 0)
        slotsBottom.addView(View(activity), weightParams(1.4f, end = px(4)))
        addSlot(5, slotsBottom, 1f, px(4))
        addSlot(6, slotsBottom, 1f, px(4))
        addSlot(7, slotsBottom, 1f, px(4))
        addSlot(8, slotsBottom, 1f, 0)
        card.addView(slotsTop)
        card.addView(slotsBottom, topParams(px(4)))

        slotLine = TextView(activity).apply {
            textSize = 10f
            letterSpacing = .03f
            setLineSpacing(px(2).toFloat(), 1f)
            setPadding(px(2), px(6), px(2), 0)
        }
        card.addView(slotLine)

        // Host-only seat lock. Guests can still claim a free pad (so a late
        // arrival joins correctly) but cannot move or swap occupied pads.
        partyLockBtn = action("LOCK PARTY", primary = false) {
            // PONG carries the confirmed lock bit; refreshLink() is called from
            // onPartyState so the label cannot claim a state the PC rejected.
            link.setPartyLock(!link.partyLocked)
        }
        partyLockBtn.visibility = View.GONE
        card.addView(partyLockBtn, topParams(px(6)))

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

        addTinter {
            val t = pad.selectedTheme
            hintLine.setTextColor(t.muted)
            slotLine.setTextColor(t.muted)
            scanLine.setTextColor(t.muted)
        }
        return card
    }

    private fun bluetoothGamepadCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("BLUETOOTH GAMEPAD", "bluetooth"))
        card.addView(
            hint(
                "Pair “Lemo GamePad” once from your PC or Android host. Opening this app never " +
                    "connects by itself — use SELECT HOST to choose one bonded computer or phone. " +
                    "It appears as a standard gamepad, with no app, driver or tethering on the host.",
                bottom = px(6),
            ),
        )
        btStatus = TextView(activity).apply {
            textSize = 11f
            setLineSpacing(px(3).toFloat(), 1f)
            setPadding(px(2), 0, px(2), px(9))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(btStatus)

        val actions = row()
        btHostBtn = action("SELECT HOST", primary = true) { showBluetoothHostPicker() }
        btDisconnectBtn = action("DISCONNECT", primary = false) {
            callbacks.onBluetoothDisconnect()
        }
        actions.addView(btHostBtn, weightParams(1f, end = px(5)))
        actions.addView(btDisconnectBtn, weightParams(1f))
        card.addView(actions)

        val setup = row().apply { setPadding(0, px(6), 0, 0) }
        btPairBtn = action("BLUETOOTH SETTINGS", primary = false) { callbacks.onBluetoothPair() }
        btDiscoverBtn = action("MAKE DISCOVERABLE", primary = false) {
            if (callbacks.bluetoothGamepadConnected()) {
                callbacks.onBluetoothHostAudioSettings()
            } else {
                callbacks.onBluetoothDiscoverable()
            }
        }
        setup.addView(btPairBtn, weightParams(1f, end = px(5)))
        setup.addView(btDiscoverBtn, weightParams(1f))
        card.addView(setup)

        btAudioHint = tiny("", top = px(4))
        btAudioHint.visibility = View.GONE
        card.addView(btAudioHint)

        btGrantBtn = action("ALLOW NEARBY DEVICES", primary = true) {
            callbacks.requestBluetoothPermissions()
        }
        btGrantBtn.visibility = View.GONE
        card.addView(btGrantBtn, topParams(px(6)))

        addTinter {
            val t = pad.selectedTheme
            btStatus.setTextColor(t.muted)
        }
        return card
    }

    /** Swipe pad, tilt steering and gyro aim on the single Gamepad layout. */
    private fun motionCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("MOTION & AIM", "motion"))
        sensorLine = tiny(pad.sensorStatus.ifBlank { "Motion sensors ready" }, bottom = px(4))
        card.addView(sensorLine)

        card.addView(
            toggle("Aim touchpad", prefs.getBoolean(PrefsKeys.TOUCHPAD_ENABLED, false)) { on ->
                callbacks.onTouchpadChanged(on)
                refreshTouchpadMode()
            },
        )
        val hint = tiny("", top = px(2))
        touchpadHint = hint
        card.addView(hint)
        val mouseToggle = toggle(
            "Mouse aim",
            prefs.getBoolean(PrefsKeys.TOUCHPAD_MOUSE, false),
        ) { on ->
            pad.touchpadMouseMode = on
            prefs.edit().putBoolean(PrefsKeys.TOUCHPAD_MOUSE, on).apply()
            refreshTouchpadMode()
        }
        touchpadMouseToggle = mouseToggle
        card.addView(mouseToggle, topParams(px(4)))
        val modeHint = tiny("", top = px(2), bottom = px(2))
        touchpadModeHint = modeHint
        card.addView(modeHint)
        card.addView(
            seekRow("TOUCHPAD SENSITIVITY", 0, 100, PrefsKeys.TOUCHPAD_SENS, (pad.touchpadSensitivity * 100f).roundToInt()) {
                pad.touchpadSensitivity = it / 100f
            },
        )
        refreshTouchpadMode()

        card.addView(
            toggle("Tilt steering", prefs.getBoolean(PrefsKeys.GYRO_STEER, false)) { on ->
                pad.gyroSteering = on
                prefs.edit().putBoolean(PrefsKeys.GYRO_STEER, on).apply()
            },
            topParams(px(8)),
        )
        card.addView(
            hint(
                "Tilt steers racing games on left X. Level is straight ahead; the left stick still works at centre.",
                top = px(2),
            ),
        )
        card.addView(
            seekRow("TILT SENSITIVITY", 0, 100, PrefsKeys.STEER_SENS, (pad.steerSensitivity * 100f).roundToInt()) {
                pad.steerSensitivity = it / 100f
            },
        )
        card.addView(
            seekRow("STEER DEAD ZONE", 0, 40, PrefsKeys.STEER_DEADZONE, (pad.steerDeadzone * 100f).roundToInt()) {
                pad.steerDeadzone = it / 100f
            },
        )
        card.addView(
            seekRow("STEER CURVE", 0, 100, PrefsKeys.STEER_CURVE, (pad.steerCurve * 100f).roundToInt()) {
                pad.steerCurve = it / 100f
            },
        )
        card.addView(
            action("CALIBRATE CENTRE", primary = false) {
                pad.calibrateTilt()
                sensorLine.text = pad.sensorStatus.ifBlank { "Sensors ready" }
            },
            topParams(px(8)),
        )
        card.addView(
            hint("Sit in your driving position, then calibrate so level means straight ahead.", top = px(2)),
        )

        card.addView(
            toggle("Gyro aim", prefs.getBoolean(PrefsKeys.GYRO_AIM, false)) { on ->
                pad.gyroAim = on
                prefs.edit().putBoolean(PrefsKeys.GYRO_AIM, on).apply()
            },
            topParams(px(8)),
        )
        card.addView(
            hint(
                "Rotating the phone moves right-stick aim in shooters. Works with or without the swipe pad.",
                top = px(2),
            ),
        )
        card.addView(
            toggle("Revert left/right aim", prefs.getBoolean(PrefsKeys.GYRO_REVERT_X, false)) { on ->
                pad.gyroRevertX = on
                prefs.edit().putBoolean(PrefsKeys.GYRO_REVERT_X, on).apply()
            },
            topParams(px(4)),
        )
        card.addView(
            toggle("Revert up/down aim", prefs.getBoolean(PrefsKeys.GYRO_REVERT_Y, false)) { on ->
                pad.gyroRevertY = on
                prefs.edit().putBoolean(PrefsKeys.GYRO_REVERT_Y, on).apply()
            },
            topParams(px(2)),
        )
        card.addView(
            hint("Each revert flips only that gyro axis. Turn on both to reverse both directions.", top = px(2)),
        )
        card.addView(
            seekRow("GYRO AIM SENSITIVITY", 0, 100, PrefsKeys.AIM_SENS, (pad.aimSensitivity * 100f).roundToInt()) {
                pad.aimSensitivity = it / 100f
            },
        )
        card.addView(
            action("AIM CALIBRATION", primary = false) { callbacks.onAimTrainer() },
            topParams(px(8)),
        )
        card.addView(
            hint("Ten-target drill for touch and gyro aim. Drag to move the reticle, then tap to fire. Controller output stays neutral while the overlay is open.", top = px(2)),
        )
        return card
    }

    /** Shows the mouse-mode choice only while the aim touchpad is enabled. */
    internal fun refreshTouchpadMode() {
        val padOn = prefs.getBoolean(PrefsKeys.TOUCHPAD_ENABLED, false)
        val mouse = prefs.getBoolean(PrefsKeys.TOUCHPAD_MOUSE, false)
        val visibility = if (padOn) View.VISIBLE else View.GONE
        touchpadHint?.visibility = visibility
        touchpadMouseToggle?.visibility = visibility
        touchpadModeHint?.visibility = visibility
        touchpadHint?.text = if (mouse) {
            "Replaces the right stick and R3. Move the pad like a game mouse; gyro can aim at the same time."
        } else {
            "Replaces the right stick with a BGMI-style swipe pad. Swipe to aim; gyro can aim at the same time."
        }
        touchpadModeHint?.text = if (mouse) {
            "Relative mouse aim: finger speed turns the camera, and stopping your finger stops the aim."
        } else {
            "Swipe mode: each sweep swings the camera, then the aim settles back to centre."
        }
    }

    /** Keeps the LOW/BALANCED/HIGH chips in step with automatic changes. */
    internal fun syncScreenQuality(quality: ScreenQuality) {
        screenQualitySync?.invoke(quality)
    }

    /** Live visual tuning: opacity and face-button spacing. */
    private fun studioCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("CUSTOMIZATION STUDIO", "studio"))
        card.addView(tiny("LIVE PREVIEW — THE PAD BEHIND THIS SHEET UPDATES AS YOU DRAG", bottom = px(6)))
        card.addView(
            seekRow("CONTROL OPACITY", 35, 100, PrefsKeys.PAD_ALPHA, (pad.controlAlpha * 100f).roundToInt()) {
                pad.controlAlpha = it / 100f
            },
        )
        card.addView(
            seekRow("FACE BUTTON SPACING", 75, 160, PrefsKeys.FACE_SPACING, (pad.faceSpacing * 100f).roundToInt()) {
                pad.faceSpacing = it / 100f
            },
        )
        card.addView(
            hint("EDIT LAYOUT lets you drag, pinch-resize and hide controls. The swipe pad resizes like the sticks.", top = px(2)),
        )
        card.addView(
            action("EDIT LAYOUT", primary = true) { callbacks.onEditLayout() },
            topParams(px(8)),
        )
        return card
    }

    /**
     * Themed slider row used by the motion and studio sections. [current] is
     * the real value; the bar is offset by [minValue] so 35..100 maps to 0..65.
     */
    private fun seekRow(
        label: String,
        minValue: Int,
        maxValue: Int,
        key: String,
        current: Int,
        onChange: (Int) -> Unit,
    ): LinearLayout {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val span = (maxValue - minValue).coerceAtLeast(1)
        val safe = current.coerceIn(minValue, maxValue)
        val title = tiny("$label  ·  $safe%", top = px(10), bottom = px(2))
        val bar = SeekBar(activity).apply {
            max = span
            progress = safe - minValue
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    val actual = minValue + value
                    title.text = "$label  ·  $actual%"
                    onChange(actual)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    prefs.edit().putInt(key, minValue + (seekBar?.progress ?: 0)).apply()
                }
            })
        }
        addTinter {
            val t = pad.selectedTheme
            bar.progressTintList = ColorStateList.valueOf(t.accent)
            bar.thumbTintList = ColorStateList.valueOf(t.accent)
            bar.progressBackgroundTintList = ColorStateList.valueOf(withAlpha(t.text, 30))
        }
        box.addView(title)
        box.addView(bar)
        return box
    }

    /**
     * Shows a palette on a mock controller before committing it. Cancelling
     * leaves the live pad exactly as it was.
     */
    private fun previewTheme(th: PadTheme) {
        closePreview(instant = true)
        val t = pad.selectedTheme
        // The settings sheet sits at 18dp elevation. Lift the preview above it
        // so the whole card — including the right half of APPLY — stays visible
        // and tappable where it overlaps the sheet in landscape.
        val overlay = FrameLayout(activity).apply {
            isClickable = true
            elevation = px(40).toFloat()
        }

        val dim = View(activity).apply {
            setBackgroundColor(withAlpha(0x000000, if (t.dark) 170 else 110))
            setOnClickListener { closePreview() }
        }
        overlay.addView(
            dim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val cardView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(13), px(13), px(13), px(12))
            background = GradientDrawable().apply {
                cornerRadius = px(20).toFloat()
                setColor(withAlpha(th.surface, 253))
                setStroke(px(1), withAlpha(th.accent, 110))
            }
            isClickable = true
        }

        val preview = ThemePreview(activity).apply {
            theme = th
            faceLabels = pad.faceLabels
        }
        cardView.addView(
            preview,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(140)).apply {
                bottomMargin = px(11)
            },
        )

        cardView.addView(
            TextView(activity).apply {
                text = th.name
                textSize = 16f
                letterSpacing = 0.03f
                setTextColor(th.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            },
        )
        cardView.addView(
            TextView(activity).apply {
                text = th.mood.ifBlank { "Preview" }
                textSize = 11f
                setTextColor(th.textDim)
                setPadding(0, px(3), 0, px(9))
            },
        )

        val dots = row()
        dots.gravity = Gravity.CENTER_VERTICAL
        for (i in 0 until 4) {
            dots.addView(
                View(activity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Iconic.face(pad.faceLabels, i))
                    }
                },
                LinearLayout.LayoutParams(px(17), px(17)).apply { marginEnd = px(7) },
            )
        }
        dots.addView(
            TextView(activity).apply {
                text = if (pad.faceLabels == FaceLabels.XBOX) {
                    "A · B · X · Y KEEP THEIR COLOURS"
                } else {
                    "CROSS · CIRCLE · SQUARE · TRIANGLE KEEP THEIR COLOURS"
                }
                textSize = 8.5f
                letterSpacing = 0.08f
                setTextColor(th.muted)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        cardView.addView(dots)

        val actions = row()
        actions.addView(
            action("CANCEL", primary = false) { closePreview() },
            weightParams(1f, end = px(6)),
        )
        actions.addView(
            action("APPLY THEME", primary = true) {
                pad.theme = th
                prefs.edit().putString(PrefsKeys.THEME, th.id).apply()
                callbacks.onThemeChanged(th)
                closePreview()
                refreshTints()
                callbacks.toast("${th.name} applied")
            },
            weightParams(1f),
        )
        cardView.addView(actions, topParams(px(12)))

        val metrics = activity.resources.displayMetrics
        overlay.addView(
            cardView,
            FrameLayout.LayoutParams(
                minOf(px(400), (metrics.widthPixels * 0.86f).toInt()),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        view.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        themePreview = overlay
        overlay.alpha = 0f
        cardView.scaleX = 0.95f
        cardView.scaleY = 0.95f
        overlay.animate().alpha(1f).setDuration(150L).start()
        cardView.animate().scaleX(1f).scaleY(1f)
            .setDuration(210L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun closePreview(instant: Boolean = false) {
        val overlay = themePreview ?: return
        themePreview = null
        if (instant) {
            view.removeView(overlay)
            return
        }
        overlay.animate().alpha(0f).setDuration(130L)
            .withEndAction { view.removeView(overlay) }
            .start()
    }

    private fun appearanceCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("APPEARANCE", "appearance"))
        card.addView(tiny("THEME  ·  TAP A PALETTE TO PREVIEW IT", bottom = px(8)))

        val swatches = ArrayList<Triple<PadTheme, LinearLayout, TextView>>()

        fun styleSwatches() {
            val selected = pad.selectedTheme
            for ((th, box, name) in swatches) {
                val on = th.id == selected.id
                box.background = GradientDrawable().apply {
                    cornerRadius = px(13).toFloat()
                    setColor(
                        if (on) {
                            withAlpha(selected.accent, if (selected.dark) 46 else 32)
                        } else {
                            withAlpha(selected.surfaceAlt, if (selected.dark) 150 else 190)
                        },
                    )
                    setStroke(
                        px(if (on) 2 else 1),
                        if (on) selected.accent else withAlpha(selected.text, 26),
                    )
                }
                name.setTextColor(if (on) selected.text else selected.textDim)
            }
        }

        fun swatch(th: PadTheme): LinearLayout {
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(7), px(7), px(7), px(7))
                isClickable = true
                isFocusable = true
            }

            // Three-colour preview so every palette reads at a glance: the pad
            // background gradient plus both accent colours.
            val preview = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val backdrop = View(activity).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(th.bgTop, th.bgMid, th.bgBottom),
                ).apply { cornerRadius = px(7).toFloat() }
            }
            preview.addView(backdrop, LinearLayout.LayoutParams(0, px(30), 1f))
            for (accentColor in intArrayOf(th.accent, th.accent2)) {
                val bar = View(activity).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = px(7).toFloat()
                        setColor(accentColor)
                    }
                }
                preview.addView(
                    bar,
                    LinearLayout.LayoutParams(px(9), px(30)).apply { marginStart = px(4) },
                )
            }
            box.addView(preview)

            val name = TextView(activity).apply {
                text = th.name
                textSize = 10.5f
                letterSpacing = 0.08f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(px(1), px(6), px(1), 0)
                isSingleLine = true
            }
            box.addView(name)

            box.setOnClickListener {
                previewTheme(th)
            }
            pressable(box)
            swatches += Triple(th, box, name)
            return box
        }

        // Two columns, any number of themes: the picker never needs editing
        // again when a palette is added to Themes.all.
        for (chunk in Themes.all.chunked(2)) {
            val rowBox = row()
            for (th in chunk) {
                rowBox.addView(swatch(th), weightParams(1f, end = px(6), bottom = px(6)))
            }
            if (chunk.size == 1) rowBox.addView(View(activity), weightParams(1f))
            card.addView(rowBox)
        }
        addTinter { styleSwatches() }
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
        addTinter { styleFaces() }
        styleFaces()

        card.addView(
            toggle("Animated buttons", pad.motionEnabled) { on ->
                pad.motionEnabled = on
                prefs.edit().putBoolean(PrefsKeys.MOTION, on).apply()
            },
            topParams(px(8)),
        )
        card.addView(hint("Each theme has its own press ripple on the pad.", top = px(2)))

        return card
    }

    private fun controlsCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("CONTROLS", "controls"))

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
        addTinter {
            val t = pad.selectedTheme
            bar.progressTintList = ColorStateList.valueOf(t.accent)
            bar.thumbTintList = ColorStateList.valueOf(t.accent)
            bar.progressBackgroundTintList = ColorStateList.valueOf(withAlpha(t.text, 30))
        }
        card.addView(bar)

        val curveLabel = tiny(
            "STICK CURVE  ·  ${(pad.stickCurve * 100f).roundToInt()}%",
            top = px(12),
            bottom = px(2),
        )
        card.addView(curveLabel)
        val curveBar = SeekBar(activity).apply {
            max = 100
            progress = (pad.stickCurve * 100f).roundToInt().coerceIn(0, 100)
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    pad.stickCurve = value / 100f
                    curveLabel.text = "STICK CURVE  ·  $value%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    prefs.edit()
                        .putInt(PrefsKeys.STICK_CURVE, (pad.stickCurve * 100f).roundToInt())
                        .apply()
                }
            })
        }
        addTinter {
            val t = pad.selectedTheme
            curveBar.progressTintList = ColorStateList.valueOf(t.accent)
            curveBar.thumbTintList = ColorStateList.valueOf(t.accent)
            curveBar.progressBackgroundTintList = ColorStateList.valueOf(withAlpha(t.text, 30))
        }
        card.addView(curveBar)
        card.addView(hint("0% is linear · higher values calm small stick corrections", top = px(2)))

        card.addView(
            toggle("Vibration feedback", prefs.getBoolean(PrefsKeys.HAPTICS, true)) { on ->
                pad.hapticsEnabled = on
                prefs.edit().putBoolean(PrefsKeys.HAPTICS, on).apply()
            },
            topParams(px(8)),
        )
        card.addView(
            toggle("Vibration from games", prefs.getBoolean(PrefsKeys.GAME_RUMBLE, true)) { on ->
                prefs.edit().putBoolean(PrefsKeys.GAME_RUMBLE, on).apply()
                callbacks.onGameRumbleChanged(on)
            },
            topParams(px(4)),
        )
        card.addView(
            hint("Keeps button clicks; only silences force feedback sent by the game.", top = px(2)),
        )

        var strength = prefs.getInt(PrefsKeys.HAPTIC_STRENGTH, 100).coerceIn(0, 100)
        val strengthLabel = tiny("VIBRATION STRENGTH  ·  $strength%", top = px(10), bottom = px(2))
        card.addView(strengthLabel)
        val strengthBar = SeekBar(activity).apply {
            max = 100
            progress = strength
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    strength = value
                    strengthLabel.text = "VIBRATION STRENGTH  ·  $value%"
                    callbacks.onHapticsChanged(value / 100f)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    prefs.edit().putInt(PrefsKeys.HAPTIC_STRENGTH, strength).apply()
                }
            })
        }
        addTinter {
            val t = pad.selectedTheme
            strengthBar.progressTintList = ColorStateList.valueOf(t.accent)
            strengthBar.thumbTintList = ColorStateList.valueOf(t.accent)
            strengthBar.progressBackgroundTintList = ColorStateList.valueOf(withAlpha(t.text, 30))
        }
        card.addView(strengthBar)

        card.addView(tiny("INPUT RATE", top = px(12), bottom = px(5)))
        val rateRow = row()
        val rateChips = LinkedHashMap<InputRate, TextView>()
        val rateHint = hint(link.inputRate.hint, top = px(5))

        fun refreshRateChips() {
            val t = pad.selectedTheme
            for ((value, chipView) in rateChips) {
                val on = link.inputRate == value
                chipView.setTextColor(if (on) onColor(t.accent) else t.textDim)
                chipView.background = GradientDrawable().apply {
                    cornerRadius = px(10).toFloat()
                    setColor(if (on) t.accent else withAlpha(t.surfaceAlt, 235))
                    setStroke(px(1), withAlpha(if (on) t.accent else t.text, if (on) 255 else 34))
                }
            }
        }

        for ((index, value) in InputRate.values().withIndex()) {
            val chipView = chip(value.label) {
                link.inputRate = value
                prefs.edit().putString(PrefsKeys.INPUT_RATE, value.id).apply()
                rateHint.text = value.hint
                refreshRateChips()
            }
            rateChips[value] = chipView
            rateRow.addView(chipView, weightParams(1f, end = if (index < 2) px(4) else 0))
        }
        card.addView(rateRow)
        card.addView(rateHint)
        addTinter { refreshRateChips() }
        refreshRateChips()

        return card
    }

    /**
     * Named layout presets. "DEFAULT" restores the built-in arrangement,
     * SAVE AS copies the current pad under a name, and tapping a saved name
     * switches to it immediately. Editing while a name is active updates that
     * preset in place, so the on-screen arrangement and its saved copy never
     * drift apart.
     */
    private fun layoutsCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("LAYOUTS", "layouts"))

        val listHost = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val summary = tiny("", top = px(7), bottom = 0)

        fun styleChip(view: TextView, selected: Boolean) {
            val t = pad.selectedTheme
            view.setTextColor(if (selected) onColor(t.accent) else t.text)
            view.background = GradientDrawable().apply {
                cornerRadius = px(10).toFloat()
                setColor(if (selected) t.accent else withAlpha(t.surfaceAlt, 235))
                setStroke(
                    px(1),
                    withAlpha(if (selected) t.accent else t.text, if (selected) 255 else 34),
                )
            }
        }

        fun styleDelete(view: TextView) {
            val t = pad.selectedTheme
            view.setTextColor(t.muted)
            view.background = GradientDrawable().apply {
                cornerRadius = px(10).toFloat()
                setColor(withAlpha(t.surfaceAlt, 235))
                setStroke(px(1), withAlpha(t.text, 30))
            }
        }

        fun refresh() {
            listHost.removeAllViews()
            val active = layoutStore().activeName()
            val saved = layoutStore().saved()

            fun addRow(name: String?, label: String, deletable: Boolean) {
                val line = row()
                val nameView = chip(label) {
                    when {
                        name == null -> {
                            pad.resetLayout()
                            layoutStore().clearActive()
                            callbacks.onLayoutSaved()
                            refresh()
                            callbacks.toast("Default layout restored")
                        }

                        layoutStore().activate(name) -> {
                            pad.applyLayout(layoutStore().activeData())
                            callbacks.onLayoutSaved()
                            refresh()
                            callbacks.toast("Layout: $name")
                        }

                        else -> {
                            refresh()
                            callbacks.toast("That layout no longer exists")
                        }
                    }
                }
                styleChip(nameView, name != null && name == active)
                line.addView(nameView, weightParams(1f, end = if (deletable) px(6) else 0))
                if (deletable) {
                    val target = name ?: label
                    val delete = TextView(activity).apply {
                        text = "DELETE"
                        textSize = 9.5f
                        letterSpacing = 0.09f
                        gravity = Gravity.CENTER
                        setPadding(px(10), px(8), px(10), px(8))
                        isClickable = true
                        isFocusable = true
                        contentDescription = "Delete layout $target"
                        setOnClickListener {
                            LayoutDialogs.confirm(
                                activity,
                                pad.selectedTheme,
                                title = "DELETE LAYOUT",
                                message = "Delete \"$target\"? The current pad keeps its " +
                                    "positions but is no longer saved under that name.",
                                positive = "DELETE",
                            ) {
                                layoutStore().remove(target)
                                refresh()
                                callbacks.toast("Layout deleted")
                            }
                        }
                        pressable(this)
                    }
                    styleDelete(delete)
                    line.addView(delete, wrapParams())
                }
                listHost.addView(line, topParams(px(5)))
            }

            addRow(null, "DEFAULT", false)
            for (entry in saved) addRow(entry.name, entry.name, true)
            summary.text = when {
                active != null -> "Current: $active"
                else -> "Current: unsaved arrangement"
            }
        }

        val actions = row()
        actions.addView(
            action("CUSTOMIZE", primary = false) { callbacks.onEditLayout() },
            weightParams(1f, end = px(6)),
        )
        actions.addView(
            action("SAVE AS…", primary = true) {
                LayoutDialogs.promptName(
                    activity,
                    pad.selectedTheme,
                    title = "SAVE LAYOUT",
                    initial = "",
                    existing = layoutStore().saved().map { it.name },
                ) { name ->
                    val replaced = layoutStore().save(name, pad.layoutSnapshot())
                    callbacks.onLayoutSaved()
                    refresh()
                    callbacks.toast(
                        if (replaced) {
                            "Layout \"$name\" updated"
                        } else {
                            "Layout \"$name\" saved"
                        },
                    )
                }
            },
            weightParams(1f),
        )
        card.addView(actions)

        val share = row()
        share.addView(
            action("EXPORT", primary = false) {
                val clip = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                if (clip == null) {
                    callbacks.toast("Clipboard unavailable")
                } else {
                    clip.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "lemoGamePad GAMEPAD layout",
                            pad.layoutSnapshot(),
                        ),
                    )
                    callbacks.toast("GAMEPAD layout copied")
                }
            },
            weightParams(1f, end = px(6)),
        )
        share.addView(
            action("IMPORT", primary = false) {
                val clip = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                val data = clip?.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(activity)
                    ?.toString()
                if (data.isNullOrBlank()) {
                    callbacks.toast("Copy a layout text first")
                } else if (!data.contains(':')) {
                    callbacks.toast("That is not a layout")
                } else {
                    pad.applyLayout(data)
                    callbacks.onLayoutSaved()
                    refresh()
                    callbacks.toast("Layout imported")
                }
            },
            weightParams(1f),
        )
        card.addView(share, topParams(px(6)))

        val qr = row()
        qr.addView(
            action("SHARE QR", primary = false) {
                val name = layoutStore().activeName() ?: "SHARED"
                val data = layoutStore().activeData()?.takeIf { it.isNotBlank() }
                    ?: pad.layoutSnapshot()
                LayoutDialogs.showQr(
                    activity,
                    pad.selectedTheme,
                    "LAYOUT QR",
                    LayoutQr.encode(name, data),
                )
            },
            weightParams(1f, end = px(6)),
        )
        qr.addView(
            action("SCAN QR", primary = false) { callbacks.onScanLayoutQr() },
            weightParams(1f),
        )
        card.addView(qr, topParams(px(6)))

        card.addView(
            hint(
                "Every control's position, size and visibility is remembered. " +
                    "Tap a saved layout to switch. SHARE QR / SCAN QR move a layout " +
                    "between phones; the browser pad uses the same code as text.",
                top = px(6),
            ),
        )
        card.addView(listHost)
        card.addView(summary)

        // Rebuild the list when the theme changes so the chips pick up the
        // new palette, without accumulating a tinter per row.
        addTinter { refresh() }
        refresh()
        return card
    }

    /** The single Gamepad layout owns all saved presets. */
    private fun layoutStore(): LayoutStore = LayoutStore(prefs)

    /**
     * Manual game profiles: tap to switch a whole control setup, save the
     * current one under a name, or delete one. Nothing here reacts to the
     * foreground app — switching is always an explicit choice.
     */
    private fun gameProfilesCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("GAME PROFILES", "profiles"))

        val listHost = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val summary = tiny("", top = px(7), bottom = 0)

        fun styleChip(view: TextView, selected: Boolean) {
            val t = pad.selectedTheme
            view.setTextColor(if (selected) onColor(t.accent) else t.text)
            view.background = GradientDrawable().apply {
                cornerRadius = px(10).toFloat()
                setColor(if (selected) t.accent else withAlpha(t.surfaceAlt, 235))
                setStroke(
                    px(1),
                    withAlpha(if (selected) t.accent else t.text, if (selected) 255 else 34),
                )
            }
        }

        fun styleDelete(view: TextView) {
            val t = pad.selectedTheme
            view.setTextColor(t.muted)
            view.background = GradientDrawable().apply {
                cornerRadius = px(10).toFloat()
                setColor(withAlpha(t.surfaceAlt, 235))
                setStroke(px(1), withAlpha(t.text, 30))
            }
        }

        fun refresh() {
            listHost.removeAllViews()
            val store = profileStore()
            val active = store.activeName()
            val saved = store.saved()
            if (saved.isEmpty()) {
                listHost.addView(tiny("No profiles yet — save your current setup.", top = px(6)))
            }
            for (entry in saved) {
                val line = row()
                val nameView = chip(entry.name) {
                    LayoutDialogs.confirm(
                        activity,
                        pad.selectedTheme,
                        title = "APPLY PROFILE",
                        message = "Apply \"${entry.name}\"? This replaces the current aim, " +
                            "motion, haptics and layout settings. Connection settings stay as they are.",
                        positive = "APPLY",
                    ) {
                        callbacks.applyGameProfile(entry.name)
                        refresh()
                        callbacks.toast("Profile: ${entry.name}")
                    }
                }
                styleChip(nameView, entry.name == active)
                line.addView(nameView, weightParams(1f, end = px(5)))

                val duplicate = TextView(activity).apply {
                    text = "COPY"
                    textSize = 9.5f
                    letterSpacing = 0.09f
                    gravity = Gravity.CENTER
                    setPadding(px(10), px(8), px(10), px(8))
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Duplicate profile ${entry.name}"
                    setOnClickListener {
                        val copyName = profileStore().duplicate(entry.name)
                        refresh()
                        callbacks.toast(
                            if (copyName == null) "Could not copy that profile" else "Copied to \"$copyName\"",
                        )
                    }
                    pressable(this)
                }
                styleDelete(duplicate)
                line.addView(duplicate, wrapParams().apply { marginEnd = px(5) })

                val delete = TextView(activity).apply {
                    text = "DELETE"
                    textSize = 9.5f
                    letterSpacing = 0.09f
                    gravity = Gravity.CENTER
                    setPadding(px(10), px(8), px(10), px(8))
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Delete profile ${entry.name}"
                    setOnClickListener {
                        LayoutDialogs.confirm(
                            activity,
                            pad.selectedTheme,
                            title = "DELETE PROFILE",
                            message = "Delete \"${entry.name}\"? The current pad settings stay as they are.",
                            positive = "DELETE",
                        ) {
                            callbacks.deleteGameProfile(entry.name)
                            refresh()
                            callbacks.toast("Profile deleted")
                        }
                    }
                    pressable(this)
                }
                styleDelete(delete)
                line.addView(delete, wrapParams())
                listHost.addView(line, topParams(px(5)))
            }
            summary.text = when {
                active != null -> "Current: $active"
                saved.isEmpty() -> "Current: no saved profiles"
                else -> "Current: no profile applied"
            }
        }

        val actions = row()
        actions.addView(
            action("SAVE CURRENT", primary = true) {
                val store = profileStore()
                LayoutDialogs.promptName(
                    activity,
                    pad.selectedTheme,
                    title = "SAVE GAME PROFILE",
                    initial = "",
                    existing = store.saved().map { it.name },
                ) { name ->
                    val replaced = store.saved().any { it.name.equals(name, ignoreCase = true) }
                    callbacks.saveGameProfile(name)
                    refresh()
                    callbacks.toast(
                        if (replaced) "Profile \"$name\" updated" else "Profile \"$name\" saved",
                    )
                }
            },
            weightParams(1f, end = px(6)),
        )
        actions.addView(
            action("CLEAR", primary = false) {
                profileStore().clearActive()
                refresh()
                callbacks.toast("Profile switch cleared")
            },
            weightParams(1f),
        )
        card.addView(actions)

        val backup = row()
        backup.addView(
            action("EXPORT", primary = false) {
                val json = profileStore().exportAll()
                if (json == "[]") {
                    callbacks.toast("No profiles to export")
                } else {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard == null) {
                        callbacks.toast("Clipboard unavailable")
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Game profiles", json))
                        callbacks.toast("Profile backup copied")
                    }
                }
            },
            weightParams(1f, end = px(6)),
        )
        backup.addView(
            action("IMPORT", primary = false) {
                LayoutDialogs.promptText(
                    activity,
                    pad.selectedTheme,
                    title = "IMPORT PROFILES",
                    message = "Paste a profile backup. Names already in use are skipped, so current profiles are never replaced.",
                    positiveLabel = "IMPORT",
                ) { raw ->
                    val result = profileStore().import(raw)
                    refresh()
                    callbacks.toast(
                        if (result.added == 0) {
                            "No new profiles (${result.skipped} skipped)"
                        } else {
                            "Imported ${result.added} profile${if (result.added == 1) "" else "s"}" +
                                if (result.skipped > 0) " · ${result.skipped} skipped" else ""
                        },
                    )
                }
            },
            weightParams(1f),
        )
        card.addView(backup, topParams(px(6)))
        card.addView(
            hint(
                "A profile saves aim, motion, haptics, input rate, face labels and the current layout. " +
                    "Switch only by tapping a name; COPY, EXPORT and IMPORT never change the link.",
                top = px(6),
            ),
        )
        card.addView(listHost)
        card.addView(summary)

        // Rebuild the rows on theme changes with one tinter, not one per row.
        addTinter { refresh() }
        refresh()
        return card
    }

    private fun profileStore(): GameProfileStore = GameProfileStore(prefs)

    private fun screenCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("PC SCREEN", "screen"))

        var enabled = prefs.getBoolean(PrefsKeys.SCREEN, false)
        var small = prefs.getBoolean(PrefsKeys.SCREEN_SMALL, true)
        var quality = ScreenQuality.fromId(
            prefs.getInt(PrefsKeys.SCREEN_QUALITY, ScreenQuality.BALANCED.wireId),
        )
        var adaptive = prefs.getBoolean(PrefsKeys.ADAPTIVE_QUALITY, false)

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
        screenQualitySync = { value ->
            quality = value
            refreshChips()
        }

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
            hint("LOW sends less data · HIGH is sharper · live FPS appears on the preview", top = px(5)),
        )
        card.addView(
            toggle("Adaptive quality", adaptive) { on ->
                adaptive = on
                prefs.edit().putBoolean(PrefsKeys.ADAPTIVE_QUALITY, on).apply()
                callbacks.onAdaptiveQuality(on)
            },
            topParams(px(8)),
        )
        card.addView(
            hint(
                "Off by default. When on, sharpness drops after sustained stutter and recovers " +
                    "only when the stream stays smooth. Tapping LOW / BALANCED / HIGH pauses auto for 45 s.",
                top = px(2),
            ),
        )
        addTinter { refreshChips() }
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
        addTinter { text.setTextColor(withAlpha(pad.selectedTheme.muted, 220)) }
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

    private fun sectionTitle(text: String, helpKey: String? = null): View {
        val label = TextView(activity).apply {
            this.text = text
            textSize = 10f
            letterSpacing = 0.17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(px(2), 0, 0, px(9))
        }
        addTinter { label.setTextColor(pad.selectedTheme.accent) }
        if (helpKey == null) return label
        return row().apply {
            addView(label, weightParams(1f))
            addView(helpButton(helpKey))
        }
    }

    private fun helpButton(key: String): TextView = TextView(activity).apply {
        text = "?"
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(px(7), 0, px(7), px(9))
        isClickable = true
        isFocusable = true
        setOnClickListener { showCardHelp(key) }
        addTinter {
            val t = pad.selectedTheme
            setTextColor(t.accent)
            background = GradientDrawable().apply {
                cornerRadius = px(9).toFloat()
                setColor(withAlpha(t.accent, 24))
                setStroke(px(1), withAlpha(t.accent, 90))
            }
        }
    }

    private fun showCardHelp(key: String) {
        val entry = CARD_HELP[key] ?: return
        LayoutDialogs.info(activity, pad.selectedTheme, entry.first, entry.second)
    }

    private fun tiny(text: String, top: Int = 0, bottom: Int = px(6)): TextView {
        val label = TextView(activity).apply {
            this.text = text
            textSize = 10f
            letterSpacing = 0.11f
            setLineSpacing(px(2).toFloat(), 1f)
            setPadding(px(2), top, 0, bottom)
        }
        addTinter { label.setTextColor(pad.selectedTheme.muted) }
        return label
    }

    /** Explanatory prose that DENSE mode can hide without touching labels. */
    private fun hint(text: String, top: Int = 0, bottom: Int = px(6)): TextView =
        tiny(text, top, bottom).apply { tag = "hint" }

    private fun action(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val button = TextView(activity).apply {
            text = label
            textSize = 11.5f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setPadding(px(8), px(10), px(8), px(10))
            minHeight = px(44)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressable(this)
        }
        addTinter {
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
            pressable(this)
        }
    }

    /**
     * Immediate scale feedback on touch. The listener returns false so the
     * normal click path still runs; only the visuals are ours.
     */
    private fun pressable(view: View, scale: Float = 0.955f) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    target.animate().scaleX(scale).scaleY(scale).setDuration(90L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    target.animate().scaleX(1f).scaleY(1f)
                        .setDuration(180L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
            }
            false
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
        addTinter { style() }
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
        addTinter { style() }
        style()
        return container
    }

    // ------------------------------------------------------------- player slot
    private fun chooseSlot(slot: Int) {
        val wanted = slot.coerceIn(0, Proto.MAX_PLAYERS)
        if (link.connected && link.partyLocked && !link.isHost) {
            callbacks.toast("Party locked by the host")
            lastSlotSignature = Int.MIN_VALUE
            refreshSlotChips()
            return
        }
        // Moving onto another player's pad needs their consent. Do not change
        // the preference yet: a decline must leave this phone exactly where it
        // was, and the server only swaps after the other player accepts. A new
        // request also replaces any older ask on the server.
        if (link.connected && link.assignedSlot > 0 && wanted != 0 &&
            wanted != link.assignedSlot
        ) {
            link.requestSwap(wanted)
            lastSlotSignature = Int.MIN_VALUE
            refreshSlotChips()
            callbacks.toast("Asked P$wanted to swap")
            return
        }
        // Choosing AUTO or the pad we already own does not send a new request,
        // so explicitly withdraw any ask still on the other player's screen.
        if (link.swapPending()) link.cancelSwap()
        link.wantSlot = wanted
        prefs.edit().putInt(PrefsKeys.PLAYER_SLOT, wanted).apply()
        lastSlotSignature = Int.MIN_VALUE
        refreshSlotChips()
        callbacks.toast(
            when {
                wanted == 0 -> "Player slot: automatic"
                wanted == link.assignedSlot -> "Player slot: P$wanted"
                else -> "Requesting P$wanted"
            },
        )
    }

    private fun refreshSlotChips() {
        val t = pad.selectedTheme
        val want = link.wantSlot
        val assigned = link.assignedSlot
        val pending = link.pendingSwapTarget()
        val lockedForGuest = link.partyLocked && !link.isHost
        var signature = ((want + 1) * 37 + (assigned + 1)) * 31 + t.accent
        signature = signature * 31 + t.ok
        signature = signature * 31 + (pending + 1)
        signature = signature * 31 + if (lockedForGuest) 1 else 0
        if (::partyLockBtn.isInitialized) {
            partyLockBtn.visibility =
                if (link.connected && link.isHost) View.VISIBLE else View.GONE
            partyLockBtn.text = if (link.partyLocked) "UNLOCK PARTY" else "LOCK PARTY"
        }
        if (signature != lastSlotSignature) {
            lastSlotSignature = signature
            for ((slot, chipView) in slotViews) {
                val selected = slot == want
                val isAssigned = slot != 0 && slot == assigned
                val isPending = slot != 0 && slot == pending
                chipView.alpha = when {
                    lockedForGuest -> 0.45f
                    !selected && want != 0 && !isAssigned && !isPending -> 0.72f
                    else -> 1f
                }
                chipView.setTextColor(
                    when {
                        selected -> onColor(t.accent)
                        isPending -> t.accent
                        isAssigned -> t.text
                        else -> t.textDim
                    },
                )
                chipView.background = GradientDrawable().apply {
                    cornerRadius = px(10).toFloat()
                    setColor(if (selected) t.accent else withAlpha(t.surfaceAlt, 235))
                    setStroke(
                        px(1),
                        withAlpha(
                            when {
                                selected -> t.accent
                                isPending -> t.accent
                                isAssigned -> t.ok
                                else -> t.text
                            },
                            when {
                                selected -> 255
                                isPending -> 200
                                isAssigned -> 220
                                else -> 34
                            },
                        ),
                    )
                }
            }
        }

        slotLine.text = when {
            link.host == null -> "AUTO takes the next free pad."
            !link.connected -> "Waiting for the PC…"
            lockedForGuest -> "Party locked — your seat is frozen."
            assigned <= 0 -> "Waiting for the PC to assign a pad…"
            link.partyLocked && link.isHost -> "Party locked — you can still change seats."
            pending > 0 -> "Asked P$pending — waiting for their answer…"
            want == 0 -> "Using P$assigned (AUTO)."
            want == assigned -> "Using P$assigned."
            else -> "P$want is busy — using P$assigned until it frees up."
        }
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
        // 65535 is not usable: the screen stream listens on port + 1.
        val port = portEdit.text.toString().toIntOrNull()?.coerceIn(1, 65534) ?: Proto.PORT
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
        // A discover run takes up to three seconds. If the sheet closes (or a
        // later scan starts) before it returns, the callback must not write to
        // the detached panel or re-enable a button nobody can see.
        val generation = ++scanGeneration
        // Remember the user's choice: the callback below may re-point the chip
        // at the link that actually answered.
        val want = selectedTransport
        val port = portEdit.text.toString().toIntOrNull()?.coerceIn(1, 65534) ?: Proto.PORT

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
                if (generation != scanGeneration) return@runOnUiThread
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
                            "Start lemoGamePad-server and, for USB, enable USB tethering."
                    }
                    callbacks.toast("No PC found on ${selectedTransport.label}")
                    return@runOnUiThread
                }

                // Follow the link the PC actually answered on: the chip may
                // say USB while the PC is reachable over Wi-Fi.
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
    fun refreshLink() {
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
            if (connected && link.assignedSlot > 0) append("  ·  P${link.assignedSlot}")
            if (connected && !batterySaver && link.rttMs >= 0) append("  ·  ${link.rttMs} ms")
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

        if (batterySaver) {
            latencyGraph.visibility = View.GONE
            latencyStats.visibility = View.VISIBLE
            latencyStats.text = "LIVE STATS PAUSED · battery saver on"
        } else {
            val history = link.rttHistory()
            val showGraph = connected && history.isNotEmpty()
            val showStats = connected || host != null
            latencyGraph.visibility = if (showGraph) View.VISIBLE else View.GONE
            latencyStats.visibility = if (showStats) View.VISIBLE else View.GONE
            if (showGraph) latencyGraph.setSamples(history, t.accent, t.muted)
            if (showStats) {
                latencyStats.text = buildString {
                    append("PATH  ").append(
                        link.transportName().ifBlank { if (host == null) "—" else "linking" },
                    )
                    if (connected && link.assignedSlot > 0) append("   ·   P${link.assignedSlot}")
                    append("\nRTT  ")
                    if (connected && link.rttMs >= 0) {
                        append("${link.rttMs} ms   ·   avg ${link.rttAvgMs}")
                        if (link.jitterMs > 0) append("   ·   ±${link.jitterMs} jitter")
                    } else {
                        append("—")
                    }
                    append("\nLOSS  ")
                    when {
                        link.lossPermille < 0 -> append("—")
                        link.lossPermille >= 5 ->
                            append("${(link.lossPermille + 5) / 10}% reported by PC")
                        else -> append("none")
                    }
                    append("\nVIDEO  ")
                    if (callbacks.streamConnected()) {
                        append("${callbacks.streamFps()} fps stream")
                    } else {
                        append("no stream")
                    }
                    if (link.battery in 0..100) append("\nPHONE BATTERY  ${link.battery}%")
                }
            }
        }

        // The primary button always dials the address typed above; RECONNECT
        // next to it rebuilds the current link. Do not relabel this one, or the
        // row shows RECONNECT twice.
        connectBtn.text = "CONNECT"
        disconnectBtn.isEnabled = host != null
        disconnectBtn.alpha = if (host == null) 0.4f else 1f

        refreshTransportChips(readiness)
        refreshSlotChips()
        refreshBluetoothGamepad()
        tetherBtn.visibility = if (
            selectedTransport == Transport.USB && readiness[Transport.USB] != true
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        updateHint(readiness[selectedTransport] == true, connected)
        styleSections()
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
                append(tr.label.lowercase())
                append(" interface yet")
                if (tr == Transport.USB) append(" — enable USB tethering")
            }
        }
    }

    /** Called by the activity after the Bluetooth permission dialog closes. */
    fun onBluetoothPermissionResult(granted: Boolean) {
        if (!granted) callbacks.toast("Bluetooth access denied")
        refreshBluetoothGamepad()
    }

    /** Live state of the manually selected HID gamepad. Cheap: no probing. */
    fun refreshBluetoothGamepad() {
        val t = pad.selectedTheme
        val supported = callbacks.bluetoothSupported()
        val enabled = callbacks.bluetoothEnabled()
        val missing = callbacks.bluetoothPermissionsMissing()
        val connected = callbacks.bluetoothGamepadConnected()
        val running = callbacks.bluetoothGamepadRunning()
        val name = callbacks.bluetoothGamepadName()
        val canPick = supported && enabled && !missing
        btStatus.text = when {
            !supported -> "No Bluetooth adapter on this phone"
            !enabled -> "Turn on Bluetooth, then pick a host"
            missing -> "Nearby devices access is off — grant it, then pick a host."
            connected -> "● Connected — ${name.ifBlank { "host" }} is receiving input"
            running -> "○ Gamepad ready — tap SELECT HOST to choose a computer"
            else -> callbacks.bluetoothGamepadStatus()
                .ifBlank { "Bluetooth gamepad is off — tap SELECT HOST to connect" }
        }
        btStatus.setTextColor(
            when {
                connected -> t.ok
                !supported || !enabled || missing || running -> t.warn
                else -> t.muted
            },
        )
        btGrantBtn.visibility = if (missing) View.VISIBLE else View.GONE
        btHostBtn.isEnabled = canPick
        btHostBtn.alpha = if (canPick) 1f else 0.45f
        btDisconnectBtn.isEnabled = connected
        btDisconnectBtn.alpha = if (connected) 1f else 0.45f
        // Pairing stays available so the user can turn Bluetooth on from here.
        btPairBtn.alpha = if (supported && !missing) 1f else 0.45f
        // While a host is linked, Android may also connect its audio profiles.
        // Swap the pairing shortcut for the one place the user can turn those
        // switches off, without growing the card.
        btDiscoverBtn.text = if (connected) "HOST AUDIO SETTINGS" else "MAKE DISCOVERABLE"
        btDiscoverBtn.isEnabled = canPick
        btDiscoverBtn.alpha = if (canPick) 1f else 0.45f
        btAudioHint.visibility = if (connected) View.VISIBLE else View.GONE
        btAudioHint.text = "Android may also connect Media audio or Phone audio to a bonded host. " +
            "Use HOST AUDIO SETTINGS to switch those off for this device."
        styleSections()
    }

    /**
     * Lists only bonded computers/phones and connects to the one the user taps.
     * No host is contacted just for opening this dialog.
     */
    private fun showBluetoothHostPicker() {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        closeHostPicker()
        val t = pad.selectedTheme
        val overlay = FrameLayout(activity).apply {
            isClickable = true
            elevation = px(40).toFloat()
            setBackgroundColor(withAlpha(0x000000, if (t.dark) 170 else 110))
        }
        fun close() = closeHostPicker()
        overlay.setOnClickListener { close() }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            elevation = px(18).toFloat()
            setPadding(px(20), px(18), px(20), px(18))
            background = GradientDrawable().apply {
                cornerRadius = px(18).toFloat()
                setColor(t.surface)
                setStroke(px(1), withAlpha(t.text, 45))
            }
        }
        box.addView(TextView(activity).apply {
            text = "SELECT BLUETOOTH HOST"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(t.accent)
        })

        val lastHost = SettingsGuard.string(prefs, PrefsKeys.BT_LAST_HOST, "")
        val hosts = callbacks.bluetoothHosts().sortedWith(
            compareByDescending<BluetoothHost> { it.address == lastHost }
                .thenBy { it.name.lowercase() },
        )
        val connectedName = callbacks.bluetoothGamepadName()
        if (hosts.isEmpty()) {
            box.addView(
                hint(
                    "No bonded computer or phone yet. Pair “Lemo GamePad” from your PC, " +
                        "then come back and select it here.",
                    top = px(12),
                    bottom = px(2),
                ),
            )
            box.addView(
                action("OPEN BLUETOOTH SETTINGS", primary = true) {
                    close()
                    callbacks.onBluetoothPair()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14) },
            )
        } else {
            val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            for ((index, host) in hosts.withIndex()) {
                val isConnected = host.name == connectedName && callbacks.bluetoothGamepadConnected()
                val label = buildString {
                    append(host.name)
                    append("  ·  ")
                    append(host.address.takeLast(5))
                    if (host.address == lastHost) append("  ·  LAST USED")
                    if (isConnected) append("  ·  CONNECTED")
                }
                val button = action(label, primary = isConnected) {
                    close()
                    callbacks.onBluetoothConnect(host.address)
                }
                list.addView(
                    button,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (index > 0) topMargin = px(7)
                    },
                )
            }
            box.addView(
                ScrollView(activity).apply { addView(list) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12) },
            )
        }
        box.addView(
            action("CANCEL", primary = false) { close() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14) },
        )

        val width = min(px(320), activity.resources.displayMetrics.widthPixels - px(32))
        overlay.addView(
            box,
            FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        decor.addView(overlay)
        hostPicker = overlay
    }

    private fun closeHostPicker() {
        val overlay = hostPicker ?: return
        hostPicker = null
        (overlay.parent as? ViewGroup)?.removeView(overlay)
    }

    /** Jumps to the system tethering page (USB). */
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

    /**
     * Registers a theme updater. Helpers like [tiny] call this for every view
     * they create, so calls made from inside [refreshTints] must not mutate the
     * list being iterated.
     */
    private fun addTinter(tint: () -> Unit) {
        if (tintBatchDepth > 0) pendingTinters += tint else tinters += tint
    }

    private fun refreshTints() {
        if (tintBatchDepth > 0) return
        tintBatchDepth++
        try {
            for (tint in tinters.toList()) tint()
            // Dynamic cards rebuild their rows from helper views. Those helpers
            // register tints while we iterate; apply them to the new views and
            // drop them, because the next refresh rebuilds the rows again.
            while (pendingTinters.isNotEmpty()) {
                val fresh = pendingTinters.toList()
                pendingTinters.clear()
                for (tint in fresh) tint()
            }
        } finally {
            tintBatchDepth--
            pendingTinters.clear()
        }
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
        // Do not pop the keyboard just because the sheet opened: the sheet
        // takes focus, so the address fields only focus when tapped.
        sheet.requestFocus()
        hideKeyboard()
        handler.post(ticker)
    }

    fun dismiss(animate: Boolean = true) {
        // Back / onPause closes the sheet; its host picker must not stay on the
        // decor view swallowing touches.
        closeHostPicker()
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
        closeHostPicker()
        if (closed) return
        closed = true
        showing = false
        closePreview(instant = true)
        // Invalidate any discover that is still running. scanning is reset so
        // a freshly opened sheet can start a new scan immediately.
        scanGeneration++
        scanning = false
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

