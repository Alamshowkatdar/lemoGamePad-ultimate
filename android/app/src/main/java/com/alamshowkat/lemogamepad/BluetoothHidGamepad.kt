package com.alamshowkat.lemogamepad

import android.Manifest
import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** A bonded host the user can pick from the settings card. */
data class BluetoothHost(val name: String, val address: String)

/** Bonded computers/phones only; headsets and keyboards are not gamepad hosts. */
internal fun bondedBluetoothDevices(context: Context): List<BluetoothDevice> {
    // Lint (and Android 12+) require an explicit check before touching the
    // bonded-device list; callers handle a denied result by asking for access.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return emptyList()
    }
    val adapter = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    } catch (_: Exception) {
        null
    }
    return adapter?.bondedDevices.orEmpty().filter { device ->
        try {
            when (device.bluetoothClass?.majorDeviceClass) {
                BluetoothClass.Device.Major.COMPUTER,
                BluetoothClass.Device.Major.PHONE,
                -> true
                else -> false
            }
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}

internal fun BluetoothDevice.safeDisplayName(): String = try {
    name?.takeIf { it.isNotBlank() } ?: "host"
} catch (_: SecurityException) {
    "host"
} catch (_: Exception) {
    "host"
}

/**
 * Exposes this phone as a standard Bluetooth HID gamepad.
 *
 * Registering the HID profile makes the phone advertise a real gamepad, so a PC
 * or Android host only has to pair "Lemo GamePad" once — there is no host app,
 * driver, tethering toggle or Accessibility permission. Nothing happens on app
 * launch: the user starts the gamepad explicitly and picks one bonded host with
 * [connect]. The class never connects to every paired device on its own.
 *
 * Android 9 (API 28) is the minimum because that is where
 * [BluetoothHidDevice] was added. Some OEM Bluetooth stacks do not ship the HID
 * device profile; [start] then reports that instead of pretending to work.
 *
 * The report is a plain 9 byte input report (no report ID):
 *
 *     byte 0-1  buttons, little endian (see [hidButtons])
 *     byte 2    hat switch 0-7 clockwise from up, 8 = centred
 *     byte 3-6  X, Y, Rx, Ry (left/right stick, signed -127..127)
 *     byte 7    Z  (L2, 0-255)
 *     byte 8    Rz (R2, 0-255)
 */
internal class BluetoothHidGamepad(
    context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val appContext = context.applicationContext

    /**
     * HID callbacks run here. The pool is created per start() and shut down in
     * stop(), so no non-daemon worker outlives the gamepad.
     */
    private var executor: ExecutorService? = null
    private var api: Api28? = null

    /** True while the HID profile is registered, even before a host connects. */
    val isRunning: Boolean get() = api?.isRegistered == true

    /** True only while a host is connected and listening for reports. */
    val isConnected: Boolean get() = api?.isConnected == true

    val connectedName: String? get() = api?.connectedName

    /**
     * Registers the HID profile. Returns false when this phone cannot act as a
     * controller (no adapter, Bluetooth off, or profile unsupported). No host is
     * contacted until [connect] is called with an address the user picked.
     */
    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onStatus("!Bluetooth gamepad needs Android 9 or newer")
            return false
        }
        api?.let { return true }
        val pool = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "vpad-bt-hid").apply { isDaemon = true }
        }
        executor = pool
        val created = Api28(appContext, pool, onStatus)
        api = created
        if (!created.start()) {
            api = null
            executor = null
            pool.shutdownNow()
            return false
        }
        return true
    }

    fun send(state: PadState) {
        api?.takeIf { it.isConnected }?.send(state)
    }

    /** Centres every axis and releases all buttons. */
    fun sendNeutral() {
        api?.sendNeutral()
    }

    /** Bonded computers/phones the user can choose to connect to. */
    fun hosts(): List<BluetoothHost> = bondedBluetoothDevices(appContext)
        .map { BluetoothHost(it.safeDisplayName(), it.address) }
        .sortedBy { it.name.lowercase() }

    /**
     * Starts the profile if needed, then connects only to [address]. Registration
     * is asynchronous, so the address is remembered until the profile is ready.
     * Returns false for a synchronous failure so the caller does not leave a
     * phantom gamepad active.
     */
    fun connect(address: String): Boolean {
        if (api == null && !start()) return false
        return api?.connect(address) == true
    }

    /** Disconnects the current host but keeps the profile ready for another pick. */
    fun disconnect() {
        api?.disconnect()
    }

    fun stop() {
        api?.stop()
        api = null
        executor?.shutdownNow()
        executor = null
    }

    @TargetApi(Build.VERSION_CODES.P)
    private class Api28(
        private val context: Context,
        private val executor: Executor,
        private val onStatus: (String) -> Unit,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private val adapter: BluetoothAdapter? = try {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        } catch (_: Exception) {
            null
        }

        @Volatile private var device: BluetoothDevice? = null
        @Volatile private var hid: BluetoothHidDevice? = null
        @Volatile private var lastAddress: String? = null
        @Volatile var isConnected = false
            private set
        @Volatile var isRegistered = false
            private set

        val connectedName: String?
            get() {
                val target = device ?: return null
                return target.safeDisplayName()
            }

        /** Address the user picked, applied once the HID profile is registered. */
        @Volatile private var pendingAddress: String? = null

        private val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                isRegistered = registered
                if (!registered) {
                    device = null
                    isConnected = false
                    onStatus("Bluetooth gamepad stopped")
                    return
                }
                onStatus("Bluetooth gamepad ready — pick a host to connect")
                pendingAddress?.let { connectRegistered(it) }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        this@Api28.device = device
                        lastAddress = device.address
                        isConnected = true
                        pendingAddress = null
                        // A fresh host can otherwise keep the last pressed
                        // button until the first report arrives.
                        sendReport(NEUTRAL_REPORT)
                        onStatus("Bluetooth gamepad connected to " + device.safeDisplayName())
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (this@Api28.device?.address == device.address) {
                            this@Api28.device = null
                        }
                        isConnected = false
                        lastAddress = try {
                            device.address
                        } catch (_: Exception) {
                            lastAddress
                        }
                        onStatus("Bluetooth gamepad disconnected — pick a host to reconnect")
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
                val allowed = bufferSize.coerceAtLeast(0).coerceAtMost(NEUTRAL_REPORT.size)
                try {
                    hid?.replyReport(device, type, id, NEUTRAL_REPORT.copyOf(allowed))
                } catch (_: SecurityException) {
                } catch (_: Exception) {
                }
            }

            override fun onVirtualCableUnplug(device: BluetoothDevice) {
                if (this@Api28.device?.address == device.address) {
                    this@Api28.device = null
                    isConnected = false
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
                    if (!accepted) {
                        onStatus("!Android could not register the Bluetooth gamepad")
                    }
                } catch (_: SecurityException) {
                    onStatus("!Allow Nearby devices, then start the Bluetooth gamepad again")
                } catch (_: Exception) {
                    onStatus("!Bluetooth gamepad is not supported on this phone")
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                isRegistered = false
                isConnected = false
                device = null
                hid = null
                pendingAddress = null
                onStatus("!Bluetooth gamepad service disconnected")
            }
        }

        fun start(): Boolean {
            val bt = adapter
            if (bt == null) {
                onStatus("!This phone has no Bluetooth adapter")
                return false
            }
            val enabled = try {
                bt.isEnabled
            } catch (_: SecurityException) {
                onStatus("!Allow Nearby devices, then start the Bluetooth gamepad again")
                return false
            } catch (_: Exception) {
                false
            }
            if (!enabled) {
                onStatus("!Turn on Bluetooth to use the Bluetooth gamepad")
                return false
            }
            return try {
                if (!bt.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)) {
                    onStatus("!This phone does not provide Bluetooth gamepad mode")
                    false
                } else {
                    onStatus("Starting Bluetooth gamepad…")
                    true
                }
            } catch (_: SecurityException) {
                onStatus("!Allow Nearby devices, then start the Bluetooth gamepad again")
                false
            } catch (_: Exception) {
                onStatus("!Bluetooth gamepad is not supported on this phone")
                false
            }
        }

        fun send(state: PadState) {
            sendReport(report(state))
        }

        fun sendNeutral() {
            sendReport(NEUTRAL_REPORT)
        }

        private fun sendReport(data: ByteArray) {
            val target = device
            val hid = hid
            if (target == null || hid == null || !isConnected) return
            try {
                hid.sendReport(target, 0, data)
            } catch (_: SecurityException) {
                onStatus("!Bluetooth permission is missing")
            } catch (_: Exception) {
            }
        }

        /**
         * Connects only to the host the user picked. Registration is async, so
         * while [start] is still in flight the address is remembered and applied
         * from onAppStatusChanged the moment the profile becomes ready.
         */
        fun connect(address: String): Boolean {
            pendingAddress = address
            // Registration is async: keeping the pick pending is a success here;
            // onAppStatusChanged applies it when the profile becomes ready.
            return if (isRegistered) connectRegistered(address) else true
        }

        /** Applies a pick once the profile is registered. */
        private fun connectRegistered(address: String): Boolean {
            val target = bondedHosts().firstOrNull { it.address == address }
            if (target == null) {
                onStatus("!Host is no longer paired — pair it again in Bluetooth settings")
                pendingAddress = null
                return false
            }
            if (isConnected && device?.address == address) {
                pendingAddress = null
                return true
            }
            val accepted = try {
                hid?.connect(target) == true
            } catch (_: SecurityException) {
                onStatus("!Allow Nearby devices, then pick the host again")
                false
            } catch (_: Exception) {
                false
            }
            if (accepted) {
                pendingAddress = null
                onStatus("Connecting to ${target.safeDisplayName()}…")
            } else {
                onStatus("!Could not connect — make sure the host is on with Lemo GamePad selected")
            }
            return accepted
        }

        /** Drops the current host; the profile stays registered for another pick. */
        fun disconnect() {
            pendingAddress = null
            val target = device ?: return
            try {
                hid?.disconnect(target)
            } catch (_: SecurityException) {
                onStatus("!Allow Nearby devices to disconnect the gamepad")
            } catch (_: Exception) {
            }
        }

        /** Bonded PCs and phones only; headsets and keyboards are left alone. */
        private fun bondedHosts(): List<BluetoothDevice> = bondedBluetoothDevices(context)

        fun stop() {
            pendingAddress = null
            try {
                hid?.unregisterApp()
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            } catch (_: SecurityException) {
                // BLUETOOTH_CONNECT can be revoked while the app is running.
            } catch (_: Exception) {
            }
            isRegistered = false
            isConnected = false
            device = null
            hid = null
        }

        private fun report(state: PadState): ByteArray {
            val buttons = hidButtons(state.buttons)
            return byteArrayOf(
                (buttons and 0xFF).toByte(),
                ((buttons ushr 8) and 0xFF).toByte(),
                hat(state.buttons),
                axis(state.lx),
                axis(-state.ly),
                axis(state.rx),
                axis(-state.ry),
                state.l2.coerceIn(0, 255).toByte(),
                state.r2.coerceIn(0, 255).toByte(),
            )
        }

        /** Converts the on-screen d-pad bits into a HID hat switch value. */
        private fun hat(b: Int): Byte {
            val up = b and (1 shl Proto.UP) != 0
            val right = b and (1 shl Proto.RIGHT) != 0
            val down = b and (1 shl Proto.DOWN) != 0
            val left = b and (1 shl Proto.LEFT) != 0
            return when {
                up && right -> 1
                right && down -> 3
                down && left -> 5
                left && up -> 7
                up -> 0
                right -> 2
                down -> 4
                left -> 6
                else -> 8
            }.toByte()
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
            return out
        }

        private fun axis(value: Int): Byte =
            (value.coerceIn(-32767, 32767) * 127f / 32767f).roundToInt().toByte()

        private companion object {
            private val NEUTRAL_REPORT = byteArrayOf(
                0, 0, 8, 0, 0, 0, 0, 0, 0,
            )

            /**
             * Generic Desktop / Game Pad: 16 buttons, one 8-way hat switch,
             * four signed 8-bit axes (X, Y, Rx, Ry) and two unsigned triggers
             * (Z, Rz). The hat switch is what DirectInput games read as a
             * d-pad; mapping the directions to buttons alone left it dead.
             */
            private val REPORT_DESCRIPTOR = byteArrayOf(
                0x05, 0x01,             // Usage Page (Generic Desktop)
                0x09, 0x05,             // Usage (Game Pad)
                0xA1.toByte(), 0x01,    // Collection (Application)
                0x05, 0x09,             //   Usage Page (Button)
                // Explicit usages, one per report bit, in the order the report
                // packs them (A, B, X, Y, LB, RB, LT, RT, Back, Start, LS, RS).
                // A 1..16 min/max looks simpler but Linux maps generic button N
                // to BTN_GAMEPAD+N, i.e. 3->BTN_C, 4->BTN_X, 5->BTN_Y ... so
                // X/Y/shoulders/Back/Start landed on the wrong evdev codes.
                // These explicit values make Linux, Android and DirectInput all
                // agree: bit 0 is usage 1 (BTN_A/A), bit 2 is usage 4 (BTN_X),
                // bit 4 is usage 7 (BTN_TL/LB), and so on.
                0x09, 0x01,             //   bit  0  A      -> BTN_A / KEYCODE_A
                0x09, 0x02,             //   bit  1  B      -> BTN_B
                0x09, 0x04,             //   bit  2  X      -> BTN_X
                0x09, 0x05,             //   bit  3  Y      -> BTN_Y
                0x09, 0x07,             //   bit  4  LB     -> BTN_TL
                0x09, 0x08,             //   bit  5  RB     -> BTN_TR
                0x09, 0x09,             //   bit  6  LT     -> BTN_TL2
                0x09, 0x0A,             //   bit  7  RT     -> BTN_TR2
                0x09, 0x0B,             //   bit  8  Back   -> BTN_SELECT
                0x09, 0x0C,             //   bit  9  Start  -> BTN_START
                0x09, 0x0E,             //   bit 10  LS     -> BTN_THUMBL
                0x09, 0x0F,             //   bit 11  RS     -> BTN_THUMBR
                0x09, 0x03,             //   bit 12  spare  -> BTN_C
                0x09, 0x06,             //   bit 13  spare  -> BTN_Z
                0x09, 0x0D,             //   bit 14  spare  -> BTN_MODE
                0x09, 0x10,             //   bit 15  spare
                0x15, 0x00,             //   Logical Minimum (0)
                0x25, 0x01,             //   Logical Maximum (1)
                0x95.toByte(), 0x10,    //   Report Count (16)
                0x75, 0x01,             //   Report Size (1)
                0x81.toByte(), 0x02,    //   Input (Data, Variable, Absolute)
                0x05, 0x01,             //   Usage Page (Generic Desktop)
                0x09, 0x39,             //   Usage (Hat switch)
                0x15, 0x00,             //   Logical Minimum (0)
                0x25, 0x07,             //   Logical Maximum (7)
                0x35, 0x00,             //   Physical Minimum (0)
                0x46, 0x3B, 0x01,       //   Physical Maximum (315)
                0x65, 0x14,             //   Unit (Eng Rot: degrees)
                0x75, 0x04,             //   Report Size (4)
                0x95.toByte(), 0x01,    //   Report Count (1)
                0x81.toByte(), 0x42,    //   Input (Data, Variable, Absolute, Null State)
                0x65, 0x00,             //   Unit (None)
                0x75, 0x04,             //   Report Size (4)
                0x95.toByte(), 0x01,    //   Report Count (1)
                0x81.toByte(), 0x03,    //   Input (Constant, Variable, Absolute)
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
