package com.alamshowkat.lemogamepad

import android.app.Activity

/**
 * Offline help centre. Text lives in the APK, so it works with Wi-Fi off and
 * never depends on the downloads website. Every topic also has a `?` button on
 * the matching settings card for in-place help.
 */
internal object HelpCenter {
    private val TOPICS = listOf(
        LayoutDialogs.HelpTopic(
            "FIRST-TIME SETUP",
            "Pick Wi-Fi, USB, Bluetooth or Web pad on first launch. The guide only shows the steps for that choice. " +
                "You can replay it from the ? / SETUP buttons at the top of Settings.",
        ),
        LayoutDialogs.HelpTopic(
            "WI-FI AND USB LINK",
            "Input uses UDP 9876 and screen video uses TCP 9877; neither changes. Start the PC server, pair with its QR " +
                "code or type the IP it prints. Use AUTO to probe both paths, or force WI-FI / USB. Guest Wi-Fi that " +
                "blocks device-to-device traffic and PC firewalls are the most common causes of LINKING forever.",
        ),
        LayoutDialogs.HelpTopic(
            "WEB PAD ON PHONE OR IPHONE",
            "The PC server also serves a browser pad on TCP 9878. Open http://PC-IP:9878 on the same network; on iPhone " +
                "use Safari's Share > Add to Home Screen. The page must be served by the PC because a public HTTPS site " +
                "cannot open the local ws:// address. Gyro, tilt and vibration are Android-only in the browser.",
        ),
        LayoutDialogs.HelpTopic(
            "BLUETOOTH GAMEPAD",
            "This is a server-less HID gamepad. Pair 'Lemo GamePad' with the PC or TV, start it here and explicitly pick a " +
                "host. The app never auto-connects, registers, calls or changes audio profiles, and the HID report stays " +
                "the original 9 bytes. Turning Bluetooth on in Android does not start the gamepad. Android itself may " +
                "also connect Media audio or Phone audio to a bonded host because the pairing is shared. A normal app " +
                "cannot switch those Android profiles off; while a host is connected, use HOST AUDIO SETTINGS, then " +
                "turn off Media audio and Phone audio for that device once.",
        ),
        LayoutDialogs.HelpTopic(
            "CONNECTION DOCTOR",
            "Three steps: the website for downloads and guides, a transport check for Wi-Fi / USB paths, and a link check " +
                "with a live TCP probe of the PC's video and web ports plus the current RTT and packet loss. RUN CHECK " +
                "is best-effort: a closed web port only means the optional browser pad is off, not that input is broken.",
        ),
        LayoutDialogs.HelpTopic(
            "MOTION AND AIM",
            "Gyro aim adds to touch aim instead of replacing it. Revert left/right and Revert up/down flip one axis each. " +
                "Tilt steering drives the left stick. Both default to off on a fresh install; existing settings are kept " +
                "on upgrade. AIM CALIBRATION can suggest sensitivity and revert values.",
        ),
        LayoutDialogs.HelpTopic(
            "TOUCHPAD",
            "With the touchpad on it replaces the right stick and R3. BGMI-style swipes move aim relatively. With Mouse aim " +
                "on, finger speed drives right-stick velocity like game-mouse input; stopping your finger stops the turn. " +
                "Gyro can contribute at the same time. Wire packets are unchanged.",
        ),
        LayoutDialogs.HelpTopic(
            "GAME PROFILES",
            "Profiles save aim, motion, haptics, input rate, face labels, opacity and layout as a snapshot. They never " +
                "detect the running game: tap a name to switch. COPY duplicates one, EXPORT copies a JSON backup to the " +
                "clipboard, and IMPORT merges names that are not already saved. Connections and player slots are excluded.",
        ),
        LayoutDialogs.HelpTopic(
            "LAYOUTS AND EDITING",
            "There is one Gamepad layout. EDIT LAYOUT unlocks drag, pinch resize, long-press hide, snap/grid, alignment " +
                "and safe-area guides, multi-select, copy/paste, lock and mirror. Undo/redo works during the session. " +
                "Named layouts are manual quick-switches; import/export uses compact layout text.",
        ),
        LayoutDialogs.HelpTopic(
            "PC SCREEN PREVIEW",
            "The small window streams JPEG frames over TCP 9877. Drag to move it, tap to toggle small/large, and use its " +
                "corner button to close it; closing does not disconnect input. LOW, BALANCED and HIGH only change " +
                "sharpness — 60 fps smoothness is always prioritised. Adaptive quality is off until you enable it, and " +
                "manual quality taps suspend it briefly.",
        ),
        LayoutDialogs.HelpTopic(
            "SETTINGS AND BATTERY",
            "Every setting applies immediately and is saved locally. Battery saver pauses the live RTT graph and detailed " +
                "stats while connection state and buttons keep working. Saved settings are validated on startup, so an " +
                "old or damaged value is repaired instead of clearing your preferences.",
        ),
    )

    fun show(activity: Activity, theme: PadTheme) {
        LayoutDialogs.help(activity, theme, TOPICS)
    }
}
