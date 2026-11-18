package com.alamshowkat.lemogamepad

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A concrete, one-tap change suggested after an aim calibration run.
 *
 * Nullable fields mean "leave this setting alone". Recommendations never touch
 * connection settings or the wire protocol; they only change aim preferences.
 */
data class AimRecommendation(
    val title: String,
    val detail: String,
    val touchpadSensitivity: Int? = null,
    val aimSensitivity: Int? = null,
    val gyroRevertX: Boolean? = null,
    val gyroRevertY: Boolean? = null,
) {
    val hasChanges: Boolean
        get() = touchpadSensitivity != null || aimSensitivity != null ||
            gyroRevertX != null || gyroRevertY != null
}

/** Turns measured trainer stats into a small, explainable setting change. */
fun recommendAim(result: AimTrainerResult): AimRecommendation? {
    if (result.shots == 0) {
        return AimRecommendation(
            title = "Fire a few shots first",
            detail = "Tap when the reticle is on a target so the drill has data to work with.",
        )
    }

    val overshoot = result.crossesX >= 2 || result.crossesY >= 2
    if (overshoot) {
        val step = max(5, min(20, max(result.crossesX, result.crossesY) * 5))
        val touchNext = if (result.usedTouch) {
            max(5, result.touchpadSensitivity - step).takeIf { it != result.touchpadSensitivity }
        } else {
            null
        }
        val gyroNext = if (result.usedGyro && result.gyroAim) {
            max(5, result.aimSensitivity - step).takeIf { it != result.aimSensitivity }
        } else {
            null
        }
        if (touchNext != null || gyroNext != null) {
            val parts = mutableListOf<String>()
            if (touchNext != null) parts += "touchpad ${touchNext}%"
            if (gyroNext != null) parts += "gyro ${gyroNext}%"
            return AimRecommendation(
                title = "You are passing targets",
                detail = "Lower ${parts.joinToString(" and ")} and try again.",
                touchpadSensitivity = touchNext,
                aimSensitivity = gyroNext,
            )
        }
    }

    // Revert switches only affect gyro, so never suggest them for a touch-only run.
    if (result.usedGyro && result.gyroAim) {
        val driftX = abs(result.driftX) > 0.12f
        val driftY = abs(result.driftY) > 0.12f
        if (driftX || driftY) {
            val flipX = if (driftX) !result.gyroRevertX else null
            val flipY = if (driftY) !result.gyroRevertY else null
            val label = when {
                driftX && driftY -> "both Revert switches"
                driftX -> "Revert left/right aim"
                else -> "Revert up/down aim"
            }
            return AimRecommendation(
                title = "Gyro aim drifts to one side",
                detail = "Flip $label, then repeat the drill.",
                gyroRevertX = flipX,
                gyroRevertY = flipY,
            )
        }
    }

    if (result.accuracy >= 80 && result.steadiness >= 50) {
        return AimRecommendation(
            title = "Aim looks aligned",
            detail = "No change needed — this setup is steady enough to play.",
        )
    }

    return AimRecommendation(
        title = "Slow down and tap",
        detail = "Place the reticle on the target, pause, then tap to fire. Accuracy ${result.accuracy}%, on target ${result.steadiness}%.",
    )
}
