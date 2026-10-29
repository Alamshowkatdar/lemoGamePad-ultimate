package com.alamshowkat.lemogamepad

import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Exposes this phone as a standard Bluetooth HID gamepad for another Android
 * phone. The host pairs to this phone from its Bluetooth settings; no host app
 * or Accessibility permission is needed. Android 9 (API 28) or newer is required.
 */
internal class BluetoothHidGamepad(
    context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val executor: Executor = Executors.newSingleThreadExecutor()
    private var api: Api28? = null

    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onStatus("Bluetooth gamepad needs Android 9 or newer")
            return false
        }
        if (api != null) return true
        api = Api28(appContext, executor, onStatus).also { it.start() }
        return true
    }

    fun send(state: PadState) {
        api?.send(state)
    }

    fun stop() {
        api?.stop()
        api = null
    }

    @TargetApi(Build.VERSION_CODES.P)
    private class Api28(
        private val context: Context,
        private val executor: Executor,
        private val onStatus: (String) -> Unit,
    ) {
        private val adapter: BluetoothAdapter? = try {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        } catch (_: Exception) {
            null
        }
        @Volatile private var device: BluetoothDevice? = null
        @Volatile private var hid: BluetoothHidDevice? = null
        @Volatile private var registered = false

        private val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
                registered = isRegistered
                onStatus(if (isRegistered) {
                    "Gamepad ready — pair this phone from the game host’s Bluetooth settings"
                } else {
                    "Bluetooth gamepad stopped"
                })
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    this@Api28.device = device
                    onStatus("Bluetooth gamepad connected to " + safeName(device))
                } else if (this@Api28.device?.address == device.address) {
                    this@Api28.device = null
                    onStatus("Bluetooth gamepad disconnected")
                }
            }

            override fun onGetReport(
                device: BluetoothDevice,
                type: Byte,
                id: Byte,
                bufferSize: Int,
            ) {
                val report = emptyReport()
                val allowed = bufferSize.coerceAtLeast(0).coerceAtMost(report.size)
                try {
                    hid?.replyReport(device, type, id, report.copyOf(allowed))
                } catch (_: SecurityException) {
                }
            }
        }

        private val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val gamepad = proxy as? BluetoothHidDevice ?: return
                hid = gamepad
                try {
                    val accepted = gamepad.registerApp(
                        BluetoothHidDeviceAppSdpSettings(
                            "Lemo GamePad",
                            "Android phone gamepad",
                            "Lemo GamePad Ultimate",
                            0x04.toByte(),
                            REPORT_DESCRIPTOR,
                        ),
                        null,
                        null,
                        executor,
                        callback,
                    )
                    if (!accepted) onStatus("Android could not register the Bluetooth gamepad")
                } catch (e: SecurityException) {
                    onStatus("Allow Nearby devices, then start the Bluetooth gamepad again")
                } catch (e: Exception) {
                    onStatus("Bluetooth gamepad is not supported on this phone")
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                registered = false
                device = null
                hid = null
                onStatus("Bluetooth gamepad service disconnected")
            }
        }

        fun start() {
            try {
                val bt = adapter
                if (bt == null || !bt.isEnabled) {
                    onStatus("Turn on Bluetooth, then start the phone gamepad")
                    return
                }
                if (!bt.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)) {
                    onStatus("This phone does not provide Bluetooth gamepad mode")
                } else {
                    onStatus("Starting Bluetooth gamepad…")
                }
            } catch (_: SecurityException) {
                onStatus("Allow Nearby devices, then start the Bluetooth gamepad again")
            } catch (_: Exception) {
                onStatus("Bluetooth gamepad is not supported on this phone")
            }
        }

        fun send(state: PadState) {
            if (!registered) return
            val target = device ?: return
            try {
                hid?.sendReport(target, 0, report(state))
            } catch (_: SecurityException) {
                onStatus("Bluetooth permission is missing")
            } catch (_: Exception) {
            }
        }

        fun stop() {
            try {
                hid?.unregisterApp()
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            } catch (_: Exception) {
            }
            registered = false
            device = null
            hid = null
        }

        private fun safeName(device: BluetoothDevice): String = try {
            device.name?.takeIf { it.isNotBlank() } ?: "game host"
        } catch (_: Exception) {
            "game host"
        }

        private fun report(state: PadState): ByteArray {
            val buttons = state.buttons and 0xFFFF
            return byteArrayOf(
                (buttons and 0xFF).toByte(),
                ((buttons ushr 8) and 0xFF).toByte(),
                axis(state.lx), axis(state.ly), axis(state.rx), axis(state.ry),
                state.l2.coerceIn(0, 255).toByte(),
                state.r2.coerceIn(0, 255).toByte(),
            )
        }

        private fun axis(value: Int): Byte =
            (value.coerceIn(-32767, 32767) * 127f / 32767f).roundToInt().toByte()

        private fun emptyReport(): ByteArray = ByteArray(8)

        companion object {
            // Generic Desktop Game Pad: 16 buttons, four signed sticks and two triggers.
            private val REPORT_DESCRIPTOR = byteArrayOf(
                0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01,
                0xA1.toByte(), 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x10,
                0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x10, 0x75, 0x01, 0x81.toByte(), 0x02,
                0xC0.toByte(), 0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x33, 0x09, 0x34,
                0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x04, 0x81.toByte(), 0x02,
                0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF.toByte(), 0x00,
                0x75, 0x08, 0x95.toByte(), 0x02, 0x81.toByte(), 0x02, 0xC0.toByte(), 0xC0.toByte(),
            )
        }
    }
}
