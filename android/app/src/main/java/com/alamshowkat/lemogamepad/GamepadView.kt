package com.alamshowkat.lemogamepad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// Node kinds. File-level so the nested Node class can use them.
private const val K_DPAD = 0
private const val K_STICK_L = 1
private const val K_STICK_R = 2
private const val K_FACE = 3
private const val K_PILL = 4
private const val K_PS = 5
private const val K_SHOULDER = 6
// Kept so old saved layouts keep their numeric IDs; no new nodes use these.
private const val K_PEDAL = 7
private const val K_STEER = 8
private const val K_TOUCHPAD = 9
private const val K_ACTION = 10

// Swipe-pad feedback. Points are stored in pad-normalised coordinates so the
// streak survives layout moves and rotation.
private const val SWIPE_TRAIL_POINTS = 16
private const val SWIPE_TRAIL_MS = 320f
private const val SWIPE_FOLLOW_MS = 70f
private const val SWIPE_RETURN_MS = 150f
private const val SWIPE_FADE_MS = 420f

// Mouse aim: touch speed becomes a right-axis velocity. It follows the finger
// quickly and stops shortly after the finger stops, like relative mouse aim in
// a game. No mouse HID report is involved; the output is still the right stick.
private const val MOUSE_FOLLOW_MS = 26f
private const val MOUSE_RETURN_MS = 70f
private const val MOUSE_FADE_MS = 220f
private const val MOUSE_MOVE_HOLD_MS = 120L
private const val MOUSE_ACTIVE_DECAY = 0.95f
private const val MOUSE_RETURN_DECAY = 0.55f
// A typical finger sweep is only a few hundred px/s. These gains make that
// sweep produce a useful right-stick deflection instead of a tiny nudge; the
// sensitivity slider scales the whole range.
private const val MOUSE_GAIN_BASE = 240f
private const val MOUSE_GAIN_SPAN = 960f
private const val SWIPE_DECAY = 0.86f

// Aim trainer: a short, self-contained drill. Output is suspended while it is
// open, so the same touch/gyro paths can move the reticle without driving a game.
private const val TRAINER_TARGETS = 10
private const val TRAINER_NEXT_DELAY_MS = 260L
private const val TRAINER_TARGET_RADIUS_DP = 22f
private const val TRAINER_RETICLE_RADIUS_DP = 12f
private const val TRAINER_SPAN = 0.72f
// A free reticle stays where it is dragged. Gyro adds angular movement to that
// same position; these gains are much smaller than the stick-velocity gain used
// by live output, which would otherwise slam the reticle to the edge.
private const val TRAINER_GYRO_GAIN_BASE = 0.25f
private const val TRAINER_GYRO_GAIN_SPAN = 0.75f
private const val TRAINER_TAP_SLOP_DP = 8f
private const val TRAINER_FEEDBACK_MS = 700L

/** Transient alignment line drawn while a control is being dragged. */
private data class EditorGuide(val vertical: Boolean, val position: Float)

/** Copyable layout attributes. Identity and button mapping stay fixed. */
private data class NodeLayoutState(
    val fx: Float,
    val fy: Float,
    val scale: Float,
    val hidden: Boolean,
    val locked: Boolean,
    val customized: Boolean,
)

/** Measured result of one calibration run, used to suggest concrete settings. */
data class AimTrainerResult(
    val hits: Int,
    val shots: Int,
    val accuracy: Int,
    val steadiness: Int,
    /** Time-weighted mean error while the reticle was near the target. */
    val driftX: Float,
    val driftY: Float,
    /** Axis sign changes, used to detect overshoot. */
    val crossesX: Int,
    val crossesY: Int,
    /** Settings at the moment the run ended, in the same units as preferences. */
    val touchpadSensitivity: Int,
    val aimSensitivity: Int,
    val gyroRevertX: Boolean,
    val gyroRevertY: Boolean,
    val gyroAim: Boolean,
    val touchpadMouseMode: Boolean,
    /** Which input actually moved the reticle during this run. */
    val usedTouch: Boolean,
    val usedGyro: Boolean,
    val summary: String,
)

/**
 * Stroke paint whose width follows the active palette's border weight, so a
 * frosted theme draws fine hairlines and an arcade theme draws thicker rims
 * without every draw call having to remember to scale.
 */
private class ScaledStrokePaint : Paint(ANTI_ALIAS_FLAG) {
    var widthScale = 1f

    init {
        style = Style.STROKE
        strokeCap = Cap.ROUND
        strokeJoin = Join.ROUND
    }

    override fun setStrokeWidth(width: Float) {
        super.setStrokeWidth(width * widthScale)
    }
}

/**
 * Full screen touch controller, restyled around [PadTheme].
 *
 * Layout is a list of draggable nodes stored as screen fractions, so a custom
 * layout survives rotation and different screen sizes. In edit mode a node can
 * be moved, pinched to resize, or held to hide it.
 */
@SuppressLint("ViewConstructor")
class GamepadView(context: Context) : View(context) {

    val state = PadState()

    var onOpenSettings: (() -> Unit)? = null
    var onHaptic: ((Int) -> Unit)? = null
    var onLayoutChanged: (() -> Unit)? = null
    var onToast: ((String) -> Unit)? = null
    /** Notifies the activity so it can hide/restore the screen PiP. */
    var onAimTrainerChanged: ((Boolean) -> Unit)? = null
    /** Delivers the end-of-run summary so the activity can show it once. */
    var onAimTrainerFinished: ((String) -> Unit)? = null
    /** Delivers the same run as measurable data for one-tap recommendations. */
    var onAimTrainerResult: ((AimTrainerResult) -> Unit)? = null

    /**
     * When enabled the right stick and its click are replaced by a swipe pad.
     * The pad can use BGMI-style swipes or relative game-mouse aim; gyro aim
     * drives the same right-stick axes in either mode. The wire format stays
     * identical for the PC server and the Bluetooth HID host.
     */
    var touchpadEnabled = false
        set(value) {
            if (field == value) return
            field = value
            clearAim()
            buildNodes()
            publish(redraw = false)
            invalidate()
        }

    /**
     * Switches the touchpad between BGMI swipes and relative game-mouse aim.
     * Mouse mode maps finger speed to right-stick velocity, so moving the finger
     * turns the camera and stopping it stops the camera. It is not an absolute
     * desktop pointer; the controller output is unchanged.
     */
    var touchpadMouseMode = false
        set(value) {
            if (field == value) return
            field = value
            touchAimX = 0f
            touchAimY = 0f
            camLastAt = 0L
            syncAim()
            touchpadNode?.clearSwipe()
            publish(redraw = false)
            invalidate()
        }

    // ------------------------------------------------------------- motion input
    private var motion: MotionInput? = null
    /** Sensors register only between startMotion() and stopMotion(). */
    private var motionStarted = false

    var gyroSteering = false
        set(value) {
            field = value
            if (motionStarted) applyMotionPrefs()
        }
    var gyroAim = false
        set(value) {
            field = value
            if (motionStarted) applyMotionPrefs()
        }

    /** Flips only the gyro's left/right aim direction. */
    var gyroRevertX = false
        set(value) {
            if (field == value) return
            field = value
            gyroAimX = 0f
            syncAim()
            publish(redraw = false)
            invalidate()
        }

    /** Flips only the gyro's up/down aim direction. */
    var gyroRevertY = false
        set(value) {
            if (field == value) return
            field = value
            gyroAimY = 0f
            syncAim()
            publish(redraw = false)
            invalidate()
        }

    /** 0 = gentle (needs a big tilt), 1 = twitchy (a small tilt steers). */
    var steerSensitivity = 0.55f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Fraction of full tilt ignored around the centre. */
    var steerDeadzone = 0.05f
        set(value) {
            field = value.coerceIn(0f, 0.4f)
        }

    /** 0 = linear tilt, 1 = strongest soft-centre response. */
    var steerCurve = 0.25f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    var aimSensitivity = 0.55f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    var touchpadSensitivity = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /** Global opacity of the controls, for the customization studio. */
    var controlAlpha = 1f
        set(value) {
            field = value.coerceIn(0.35f, 1f)
            invalidate()
        }

    /** Distance multiplier for the four face buttons. */
    var faceSpacing = 1f
        set(value) {
            field = value.coerceIn(0.75f, 1.6f)
            layoutControls()
            invalidate()
        }

    /** Latest tilt in degrees, relative to the last calibration. */
    private var tiltDeg = 0f
    /** Steering value after dead zone and curve, -1..1. */
    private var steerValue = 0f
    /** Combined right-axis aim from the touchpad and gyro, -1..1. */
    private var aimX = 0f
    private var aimY = 0f

    /** Separate sources let each keep its own decay while adding to [aimX]/[aimY]. */
    private var touchAimX = 0f
    private var touchAimY = 0f
    private var gyroAimX = 0f
    private var gyroAimY = 0f

    private var camLastX = 0f
    private var camLastY = 0f
    private var camLastAt = 0L

    var sensorStatus: String = ""
        private set

    val currentSteer: Float get() = steerValue
    val currentTilt: Float get() = tiltDeg

