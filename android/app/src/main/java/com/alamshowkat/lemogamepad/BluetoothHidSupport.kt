package com.alamshowkat.lemogamepad

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
     * Opens Android's details page for one bonded host when the ROM supports the
     * deep link, so the user can switch **Media audio** and **Phone audio** off
     * for that device. Android owns those profile switches; a normal app has no
     * public API to change them, so the details page (or the normal paired-device
     * list on OEM ROMs) is the safe place to do it.
     */
    fun openHostSettings(activity: Activity, address: String): Boolean {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return openSettings(activity)
        // AOSP's SettingsActivity passes this Bundle to the device-details
        // fragment, which reads "device_address" from its arguments. The flat
        // extra is a harmless fallback for OEM skins with their own contract.
        val args = Bundle().apply { putString(KEY_DEVICE_ADDRESS, address) }
        val intent = Intent(ACTION_DEVICE_DETAIL_SETTINGS).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(EXTRA_SHOW_FRAGMENT_ARGS, args)
            putExtra(KEY_DEVICE_ADDRESS, address)
        }
        return start(activity, intent) || openSettings(activity)
    }

    private const val ACTION_DEVICE_DETAIL_SETTINGS =
        "com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS"
    private const val KEY_DEVICE_ADDRESS = "device_address"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

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
