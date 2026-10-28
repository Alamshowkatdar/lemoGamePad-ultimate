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
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Bluetooth pairing helper.
 *
 * Android cannot initiate a PC pairing on its own, so this exposes the pieces
 * the user actually needs: the adapter state, the phone's own name/address,
 * already-bonded devices, a "make discoverable" request, shortcuts to the
 * system Bluetooth and tethering pages, and the live PAN interface address.
 * Once Windows joins the phone's Personal Area Network, the normal UDP link
 * takes over - no serial/RFCOMM code is involved.
 */
internal object BluetoothPairing {

    data class Status(
        val supported: Boolean,
        val enabled: Boolean,
        val permitted: Boolean,
        val phoneName: String,
        val bonded: List<String>,
        val panAddress: String?,
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
        val pan = panAddress()
        val ad = adapter(context) ?: return Status(
            supported = false,
            enabled = false,
            permitted = !permissionsMissing(context),
            phoneName = "",
            bonded = emptyList(),
            panAddress = pan,
        )
        val permitted = !permissionsMissing(context)
        var enabled = false
        try {
            enabled = ad.isEnabled
        } catch (_: SecurityException) {
        }
        var name = ""
        var bonded = emptyList<String>()
        if (permitted) {
            try {
                name = ad.name ?: ""
            } catch (_: SecurityException) {
            }
            try {
                bonded = ad.bondedDevices
                    ?.mapNotNull { device ->
                        try {
                            device.name ?: device.address
                        } catch (_: SecurityException) {
                            null
                        }
                    }
                    ?.filter { it.isNotBlank() }
                    ?.sorted()
                    ?: emptyList()
            } catch (_: SecurityException) {
            }
        }
        return Status(true, enabled, permitted, name, bonded, pan)
    }

    /** The IPv4 address of the Bluetooth PAN interface, when tethering is up. */
    fun panAddress(): String? = try {
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .filter { classifyInterface(it.name) == Transport.BLUETOOTH }
            .mapNotNull { nif ->
                nif.interfaceAddresses
                    .firstOrNull { it.address is Inet4Address }
                    ?.address
                    ?.hostAddress
            }
            .firstOrNull()
    } catch (_: Exception) {
        null
    }

    /** Opens the system Bluetooth page (pair/unpair devices there). */
    fun openSettings(activity: Activity): Boolean =
        start(activity, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    /** Asks Android to make this phone visible to PCs for a couple of minutes. */
    fun requestDiscoverable(activity: Activity, seconds: Int = 120): Boolean {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, seconds.coerceIn(1, 300))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
