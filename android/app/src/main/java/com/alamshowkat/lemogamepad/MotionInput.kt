package com.alamshowkat.lemogamepad

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Accelerometer + gyroscope input for the racing and FPS layouts.
 *
 * Only the sensors a mode actually needs are registered, and [stop] releases
 * them in `onPause`, so an idle pad never keeps a sensor awake.
 *
 * Coordinates are rotated into the current screen orientation before use:
 * the accelerometer gives a gravity-referenced tilt angle (no drift), and the
 * gyroscope gives angular deltas that drive FPS aiming.
 */
class MotionInput(context: Context) {

    interface Listener {
        /** Tilt in degrees relative to the last [calibrate] or zero. */
        fun onTilt(degrees: Float)

        /** Screen-relative rotation since the previous sample, in radians. */
        fun onGyro(yaw: Float, pitch: Float)

        fun onAvailability(accelerometer: Boolean, gyroscope: Boolean)
    }

    private val appContext = context.applicationContext
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val sensors =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelerometer: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile
    var listener: Listener? = null

    private var steeringWanted = false
    private var aimWanted = false
    private var registered = false

    /** Which sensors the current registration actually holds. */
    private var accelRegistered = false
    private var gyroRegistered = false

    // Gravity low-pass, in device coordinates.
    private val gravity = FloatArray(3)
    private var gravityPrimed = false
    private var angle = 0f
    private var angleSmooth = 0f
    private var center = 0f
    private var centerPending = false
    private var lastGyroAt = 0L

    val hasAccelerometer: Boolean get() = accelerometer != null
    val hasGyroscope: Boolean get() = gyroscope != null

    /** Human-readable sensor state for the settings sheet. */
    fun status(): String {
        val accel = if (hasAccelerometer) "accelerometer ✓" else "accelerometer —"
        val gyro = if (hasGyroscope) "gyroscope ✓" else "gyroscope —"
        val note = when {
            !hasAccelerometer && !hasGyroscope -> "No motion sensors on this phone"
            !hasAccelerometer -> "Tilt steering unavailable · $gyro"
            !hasGyroscope -> "Gyro aiming unavailable · $accel"
            else -> "$accel  ·  $gyro"
        }
        return note
    }

    /** Enables the sensors the current mode needs. Safe to call repeatedly. */
    fun setEnabled(steering: Boolean, aim: Boolean) {
        val changed = steering != steeringWanted || aim != aimWanted
        steeringWanted = steering
        aimWanted = aim
        if (changed) updateRegistration()
    }

    fun start() {
        updateRegistration()
    }

    fun stop() {
        if (!registered) return
        registered = false
        accelRegistered = false
        gyroRegistered = false
        try {
            sensors?.unregisterListener(eventListener)
        } catch (_: Exception) {
        }
    }

    /**
     * Treats the current tilt as dead centre. Relative readings start again
     * from zero, so the steering bar does not jump when the phone is held in a
     * new, more comfortable position.
     */
    fun calibrate() {
        center = angleSmooth
        centerPending = false
        listener?.onTilt(0f)
    }

    fun availability(): Pair<Boolean, Boolean> = hasAccelerometer to hasGyroscope

    private fun updateRegistration() {
        val wantAccel = steeringWanted && hasAccelerometer
        val wantGyro = (aimWanted || steeringWanted) && hasGyroscope
        val needed = wantAccel || wantGyro
        if (!needed) {
            stop()
            listener?.onAvailability(hasAccelerometer, hasGyroscope)
            return
        }
        // A mode switch can change which sensors are needed while the view is
        // already running (racing needs accel, FPS aim only needs the gyro).
        // Re-register whenever the set differs, not just on the first start.
        if (registered && (wantAccel != accelRegistered || wantGyro != gyroRegistered)) {
            stop()
        }
        if (!registered) {
            registered = true
            accelRegistered = wantAccel
            gyroRegistered = wantGyro
            try {
                if (wantAccel) {
                    centerPending = true
                    sensors?.registerListener(
                        eventListener,
                        accelerometer,
                        SensorManager.SENSOR_DELAY_GAME,
                    )
                }
                if (wantGyro) {
                    sensors?.registerListener(
                        eventListener,
                        gyroscope,
                        SensorManager.SENSOR_DELAY_GAME,
                    )
                }
            } catch (_: Exception) {
                registered = false
                accelRegistered = false
                gyroRegistered = false
            }
        }
        listener?.onAvailability(hasAccelerometer, hasGyroscope)
    }

    private val eventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> onAccelerometer(event)
                Sensor.TYPE_GYROSCOPE -> onGyroscope(event)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun onAccelerometer(event: SensorEvent) {
        if (event.values.size < 3) return
        val alpha = if (gravityPrimed) 0.16f else 1f
        for (i in 0 until 3) {
            gravity[i] += (event.values[i] - gravity[i]) * alpha
        }
        gravityPrimed = true

        val mapped = toScreen(gravity[0], gravity[1], gravity[2])
        // Angle of gravity away from the screen's vertical plane. This reads
        // the same whether the phone is held upright or flat like a tray.
        val raw = Math.toDegrees(
            atan2(mapped[0].toDouble(), hypot(mapped[1].toDouble(), mapped[2].toDouble())),
        ).toFloat()
        angle = raw
        angleSmooth += (angle - angleSmooth) * 0.22f
        if (centerPending) {
            // The first reading is how the player is already holding the
            // phone, so enabling tilt never snaps the wheel to full lock.
            center = angleSmooth
            centerPending = false
        }
        listener?.onTilt(angleSmooth - center)
    }

    private fun onGyroscope(event: SensorEvent) {
        if (event.values.size < 3) return
        val now = event.timestamp
        val dt = if (lastGyroAt == 0L) 0f else ((now - lastGyroAt) / 1_000_000_000.0).toFloat()
        lastGyroAt = now
        if (dt <= 0f || dt > 0.08f) return
        val mapped = toScreen(event.values[0], event.values[1], event.values[2])
        // Screen Y is up: turning right is a negative yaw, and tilting the top
        // of the phone away from the player is a negative pitch.
        listener?.onGyro(-mapped[1] * dt, -mapped[0] * dt)
    }

    /** Rotates a device vector into the axes of the current display. */
    private fun toScreen(x: Float, y: Float, z: Float): FloatArray {
        return when (displayRotation()) {
            Surface.ROTATION_90 -> floatArrayOf(y, -x, z)
            Surface.ROTATION_180 -> floatArrayOf(-x, -y, z)
            Surface.ROTATION_270 -> floatArrayOf(-y, x, z)
            else -> floatArrayOf(x, y, z)
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = try {
        windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    } catch (_: Exception) {
        Surface.ROTATION_0
    }
}
