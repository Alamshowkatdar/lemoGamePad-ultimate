package com.alamshowkat.lemogamepad

/**
 * Inputs available to the adaptive stream-quality controller.
 *
 * Video and link statistics live in different objects: the control link owns
 * RTT/jitter/loss, while [ScreenView] owns the received video FPS. MainActivity
 * is the only place that can see both, so it samples this snapshot there.
 */
internal data class AdaptiveSignal(
    val linkConnected: Boolean,
    val streamConnected: Boolean,
    val streamFps: Int,
    val rttMs: Long,
    val jitterMs: Int,
    val lossPermille: Int,
)

/**
 * Suggests a sharpness change from recent stream health.
 *
 * Every quality change reconnects the video socket, so this controller is
 * deliberately slow: it needs several consecutive bad or good windows, waits
 * after each change, and steps aside for a while after a manual chip tap.
 * All profiles still target 60 fps; this only lowers or raises sharpness.
 */
internal class AdaptiveQualityController(
    private val nowMs: () -> Long,
) {
    private var manualUntilMs = 0L
    // Negative sentinel: uptimeMillis() starts near 0 after boot, so a plain 0
    // would look like a recent change and block decisions for the cooldown.
    private var lastChangeMs = -CHANGE_COOLDOWN_MS
    private var badWindows = 0
    private var goodWindows = 0

    /**
     * Returns the next quality to apply, or null to stay where we are.
     * [current] is passed in so a profile switch or manual tap can never leave
     * the controller with a stale idea of the active quality.
     */
    fun update(current: ScreenQuality, signal: AdaptiveSignal): ScreenQuality? {
        val now = nowMs()
        if (now < manualUntilMs || now - lastChangeMs < CHANGE_COOLDOWN_MS) {
            resetWindows()
            return null
        }
        if (!signal.linkConnected || !signal.streamConnected || signal.streamFps <= 0) {
            // Reconnecting windows say nothing about steady-state sharpness.
            resetWindows()
            return null
        }

        val loss = signal.lossPermille.coerceAtLeast(0)
        val rtt = signal.rttMs
        val bad = signal.streamFps < 45 ||
            (rtt >= 0 && rtt > HIGH_RTT_MS) ||
            signal.jitterMs > HIGH_JITTER_MS ||
            loss > HIGH_LOSS_PERMILLE
        val good = signal.streamFps >= 57 &&
            rtt >= 0 && rtt < LOW_RTT_MS &&
            signal.jitterMs <= LOW_JITTER_MS &&
            loss <= LOW_LOSS_PERMILLE

        when {
            bad -> {
                badWindows++
                goodWindows = 0
                if (badWindows >= DOWNGRADE_WINDOWS && current != ScreenQuality.LOW) {
                    lastChangeMs = now
                    resetWindows()
                    return lower(current)
                }
            }
            good -> {
                goodWindows++
                badWindows = 0
                if (goodWindows >= UPGRADE_WINDOWS && current != ScreenQuality.HIGH) {
                    lastChangeMs = now
                    resetWindows()
                    return higher(current)
                }
            }
            else -> resetWindows()
        }
        return null
    }

    /** Manual chip tap: leave the user's choice alone for a while. */
    fun noteManual() {
        manualUntilMs = nowMs() + MANUAL_SUSPEND_MS
        resetWindows()
    }

    /** Stream started/stopped or a profile changed quality; restart sampling. */
    fun reset() {
        lastChangeMs = -CHANGE_COOLDOWN_MS
        resetWindows()
    }

    private fun resetWindows() {
        badWindows = 0
        goodWindows = 0
    }

    private fun lower(q: ScreenQuality): ScreenQuality = when (q) {
        ScreenQuality.HIGH -> ScreenQuality.BALANCED
        ScreenQuality.BALANCED -> ScreenQuality.LOW
        ScreenQuality.LOW -> ScreenQuality.LOW
    }

    private fun higher(q: ScreenQuality): ScreenQuality = when (q) {
        ScreenQuality.LOW -> ScreenQuality.BALANCED
        ScreenQuality.BALANCED -> ScreenQuality.HIGH
        ScreenQuality.HIGH -> ScreenQuality.HIGH
    }

    private companion object {
        const val DOWNGRADE_WINDOWS = 2
        const val UPGRADE_WINDOWS = 6
        const val CHANGE_COOLDOWN_MS = 20_000L
        const val MANUAL_SUSPEND_MS = 45_000L
        const val HIGH_RTT_MS = 180L
        const val LOW_RTT_MS = 100L
        const val HIGH_JITTER_MS = 60
        const val LOW_JITTER_MS = 25
        const val HIGH_LOSS_PERMILLE = 60
        const val LOW_LOSS_PERMILLE = 20
    }
}