    /**
     * Modal aim drill. While active, touches drive the reticle through the same
     * aim sources as the pad, every output packet is held neutral, and Back or
     * the on-screen DONE button closes it.
     */
    var aimTrainerActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                trainerBegin()
                // publish() is already gated; this emits one neutral state so a
                // connected PC/host never keeps a stale button or axis held.
                resetInputs()
            } else {
                val summary = trainerSummary()
                val result = trainerBuildResult(summary)
                trainerReset()
                resetInputs()
                onAimTrainerFinished?.invoke(summary)
                onAimTrainerResult?.invoke(result)
            }
            onAimTrainerChanged?.invoke(value)
        }

    private var trainerNeutralSent = false
    private var trainerHits = 0
    private var trainerShots = 0
    private var trainerTotalMs = 0f
    private var trainerOnTargetMs = 0f
    private var trainerTargetX = 0f
    private var trainerTargetY = 0f
    private var trainerTargetVisible = false
    private var trainerNextAt = 0L
    /** Persistent aim point in trainer-normalised coordinates: +X right, +Y up. */
    private var trainerReticleX = 0f
    private var trainerReticleY = 0f
    private var trainerHitX = 0f
    private var trainerHitY = 0f
    private var trainerFlash = 0f
    private var trainerMissFlash = 0f
    private var trainerFeedback = ""
    private var trainerFeedbackUntil = 0L
    private var trainerComplete = false
    private var trainerSummaryText = ""
    private var trainerPointerId = -1
    private var trainerDownX = 0f
    private var trainerDownY = 0f
    private var trainerLastX = 0f
    private var trainerLastY = 0f
    private var trainerMoved = false
    private var trainerErrSumX = 0f
    private var trainerErrSumY = 0f
    private var trainerErrMs = 0f
    private var trainerTouchDelta = 0f
    private var trainerGyroDelta = 0f
    private var trainerPrevErrX = 0f
    private var trainerPrevErrY = 0f
    private var trainerHasPrevErr = false
    private var trainerCrossX = 0
    private var trainerCrossY = 0
    private val trainerDoneRect = RectF()

    /** Clears every aim source and the touchpad's last sample. */
    private fun clearAim() {
        aimX = 0f
        aimY = 0f
        touchAimX = 0f
        touchAimY = 0f
        gyroAimX = 0f
        gyroAimY = 0f
        camLastX = 0f
        camLastY = 0f
        camLastAt = 0L
    }

    /** Adds the touchpad and gyro impulses into the published right axes. */
    private fun syncAim() {
        aimX = (touchAimX + gyroAimX).coerceIn(-1f, 1f)
        aimY = (touchAimY + gyroAimY).coerceIn(-1f, 1f)
    }

    /** Centres tilt steering on how the phone is being held right now. */
    fun calibrateTilt() {
        motion?.calibrate()
        tiltDeg = 0f
        steerValue = 0f
        onHaptic?.invoke(18)
        onToast?.invoke("Steering centred")
        invalidate()
    }

    var deadzone: Float = 0.12f
        set(value) {
            field = value.coerceIn(0f, 0.4f)
        }

    /** 0 = linear stick response, 1 = strongest precision (soft-centre) curve. */
    var stickCurve: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    @Volatile
    var hapticsEnabled = true

    /** Decorative press ripples drawn by each palette's motion style. */
    var motionEnabled = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) for (n in nodes) n.ripple = 0f
            invalidate()
        }

    // Theme switching is a 300 ms cross-fade, so the whole pad melts from one
    // palette into the next instead of snapping.
    private var themeTarget: PadTheme = Themes.MIDNIGHT
    private var themeFrom: PadTheme = Themes.MIDNIGHT
    private var themeNow: PadTheme = Themes.MIDNIGHT
    private var themeT = 1f

    var theme: PadTheme
        get() = themeNow
        set(value) {
            if (value.id == themeTarget.id) return
            themeFrom = themeNow
            themeTarget = value
            themeT = 0f
            startAnimating()
        }

    /** The palette the pad is fading towards: what the user picked. */
    val selectedTheme: PadTheme get() = themeTarget

    var faceLabels: FaceLabels = FaceLabels.PLAYSTATION
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var editMode = false
        set(value) {
            if (field == value) return
            field = value
            if (value) resetInputs()
            removeCallbacks(longPressRunnable)
            editNode = null
            editPointer = -1
            pinchPointer = -1
            editBefore = null
            selectedNodes.clear()
            activeGuides.clear()
            invalidate()
        }

    // ------------------------------------------------------------- control model
    private class Node(
        val key: String,
        val kind: Int,
        val bit: Int,
        val label: String,
        var fx: Float,
        var fy: Float,
        val faceIdx: Int = -1,
        val homeDx: Float = 0f,
        val homeDy: Float = 0f,
    ) {
        var defFx = fx
        var defFy = fy
        var scale = 1f
        var hidden = false

        /** True once the user moves or resizes a face button. */
        var customized = false

        /** Locked controls cannot be picked up, hidden, or transformed. */
        var locked = false

        // resolved geometry, recalculated on every size/layout change
        var cx = 0f
        var cy = 0f
        var hw = 0f
        var hh = 0f
        var r = 0f

        // per-control state
        var pressed = false
        var pointer = -1
        var up = false
        var down = false
        var left = false
        var right = false
        var kx = 0f
        var ky = 0f

        /** Analog 0..1 travel for a pedal, and the raw manual steering value. */
        var value = 0f

        /** Touchpad feedback: eased target, fade and a short swipe history. */
        var trailX = 0f
        var trailY = 0f
        var trail = 0f
        val swipeX = FloatArray(SWIPE_TRAIL_POINTS)
        val swipeY = FloatArray(SWIPE_TRAIL_POINTS)
        val swipeAge = FloatArray(SWIPE_TRAIL_POINTS) { SWIPE_TRAIL_MS }
        private var swipeHead = 0
        private var swipeCount = 0

        // Reused by drawTouchpad() so an animating ribbon allocates nothing.
        val sampleX = FloatArray(SWIPE_TRAIL_POINTS)
        val sampleY = FloatArray(SWIPE_TRAIL_POINTS)
        val sampleLife = FloatArray(SWIPE_TRAIL_POINTS)

        fun pushSwipe(x: Float, y: Float) {
            swipeX[swipeHead] = x
            swipeY[swipeHead] = y
            swipeAge[swipeHead] = 0f
            swipeHead = (swipeHead + 1) % SWIPE_TRAIL_POINTS
            swipeCount = min(swipeCount + 1, SWIPE_TRAIL_POINTS)
        }

        fun lastSwipe(): Pair<Float, Float>? {
            if (swipeCount == 0) return null
            val i = (swipeHead - 1 + SWIPE_TRAIL_POINTS) % SWIPE_TRAIL_POINTS
            return swipeX[i] to swipeY[i]
        }

        /**
         * Copies chronological, still-visible points into reusable arrays and
         * returns the count. Drawing uses the real age so the streak thins
         * smoothly instead of jumping when a new point is pushed.
         */
        fun fillSwipeSamples(): Int {
            var count = 0
            for (i in 0 until swipeCount) {
                val idx = (swipeHead - swipeCount + i + SWIPE_TRAIL_POINTS) % SWIPE_TRAIL_POINTS
                val age = swipeAge[idx]
                if (age < SWIPE_TRAIL_MS) {
                    sampleX[count] = swipeX[idx]
                    sampleY[count] = swipeY[idx]
                    sampleLife[count] = 1f - age / SWIPE_TRAIL_MS
                    count++
                }
            }
            return count
        }

        /** Advances the streak and reports whether any point is still visible. */
        fun ageSwipe(dt: Float): Boolean {
            var alive = false
            for (i in 0 until SWIPE_TRAIL_POINTS) {
                if (swipeAge[i] >= SWIPE_TRAIL_MS) continue
                swipeAge[i] += dt
                if (swipeAge[i] < SWIPE_TRAIL_MS) alive = true
            }
            if (!alive) swipeCount = 0
            return alive
        }

        fun clearSwipe() {
            for (i in 0 until SWIPE_TRAIL_POINTS) swipeAge[i] = SWIPE_TRAIL_MS
            swipeHead = 0
            swipeCount = 0
            trailX = 0f
            trailY = 0f
            trail = 0f
            kx = 0f
            ky = 0f
        }

        // 0 = idle, 1 = fully pressed. Drives the springy press animation.
        var pressT = 0f

        /** Velocity of [pressT] so presses spring instead of easing linearly. */
        var pressVel = 0f

        /** 0 = idle, otherwise 0..1 progress through the palette's press ripple. */
        var ripple = 0f

        /** Per-arm press springs for up/down/left/right on the d-pad. */
        val armT = FloatArray(4)
        val armVel = FloatArray(4)

        var bw = 0f
        var bh = 0f

        fun extW(): Float = when (kind) {
            K_PILL, K_PS, K_SHOULDER, K_PEDAL, K_STEER, K_TOUCHPAD -> hw
            else -> r
        }

        fun extH(): Float = when (kind) {
            K_PILL, K_PS, K_SHOULDER, K_PEDAL, K_STEER, K_TOUCHPAD -> hh
            else -> r
        }

        fun hit(x: Float, y: Float): Boolean = when (kind) {
            K_PILL, K_PS, K_SHOULDER, K_PEDAL, K_STEER, K_TOUCHPAD ->
                x >= cx - hw * 1.1f && x <= cx + hw * 1.1f &&
                    y >= cy - hh * 1.15f && y <= cy + hh * 1.15f
            K_FACE -> hypot(x - cx, y - cy) <= r * 1.4f
            else -> hypot(x - cx, y - cy) <= r * 1.5f
        }
    }

    private val nodes = ArrayList<Node>(20)
    private val playOrder = ArrayList<Node>(20)
    private val faceNodes = ArrayList<Node>(4)
    private var dpadNode: Node? = null
    private var leftStick: Node? = null
    private var rightStick: Node? = null
    private var touchpadNode: Node? = null

    // --------------------------------------------------------------- paints
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = ScaledStrokePaint()
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val bgPaint = Paint()
    private val trainerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trainerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val trainerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()

    /**
     * Scale a glow colour's alpha by the active palette's shadow weight.
     * AMOLED sets this to 0 for a flat, bloom-free look; arcade/neon palettes
     * push it past 1 for a stronger halo.
     */
    private fun glow(color: Int): Int {
        val s = theme.shadow.coerceIn(0f, 2f)
        if (s == 1f) return color
        val a = (color ushr 24) and 0xFF
        val na = (a * s).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (na shl 24)
    }

    /** Per-palette letter spacing multiplier applied to every tracked label. */
    private fun track(spacing: Float): Float =
        spacing * theme.tracking.coerceIn(0.7f, 1.5f)

    private val boldFace: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val labelFace: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // --------------------------------------------------------------- geometry
    private var W = 0f
    private var H = 0f
    private var bgShader: Shader? = null
    private var glowShader: Shader? = null

    private val hudRect = RectF()
    private var hudPointer = -1
    private var hudPressed = false

    var statusText: String = "TAP TO CONNECT"
        private set
    private var statusKind = STATUS_IDLE

    // --------------------------------------------------------------- edit mode
    private var editNode: Node? = null
    private var editPointer = -1
    private var pinchPointer = -1
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var pinchStartDist = 1f
    private var pinchStartScale = 1f

    /** Controls selected by the current edit gesture or multi-select taps. */
    private val selectedNodes = LinkedHashSet<Node>()
    private val dragStart = HashMap<Node, Pair<Float, Float>>()
    private val pinchStartScales = HashMap<Node, Float>()
    private val activeGuides = ArrayList<EditorGuide>()
    private val clipboard = LinkedHashMap<String, NodeLayoutState>()

    /** Snapshot taken before an in-progress drag, pushed only if it changed. */
    private var editBefore: String? = null
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    var snapEnabled = false
    var guidesEnabled = true
    var safeAreaEnabled = false
    var multiSelectEnabled = false
    var safeArea = RectF()

    /** Old 1.0 layout: face cluster as one node [fx, fy, scale, hidden]. */
    private var legacyFace: FloatArray? = null

    // ------------------------------------------------------------- animation
    private var animating = false
    private var lastAnimAt = 0L
    private var statusPulse = 0f

    // One-time fade/scale entrance when the pad first appears.
    private var enterT = 1f
    private var entered = false

    private val longPressRunnable = Runnable {
        val n = editNode ?: return@Runnable
        if (moved || n.locked) return@Runnable
        n.hidden = true
        onHaptic?.invoke(20)
        onToast?.invoke("${n.label} hidden · SHOW ALL restores it")
        invalidate()
    }

    init {
        motion = MotionInput(context).apply {
            listener = object : MotionInput.Listener {
                override fun onTilt(degrees: Float) = handleTilt(degrees)
                override fun onGyro(yaw: Float, pitch: Float) = handleGyro(yaw, pitch)
                override fun onAvailability(accelerometer: Boolean, gyroscope: Boolean) {
                    sensorStatus = status()
                }
            }
        }
        sensorStatus = motion?.status().orEmpty()
        buildNodes()
    }

    /**
     * Builds the single Gamepad layout. Keys are stable so a saved layout is
     * applied to the right node after the swipe pad is toggled.
     */
    private fun buildNodes() {
        nodes.clear()
        faceNodes.clear()
        playOrder.clear()
        dpadNode = null
        leftStick = null
        rightStick = null
        touchpadNode = null

        buildGamepadNodes()
        rebuildPlayOrder()
        layoutControls()
    }

    private fun buildGamepadNodes() {
        dpadNode = Node("dpad", K_DPAD, -1, "D-PAD", 0.115f, 0.585f)
        leftStick = Node("ls", K_STICK_L, -1, "LEFT STICK", 0.265f, 0.75f)
        if (touchpadEnabled) {
            touchpadNode = Node("touchpad", K_TOUCHPAD, -1, "SWIPE AIM", 0.705f, 0.75f).apply {
                bw = 0.32f; bh = 0.30f
            }
        } else {
            rightStick = Node("rs", K_STICK_R, -1, "RIGHT STICK", 0.705f, 0.75f)
        }

        // A / B / X / Y (or the PS symbols) are four independent controls now.
        // homeDx/homeDy are offsets in H units, so the cluster keeps even pixel
        // spacing on every aspect ratio until the user moves a button.
        val tri = Node("tri", K_FACE, Proto.TRIANGLE, "TRIANGLE", 0.855f, 0.470f, 0, 0f, -FACE_SPREAD)
        val cir = Node("cir", K_FACE, Proto.CIRCLE, "CIRCLE", 0.913f, 0.585f, 1, FACE_SPREAD, 0f)
        val cro = Node("cro", K_FACE, Proto.CROSS, "CROSS", 0.855f, 0.700f, 2, 0f, FACE_SPREAD)
        val squ = Node("squ", K_FACE, Proto.SQUARE, "SQUARE", 0.797f, 0.585f, 3, -FACE_SPREAD, 0f)

        val l2 = Node("l2", K_SHOULDER, Proto.L2, "L2", 0.115f, 0.105f)
        val l1 = Node("l1", K_SHOULDER, Proto.L1, "L1", 0.115f, 0.2475f)
        val r2 = Node("r2", K_SHOULDER, Proto.R2, "R2", 0.885f, 0.105f)
        val r1 = Node("r1", K_SHOULDER, Proto.R1, "R1", 0.885f, 0.2475f)
        val select = Node("select", K_PILL, Proto.SELECT, "SELECT", 0.435f, 0.915f).apply {
            bw = 0.078f; bh = 0.042f
        }
        val start = Node("start", K_PILL, Proto.START, "START", 0.565f, 0.915f).apply {
            bw = 0.078f; bh = 0.042f
        }
        val ps = Node("ps", K_PS, Proto.PS, "PS", 0.5f, 0.80f).apply {
            bw = 0.05f; bh = 0.05f
        }
        val l3 = Node("l3", K_PILL, Proto.L3, "L3", 0.265f, 0.925f).apply {
            bw = 0.055f; bh = 0.033f
        }

        nodes += dpadNode!!
        nodes += leftStick!!
        if (touchpadEnabled) nodes += touchpadNode!! else nodes += rightStick!!
        nodes += tri
        nodes += cir
        nodes += cro
        nodes += squ
        faceNodes += tri
        faceNodes += cir
        faceNodes += cro
        faceNodes += squ
        nodes += l2
        nodes += l1
        nodes += r2
        nodes += r1
        nodes += select
        nodes += start
        nodes += ps
        nodes += l3
        if (!touchpadEnabled) {
            nodes += Node("r3", K_PILL, Proto.R3, "R3", 0.705f, 0.925f).apply {
                bw = 0.055f; bh = 0.033f
            }
        }
    }

    /** Small controls are hit-tested before the sticks so actions stay reachable. */
    private fun rebuildPlayOrder() {
        playOrder.clear()
        dpadNode?.let { playOrder += it }
        for (n in nodes) {
            if (n.kind == K_SHOULDER || n.kind == K_PILL || n.kind == K_PS ||
                n.kind == K_ACTION || n.kind == K_PEDAL
            ) {
                playOrder += n
            }
        }
        playOrder += faceNodes
        leftStick?.let { playOrder += it }
        touchpadNode?.let { playOrder += it }
        rightStick?.let { playOrder += it }
    }
    // --------------------------------------------------------------- layout
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        W = w.toFloat()
        H = h.toFloat()
        if (W <= 0f || H <= 0f) return
        rebuildShaders()
        layoutControls()
        publish(redraw = false)
        if (!entered) {
            entered = true
            enterT = 0f
            startAnimating()
        }
    }

    /**
     * Static, cheap background decoration chosen by the palette. No particles
     * and no animation, so it costs the same whether the pad is idle or busy.
     */
    private fun drawBackdrop(canvas: Canvas) {
        if (W <= 0f || H <= 0f) return
        val style = theme.backdrop
        if (style == "none" || style == "glow") return
        fillPaint.shader = null
        when (style) {
            "grid" -> {
                val step = H * 0.13f
                strokePaint.color = withAlpha(theme.accent, 13)
                strokePaint.strokeWidth = dp(1f)
                var x = W * 0.06f
                while (x < W) {
                    canvas.drawLine(x, H * 0.10f, x, H * 0.94f, strokePaint)
                    x += step
                }
                var y = H * 0.12f
                while (y < H * 0.94f) {
                    canvas.drawLine(W * 0.02f, y, W * 0.98f, y, strokePaint)
                    y += step
                }
            }
            "scan" -> {
                val step = dp(6f)
                strokePaint.color = withAlpha(theme.text, 8)
                strokePaint.strokeWidth = dp(1f)
                var y = 0f
                while (y < H) {
                    canvas.drawLine(0f, y, W, y, strokePaint)
                    y += step
                }
            }
            "speed" -> {
                strokePaint.strokeWidth = dp(1.4f)
                for (i in 0 until 7) {
                    val y = H * (0.22f + i * 0.108f)
                    val left = W * (0.04f + (i % 3) * 0.03f)
                    val len = W * (0.10f + (i % 4) * 0.035f)
                    strokePaint.color = withAlpha(
                        if (i % 2 == 0) theme.accent else theme.accent2,
                        15 + (i % 3) * 5,
                    )
                    canvas.drawLine(left, y, left + len, y, strokePaint)
                    canvas.drawLine(W - left, y, W - left - len, y, strokePaint)
                }
            }
            "frost" -> {
                fillPaint.shader = LinearGradient(
                    0f, 0f, W * 0.9f, H * 0.85f,
                    intArrayOf(withAlpha(0xFFFFFF, 46), withAlpha(0xFFFFFF, 0)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawPaint(fillPaint)
                fillPaint.shader = null
            }
        }
    }

    private fun rebuildShaders() {
        if (W <= 0f || H <= 0f) return
        bgShader = LinearGradient(
            0f, 0f, 0f, H,
            intArrayOf(theme.bgTop, theme.bgMid, theme.bgBottom),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        glowShader = if (theme.glow <= 0.001f) {
            // AMOLED: no decorative glow at all, so the background stays #000.
            null
        } else {
            RadialGradient(
                W * 0.5f, -H * 0.04f, H * 1.05f,
                intArrayOf(
                    withAlpha(
                        theme.accent,
                        ((if (theme.dark) 40 else 20) * theme.glow).toInt(),
                    ),
                    withAlpha(theme.accent2, 0),
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    private fun layoutControls() {
        if (W <= 0f || H <= 0f) return
        val a = aspectScale()

        // Face cluster anchor (same spot the old single cluster used).
        val baseX = min(W * 0.855f, W - H * 0.20f)
        val baseY = H * 0.585f

        // Migrate a layout saved by 1.0, where all four buttons were one node.
        legacyFace?.let { old ->
            val lscale = old[2]
            for (n in faceNodes) {
                n.scale = lscale
                n.hidden = old[3] > 0.5f
                n.fx = (old[0] * W + n.homeDx * H * a * lscale) / W
                n.fy = (old[1] * H + n.homeDy * H * a * lscale) / H
                n.customized = true
            }
            legacyFace = null
        }

        for (n in nodes) {
            when (n.kind) {
                K_DPAD -> n.r = H * 0.145f * a * n.scale
                K_STICK_L, K_STICK_R ->
                    n.r = H * (if (n.bw > 0f) n.bw else 0.14f) * a * n.scale
                K_FACE -> {
                    n.r = H * 0.058f * a * n.scale
                    if (!n.customized) {
                        n.fx = (baseX + n.homeDx * H * a * faceSpacing) / W
                        n.fy = (baseY + n.homeDy * H * a * faceSpacing) / H
                        n.defFx = n.fx
                        n.defFy = n.fy
                    }
                }
                K_PILL -> {
                    n.hw = H * n.bw * n.scale
                    n.hh = H * n.bh * n.scale
                }
                K_PS -> {
                    n.r = H * n.bw * n.scale
                    n.hw = n.r
                    n.hh = n.r
                }
                K_SHOULDER -> {
                    n.hw = W * 0.0975f * n.scale
                    n.hh = H * 0.0625f * n.scale
                }
                K_PEDAL, K_STEER, K_TOUCHPAD -> {
                    n.hw = H * n.bw * n.scale * a
                    n.hh = H * n.bh * n.scale * a
                }
                K_ACTION -> n.r = H * n.bw * n.scale * a
            }

            // Keep every control fully on screen, even after a custom drag.
            val pad = H * 0.012f
            val ex = n.extW() + pad
            val ey = n.extH() + pad
            n.cx = (n.fx * W).coerceIn(ex, max(ex, W - ex))
            n.cy = (n.fy * H).coerceIn(ey, max(ey, H - ey))
        }
    }

    // ------------------------------------------------------------------ draw
    override fun onDraw(canvas: Canvas) {
        if (W <= 0f || H <= 0f) return

        if (animating) {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastAnimAt == 0L) 16f else (now - lastAnimAt).toFloat().coerceIn(1f, 48f)
            lastAnimAt = now
            stepAnimations(dt)
        }

        bgPaint.shader = bgShader
        canvas.drawPaint(bgPaint)
        glowShader?.let {
            glowPaint.shader = it
            canvas.drawPaint(glowPaint)
            glowPaint.shader = null
        }
        strokePaint.widthScale = theme.border.coerceIn(0.5f, 2f)
        drawBackdrop(canvas)

        if (enterT < 1f) {
            val k = 1f - (1f - enterT) * (1f - enterT) * (1f - enterT)
            canvas.saveLayerAlpha(0f, 0f, W, H, (255 * k).toInt())
            canvas.translate(W / 2f, H / 2f)
            val s = 0.982f + 0.018f * k
            canvas.scale(s, s)
            canvas.translate(-W / 2f, -H / 2f)
        }

        if (controlAlpha < 0.999f) {
            canvas.saveLayerAlpha(0f, 0f, W, H, (255 * controlAlpha).toInt())
        }
        for (n in nodes) {
            if (n.hidden) {
                if (editMode) drawPlaceholder(canvas, n)
                continue
            }
            when (n.kind) {
                K_DPAD -> drawDpad(canvas, n)
                K_STICK_L, K_STICK_R -> drawStick(canvas, n)
                K_FACE -> drawFace(canvas, n)
                K_PEDAL -> drawPedal(canvas, n)
                K_STEER -> drawSteer(canvas, n)
                K_TOUCHPAD -> drawTouchpad(canvas, n)
                K_ACTION -> drawAction(canvas, n)
                else -> drawPill(canvas, n)
            }
        }
        if (controlAlpha < 0.999f) canvas.restore()

        if (enterT < 1f) canvas.restore()

        if (editMode) {
            drawEditOverlay(canvas)
        } else {
            drawHud(canvas)
            drawWatermark(canvas)
        }
        if (aimTrainerActive) drawAimTrainer(canvas)

        if (animating) postInvalidateOnAnimation()
    }

    /** Press springs, theme cross-fade and the one-shot status ripple. */
    private fun stepAnimations(dt: Float) {
        animating = false
        // Normalise to 60 Hz frames so the spring behaves the same on any
        // refresh rate. dt is in milliseconds.
        val step = (dt / 16.667f).coerceIn(0.25f, 3f)

        for (n in nodes) {
            if (n.kind == K_DPAD) {
                val armTargets = floatArrayOf(
                    if (n.up) 1f else 0f,
                    if (n.down) 1f else 0f,
                    if (n.left) 1f else 0f,
                    if (n.right) 1f else 0f,
                )
                for (i in 0 until 4) {
                    val armDelta = armTargets[i] - n.armT[i]
                    if (abs(armDelta) < 0.003f && abs(n.armVel[i]) < 0.003f) {
                        n.armT[i] = armTargets[i]
                        n.armVel[i] = 0f
                        continue
                    }
                    n.armVel[i] +=
                        (armDelta * PRESS_STIFFNESS - n.armVel[i] * PRESS_DAMPING) * step
                    n.armT[i] = (n.armT[i] + n.armVel[i] * step).coerceIn(-0.02f, 1.15f)
                    animating = true
                }
            }

            if (n.ripple > 0f) {
                if (motionEnabled) {
                    n.ripple += dt / Motions.of(themeNow).ms
                    if (n.ripple >= 1f) n.ripple = 0f
                    animating = true
                } else {
                    n.ripple = 0f
                }
            }

            val target = if (n.pressed || n.pointer != -1) 1f else 0f
            val delta = target - n.pressT
            if (abs(delta) < 0.003f && abs(n.pressVel) < 0.003f) {
                n.pressT = target
                n.pressVel = 0f
                continue
            }
            // Lightly under-damped spring: a quick overshoot that settles in
            // about 120 ms, so the pad feels physical rather than laggy.
            n.pressVel += (delta * PRESS_STIFFNESS - n.pressVel * PRESS_DAMPING) * step
            n.pressT = (n.pressT + n.pressVel * step).coerceIn(-0.02f, 1.15f)
            animating = true
        }

        val now = SystemClock.uptimeMillis()
        var aimChanged = false

        // Gyro aim keeps its existing glide. Touch aim gets a mode-specific
        // decay: BGMI swipes linger, mouse aim stops as soon as motion stops.
        if (gyroAimX != 0f || gyroAimY != 0f) {
            val k = Math.pow(SWIPE_DECAY.toDouble(), step.toDouble()).toFloat()
            gyroAimX *= k
            gyroAimY *= k
            if (abs(gyroAimX) < 0.008f) gyroAimX = 0f
            if (abs(gyroAimY) < 0.008f) gyroAimY = 0f
            aimChanged = true
        }

        if (touchAimX != 0f || touchAimY != 0f) {
            val moving = touchpadMouseMode && (touchpadNode?.pointer ?: -1) != -1 &&
                now - camLastAt <= MOUSE_MOVE_HOLD_MS
            val base = when {
                !touchpadMouseMode -> SWIPE_DECAY
                moving -> MOUSE_ACTIVE_DECAY
                else -> MOUSE_RETURN_DECAY
            }
            val k = Math.pow(base.toDouble(), step.toDouble()).toFloat()
            touchAimX *= k
            touchAimY *= k
            if (abs(touchAimX) < 0.004f) touchAimX = 0f
            if (abs(touchAimY) < 0.004f) touchAimY = 0f
            aimChanged = true
        }

        if (aimChanged) {
            syncAim()
            publish(redraw = false)
            if (aimX != 0f || aimY != 0f || (touchpadNode?.pointer ?: -1) != -1) animating = true
        }

        if (aimTrainerActive) {
            // The trainer reticle is persistent: only flashes and target spawns
            // need a redraw here, never an aim-source decay.
            stepAimTrainer(now, dt)
            animating = true
        }

        val pad = touchpadNode
        if (pad != null) {
            // Ease the contact dot toward the finger instead of snapping to
            // every MotionEvent, then glide it home after release. Mouse mode
            // uses shorter times so the pointer feels immediate.
            val followMs = if (touchpadMouseMode) MOUSE_FOLLOW_MS else SWIPE_FOLLOW_MS
            val returnMs = if (touchpadMouseMode) MOUSE_RETURN_MS else SWIPE_RETURN_MS
            val fadeMs = if (touchpadMouseMode) MOUSE_FADE_MS else SWIPE_FADE_MS
            val follow = 1f - Math.exp((-dt / followMs).toDouble()).toFloat()
            pad.kx += (pad.trailX - pad.kx) * follow
            pad.ky += (pad.trailY - pad.ky) * follow
            if (abs(pad.kx) < 0.001f) pad.kx = 0f
            if (abs(pad.ky) < 0.001f) pad.ky = 0f

            val streakAlive = pad.ageSwipe(dt)
            if (pad.pointer == -1) {
                pad.trail = (pad.trail - dt / fadeMs).coerceAtLeast(0f)
                val home = Math.exp((-dt / returnMs).toDouble()).toFloat()
                pad.trailX *= home
                pad.trailY *= home
            } else {
                pad.trail = 1f
            }
            if (pad.trail > 0f || pad.kx != 0f || pad.ky != 0f || streakAlive) animating = true
        }

        if (themeT < 1f) {
            themeT = (themeT + dt / 300f).coerceAtMost(1f)
            // Smootherstep: no visible kick when the fade starts or ends.
            val e = themeT * themeT * themeT * (themeT * (themeT * 6f - 15f) + 10f)
            themeNow = if (themeT >= 1f) themeTarget else blendThemes(themeFrom, themeTarget, e)
            rebuildShaders()
            animating = true
        }

        if (enterT < 1f) {
            enterT = (enterT + dt / 460f).coerceAtMost(1f)
            animating = true
        }

        if (statusPulse > 0f) {
            statusPulse = (statusPulse - dt / 620f).coerceAtLeast(0f)
            animating = true
        }
    }

    private fun startAnimating() {
        if (animating) return
        animating = true
        lastAnimAt = 0L
        invalidate()
    }

    private fun drawWatermark(canvas: Canvas) {
        val size = H * 0.0132f
        val tracking = size * 0.18f
        val w = trackedWidth(Brand.MARK, size, tracking, true)
        drawTracked(
            canvas, Brand.MARK, W * 0.018f + w / 2f, H - size * 1.7f,
            size, withAlpha(theme.muted, 150), tracking, true,
        )
    }

    private fun drawPlaceholder(canvas: Canvas, n: Node) {
        val ex = n.extW() + dp(7f)
        val ey = n.extH() + dp(7f)
        rect.set(n.cx - ex, n.cy - ey, n.cx + ex, n.cy + ey)
        fillPaint.shader = null
        fillPaint.color = withAlpha(theme.surfaceAlt, 55)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), fillPaint)
        dashPaint.color = withAlpha(theme.muted, 160)
        dashPaint.strokeWidth = dp(1.2f)
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), dashPaint)
        dashPaint.pathEffect = null
        drawTracked(
            canvas, displayLabel(n), n.cx, n.cy, min(H * 0.019f, ey * 0.6f),
            withAlpha(theme.muted, 220), H * 0.0035f, true,
        )
    }

    /**
     * One-shot press ripple. Each palette owns a motion style, so the same
     * press reads differently without adding a second theme system.
     */
    private fun drawPressFx(
        canvas: Canvas,
        n: Node,
        cx: Float,
        cy: Float,
        radius: Float,
        accent: Int,
    ) {
        val t = n.ripple
        if (!motionEnabled || t <= 0f) return
        val m = Motions.of(theme)
        val ease = 1f - (1f - t) * (1f - t) * (1f - t)
        val fade = (1f - t).coerceAtLeast(0f)
        val reach = radius + radius * (m.spread - 1f) * ease
        strokePaint.pathEffect = null

        when (m.style) {
            "pulse" -> {
                strokePaint.color = withAlpha(accent, (140 * fade).toInt())
                strokePaint.strokeWidth = dp(1.2f + 2.4f * fade)
                canvas.drawCircle(cx, cy, reach, strokePaint)
            }
            "wave" -> {
                strokePaint.color = withAlpha(accent, (128 * fade).toInt())
                strokePaint.strokeWidth = dp(1.2f + 2f * fade)
                rect.set(cx - reach, cy - reach, cx + reach, cy + reach)
                canvas.drawArc(rect, -63f, 270f, false, strokePaint)
                strokePaint.color = withAlpha(accent, (72 * fade).toInt())
                strokePaint.strokeWidth = dp(1.4f)
                val r2 = reach * 0.8f
                rect.set(cx - r2, cy - r2, cx + r2, cy + r2)
                canvas.drawArc(rect, 27f, 270f, false, strokePaint)
            }
            "halo" -> {
                glowPaint.shader = RadialGradient(
                    cx, cy, reach.coerceAtLeast(1f),
                    intArrayOf(
                        glow(withAlpha(accent, (77 * fade).toInt())),
                        glow(withAlpha(accent, (41 * fade).toInt())),
                        glow(withAlpha(accent, 0)),
                    ),
                    floatArrayOf(0f, 0.62f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(cx, cy, reach, glowPaint)
                glowPaint.shader = null
                strokePaint.color = withAlpha(accent, (87 * fade).toInt())
                strokePaint.strokeWidth = dp(1.4f)
                canvas.drawCircle(cx, cy, reach * 0.94f, strokePaint)
            }
            "shock" -> {
                strokePaint.color = withAlpha(accent, (158 * fade).toInt())
                strokePaint.strokeWidth = dp(0.6f + 2f * fade)
                canvas.drawCircle(cx, cy, reach, strokePaint)
                strokePaint.color = withAlpha(accent, (77 * fade).toInt())
                strokePaint.strokeWidth = dp(1.4f)
                canvas.drawCircle(cx, cy, reach * 0.74f, strokePaint)
                strokePaint.color = withAlpha(accent, (56 * fade).toInt())
                for (i in 0 until 4) {
                    val a = Math.PI / 4.0 + i * Math.PI / 2.0
                    val c = cos(a).toFloat()
                    val sn = sin(a).toFloat()
                    canvas.drawLine(
                        cx + c * radius * 0.92f, cy + sn * radius * 0.92f,
                        cx + c * reach * 1.06f, cy + sn * reach * 1.06f,
                        strokePaint,
                    )
                }
            }
            "burst" -> {
                strokePaint.color = withAlpha(accent, (133 * fade).toInt())
                strokePaint.strokeWidth = dp(1.6f)
                val inner = radius * (0.72f + 0.16f * ease)
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4.0
                    val c = cos(a).toFloat()
                    val sn = sin(a).toFloat()
                    canvas.drawLine(
                        cx + c * inner, cy + sn * inner,
                        cx + c * reach * 1.08f, cy + sn * reach * 1.08f,
                        strokePaint,
                    )
                }
            }
            "step" -> {
                val stepped = kotlin.math.ceil(ease * 4f) / 4f
                val box = radius * (0.86f + (m.spread - 0.86f) * stepped)
                strokePaint.color = withAlpha(accent, (133 * fade).toInt())
                strokePaint.strokeWidth = dp(2.4f)
                rect.set(cx - box, cy - box, cx + box, cy + box)
                canvas.drawRect(rect, strokePaint)
            }
            "ripple" -> {
                strokePaint.strokeWidth = dp(1.4f)
                for (i in 0 until 3) {
                    val local = t - i * 0.20f
                    if (local <= 0f) continue
                    val k = local.coerceAtMost(1f)
                    val r = radius * (
                        0.9f + (m.spread - 0.9f) * (1f - (1f - k) * (1f - k) * (1f - k))
                        )
                    strokePaint.color = withAlpha(accent, (107 * (1f - k)).toInt())
                    canvas.drawCircle(cx, cy, r, strokePaint)
                }
            }
            "void" -> {
                // One hairline ring that snaps outward into the black.
                strokePaint.color = withAlpha(accent, (199 * fade).toInt())
                strokePaint.strokeWidth = dp(1f)
                canvas.drawCircle(cx, cy, reach, strokePaint)
                strokePaint.color = withAlpha(theme.text, (51 * fade).toInt())
                strokePaint.strokeWidth = dp(1f)
                canvas.drawCircle(cx, cy, reach + dp(2.6f), strokePaint)
            }
            else -> {
                val y = cy + radius * 0.86f
                val half = radius * (0.35f + (m.spread - 0.35f) * ease)
                strokePaint.color = withAlpha(accent, (158 * fade).toInt())
                strokePaint.strokeWidth = dp(2f)
                canvas.drawLine(cx - half, y, cx + half, y, strokePaint)
                fillPaint.color = withAlpha(accent, (168 * fade).toInt())
                canvas.drawCircle(cx - half, y, dp(1.6f), fillPaint)
                canvas.drawCircle(cx + half, y, dp(1.6f), fillPaint)
            }
        }
    }

    private fun drawDpad(canvas: Canvas, n: Node) {
        val r = n.r
        val accent = theme.accent

        fillPaint.shader = null
        fillPaint.color = theme.surface
        canvas.drawCircle(n.cx, n.cy, r, fillPaint)

        strokePaint.color = withAlpha(theme.text, 30)
        strokePaint.strokeWidth = dp(1.2f)
        canvas.drawCircle(n.cx, n.cy, r - dp(0.6f), strokePaint)

        strokePaint.color = withAlpha(accent, 80)
        strokePaint.strokeWidth = dp(2f)
        rect.set(n.cx - r + dp(2f), n.cy - r + dp(2f), n.cx + r - dp(2f), n.cy + r - dp(2f))
        canvas.drawArc(rect, 205f, 130f, false, strokePaint)

        drawPressFx(canvas, n, n.cx, n.cy, r, accent)

        val armT = r * 0.30f

        fun arm(cx: Float, cy: Float, hw: Float, hh: Float, press: Float, dx: Float, dy: Float) {
            val k = press.coerceIn(0f, 1f)
            val held = k > 0.01f
            if (held) {
                glowPaint.color = glow(withAlpha(accent, (26 + 29 * k).toInt()))
                rect.set(cx - hw - dp(4f), cy - hh - dp(4f), cx + hw + dp(4f), cy + hh + dp(4f))
                canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, glowPaint)
            }
            val inset = 1f - 0.02f * k
            val ahw = hw * inset
            val ahh = hh * inset
            rect.set(cx - ahw, cy - ahh, cx + ahw, cy + ahh)
            fillPaint.color = if (held) blend(theme.surfaceAlt, accent, 0.35f + 0.65f * k) else theme.surfaceAlt
            canvas.drawRoundRect(rect, r * 0.16f, r * 0.16f, fillPaint)
            strokePaint.color = if (held) withAlpha(accent, (115 + 105 * k).toInt()) else withAlpha(theme.text, 26)
            strokePaint.strokeWidth = dp(1f)
            canvas.drawRoundRect(rect, r * 0.16f, r * 0.16f, strokePaint)
            drawArrow(
                canvas, cx, cy, r, dx, dy,
                if (!held) {
                    withAlpha(theme.text, 150)
                } else if (k > 0.55f) {
                    theme.bgBottom
                } else {
                    withAlpha(theme.bgBottom, (102 + 153 * k).toInt())
                },
            )
        }

        arm(n.cx, n.cy - r * 0.55f, armT, r * 0.36f, n.armT[0], 0f, -1f)
        arm(n.cx, n.cy + r * 0.55f, armT, r * 0.36f, n.armT[1], 0f, 1f)
        arm(n.cx - r * 0.55f, n.cy, r * 0.36f, armT, n.armT[2], -1f, 0f)
        arm(n.cx + r * 0.55f, n.cy, r * 0.36f, armT, n.armT[3], 1f, 0f)

        fillPaint.color = theme.surfaceAlt
        canvas.drawCircle(n.cx, n.cy, r * 0.19f, fillPaint)
        fillPaint.color = if (n.pointer != -1) accent else withAlpha(accent, 130)
        canvas.drawCircle(n.cx, n.cy, r * 0.055f, fillPaint)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, r: Float, dx: Float, dy: Float, color: Int) {
        path.reset()
        val ax = cx + dx * r * 0.1f
        val ay = cy + dy * r * 0.1f
        val s = r * 0.11f
        if (dy != 0f) {
            val dir = if (dy < 0) -1f else 1f
            path.moveTo(ax, ay + s * dir * 1.2f)
            path.lineTo(ax - s, ay - s * dir * 0.5f)
            path.lineTo(ax + s, ay - s * dir * 0.5f)
        } else {
            val dir = if (dx < 0) -1f else 1f
            path.moveTo(ax + s * dir * 1.2f, ay)
            path.lineTo(ax - s * dir * 0.5f, ay - s)
            path.lineTo(ax - s * dir * 0.5f, ay + s)
        }
        path.close()
        fillPaint.color = color
        canvas.drawPath(path, fillPaint)
    }

    private fun drawStick(canvas: Canvas, n: Node) {
        val active = n.pointer != -1
        val accent = if (n.kind == K_STICK_L) theme.accent else theme.accent2
        val r = n.r

        fillPaint.shader = null
        fillPaint.color = theme.surface
        canvas.drawCircle(n.cx, n.cy, r, fillPaint)

        if (active) {
            strokePaint.color = withAlpha(accent, 40)
            strokePaint.strokeWidth = dp(7f)
            canvas.drawCircle(n.cx, n.cy, r - dp(3f), strokePaint)
        }
        strokePaint.color = withAlpha(accent, if (active) 230 else 70)
        strokePaint.strokeWidth = if (active) dp(2.4f) else dp(1.3f)
        canvas.drawCircle(n.cx, n.cy, r - dp(1f), strokePaint)

        strokePaint.color = withAlpha(theme.text, 26)
        strokePaint.strokeWidth = dp(1f)
        for (i in 0 until 4) {
            val a = Math.toRadians(45.0 + i * 90.0).toFloat()
            val c = cos(a)
            val s = sin(a)
            canvas.drawLine(
                n.cx + c * r * 0.82f, n.cy + s * r * 0.82f,
                n.cx + c * r * 0.93f, n.cy + s * r * 0.93f,
                strokePaint,
            )
        }

        strokePaint.color = withAlpha(theme.text, 18)
        strokePaint.strokeWidth = dp(1f)
        canvas.drawLine(n.cx - r * 0.92f, n.cy, n.cx + r * 0.92f, n.cy, strokePaint)
        canvas.drawLine(n.cx, n.cy - r * 0.92f, n.cx, n.cy + r * 0.92f, strokePaint)

        val kx = n.cx + n.kx * r
        val ky = n.cy + n.ky * r
        val kr = r * 0.52f

        if (active) {
            glowPaint.color = glow(withAlpha(accent, 50))
            canvas.drawCircle(kx, ky, kr * 1.3f, glowPaint)
        }
        fillPaint.color = if (active) blend(theme.surfaceAlt, accent, 0.20f) else theme.surfaceAlt
        canvas.drawCircle(kx, ky, kr, fillPaint)
        strokePaint.color = if (active) withAlpha(accent, 240) else withAlpha(theme.text, 55)
        strokePaint.strokeWidth = if (active) dp(2.2f) else dp(1.3f)
        canvas.drawCircle(kx, ky, kr, strokePaint)

        fillPaint.color = if (active) accent else withAlpha(accent, 170)
        canvas.drawCircle(kx, ky, kr * 0.16f, fillPaint)

        if (!active) {
            drawTracked(
                canvas,
                if (n.kind == K_STICK_L) "L STICK" else "R STICK",
                n.cx, n.cy + r * 0.72f, r * 0.105f,
                withAlpha(theme.text, 95),
                r * 0.028f, false,
            )
        }
    }
    private fun drawFace(canvas: Canvas, n: Node) {
        val i = n.faceIdx
        val accent = faceColor(i)
        val p = n.pressT
        val r = n.r * (1f + 0.055f * p)
        val cx = n.cx
        val cy = n.cy

        if (p > 0.01f) {
            glowPaint.color = glow(withAlpha(accent, (70 * p).toInt()))
            canvas.drawCircle(cx, cy, n.r * (1.10f + 0.22f * p), glowPaint)
        }
        fillPaint.shader = null
        fillPaint.color = blend(theme.surface, accent, 0.30f * p)
        canvas.drawCircle(cx, cy, r, fillPaint)

        strokePaint.color = withAlpha(accent, (150 + 105 * p).toInt())
        strokePaint.strokeWidth = dp(1.6f + 1.2f * p)
        canvas.drawCircle(cx, cy, r - dp(0.7f), strokePaint)

        // Faint inner ring adds the glassy depth the concept art has.
        strokePaint.color = withAlpha(theme.text, (18 + 24 * p).toInt())
        strokePaint.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, r * 0.80f, strokePaint)

        drawPressFx(canvas, n, cx, cy, n.r, accent)

        val ink = if (p > 0.55f) theme.bgBottom else accent
        if (faceLabels == FaceLabels.PLAYSTATION) {
            drawGlyph(canvas, i, cx, cy, r, ink)
        } else {
            val letter = when (i) {
                0 -> "Y"
                1 -> "B"
                2 -> "A"
                else -> "X"
            }
            drawTracked(canvas, letter, cx, cy, r * 0.95f, ink, 0f, true)
        }
    }

    private fun faceColor(i: Int): Int = Iconic.face(faceLabels, i)

    private fun drawGlyph(canvas: Canvas, i: Int, cx: Float, cy: Float, r: Float, color: Int) {
        glyphPaint.color = color
        glyphPaint.strokeWidth = r * 0.20f
        val g = r * 0.40f
        when (i) {
            0 -> {
                path.reset()
                path.moveTo(cx, cy - g * 1.12f)
                path.lineTo(cx + g * 1.02f, cy + g * 0.72f)
                path.lineTo(cx - g * 1.02f, cy + g * 0.72f)
                path.close()
                canvas.drawPath(path, glyphPaint)
            }
            1 -> canvas.drawCircle(cx, cy, g, glyphPaint)
            2 -> {
                canvas.drawLine(cx - g, cy - g, cx + g, cy + g, glyphPaint)
                canvas.drawLine(cx + g, cy - g, cx - g, cy + g, glyphPaint)
            }
            else -> {
                rect.set(cx - g, cy - g, cx + g, cy + g)
                canvas.drawRect(rect, glyphPaint)
            }
        }
    }

    private fun drawAction(canvas: Canvas, n: Node) {
        val p = n.pressT.coerceIn(0f, 1f)
        val pressed = n.pressed
        val accent = actionAccent(n)
        val r = n.r * (1f + 0.05f * p)

        if (p > 0.01f) {
            glowPaint.color = glow(withAlpha(accent, (76 * p).toInt()))
            canvas.drawCircle(n.cx, n.cy, n.r * (1.12f + 0.22f * p), glowPaint)
        }
        fillPaint.shader = null
        fillPaint.color = if (pressed) blend(theme.surface, accent, 0.45f * p) else theme.surface
        canvas.drawCircle(n.cx, n.cy, r, fillPaint)
        strokePaint.color = withAlpha(accent, (95 + 150 * p).toInt())
        strokePaint.strokeWidth = dp(1.4f + 1.1f * p)
        canvas.drawCircle(n.cx, n.cy, r - dp(0.7f), strokePaint)
        strokePaint.color = withAlpha(theme.text, (14 + 22 * p).toInt())
        strokePaint.strokeWidth = dp(1f)
        canvas.drawCircle(n.cx, n.cy, r * 0.82f, strokePaint)

        drawPressFx(canvas, n, n.cx, n.cy, n.r, accent)

        val label = displayLabel(n)
        val size = fitTrackedSize(label, n.r * 1.55f, n.r * 0.40f)
        drawTracked(
            canvas, label, n.cx, n.cy, size,
            if (p > 0.55f) theme.bgBottom else theme.textDim,
            size * 0.10f, true,
        )
    }

    private fun actionAccent(n: Node): Int = when (n.key) {
        "fire" -> FIRE_RED
        "aim" -> theme.accent2
        "nitro" -> theme.accent2
        "hand" -> theme.warn
        "gearUp" -> theme.ok
        "gearDown" -> theme.accent
        "brake" -> theme.warn
        else -> theme.accent
    }

    /** Progressive throttle/brake pedal: how low the thumb is sets the travel. */
    private fun drawPedal(canvas: Canvas, n: Node) {
        val accent = if (n.bit == Proto.R2) theme.ok else theme.warn
        val value = n.value.coerceIn(0f, 1f)
        val held = n.pointer != -1
        val radius = min(n.hw, n.hh) * 0.42f

        fillPaint.shader = null
        fillPaint.color = theme.surface
        rect.set(n.cx - n.hw, n.cy - n.hh, n.cx + n.hw, n.cy + n.hh)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        // Travel rises from the bottom of the pedal.
        if (value > 0.001f) {
            val bottom = n.cy + n.hh
            canvas.save()
            canvas.clipRect(n.cx - n.hw, n.cy - n.hh, n.cx + n.hw, bottom)
            fillPaint.color = withAlpha(accent, (44 + 118 * value).toInt())
            val top = bottom - n.hh * 2f * value
            rect.set(n.cx - n.hw, top, n.cx + n.hw, bottom)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            canvas.restore()
            strokePaint.color = withAlpha(accent, (120 + 110 * value).toInt())
            strokePaint.strokeWidth = dp(1.6f)
            canvas.drawLine(n.cx - n.hw + dp(3f), top, n.cx + n.hw - dp(3f), top, strokePaint)
        }

        rect.set(n.cx - n.hw, n.cy - n.hh, n.cx + n.hw, n.cy + n.hh)
        strokePaint.color = if (held) withAlpha(accent, 235) else withAlpha(theme.text, 46)
        strokePaint.strokeWidth = dp(1.3f + if (held) 1f else 0f)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        // Tick marks on the outer edge for a mechanical feel.
        strokePaint.color = withAlpha(theme.text, 26)
        strokePaint.strokeWidth = dp(1f)
        for (i in 1..4) {
            val y = n.cy + n.hh - n.hh * 2f * (i / 5f)
            canvas.drawLine(n.cx + n.hw - dp(5f), y, n.cx + n.hw - dp(1f), y, strokePaint)
        }

        drawPressFx(canvas, n, n.cx, n.cy, max(n.hw, n.hh) * 0.8f, accent)

        canvas.save()
        canvas.rotate(-90f, n.cx, n.cy - n.hh * 0.16f)
        val size = fitTrackedSize(n.label, n.hh * 1.15f, n.hw * 0.62f)
        drawTracked(
            canvas, n.label, n.cx, n.cy - n.hh * 0.16f, size,
            if (held) theme.text else theme.textDim, size * 0.12f, true,
        )
        canvas.restore()

        drawTracked(
            canvas, "${(value * 100f).roundToInt()}%", n.cx, n.cy + n.hh * 0.66f,
            n.hw * 0.46f, withAlpha(accent, if (value > 0f) 235 else 150), 0f, true,
        )
    }

    /** Wide steering bar: manual drag or the live tilt position. */
    private fun drawSteer(canvas: Canvas, n: Node) {
        val accent = theme.accent
        val steer = effectiveSteer()
        val radius = n.hh * 0.75f
        val manual = n.pointer != -1

        rect.set(n.cx - n.hw, n.cy - n.hh, n.cx + n.hw, n.cy + n.hh)
        fillPaint.shader = null
        fillPaint.color = theme.surface
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        strokePaint.color = if (manual) withAlpha(accent, 235) else withAlpha(theme.text, 44)
        strokePaint.strokeWidth = dp(1.3f + if (manual) 1f else 0f)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        // Scale ticks and the centre notch.
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = withAlpha(theme.text, 34)
        for (i in -4..4) {
            val x = n.cx + n.hw * 0.9f * (i / 4f)
            val tall = i == 0
            canvas.drawLine(
                x, n.cy - n.hh * (if (tall) 0.62f else 0.26f),
                x, n.cy + n.hh * (if (tall) 0.62f else 0.26f),
                strokePaint,
            )
        }

        // Highlighted travel from centre to the current steering angle.
        if (abs(steer) > 0.01f) {
            strokePaint.color = withAlpha(accent, 120)
            strokePaint.strokeWidth = dp(3f)
            canvas.drawLine(n.cx, n.cy + n.hh * 0.78f, n.cx + steer * n.hw * 0.9f, n.cy + n.hh * 0.78f, strokePaint)
        }

        val kx = n.cx + steer * n.hw * 0.9f
        glowPaint.color = glow(withAlpha(accent, if (manual) 90 else 55))
        canvas.drawCircle(kx, n.cy, n.hh * 1.05f, glowPaint)
        fillPaint.color = blend(theme.surfaceAlt, accent, if (manual) 0.55f else 0.30f)
        canvas.drawCircle(kx, n.cy, n.hh * 0.78f, fillPaint)
        strokePaint.color = withAlpha(accent, 220)
        strokePaint.strokeWidth = dp(1.5f)
        canvas.drawCircle(kx, n.cy, n.hh * 0.78f, strokePaint)
        fillPaint.color = accent
        canvas.drawCircle(kx, n.cy, n.hh * 0.16f, fillPaint)

        val hint = when {
            manual -> "MANUAL"
            gyroSteering -> "GYRO  ${tiltDeg.roundToInt()}°"
            else -> "TILT OFF · DRAG TO STEER"
        }
        val size = fitTrackedSize(hint, n.hw * 1.5f, n.hh * 0.74f)
        drawTracked(
            canvas, hint, n.cx, n.cy - n.hh * 0.42f, size,
            withAlpha(if (manual) theme.text else accent, 220), size * 0.12f, true,
        )
    }

    /** Swipe aim pad: swipe to swing the view, gyro may add to it. */
    private fun drawTouchpad(canvas: Canvas, n: Node) {
        val accent = theme.accent2
        val active = n.pointer != -1
        val radius = min(n.hw, n.hh) * 0.28f

        rect.set(n.cx - n.hw, n.cy - n.hh, n.cx + n.hw, n.cy + n.hh)
        fillPaint.shader = null
        fillPaint.color = withAlpha(theme.surface, if (active) 238 else 208)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        // Soft inner wash keeps the pad readable on light and dark palettes
        // without adding another outline or decorative pattern.
        val inset = dp(2f)
        rect.inset(inset, inset)
        fillPaint.color = withAlpha(theme.surfaceAlt, if (theme.dark) 26 else 42)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        rect.inset(-inset, -inset)

        strokePaint.color = withAlpha(accent, if (active) 210 else 72)
        strokePaint.strokeWidth = dp(1f + 0.9f * theme.border.coerceIn(0.6f, 1.8f))
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        // One quiet centre point replaces the old grid and crosshair.
        fillPaint.color = withAlpha(theme.textDim, if (active) 105 else 62)
        canvas.drawCircle(n.cx, n.cy, dp(if (active) 2.8f else 2.2f), fillPaint)

        val sampleCount = n.fillSwipeSamples()
        if (sampleCount >= 2) {
            fun sx(x: Float) = n.cx + x * n.hw * 0.82f
            fun sy(y: Float) = n.cy + y * n.hh * 0.82f

            // Draw each piece as a short quad through its newest point. The
            // endpoints are midpoints, so neighbouring pieces join smoothly
            // while still carrying their own age-based fade and width.
            for (i in 1 until sampleCount) {
                val x0 = (sx(n.sampleX[i - 1]) + sx(n.sampleX[i])) / 2f
                val y0 = (sy(n.sampleY[i - 1]) + sy(n.sampleY[i])) / 2f
                val x1 = sx(n.sampleX[i])
                val y1 = sy(n.sampleY[i])
                val hasNext = i + 1 < sampleCount
                val x2 = if (!hasNext) x1 else (x1 + sx(n.sampleX[i + 1])) / 2f
                val y2 = if (!hasNext) y1 else (y1 + sy(n.sampleY[i + 1])) / 2f
                val life = (n.sampleLife[i] * n.trail).coerceIn(0f, 1f)

                path.reset()
                path.moveTo(x0, y0)
                path.quadTo(x1, y1, x2, y2)
                strokePaint.color = glow(withAlpha(accent, (26 * life).toInt()))
                strokePaint.strokeWidth = dp(8f + 7f * life)
                canvas.drawPath(path, strokePaint)
                strokePaint.color = withAlpha(accent, (52 + 156 * life).toInt())
                strokePaint.strokeWidth = dp(1.1f + 2.1f * life)
                canvas.drawPath(path, strokePaint)
            }
        }

        val tx = n.cx + n.kx * n.hw * 0.82f
        val ty = n.cy + n.ky * n.hh * 0.82f
        if (n.trail > 0f) {
            val newestLife = if (sampleCount > 0) n.sampleLife[sampleCount - 1] else n.trail
            val mark = newestLife * n.trail
            glowPaint.color = glow(withAlpha(accent, (70 * mark).toInt()))
            canvas.drawCircle(tx, ty, n.hh * (0.10f + 0.05f * mark), glowPaint)
            if (touchpadMouseMode) {
                val s = n.hh * 0.07f
                path.reset()
                path.moveTo(tx, ty - s)
                path.lineTo(tx + s, ty + s)
                path.lineTo(tx, ty + s * 0.42f)
                path.lineTo(tx - s, ty + s)
                path.close()
                fillPaint.color = withAlpha(accent, (225 * n.trail).toInt())
                canvas.drawPath(path, fillPaint)
                strokePaint.color = withAlpha(theme.bgBottom, (130 * n.trail).toInt())
                strokePaint.strokeWidth = dp(1f)
                canvas.drawPath(path, strokePaint)
            } else {
                fillPaint.color = withAlpha(accent, (225 * n.trail).toInt())
                canvas.drawCircle(tx, ty, n.hh * (0.05f + 0.015f * mark), fillPaint)
            }
        }

        val label = when {
            touchpadMouseMode && gyroAim -> "MOUSE · GYRO"
            touchpadMouseMode -> "MOUSE AIM"
            gyroAim -> "SWIPE · GYRO"
            else -> "SWIPE AIM"
        }
        drawTracked(
            canvas, label, n.cx, n.cy - n.hh + dp(12f), n.hh * 0.095f,
            withAlpha(accent, if (active) 235 else 170), n.hh * 0.012f, true,
        )
        if (active) {
            drawTracked(
                canvas, "${
                    (hypot(aimX, aimY) * 100f).roundToInt()
                }%", n.cx, n.cy + n.hh - dp(12f), n.hh * 0.085f,
                withAlpha(theme.text, 150), n.hh * 0.008f, true,
            )
        }
    }

    /** Tilt steering is active only while a calibrated tilt is off centre. */
    private fun effectiveSteer(): Float = if (gyroSteering) steerValue else 0f

    private fun drawPill(canvas: Canvas, n: Node) {
        val accent = theme.accent
        val pressed = n.pressed
        val p = n.pressT.coerceIn(0f, 1f)
        fillPaint.shader = null

        if (n.kind == K_PS) {
            if (p > 0.01f) {
                glowPaint.color = glow(withAlpha(accent, (69 * p).toInt()))
                canvas.drawCircle(n.cx, n.cy, n.r * (1.08f + 0.14f * p), glowPaint)
            }
            fillPaint.color = if (pressed) blend(theme.surface, accent, 0.45f * p) else theme.surface
            canvas.drawCircle(n.cx, n.cy, n.r * (1f - 0.02f * p), fillPaint)
            strokePaint.color = if (pressed) withAlpha(accent, (140 + 115 * p).toInt()) else withAlpha(theme.text, 48)
            strokePaint.strokeWidth = dp(1.3f + 0.9f * p)
            canvas.drawCircle(n.cx, n.cy, n.r - dp(0.6f), strokePaint)
            drawPressFx(canvas, n, n.cx, n.cy, n.r, accent)
            drawTracked(
                canvas, n.label, n.cx, n.cy, n.r * 0.60f,
                if (p > 0.55f) theme.bgBottom else theme.textDim, n.r * 0.08f, true,
            )
            return
        }

        val radius = if (n.hh > n.hw) min(n.hw, n.hh) * 0.55f else n.hh * 0.55f
        val hw = n.hw * (1f - 0.018f * p)
        val hh = n.hh * (1f - 0.018f * p)

        if (p > 0.01f) {
            glowPaint.color = glow(withAlpha(accent, (56 * p).toInt()))
            rect.set(
                n.cx - n.hw - dp(3f), n.cy - n.hh - dp(3f),
                n.cx + n.hw + dp(3f), n.cy + n.hh + dp(3f),
            )
            canvas.drawRoundRect(rect, radius + dp(3f), radius + dp(3f), glowPaint)
        }
        rect.set(n.cx - hw, n.cy - hh, n.cx + hw, n.cy + hh)
        fillPaint.color = if (pressed) blend(theme.surface, accent, 0.42f * p) else theme.surface
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        strokePaint.color = if (pressed) withAlpha(accent, (140 + 115 * p).toInt()) else withAlpha(theme.text, 40)
        strokePaint.strokeWidth = dp(1.2f + 0.8f * p)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        drawPressFx(canvas, n, n.cx, n.cy, max(n.hw, n.hh), accent)

        val label = displayLabel(n)
        val size = fitTrackedSize(label, n.hw * 1.6f, min(n.hh * 1.05f, n.hw * 0.60f))
        drawTracked(
            canvas, label, n.cx, n.cy, size,
            if (p > 0.55f) theme.bgBottom else theme.textDim, size * 0.09f, true,
        )
    }

    private fun displayLabel(n: Node): String {
        if (faceLabels == FaceLabels.XBOX) {
            return when (n.key) {
                "l1" -> "LB"
                "l2" -> "LT"
                "r1" -> "RB"
                "r2" -> "RT"
                else -> n.label
            }
        }
        return n.label
    }

    // ------------------------------------------------------------------- HUD
    private fun drawHud(canvas: Canvas) {
        val h = H * 0.066f
        var textSize = H * 0.026f
        var tracking = textSize * 0.10f
        var tw = trackedWidth(statusText, textSize, tracking, true)

        // Long server names must not push the pill past the screen edges.
        val maxText = W * 0.82f - h * 1.42f
        if (tw > maxText && tw > 0f) {
            textSize *= maxText / tw
            tracking = textSize * 0.10f
            tw = trackedWidth(statusText, textSize, tracking, true)
        }

        val w = h * 0.62f + tw + h * 0.80f
        hudRect.set(W / 2f - w / 2f, H * 0.030f, W / 2f + w / 2f, H * 0.030f + h)

        if (hudPressed) {
            glowPaint.color = glow(withAlpha(theme.accent, 50))
            rect.set(hudRect.left - dp(4f), hudRect.top - dp(4f), hudRect.right + dp(4f), hudRect.bottom + dp(4f))
            canvas.drawRoundRect(rect, h / 2f, h / 2f, glowPaint)
        }

        fillPaint.shader = null
        fillPaint.color = withAlpha(theme.surface, 232)
        canvas.drawRoundRect(hudRect, h / 2f, h / 2f, fillPaint)
        strokePaint.color = if (hudPressed) withAlpha(theme.accent, 220) else withAlpha(theme.text, 34)
        strokePaint.strokeWidth = dp(1.2f)
        canvas.drawRoundRect(hudRect, h / 2f, h / 2f, strokePaint)

        val dotX = hudRect.left + h * 0.38f
        val dotR = h * 0.10f
        glowPaint.color = glow(withAlpha(statusColor(), 70))
        canvas.drawCircle(dotX, hudRect.centerY(), dotR * 1.9f, glowPaint)
        fillPaint.color = statusColor()
        canvas.drawCircle(dotX, hudRect.centerY(), dotR, fillPaint)

        // One-shot ripple when the status text changes.
        if (statusPulse > 0f) {
            strokePaint.color = withAlpha(statusColor(), (170 * statusPulse).toInt())
            strokePaint.strokeWidth = dp(1.2f)
            canvas.drawCircle(
                dotX, hudRect.centerY(),
                dotR * (1.3f + 3.0f * (1f - statusPulse)), strokePaint,
            )
        }

        drawTracked(
            canvas, statusText, hudRect.left + h * 0.62f + tw / 2f, hudRect.centerY(),
            textSize, theme.text, tracking, true,
        )
        drawGear(
            canvas, hudRect.right - h * 0.40f, hudRect.centerY(),
            h * 0.19f, withAlpha(theme.text, 150),
        )

        // Badge centred under the status pill so a long server name can never
        // collide with it. Surface whichever motion input is active.
        val badge = when {
            gyroSteering && abs(tiltDeg) >= 1f -> "GYRO · ${tiltDeg.roundToInt()}°"
            touchpadEnabled && touchpadMouseMode && gyroAim -> "MOUSE + GYRO"
            touchpadEnabled && touchpadMouseMode -> "MOUSE AIM"
            touchpadEnabled && gyroAim -> "SWIPE + GYRO"
            touchpadEnabled -> "SWIPE AIM"
            gyroAim || gyroSteering -> "GYRO READY"
            else -> "GAMEPAD"
        }
        val bsize = fitTrackedSize(badge, W * 0.42f, H * 0.0172f)
        val badgeY = hudRect.bottom + bsize * 0.92f
        val tw2 = trackedWidth(badge, bsize, bsize * 0.12f, true)
        val badgeDotR = bsize * 0.22f
        val dotGap = bsize * 0.55f
        val total = badgeDotR * 2f + dotGap + tw2
        val left = hudRect.centerX() - total / 2f
        fillPaint.shader = null
        fillPaint.color = withAlpha(theme.accent, 200)
        canvas.drawCircle(left + badgeDotR, badgeY, badgeDotR, fillPaint)
        drawTracked(
            canvas, badge, left + badgeDotR * 2f + dotGap + tw2 / 2f, badgeY,
            bsize, withAlpha(theme.accent, 225), bsize * 0.12f, true,
        )
    }

    private fun drawGear(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = dp(1.2f)
        canvas.drawCircle(cx, cy, r * 0.55f, strokePaint)
        for (i in 0 until 6) {
            val a = (Math.PI / 3.0 * i).toFloat()
            val c = cos(a)
            val s = sin(a)
            canvas.drawLine(
                cx + c * r * 0.75f, cy + s * r * 0.75f,
                cx + c * r * 1.05f, cy + s * r * 1.05f,
                strokePaint,
            )
        }
    }

    // --------------------------------------------------------------- text
    private fun trackedWidth(text: String, size: Float, tracking: Float, bold: Boolean): Float {
        textPaint.textSize = size
        textPaint.typeface = if (bold) boldFace else labelFace
        val tr = track(tracking)
        var w = 0f
        for (i in text.indices) {
            w += textPaint.measureText(text, i, i + 1)
            if (i < text.length - 1) w += tr
        }
        return w
    }

    private fun fitTrackedSize(text: String, maxWidth: Float, maxHeight: Float): Float {
        if (text.isEmpty()) return maxHeight
        val w = trackedWidth(text, maxHeight, maxHeight * 0.09f, true)
        if (w <= maxWidth || w <= 0f) return maxHeight
        return (maxHeight * maxWidth / w).coerceAtLeast(1f)
    }

    private fun drawTracked(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        tracking: Float,
        bold: Boolean,
    ) {
        if (text.isEmpty() || size <= 0f) return
        textPaint.textSize = size
        textPaint.typeface = if (bold) boldFace else labelFace
        textPaint.color = color
        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        val tr = track(tracking)
        var x = cx - trackedWidth(text, size, tracking, bold) / 2f
        for (i in text.indices) {
            val cw = textPaint.measureText(text, i, i + 1)
            canvas.drawText(text, i, i + 1, x, baseline, textPaint)
            x += cw + tr
        }
    }

    // --------------------------------------------------------------- edit overlay
    private fun drawEditOverlay(canvas: Canvas) {
        if (safeAreaEnabled && !safeArea.isEmpty) {
            dashPaint.color = withAlpha(theme.warn, 150)
            dashPaint.strokeWidth = dp(1.3f)
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(dp(9f), dp(7f)), 0f)
            canvas.drawRect(safeArea, dashPaint)
            dashPaint.pathEffect = null
            val labelSize = H * 0.015f
            drawTracked(
                canvas, "SAFE AREA", safeArea.centerX(),
                (safeArea.top - dp(9f)).coerceIn(labelSize, H),
                labelSize, withAlpha(theme.warn, 190), labelSize * 0.1f, true,
            )
        }

        if (snapEnabled) {
            val step = gridStep()
            dashPaint.color = withAlpha(theme.muted, 55)
            dashPaint.strokeWidth = dp(0.7f)
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(6f)), 0f)
            var gx = step
            while (gx < W) {
                canvas.drawLine(gx, 0f, gx, H, dashPaint)
                gx += step
            }
            var gy = step
            while (gy < H) {
                canvas.drawLine(0f, gy, W, gy, dashPaint)
                gy += step
            }
            dashPaint.pathEffect = null
        }

        for (n in nodes) {
            if (n.hidden) continue
            val ex = n.extW() + dp(7f)
            val ey = n.extH() + dp(7f)
            rect.set(n.cx - ex, n.cy - ey, n.cx + ex, n.cy + ey)
            val selected = selectedNodes.contains(n)

            if (selected) {
                fillPaint.shader = null
                fillPaint.color = withAlpha(theme.accent, 24)
                canvas.drawRoundRect(rect, dp(12f), dp(12f), fillPaint)
            }
            dashPaint.color = withAlpha(
                if (n.locked) theme.warn else if (selected) theme.accent else theme.muted,
                if (selected || n.locked) 230 else 105,
            )
            dashPaint.strokeWidth = if (selected) dp(1.8f) else dp(1.1f)
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
            canvas.drawRoundRect(rect, dp(12f), dp(12f), dashPaint)
            dashPaint.pathEffect = null

            val suffix = when {
                n.locked -> "  LOCKED"
                selected -> "  SELECTED"
                else -> ""
            }
            val label = "${n.label}  ${(n.scale * 100f).toInt()}%$suffix"
            val size = H * 0.017f
            val tracking = size * 0.09f
            val tw = trackedWidth(label, size, tracking, true)
            val lx = rect.centerX().coerceIn(tw / 2f + dp(4f), W - tw / 2f - dp(4f))
            val ly = if (rect.top > H * 0.10f) rect.top - dp(10f) else rect.bottom + dp(14f)
            drawTracked(canvas, label, lx, ly, size, withAlpha(theme.text, 195), tracking, true)
        }

        if (activeGuides.isNotEmpty()) {
            dashPaint.color = withAlpha(theme.ok, 200)
            dashPaint.strokeWidth = dp(1.2f)
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(dp(10f), dp(5f)), 0f)
            for (g in activeGuides) {
                if (g.vertical) canvas.drawLine(g.position, 0f, g.position, H, dashPaint)
                else canvas.drawLine(0f, g.position, W, g.position, dashPaint)
            }
            dashPaint.pathEffect = null
        }

        drawTracked(
            canvas, "DRAG TO MOVE   ·   PINCH TO RESIZE   ·   HOLD TO HIDE",
            W / 2f, H * 0.44f, H * 0.019f, withAlpha(theme.muted, 225), H * 0.0048f, true,
        )
    }
    // ---------------------------------------------------------------- status
    fun setStatus(text: String, kind: Int) {
        if (text == statusText && kind == statusKind) return
        // The text carries live RTT and battery, so it changes constantly.
        // Only a state change (idle -> linking -> connected) deserves the
        // ripple, otherwise the dot would pulse non-stop.
        val stateChanged = kind != statusKind
        statusText = text
        statusKind = kind
        if (stateChanged) statusPulse = 1f
        startAnimating()
        if (!editMode) invalidate()
    }

    private fun statusColor(): Int = when (statusKind) {
        STATUS_OK -> theme.ok
        STATUS_WARN -> theme.warn
        else -> theme.muted
    }

    fun resetInputs() {
        clearAim()
        steerValue = 0f
        for (n in nodes) {
            n.pressed = false
            n.pointer = -1
            n.up = false
            n.down = false
            n.left = false
            n.right = false
            n.kx = 0f
            n.ky = 0f
            n.value = 0f
            if (n.kind == K_TOUCHPAD) n.clearSwipe() else n.trail = 0f
            n.ripple = 0f
            for (i in 0 until 4) {
                n.armT[i] = 0f
                n.armVel[i] = 0f
            }
        }
        hudPointer = -1
        hudPressed = false
        publish()
        startAnimating()
    }

    private fun pressFx(n: Node) {
        if (!motionEnabled) return
        n.ripple = 0.0001f
        startAnimating()
    }

    // ----------------------------------------------------------------- touch
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (aimTrainerActive) {
            handleAimTrainerTouch(event)
            invalidate()
            return true
        }
        if (editMode) {
            handleEditTouch(event)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                grab(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    dragNode(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                releaseNode(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> resetInputs()
        }
        return true
    }

    private fun grab(id: Int, x: Float, y: Float) {
        // The four face buttons sit close together, so hit-testing picks the
        // nearest centre instead of whichever node happens to come first.
        val face = nearestFace(x, y)
        if (face != null && face.pointer == -1) {
            face.pointer = id
            face.pressed = true
            pressFx(face)
            tap()
            publish()
            invalidate()
            return
        }

        for (n in playOrder) {
            if (n.kind == K_FACE) continue
            if (n.hidden || n.pointer != -1 || !n.hit(x, y)) continue
            n.pointer = id
            when (n.kind) {
                K_DPAD -> moveDpad(n, x, y)
                K_STICK_L, K_STICK_R -> moveStick(n, x, y)
                K_STEER -> moveSteer(n, x)
                K_PEDAL -> movePedal(n, y)
                K_TOUCHPAD -> beginCamera(n, x, y)
                else -> {
                    n.pressed = true
                    pressFx(n)
                    tap()
                    publish()
                }
            }
            invalidate()
            return
        }
        if (hudPointer == -1 && hudRect.contains(x, y)) {
            hudPointer = id
            hudPressed = true
            tap()
            invalidate()
        }
    }

    private fun dragNode(id: Int, x: Float, y: Float) {
        for (n in playOrder) {
            if (n.pointer != id) continue
            when (n.kind) {
                K_DPAD -> moveDpad(n, x, y)
                K_STICK_L, K_STICK_R -> moveStick(n, x, y)
                K_FACE -> rollFace(n, id, x, y)
                K_STEER -> {
                    moveSteer(n, x)
                    invalidate()
                }
                K_PEDAL -> {
                    movePedal(n, y)
                    invalidate()
                }
                K_TOUCHPAD -> {
                    moveCamera(n, x, y)
                    invalidate()
                }
                else -> {
                    val inside = n.hit(x, y)
                    if (inside != n.pressed) {
                        n.pressed = inside
                        if (inside) pressFx(n)
                        publish()
                    }
                }
            }
            return
        }
        if (hudPointer == id) {
            hudPressed = hudRect.contains(x, y)
            invalidate()
        }
    }

    private fun releaseNode(id: Int, x: Float, y: Float) {
        for (n in playOrder) {
            if (n.pointer != id) continue
            n.pointer = -1
            when (n.kind) {
                K_STICK_L, K_STICK_R -> {
                    n.kx = 0f
                    n.ky = 0f
                }
                K_DPAD -> {
                    n.up = false
                    n.down = false
                    n.left = false
                    n.right = false
                }
                K_STEER -> n.value = 0f
                K_PEDAL -> {
                    n.value = 0f
                    n.pressed = false
                }
                // Leave the eased dot and swipe streak to fade out in
                // stepAnimations; snapping home here makes the release jump.
                // Mouse aim stops its velocity decay immediately on release.
                K_TOUCHPAD -> camLastAt = 0L
                else -> n.pressed = false
            }
            publish()
            return
        }
        if (hudPointer == id) {
            hudPointer = -1
            hudPressed = false
            invalidate()
            if (hudRect.contains(x, y)) onOpenSettings?.invoke()
        }
    }

    /**
     * A thumb that stays down rolls across the four separate buttons: the
     * press moves to whichever button centre is nearest the finger.
     */
    private fun rollFace(n: Node, id: Int, x: Float, y: Float) {
        val target = nearestFace(x, y, except = n)
        if (target === n) {
            if (!n.pressed) {
                n.pressed = true
                pressFx(n)
                tap()
                publish()
            }
            return
        }
        if (target != null) {
            n.pressed = false
            n.pointer = -1
            target.pointer = id
            target.pressed = true
            pressFx(target)
            tap()
            publish()
        } else if (n.pressed) {
            n.pressed = false
            publish()
        }
    }

    /** Nearest visible face button within reach, or null. */
    private fun nearestFace(x: Float, y: Float, except: Node? = null): Node? {
        var best: Node? = null
        var bestD = Float.MAX_VALUE
        for (n in faceNodes) {
            if (n.hidden) continue
            // Skip buttons another finger already holds, otherwise a second
            // thumb landing on the cluster steals the press and gets nothing.
            if (n.pointer != -1 && n !== except) continue
            val d = hypot(x - n.cx, y - n.cy)
            if (d <= n.r * 1.55f && d < bestD) {
                bestD = d
                best = n
            }
        }
        return best
    }

    private fun moveStick(n: Node, x: Float, y: Float) {
        var dx = x - n.cx
        var dy = y - n.cy
        val d = hypot(dx, dy)
        if (d > n.r) {
            dx = dx / d * n.r
            dy = dy / d * n.r
        }
        n.kx = dx / n.r
        n.ky = dy / n.r
        publish()
    }

    private fun moveDpad(n: Node, x: Float, y: Float) {
        val dx = x - n.cx
        val dy = y - n.cy
        val t = n.r * 0.33f
        val up = dy < -t
        val down = dy > t
        val left = dx < -t
        val right = dx > t
        if (up != n.up || down != n.down || left != n.left || right != n.right) {
            val wasActive = n.up || n.down || n.left || n.right
            n.up = up
            n.down = down
            n.left = left
            n.right = right
            if (!wasActive && (up || down || left || right)) pressFx(n)
            publish()
        }
    }

    private fun moveSteer(n: Node, x: Float) {
        n.value = ((x - n.cx) / (n.hw * 0.9f)).coerceIn(-1f, 1f)
        publish()
    }

    private fun movePedal(n: Node, y: Float) {
        val frac = ((y - (n.cy - n.hh)) / (2f * n.hh)).coerceIn(0f, 1f)
        n.value = frac
        n.pressed = frac > 0.02f
        publish()
    }

    private fun beginCamera(n: Node, x: Float, y: Float) {
        camLastX = x
        camLastY = y
        camLastAt = SystemClock.uptimeMillis()
        val sx = ((x - n.cx) / n.hw).coerceIn(-1f, 1f)
        val sy = ((y - n.cy) / n.hh).coerceIn(-1f, 1f)
        n.clearSwipe()
        n.trailX = sx
        n.trailY = sy
        n.kx = sx
        n.ky = sy
        n.trail = 1f
        n.pushSwipe(sx, sy)
        // A fresh mouse contact starts from rest; a BGMI swipe continues the
        // previous impulse so quick repeated swipes still chain naturally.
        if (touchpadMouseMode) {
            touchAimX = 0f
            touchAimY = 0f
            syncAim()
            publish(redraw = false)
        }
        tap()
        startAnimating()
    }

    /**
     * BGMI mode turns accumulated swipe distance into a camera impulse. Mouse
     * mode turns finger speed into right-axis velocity instead, so aim tracks
     * the movement and stops when the finger stops.
     */
    private fun moveCamera(n: Node, x: Float, y: Float) {
        val dx = x - camLastX
        val dy = y - camLastY
        val now = SystemClock.uptimeMillis()
        val dt = if (camLastAt == 0L) 16f else (now - camLastAt).toFloat().coerceIn(4f, 80f)
        camLastX = x
        camLastY = y
        camLastAt = now

        if (touchpadMouseMode) {
            val gain = MOUSE_GAIN_BASE + MOUSE_GAIN_SPAN * touchpadSensitivity
            val vx = (dx / dt * gain / max(1f, n.hw)).coerceIn(-1f, 1f)
            val vy = (dy / dt * gain / max(1f, n.hh)).coerceIn(-1f, 1f)
            val follow = 1f - Math.exp((-dt / MOUSE_FOLLOW_MS).toDouble()).toFloat()
            touchAimX = (touchAimX + (vx - touchAimX) * follow).coerceIn(-1f, 1f)
            touchAimY = (touchAimY + (vy - touchAimY) * follow).coerceIn(-1f, 1f)
        } else {
            val gain = (0.7f + 2.8f * touchpadSensitivity) / max(1f, n.hw)
            touchAimX = (touchAimX + dx * gain).coerceIn(-1f, 1f)
            touchAimY = (touchAimY + dy * gain).coerceIn(-1f, 1f)
        }
        syncAim()

        val sx = ((x - n.cx) / n.hw).coerceIn(-1f, 1f)
        val sy = ((y - n.cy) / n.hh).coerceIn(-1f, 1f)
        n.trailX = sx
        n.trailY = sy
        val last = n.lastSwipe()
        if (last == null || hypot(sx - last.first, sy - last.second) > 0.04f) {
            n.pushSwipe(sx, sy)
        }
        n.trail = 1f
        publish()
        startAnimating()
    }

    // --------------------------------------------------------------- edit touch
    private fun handleEditTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val i = event.actionIndex
                val x = event.getX(i)
                val y = event.getY(i)
                val n = nodeAt(x, y)
                if (n == null) {
                    if (multiSelectEnabled && selectedNodes.isNotEmpty()) {
                        selectedNodes.clear()
                        invalidate()
                    }
                    return
                }
                if (n.locked) {
                    onToast?.invoke("${n.label} is locked · unlock to edit")
                    return
                }
                if (multiSelectEnabled) {
                    selectedNodes.add(n)
                } else {
                    selectedNodes.clear()
                    selectedNodes.add(n)
                }
                editNode = n
                editPointer = event.getPointerId(i)
                if (n.hidden) {
                    n.hidden = false
                    onToast?.invoke("${n.label} restored")
                }
                dragOffX = n.cx - x
                dragOffY = n.cy - y
                downX = x
                downY = y
                moved = false
                beginEditChange()
                dragStart.clear()
                for (s in selectedNodes) {
                    if (!s.locked) dragStart[s] = s.cx to s.cy
                }
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, 520L)
                onHaptic?.invoke(8)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (editNode != null && pinchPointer == -1) {
                    val i = event.actionIndex
                    pinchPointer = event.getPointerId(i)
                    val ei = event.findPointerIndex(editPointer)
                    if (ei >= 0) {
                        pinchStartDist = hypot(
                            event.getX(i) - event.getX(ei),
                            event.getY(i) - event.getY(ei),
                        ).coerceAtLeast(1f)
                    }
                    pinchStartScales.clear()
                    for (s in selectedNodes) {
                        if (!s.locked) pinchStartScales[s] = s.scale
                    }
                    pinchStartScale = editNode?.scale ?: 1f
                    moved = true
                    removeCallbacks(longPressRunnable)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val n = editNode ?: return
                if (pinchPointer != -1) {
                    val pi = event.findPointerIndex(pinchPointer)
                    val ei = event.findPointerIndex(editPointer)
                    if (pi >= 0 && ei >= 0) {
                        val d = hypot(
                            event.getX(pi) - event.getX(ei),
                            event.getY(pi) - event.getY(ei),
                        )
                        val ratio = (d / pinchStartDist).coerceIn(0.3f, 3.5f)
                        for (s in selectedNodes) {
                            if (s.locked) continue
                            val base = pinchStartScales[s] ?: s.scale
                            s.scale = (base * ratio).coerceIn(0.55f, 1.9f)
                            if (s.kind == K_FACE) s.customized = true
                        }
                        layoutControls()
                        invalidate()
                    }
                    return
                }
                val ei = event.findPointerIndex(editPointer)
                if (ei < 0) return
                val x = event.getX(ei)
                val y = event.getY(ei)
                if (!moved && hypot(x - downX, y - downY) > dp(10f)) {
                    moved = true
                    removeCallbacks(longPressRunnable)
                }
                if (!moved) return
                activeGuides.clear()
                var targetX = (x + dragOffX).coerceIn(0f, W)
                var targetY = (y + dragOffY).coerceIn(0f, H)
                if (snapEnabled) {
                    val step = gridStep()
                    targetX = round(targetX / step) * step
                    targetY = round(targetY / step) * step
                }
                if (guidesEnabled) {
                    val snapped = snapToGuides(n, targetX, targetY)
                    targetX = snapped.first
                    targetY = snapped.second
                }
                val start = dragStart[n] ?: (n.cx to n.cy)
                val dx = targetX - start.first
                val dy = targetY - start.second
                for ((node, origin) in dragStart) {
                    node.fx = ((origin.first + dx) / W).coerceIn(0f, 1f)
                    node.fy = ((origin.second + dy) / H).coerceIn(0f, 1f)
                    if (node.kind == K_FACE) node.customized = true
                }
                layoutControls()
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (id == pinchPointer) {
                    pinchPointer = -1
                } else if (id == editPointer) {
                    finishEdit()
                }
            }
            MotionEvent.ACTION_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (id == pinchPointer) pinchPointer = -1
                if (id == editPointer) finishEdit()
            }
            MotionEvent.ACTION_CANCEL -> finishEdit()
        }
    }

    private fun gridStep(): Float = max(8f, dp(18f))

    /** Aligns the dragged control with other controls and the canvas midpoint. */
    private fun snapToGuides(primary: Node, x: Float, y: Float): Pair<Float, Float> {
        activeGuides.clear()
        if (!guidesEnabled) return x to y
        val threshold = dp(6f)
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        xs += W / 2f
        ys += H / 2f
        for (n in nodes) {
            if (n === primary || n.hidden || n.locked) continue
            val ex = n.extW()
            val ey = n.extH()
            xs += n.cx
            xs += n.cx - ex
            xs += n.cx + ex
            ys += n.cy
            ys += n.cy - ey
            ys += n.cy + ey
        }
        var outX = x
        var outY = y
        var bestX: Float? = null
        var bestDX = threshold
        for (c in xs) {
            val d = abs(c - x)
            if (d <= bestDX) {
                bestDX = d
                bestX = c
            }
        }
        var bestY: Float? = null
        var bestDY = threshold
        for (c in ys) {
            val d = abs(c - y)
            if (d <= bestDY) {
                bestDY = d
                bestY = c
            }
        }
        if (bestX != null) {
            outX = bestX
            activeGuides += EditorGuide(true, outX)
        }
        if (bestY != null) {
            outY = bestY
            activeGuides += EditorGuide(false, outY)
        }
        return outX to outY
    }

    private fun beginEditChange() {
        if (editBefore == null) editBefore = layoutSnapshot()
    }

    /** Commits the snapshot taken when the current drag began, if it changed. */
    private fun commitPendingEdit() {
        val before = editBefore ?: return
        editBefore = null
        layoutControls()
        val after = layoutSnapshot()
        if (after == before) return
        pushHistory(before)
        onLayoutChanged?.invoke()
        invalidate()
    }

    /** Applies a tool action as one undoable step. */
    private fun commitLayoutChange(change: () -> Unit) {
        commitPendingEdit()
        val before = layoutSnapshot()
        change()
        layoutControls()
        val after = layoutSnapshot()
        if (after == before) return
        pushHistory(before)
        onLayoutChanged?.invoke()
        invalidate()
    }

    private fun pushHistory(snapshot: String) {
        if (undoStack.lastOrNull() == snapshot) return
        undoStack.addLast(snapshot)
        while (undoStack.size > 40) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun finishEdit() {
        removeCallbacks(longPressRunnable)
        editNode = null
        editPointer = -1
        pinchPointer = -1
        moved = false
        activeGuides.clear()
        dragStart.clear()
        pinchStartScales.clear()
        commitPendingEdit()
    }

    private fun nodeAt(x: Float, y: Float): Node? {
        for (i in nodes.indices.reversed()) {
            val n = nodes[i]
            if (n.locked) continue
            val ex = n.extW() + dp(10f)
            val ey = n.extH() + dp(10f)
            if (x >= n.cx - ex && x <= n.cx + ex && y >= n.cy - ey && y <= n.cy + ey) return n
        }
        return null
    }

    // ------------------------------------------------------------- motion input
    private fun handleTilt(degrees: Float) {
        tiltDeg = degrees
        val maxTilt = 38f - 26f * steerSensitivity
        val raw = (degrees / maxTilt).coerceIn(-1f, 1f)
        val dz = steerDeadzone
        steerValue = if (abs(raw) <= dz || dz >= 0.999f) {
            0f
        } else {
            val sign = if (raw < 0f) -1f else 1f
            val x = (abs(raw) - dz) / (1f - dz)
            sign * (x * (1f - steerCurve + steerCurve * x * x))
        }
        if (gyroSteering) publish(redraw = false)
        invalidate()
    }

    private fun handleGyro(yaw: Float, pitch: Float) {
        if (!gyroAim) return
        if (aimTrainerActive) {
            // In the drill, gyro nudges the persistent reticle instead of a
            // decaying right-stick impulse, so it stays where you stop rotating.
            val gain = TRAINER_GYRO_GAIN_BASE + TRAINER_GYRO_GAIN_SPAN * aimSensitivity
            val xSign = if (gyroRevertX) -1f else 1f
            val ySign = if (gyroRevertY) -1f else 1f
            trainerReticleX = (trainerReticleX + yaw * gain * xSign).coerceIn(-TRAINER_SPAN, TRAINER_SPAN)
            trainerReticleY = (trainerReticleY + pitch * gain * ySign).coerceIn(-TRAINER_SPAN, TRAINER_SPAN)
            trainerGyroDelta += abs(yaw) + abs(pitch)
            startAnimating()
            invalidate()
            return
        }
        val gain = 1.3f + 4.6f * aimSensitivity
        val xSign = if (gyroRevertX) -1f else 1f
        val ySign = if (gyroRevertY) -1f else 1f
        gyroAimX = (gyroAimX + yaw * gain * xSign).coerceIn(-1f, 1f)
        gyroAimY = (gyroAimY + pitch * gain * ySign).coerceIn(-1f, 1f)
        syncAim()
        if (aimX != 0f || aimY != 0f) {
            publish(redraw = false)
            startAnimating()
        }
        invalidate()
    }

    /** Registers only the sensors enabled for tilt steering and gyro aim. */
    private fun applyMotionPrefs() {
        motion?.setEnabled(gyroSteering, gyroAim)
        sensorStatus = motion?.status().orEmpty()
    }

    fun startMotion() {
        motionStarted = true
        applyMotionPrefs()
        motion?.start()
    }

    fun stopMotion() {
        motionStarted = false
        motion?.stop()
    }

    // ---------------------------------------------------------------- publish
    private fun publish(redraw: Boolean = true) {
        if (aimTrainerActive) {
            // Gyro callbacks and aim decay keep calling publish() with no
            // touch at all. Hold every output neutral for the whole drill,
            // bumping only once when the suspension begins.
            state.buttons = 0
            state.lx = 0
            state.ly = 0
            state.rx = 0
            state.ry = 0
            state.l2 = 0
            state.r2 = 0
            if (!trainerNeutralSent) {
                trainerNeutralSent = true
                state.bump()
            }
            return
        }
        var b = 0
        for (n in nodes) {
            when (n.kind) {
                K_DPAD -> {
                    if (n.up) b = b or (1 shl Proto.UP)
                    if (n.down) b = b or (1 shl Proto.DOWN)
                    if (n.left) b = b or (1 shl Proto.LEFT)
                    if (n.right) b = b or (1 shl Proto.RIGHT)
                }
                K_PEDAL -> {
                    if (n.bit >= 0 && n.value > 0.02f) b = b or (1 shl n.bit)
                }
                else -> if (n.bit >= 0 && n.pressed) b = b or (1 shl n.bit)
            }
        }

        state.buttons = b

        // Tilt takes over left X only while it is off centre; the physical
        // stick still drives movement when the phone is held level.
        val (lx, ly) = leftStick?.let { axes(it) } ?: (0 to 0)
        val steer = effectiveSteer()
        state.lx = if (steer != 0f) (steer * 32767f).roundToInt() else lx
        state.ly = ly

        // The swipe pad replaces right-stick aim when enabled. Without it,
        // gyro aim is added on top of the stick and fades back to centre.
        val aimRx = (aimX * 32767f).roundToInt()
        val aimRy = (-aimY * 32767f).roundToInt()
        val (rx, ry) = rightStick?.let { axes(it) } ?: (0 to 0)
        if (touchpadEnabled && touchpadNode != null) {
            state.rx = aimRx
            state.ry = aimRy
        } else if (gyroAim && (aimX != 0f || aimY != 0f)) {
            state.rx = (rx + aimRx).coerceIn(-32767, 32767)
            state.ry = (ry + aimRy).coerceIn(-32767, 32767)
        } else {
            state.rx = rx
            state.ry = ry
        }

        var l2 = 0f
        var r2 = 0f
        for (n in nodes) {
            val value = when {
                n.bit != Proto.L2 && n.bit != Proto.R2 -> 0f
                n.kind == K_PEDAL -> n.value
                n.pressed -> 1f
                else -> 0f
            }
            if (n.bit == Proto.L2) l2 = max(l2, value)
            if (n.bit == Proto.R2) r2 = max(r2, value)
        }
        state.l2 = (l2 * 255f).roundToInt().coerceIn(0, 255)
        state.r2 = (r2 * 255f).roundToInt().coerceIn(0, 255)
        state.bump()

        if (redraw) {
            invalidate()
            startAnimating()
        }
    }

    private fun axes(n: Node): Pair<Int, Int> {
        val mag = hypot(n.kx, n.ky)
        if (mag <= deadzone) return 0 to 0
        val scaled = ((mag - deadzone) / (1f - deadzone)).coerceAtMost(1f)
        // A precision curve keeps small corrections small: 0 is linear, 1 pulls
        // the response towards the centre without changing full deflection.
        val curve = stickCurve.coerceIn(0f, 1f)
        val shaped = if (curve <= 0.001f) scaled else scaled * (1f - curve + curve * scaled * scaled)
        val ux = n.kx / mag * shaped
        val uy = n.ky / mag * shaped
        return (ux * 32767f).toInt() to (-uy * 32767f).toInt()
    }

    // ----------------------------------------------------------------- layout persistence
    fun layoutSnapshot(): String {
        val sb = StringBuilder()
        for (n in nodes) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(n.key).append(':')
                .append((n.fx * 10000f).toInt()).append(',')
                .append((n.fy * 10000f).toInt()).append(',')
                .append((n.scale * 1000f).toInt()).append(',')
                .append(if (n.hidden) 1 else 0)
            // Trailing field keeps every existing 4-field layout byte-identical.
            if (n.locked) sb.append(",1")
        }
        return sb.toString()
    }

    /** Applies a saved snapshot without touching undo history. */
    fun applyLayout(data: String?) {
        applyLayoutData(data)
        clearLayoutHistory()
    }

    private fun applyLayoutData(data: String?) {
        if (data.isNullOrBlank()) return
        legacyFace = null
        val parsed = HashMap<String, IntArray>()
        for (chunk in data.split(';')) {
            val pair = chunk.split(':', limit = 2)
            if (pair.size != 2) continue
            val v = pair[1].split(',')
            if (v.size < 3) continue
            parsed[pair[0]] = intArrayOf(
                v[0].toIntOrNull() ?: 0,
                v[1].toIntOrNull() ?: 0,
                v[2].toIntOrNull() ?: 1000,
                if (v.getOrNull(3) == "1") 1 else 0,
                if (v.getOrNull(4) == "1") 1 else 0,
            )
        }

        // Keep a customised right-stick placement when swapping to or from the
        // swipe pad, but never inherit a hidden flag.
        if (touchpadEnabled && touchpadNode != null && !parsed.containsKey("touchpad")) {
            parsed["rs"]?.let { parsed["touchpad"] = intArrayOf(it[0], it[1], it[2], 0, it.getOrNull(4) ?: 0) }
        } else if (!touchpadEnabled && rightStick != null && !parsed.containsKey("rs")) {
            parsed["touchpad"]?.let { parsed["rs"] = intArrayOf(it[0], it[1], it[2], 0, it.getOrNull(4) ?: 0) }
        }

        // 1.0 saved the four face buttons as a single "face" node.
        parsed["face"]?.let { old ->
            legacyFace = floatArrayOf(
                old[0] / 10000f,
                old[1] / 10000f,
                (old[2] / 1000f).coerceIn(0.55f, 1.9f),
                old[3].toFloat(),
            )
        }

        for ((key, v) in parsed) {
            if (key == "face") continue
            val n = nodes.firstOrNull { it.key == key } ?: continue
            val fx = (v[0] / 10000f).coerceIn(0f, 1f)
            val fy = (v[1] / 10000f).coerceIn(0f, 1f)
            val scale = (v[2] / 1000f).coerceIn(0.55f, 1.9f)
            n.fx = fx
            n.fy = fy
            n.scale = scale
            n.hidden = v[3] == 1
            n.locked = v.getOrNull(4) == 1
            // A saved layout that still sits exactly on the defaults
            // must not lock the face cluster out of the spacing slider.
            val moved = abs(fx - n.defFx) > 0.0008f || abs(fy - n.defFy) > 0.0008f
            n.customized = moved || n.hidden || abs(scale - 1f) > 0.01f
        }
        layoutControls()
        invalidate()
    }

    fun resetLayout() = commitLayoutChange {
        legacyFace = null
        clearAim()
        for (n in nodes) {
            n.scale = 1f
            n.hidden = false
            n.locked = false
            n.customized = false
            n.fx = n.defFx
            n.fy = n.defFy
        }
        selectedNodes.clear()
    }

    fun showAll() = commitLayoutChange {
        for (n in nodes) n.hidden = false
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
    fun hasSelection(): Boolean = selectedNodes.isNotEmpty()
    fun selectionLocked(): Boolean =
        selectedNodes.isNotEmpty() && selectedNodes.all { it.locked }

    fun clearLayoutHistory() {
        undoStack.clear()
        redoStack.clear()
        editBefore = null
    }

    fun undoLayout(): Boolean {
        commitPendingEdit()
        if (undoStack.isEmpty()) return false
        val current = layoutSnapshot()
        val previous = undoStack.removeLast()
        redoStack.addLast(current)
        applyLayoutData(previous)
        onLayoutChanged?.invoke()
        invalidate()
        return true
    }

    fun redoLayout(): Boolean {
        commitPendingEdit()
        if (redoStack.isEmpty()) return false
        val current = layoutSnapshot()
        val next = redoStack.removeLast()
        undoStack.addLast(current)
        applyLayoutData(next)
        onLayoutChanged?.invoke()
        invalidate()
        return true
    }

    /** Copies position/scale/visibility/lock from every selected control. */
    fun copySelected(): Int {
        val targets = selectedNodes.filter { !it.locked }
        if (targets.isEmpty()) {
            onToast?.invoke("Tap a control to copy first")
            return 0
        }
        clipboard.clear()
        for (n in targets) {
            clipboard[n.key] = NodeLayoutState(
                n.fx, n.fy, n.scale, n.hidden, n.locked, n.customized,
            )
        }
        onToast?.invoke("Copied ${clipboard.size} control(s)")
        return clipboard.size
    }

    /**
     * Pastes by key, or in key order onto the current selection. Button bits and
     * identities never change, so this cannot alter the HID report mapping.
     */
    fun pasteSelected(): Int {
        if (clipboard.isEmpty()) {
            onToast?.invoke("Copy a control first")
            return 0
        }
        val targets = if (selectedNodes.isNotEmpty()) {
            selectedNodes.sortedBy { it.key }
        } else {
            clipboard.keys.mapNotNull { key -> nodeForPasteKey(key) }
        }
        val usable = targets.filter { !it.locked }
        if (usable.isEmpty()) {
            onToast?.invoke("Unlock a target before pasting")
            return 0
        }
        val entries = clipboard.entries.sortedBy { it.key }
        var applied = 0
        commitLayoutChange {
            for (i in entries.indices) {
                val target = usable.getOrNull(i) ?: break
                val s = entries[i].value
                target.fx = s.fx
                target.fy = s.fy
                target.scale = s.scale
                target.hidden = s.hidden
                target.locked = s.locked
                target.customized = s.customized
                applied++
            }
        }
        onToast?.invoke("Pasted $applied control(s)")
        return applied
    }

    private fun nodeForPasteKey(key: String): Node? = nodes.firstOrNull { it.key == key }
        ?: when (key) {
            "rs" -> nodes.firstOrNull { it.key == "touchpad" }
            "touchpad" -> nodes.firstOrNull { it.key == "rs" }
            else -> null
        }

    fun toggleLockSelected(): Boolean {
        val targets = selectedNodes.ifEmpty { listOfNotNull(editNode) }
        if (targets.isEmpty()) {
            onToast?.invoke("Tap a control to lock first")
            return false
        }
        val lock = targets.any { !it.locked }
        commitLayoutChange {
            for (n in targets) n.locked = lock
        }
        onToast?.invoke(if (lock) "Locked ${targets.size} control(s)" else "Unlocked ${targets.size} control(s)")
        return lock
    }

    fun mirrorSelected(): Int {
        val targets = if (selectedNodes.isNotEmpty()) {
            selectedNodes.toList()
        } else {
            nodes.toList()
        }.filter { !it.locked }
        if (targets.isEmpty()) {
            onToast?.invoke("Unlock a control before mirroring")
            return 0
        }
        commitLayoutChange {
            for (n in targets) {
                if (n.kind == K_FACE) n.customized = true
                n.fx = 1f - n.fx
            }
        }
        onToast?.invoke("Mirrored ${targets.size} control(s)")
        return targets.size
    }

    // ------------------------------------------------------------- aim trainer
    private fun trainerBegin() {
        clearAim()
        trainerHits = 0
        trainerShots = 0
        trainerTotalMs = 0f
        trainerOnTargetMs = 0f
        trainerTargetVisible = false
        trainerNextAt = 0L
        trainerReticleX = 0f
        trainerReticleY = 0f
        trainerHitX = 0f
        trainerHitY = 0f
        trainerFlash = 0f
        trainerMissFlash = 0f
        trainerFeedback = ""
        trainerFeedbackUntil = 0L
        trainerComplete = false
        trainerSummaryText = ""
        trainerPointerId = -1
        trainerMoved = false
        trainerErrSumX = 0f
        trainerErrSumY = 0f
        trainerErrMs = 0f
        trainerTouchDelta = 0f
        trainerGyroDelta = 0f
        trainerHasPrevErr = false
        trainerCrossX = 0
        trainerCrossY = 0
        trainerDoneRect.set(0f, 0f, 0f, 0f)
        trainerNeutralSent = false
        trainerSpawnTarget(SystemClock.uptimeMillis())
    }

    private fun trainerReset() {
        trainerTargetVisible = false
        trainerPointerId = -1
        trainerMoved = false
        trainerComplete = false
        trainerDoneRect.set(0f, 0f, 0f, 0f)
    }

    private fun trainerSummary(): String =
        trainerSummaryText.ifBlank { trainerBuildSummary() }

    private fun trainerSpawnTarget(now: Long) {
        var x = 0f
        var y = 0f
        // Keep the target away from wherever the persistent reticle currently sits.
        for (attempt in 0 until 12) {
            x = Random.nextFloat() * 2f * TRAINER_SPAN - TRAINER_SPAN
            y = Random.nextFloat() * 2f * TRAINER_SPAN - TRAINER_SPAN
            if (hypot(x - trainerReticleX, y - trainerReticleY) > 0.38f || attempt == 11) break
        }
        trainerTargetX = x
        trainerTargetY = y
        trainerTargetVisible = true
        trainerPrevErrX = trainerReticleX - x
        trainerPrevErrY = trainerReticleY - y
        trainerHasPrevErr = true
        trainerNextAt = now
    }

    private fun startTrainerPointer(id: Int, x: Float, y: Float) {
        trainerPointerId = id
        trainerDownX = x
        trainerDownY = y
        trainerLastX = x
        trainerLastY = y
        trainerMoved = false
    }

    private fun handleAimTrainerTouch(event: MotionEvent) {
        val action = event.actionMasked
        val i = event.actionIndex.coerceAtMost((event.pointerCount - 1).coerceAtLeast(0))
        val x = event.getX(i)
        val y = event.getY(i)
        val id = event.getPointerId(i)
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (trainerDoneRect.contains(x, y)) {
                    aimTrainerActive = false
                    return
                }
                if (trainerPointerId == -1) startTrainerPointer(id, x, y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (trainerPointerId == -1) startTrainerPointer(id, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(trainerPointerId)
                if (trainerComplete || index < 0 || trainerPointerId == -1) return
                val nx = event.getX(index)
                val ny = event.getY(index)
                val dx = nx - trainerLastX
                val dy = ny - trainerLastY
                trainerLastX = nx
                trainerLastY = ny
                if (!trainerMoved &&
                    hypot(nx - trainerDownX, ny - trainerDownY) > dp(TRAINER_TAP_SLOP_DP)
                ) {
                    trainerMoved = true
                }
                if (dx != 0f || dy != 0f) applyTrainerAim(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (id != trainerPointerId) return
                val wasTap = !trainerMoved
                trainerPointerId = -1
                if (wasTap) trainerFire()
            }
            MotionEvent.ACTION_CANCEL -> trainerPointerId = -1
        }
    }

    /**
     * Moves the persistent aim point directly, the way relative mouse aim moves
     * a cursor: what you drag is where the reticle stays. Reuses the touchpad
     * sensitivity so the drill reflects the live aim setup.
     */
    private fun applyTrainerAim(dx: Float, dy: Float) {
        val reach = max(1f, min(W, H) * TRAINER_SPAN)
        val gain = (0.7f + 2.8f * touchpadSensitivity) / reach
        trainerReticleX = (trainerReticleX + dx * gain).coerceIn(-TRAINER_SPAN, TRAINER_SPAN)
        // Screen Y grows downwards; trainerReticleY is positive upwards.
        trainerReticleY = (trainerReticleY - dy * gain).coerceIn(-TRAINER_SPAN, TRAINER_SPAN)
        trainerTouchDelta += abs(dx) + abs(dy)
        startAnimating()
    }

    private fun stepAimTrainer(now: Long, dt: Float) {
        if (trainerFlash > 0f) trainerFlash = (trainerFlash - dt / 320f).coerceAtLeast(0f)
        if (trainerMissFlash > 0f) trainerMissFlash = (trainerMissFlash - dt / 260f).coerceAtLeast(0f)
        if (trainerFeedback.isNotEmpty() && now > trainerFeedbackUntil) trainerFeedback = ""
        if (trainerComplete) return
        if (!trainerTargetVisible) {
            if (now >= trainerNextAt) trainerSpawnTarget(now)
            return
        }

        val errX = trainerReticleX - trainerTargetX
        val errY = trainerReticleY - trainerTargetY
        val reach = max(1f, min(W, H) * TRAINER_SPAN)
        val onTarget = hypot(errX, errY) <= dp(TRAINER_TARGET_RADIUS_DP) / reach

        trainerTotalMs += dt
        if (onTarget) trainerOnTargetMs += dt
        if (abs(errX) < 0.6f && abs(errY) < 0.6f) {
            trainerErrSumX += errX * dt
            trainerErrSumY += errY * dt
            trainerErrMs += dt
        }
        if (trainerHasPrevErr) {
            if (trainerPrevErrX * errX < 0f && abs(trainerPrevErrX) > 0.03f && abs(errX) > 0.03f) {
                trainerCrossX++
            }
            if (trainerPrevErrY * errY < 0f && abs(trainerPrevErrY) > 0.03f && abs(errY) > 0.03f) {
                trainerCrossY++
            }
        }
        trainerPrevErrX = errX
        trainerPrevErrY = errY
        trainerHasPrevErr = true
    }

    /** Tap-to-fire: a tap scores only when the persistent reticle is on target. */
    private fun trainerFire() {
        if (trainerComplete || !trainerTargetVisible) return
        val now = SystemClock.uptimeMillis()
        trainerShots++
        val reach = max(1f, min(W, H) * TRAINER_SPAN)
        val onTarget = hypot(
            trainerReticleX - trainerTargetX,
            trainerReticleY - trainerTargetY,
        ) <= dp(TRAINER_TARGET_RADIUS_DP) / reach
        trainerFeedbackUntil = now + TRAINER_FEEDBACK_MS
        if (onTarget) {
            trainerFeedback = "HIT"
            trainerRegisterHit(now)
        } else {
            trainerFeedback = "MISS"
            trainerMissFlash = 1f
            onHaptic?.invoke(8)
        }
    }

    private fun trainerRegisterHit(now: Long) {
        trainerHits++
        trainerFlash = 1f
        trainerHitX = trainerTargetX
        trainerHitY = trainerTargetY
        onHaptic?.invoke(20)
        trainerTargetVisible = false
        if (trainerHits >= TRAINER_TARGETS) {
            trainerComplete = true
            trainerSummaryText = trainerBuildSummary()
            onHaptic?.invoke(28)
        } else {
            trainerNextAt = now + TRAINER_NEXT_DELAY_MS
        }
    }

    private fun trainerBuildSummary(): String {
        val steady = if (trainerTotalMs > 0f) {
            (trainerOnTargetMs * 100f / trainerTotalMs).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        val accuracy = if (trainerShots > 0) {
            (trainerHits * 100f / trainerShots).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        val avgX = if (trainerErrMs > 0f) trainerErrSumX / trainerErrMs else 0f
        val avgY = if (trainerErrMs > 0f) trainerErrSumY / trainerErrMs else 0f
        val advice = when {
            trainerCrossX >= 2 || trainerCrossY >= 2 ->
                "You pass the target — lower aim sensitivity."
            abs(avgX) > 0.12f && abs(avgY) > 0.12f ->
                "Both axes drift — try both Revert switches."
            abs(avgX) > 0.12f ->
                "Horizontal drift — try Revert left/right aim."
            abs(avgY) > 0.12f ->
                "Vertical drift — try Revert up/down aim."
            accuracy >= 80 && steady >= 50 -> "Steady aim — this setup looks aligned."
            else -> "Drag to move the reticle, then tap to fire."
        }
        return "$trainerHits/$TRAINER_TARGETS targets · $accuracy% shots · $steady% on target\n$advice"
    }

    private fun trainerBuildResult(summary: String): AimTrainerResult {
        val steady = if (trainerTotalMs > 0f) {
            (trainerOnTargetMs * 100f / trainerTotalMs).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        val accuracy = if (trainerShots > 0) {
            (trainerHits * 100f / trainerShots).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        return AimTrainerResult(
            hits = trainerHits,
            shots = trainerShots,
            accuracy = accuracy,
            steadiness = steady,
            driftX = if (trainerErrMs > 0f) trainerErrSumX / trainerErrMs else 0f,
            driftY = if (trainerErrMs > 0f) trainerErrSumY / trainerErrMs else 0f,
            crossesX = trainerCrossX,
            crossesY = trainerCrossY,
            touchpadSensitivity = (touchpadSensitivity * 100f).roundToInt().coerceIn(0, 100),
            aimSensitivity = (aimSensitivity * 100f).roundToInt().coerceIn(0, 100),
            gyroRevertX = gyroRevertX,
            gyroRevertY = gyroRevertY,
            gyroAim = gyroAim,
            touchpadMouseMode = touchpadMouseMode,
            usedTouch = trainerTouchDelta > 0f,
            usedGyro = trainerGyroDelta > 0f,
            summary = summary,
        )
    }

    private fun drawAimTrainer(canvas: Canvas) {
        val t = theme
        trainerFill.color = withAlpha(t.bgTop, 238)
        canvas.drawRect(0f, 0f, W, H, trainerFill)

        val cx = W / 2f
        val cy = H / 2f
        val reach = max(1f, min(W, H) * TRAINER_SPAN)
        trainerLine.strokeWidth = dp(1f)
        trainerLine.color = withAlpha(t.accent, 75)
        canvas.drawRoundRect(
            cx - reach - dp(12f), cy - reach - dp(12f),
            cx + reach + dp(12f), cy + reach + dp(12f),
            dp(18f), dp(18f), trainerLine,
        )

        val doneW = dp(78f)
        val doneH = dp(34f)
        val doneX = W - doneW - dp(14f)
        val doneY = dp(14f)
        trainerDoneRect.set(doneX, doneY, doneX + doneW, doneY + doneH)
        trainerFill.color = withAlpha(t.surfaceAlt, 240)
        canvas.drawRoundRect(trainerDoneRect, dp(10f), dp(10f), trainerFill)
        trainerLine.color = withAlpha(t.accent, 170)
        canvas.drawRoundRect(trainerDoneRect, dp(10f), dp(10f), trainerLine)
        trainerText.color = t.text
        trainerText.textSize = dp(11f)
        canvas.drawText("DONE", trainerDoneRect.centerX(), doneY + doneH * 0.66f, trainerText)

        trainerText.color = t.accent
        trainerText.textSize = dp(12f)
        canvas.drawText("AIM TRAINER  ·  $trainerHits/$TRAINER_TARGETS", cx, dp(38f), trainerText)
        trainerText.color = withAlpha(t.muted, 225)
        trainerText.textSize = dp(10f)
        canvas.drawText(
            if (trainerComplete) "Run complete · DONE to close"
            else "Drag to move the reticle · tap to fire",
            cx,
            H - dp(22f),
            trainerText,
        )

        if (trainerComplete) {
            val lines = trainerSummaryText.split('\n')
            val boxW = min(W - dp(48f), dp(330f))
            val boxH = dp(126f)
            rect.set(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f)
            trainerFill.color = withAlpha(t.surface, 248)
            canvas.drawRoundRect(rect, dp(16f), dp(16f), trainerFill)
            trainerLine.color = withAlpha(t.accent, 200)
            trainerLine.strokeWidth = dp(1.5f)
            canvas.drawRoundRect(rect, dp(16f), dp(16f), trainerLine)
            trainerText.color = t.accent
            trainerText.textSize = dp(13f)
            canvas.drawText("RUN COMPLETE", cx, rect.top + dp(30f), trainerText)
            trainerText.color = t.text
            trainerText.textSize = dp(11f)
            for ((index, line) in lines.withIndex()) {
                canvas.drawText(line, cx, rect.top + dp(58f) + index * dp(24f), trainerText)
            }
            return
        }

        // Hit burst is drawn even after the target disappears, so the shot has
        // the same visual confirmation as a game hit marker.
        if (trainerFlash > 0f) {
            val hx = cx + trainerHitX * reach
            val hy = cy - trainerHitY * reach
            trainerLine.color = withAlpha(t.ok, (180 * trainerFlash).toInt())
            trainerLine.strokeWidth = dp(3f)
            canvas.drawCircle(hx, hy, dp(TRAINER_TARGET_RADIUS_DP) + (1f - trainerFlash) * dp(34f), trainerLine)
        }

        if (trainerTargetVisible) {
            val tx = cx + trainerTargetX * reach
            val ty = cy - trainerTargetY * reach
            val tr = dp(TRAINER_TARGET_RADIUS_DP)
            trainerFill.color = withAlpha(t.accent2, 55)
            canvas.drawCircle(tx, ty, tr, trainerFill)
            trainerLine.color = withAlpha(t.accent2, 230)
            trainerLine.strokeWidth = dp(2f)
            canvas.drawCircle(tx, ty, tr, trainerLine)
            trainerLine.color = withAlpha(t.accent2, 110)
            trainerLine.strokeWidth = dp(1f)
            canvas.drawCircle(tx, ty, tr * 0.5f, trainerLine)
        }

        val rx = cx + (trainerReticleX * reach).coerceIn(-reach, reach)
        val ry = cy - (trainerReticleY * reach).coerceIn(-reach, reach)
        val rr = dp(TRAINER_RETICLE_RADIUS_DP)
        if (trainerMissFlash > 0f) {
            trainerLine.color = withAlpha(FIRE_RED, (170 * trainerMissFlash).toInt())
            trainerLine.strokeWidth = dp(2f)
            canvas.drawCircle(rx, ry, rr + (1f - trainerMissFlash) * dp(18f), trainerLine)
        }
        trainerLine.color = withAlpha(t.text, 235)
        trainerLine.strokeWidth = dp(1.6f)
        canvas.drawCircle(rx, ry, rr, trainerLine)
        trainerLine.color = withAlpha(t.accent, 230)
        trainerLine.strokeWidth = dp(2f)
        canvas.drawLine(rx - rr - dp(6f), ry, rx - rr + dp(2f), ry, trainerLine)
        canvas.drawLine(rx + rr - dp(2f), ry, rx + rr + dp(6f), ry, trainerLine)
        canvas.drawLine(rx, ry - rr - dp(6f), rx, ry - rr + dp(2f), trainerLine)
        canvas.drawLine(rx, ry + rr - dp(2f), rx, ry + rr + dp(6f), trainerLine)
        trainerFill.color = t.accent
        canvas.drawCircle(rx, ry, dp(2.5f), trainerFill)

        if (trainerFeedback.isNotEmpty()) {
            trainerText.color = if (trainerFeedback == "HIT") t.ok else FIRE_RED
            trainerText.textSize = dp(14f)
            canvas.drawText(trainerFeedback, rx, ry - rr - dp(24f), trainerText)
        }
    }

    // ----------------------------------------------------------------- misc
    private fun tap() {
        if (hapticsEnabled) onHaptic?.invoke(12)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** Squarer screens (4:3 tablets) shrink the big controls to avoid collisions. */
    private fun aspectScale(): Float = min(1f, (W / H) / 1.75f)

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        const val STATUS_IDLE = 0
        const val STATUS_OK = 1
        const val STATUS_WARN = 2

        /** Face buttons are laid out ±this many screen-heights from the anchor. */
        private const val FACE_SPREAD = 0.115f

        /** Press spring, tuned per 60 Hz frame (slightly under-damped). */
        private const val PRESS_STIFFNESS = 0.30f
        private const val PRESS_DAMPING = 0.70f

        /** Fire is hot red in every theme, like the trigger it maps to. */
        private val FIRE_RED = 0xFFFF5A45.toInt()
    }
}
