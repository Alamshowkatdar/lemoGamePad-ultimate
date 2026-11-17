package com.alamshowkat.lemogamepad

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Small helper around the Bluetooth HID device profile.
 *
 * The app no longer uses Bluetooth tethering (PAN), so there is nothing to
 * configure here: HID only needs the adapter switched on, the Android 12+
 * "Nearby devices" permission, and one trip through the system pairing prompt.
 * Everything after that is handled by [BluetoothHidGamepad].
 */
internal object BluetoothHidSupport {

    data class Status(
        val supported: Boolean,
        val enabled: Boolean,
        val permitted: Boolean,
        val phoneName: String,
    )

    /** Runtime permissions are only needed on Android 12+. */
    fun permissionsMissing(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return !granted(context, Manifest.permission.BLUETOOTH_CONNECT) ||
            !granted(context, Manifest.permission.BLUETOOTH_ADVERTISE)
    }

    fun requestPermissions(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!permissionsMissing(activity)) return
        activity.requestPermissions(
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ),
            requestCode,
        )
    }

    private fun granted(context: Context, permission: String): Boolean = try {
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    private fun adapter(context: Context): BluetoothAdapter? = try {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    } catch (_: Exception) {
        null
    }

    fun status(context: Context): Status {
        val ad = adapter(context) ?: return Status(
            supported = false,
            enabled = false,
            permitted = !permissionsMissing(context),
            phoneName = "",
        )
        val permitted = !permissionsMissing(context)
        var enabled = false
        try {
            enabled = ad.isEnabled
        } catch (_: SecurityException) {
        }
        var name = ""
        if (permitted) {
            try {
                name = ad.name ?: ""
            } catch (_: SecurityException) {
            }
        }
        return Status(true, enabled, permitted, name)
    }

    /** Opens the system Bluetooth page where hosts are paired and forgotten. */
    fun openSettings(activity: Activity): Boolean =
        start(activity, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    /**
     * Makes the phone visible to a PC or Android host for five minutes. The
     * HID profile itself keeps running afterwards; only pairing needs this.
     */
    fun requestDiscoverable(activity: Activity, seconds: Int = 300): Boolean {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, seconds.coerceIn(1, 300))
        }
        return start(activity, intent)
    }

    private fun start(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
