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
 * or Accessibility permission is needed. Android 9 (API 28) or newer is
 * required because that is where [BluetoothHidDevice] was added.
 *
 * The report is a plain 8 byte input report (no report ID):
 *
 *     byte 0-1  buttons, little endian (see [HID_BUTTONS])
 *     byte 2-5  X, Y, Rx, Ry  (left / right stick, signed)
 *     byte 6    Z             (L2, 0-255)
 *     byte 7    Rz            (R2, 0-255)
 */
internal class BluetoothHidGamepad(
    context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val executor: Executor = Executors.newSingleThreadExecutor()
    private var api: Api28? = null

    /** True only while a host phone is actually connected and listening. */
    val isConnected: Boolean get() = api?.isConnected == true

    /**
     * Registers the HID profile. Returns false when this phone cannot act as a
     * controller (no adapter, Bluetooth off, or profile unsupported) so the
     * caller does not pretend the gamepad is running.
     */
    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onStatus("Bluetooth gamepad needs Android 9 or newer")
            return false
        }
        api?.let { return true }
        val created = Api28(appContext, executor, onStatus)
        api = created
        if (!created.start()) {
            api = null
            return false
        }
        return true
    }

    fun send(state: PadState) {
        api?.takeIf { it.isConnected }?.send(state)
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
        @Volatile var isConnected = false
            private set
        @Volatile var isRegistered = false
            private set

        private val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
                this@Api28.isRegistered = isRegistered
                if (!isRegistered) {
                    device = null
                    isConnected = false
                }
                onStatus(
                    if (isRegistered) {
                        "Gamepad ready — pair this phone from the game host’s Bluetooth settings"
                    } else {
                        "Bluetooth gamepad stopped"
                    },
                )
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        this@Api28.device = device
                        isConnected = true
                        onStatus("Bluetooth gamepad connected to " + safeName(device))
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (this@Api28.device?.address == device.address) {
                            this@Api28.device = null
                        }
                        isConnected = false
                        onStatus("Bluetooth gamepad disconnected — pair the host again")
                    }
                }
            }

            /**
             * Hosts read the current state after connecting (and after a
             * suspend/resume). Answer with the neutral report so a stale press
             * cannot stick.
             */
            override fun onGetReport(
                device: BluetoothDevice,
                type: Byte,
                id: Byte,
                bufferSize: Int,
            ) {
                val report = NEUTRAL_REPORT
                val allowed = bufferSize.coerceAtLeast(0).coerceAtMost(report.size)
                try {
                    hid?.replyReport(device, type, id, report.copyOf(allowed))
                } catch (_: SecurityException) {
                } catch (_: Exception) {
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
                            0x00.toByte(), // no boot subclass; usage comes from the descriptor
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
                isRegistered = false
                isConnected = false
                device = null
                hid = null
                onStatus("Bluetooth gamepad service disconnected")
            }
        }

        fun start(): Boolean {
            val bt = adapter
            if (bt == null) {
                onStatus("This phone has no Bluetooth adapter")
                return false
            }
            val enabled = try {
                bt.isEnabled
            } catch (_: SecurityException) {
                onStatus("Allow Nearby devices, then start the Bluetooth gamepad again")
                return false
            } catch (_: Exception) {
                false
            }
            if (!enabled) {
                onStatus("Turn on Bluetooth, then start the phone gamepad")
                return false
            }
            return try {
                if (!bt.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)) {
                    onStatus("This phone does not provide Bluetooth gamepad mode")
                    false
                } else {
                    onStatus("Starting Bluetooth gamepad…")
                    true
                }
            } catch (_: SecurityException) {
                onStatus("Allow Nearby devices, then start the Bluetooth gamepad again")
                false
            } catch (_: Exception) {
                onStatus("Bluetooth gamepad is not supported on this phone")
                false
            }
        }

        fun send(state: PadState) {
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
            isRegistered = false
            isConnected = false
            device = null
            hid = null
        }

        private fun safeName(device: BluetoothDevice): String = try {
            device.name?.takeIf { it.isNotBlank() } ?: "game host"
        } catch (_: Exception) {
            "game host"
        }

        private fun report(state: PadState): ByteArray {
            val buttons = hidButtons(state.buttons)
            return byteArrayOf(
                (buttons and 0xFF).toByte(),
                ((buttons ushr 8) and 0xFF).toByte(),
                axis(state.lx), axis(state.ly), axis(state.rx), axis(state.ry),
                state.l2.coerceIn(0, 255).toByte(),
                state.r2.coerceIn(0, 255).toByte(),
            )
        }

        /**
         * Translate the wire format's PS3 bit order into the button order a
         * standard HID gamepad uses. Bit 0 is HID button 1.
         */
        private fun hidButtons(b: Int): Int {
            var out = 0
            fun put(psBit: Int, hidBit: Int) {
                if (b and (1 shl psBit) != 0) out = out or (1 shl hidBit)
            }
            put(Proto.CROSS, 0)      // A
            put(Proto.CIRCLE, 1)     // B
            put(Proto.SQUARE, 2)     // X
            put(Proto.TRIANGLE, 3)   // Y
            put(Proto.L1, 4)         // LB
            put(Proto.R1, 5)         // RB
            put(Proto.L2, 6)         // LT
            put(Proto.R2, 7)         // RT
            put(Proto.SELECT, 8)     // Back
            put(Proto.START, 9)      // Start
            put(Proto.L3, 10)        // LS click
            put(Proto.R3, 11)        // RS click
            put(Proto.UP, 12)
            put(Proto.DOWN, 13)
            put(Proto.LEFT, 14)
            put(Proto.RIGHT, 15)
            return out
        }

        private fun axis(value: Int): Byte =
            (value.coerceIn(-32767, 32767) * 127f / 32767f).roundToInt().toByte()

        companion object {
            private val NEUTRAL_REPORT = ByteArray(8)

            /**
             * Generic Desktop / Game Pad: 16 buttons, four signed 8-bit axes
             * (X, Y, Rx, Ry) and two unsigned triggers (Z, Rz).
             */
            private val REPORT_DESCRIPTOR = byteArrayOf(
                0x05, 0x01,             // Usage Page (Generic Desktop)
                0x09, 0x05,             // Usage (Game Pad)
                0xA1.toByte(), 0x01,    // Collection (Application)
                0xA1.toByte(), 0x00,    //   Collection (Physical)
                0x05, 0x09,             //     Usage Page (Button)
                0x19, 0x01,             //     Usage Minimum (1)
                0x29, 0x10,             //     Usage Maximum (16)
                0x15, 0x00,             //     Logical Minimum (0)
                0x25, 0x01,             //     Logical Maximum (1)
                0x95.toByte(), 0x10,    //     Report Count (16)
                0x75, 0x01,             //     Report Size (1)
                0x81.toByte(), 0x02,    //     Input (Data, Variable, Absolute)
                0xC0.toByte(),          //   End Collection (Physical)
                0x05, 0x01,             //   Usage Page (Generic Desktop)
                0x09, 0x30,             //   Usage (X)
                0x09, 0x31,             //   Usage (Y)
                0x09, 0x33,             //   Usage (Rx)
                0x09, 0x34,             //   Usage (Ry)
                0x15, 0x81.toByte(),    //   Logical Minimum (-127)
                0x25, 0x7F,             //   Logical Maximum (127)
                0x75, 0x08,             //   Report Size (8)
                0x95.toByte(), 0x04,    //   Report Count (4)
                0x81.toByte(), 0x02,    //   Input (Data, Variable, Absolute)
                0x09, 0x32,             //   Usage (Z)
                0x09, 0x35,             //   Usage (Rz)
                0x15, 0x00,             //   Logical Minimum (0)
                0x26, 0xFF.toByte(), 0x00, // Logical Maximum (255)
                0x75, 0x08,             //   Report Size (8)
                0x95.toByte(), 0x02,    //   Report Count (2)
                0x81.toByte(), 0x02,    //   Input (Data, Variable, Absolute)
                0xC0.toByte(),          // End Collection (Application)
            )
        }
    }
}
