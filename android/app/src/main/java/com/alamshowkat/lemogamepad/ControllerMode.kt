package com.alamshowkat.lemogamepad

/**
 * The three experiences the pad can present.
 *
 * Every mode reuses the same wire format ([Proto]) and the same [PadState]
 * axes, so switching a layout never changes what the PC server or the
 * Bluetooth HID host receives:
 *
 * - [GAMEPAD] keeps the original Xbox/PlayStation arrangement.
 * - [RACING] maps tilt or the steering bar to the left X axis, throttle to
 *   R2 and brake to L2, and the remaining actions to ordinary buttons.
 * - [FPS] maps the camera touchpad (and optional gyro aiming) to the right
 *   stick, fire to R2 and aim to L2.
 */
enum class ControllerMode(
    val id: String,
    val label: String,
    val tagline: String,
    val detail: String,
) {
    GAMEPAD(
        "gamepad",
        "GAMEPAD",
        "Xbox · PlayStation",
        "Full controller with sticks, d-pad, triggers and face buttons.",
    ),
    RACING(
        "racing",
        "RACING",
        "Tilt to steer",
        "Gyro or manual steering, throttle, brake, nitro, gears and camera.",
    ),
    FPS(
        "fps",
        "FPS",
        "Swipe camera",
        "Touchpad camera, fire, aim, reload, jump, crouch, sprint and swap.",
    );

    companion object {
        fun fromId(id: String?): ControllerMode =
            values().firstOrNull { it.id == id } ?: GAMEPAD
    }
}

/** Preference key that stores the live layout for a mode. */
internal fun layoutPrefKey(mode: ControllerMode): String = when (mode) {
    // The original key is kept for GAMEPAD so upgrading users keep their pad.
    ControllerMode.GAMEPAD -> PrefsKeys.LAYOUT
    ControllerMode.RACING -> PrefsKeys.LAYOUT_RACING
    ControllerMode.FPS -> PrefsKeys.LAYOUT_FPS
}

/** Preference key that stores the active named layout for a mode. */
internal fun activeLayoutPrefKey(mode: ControllerMode): String = when (mode) {
    ControllerMode.GAMEPAD -> PrefsKeys.LAYOUT_ACTIVE
    ControllerMode.RACING -> PrefsKeys.LAYOUT_ACTIVE_RACING
    ControllerMode.FPS -> PrefsKeys.LAYOUT_ACTIVE_FPS
}

/** Preference key that stores the saved layout list for a mode. */
internal fun layoutsPrefKey(mode: ControllerMode): String = when (mode) {
    ControllerMode.GAMEPAD -> PrefsKeys.LAYOUTS
    ControllerMode.RACING -> PrefsKeys.LAYOUTS_RACING
    ControllerMode.FPS -> PrefsKeys.LAYOUTS_FPS
}
