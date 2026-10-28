package com.alamshowkat.lemogamepad

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire protocol shared with the PC server (pc/vpad_server.py).
 *
 * Every datagram starts with a 12 byte header:
 *   magic "VPAD" | version u8 | type u8 | seq u16 | token u32   (little endian)
 */
object Proto {
    const val PORT = 9876
    const val MAGIC = "VPAD"
    const val VERSION = 1

    const val MSG_INPUT = 1
    const val MSG_DISCOVER = 2
    const val MSG_OFFER = 3
    const val MSG_PING = 5
    const val MSG_PONG = 6
    const val MSG_RUMBLE = 7

    const val HEADER_SIZE = 12
    const val INPUT_SIZE = 28

    /** The PC screen stream listens on the UDP port + 1 (9877 by default). */
    const val VIDEO_PORT_OFFSET = 1

    fun videoPort(controlPort: Int): Int = controlPort + VIDEO_PORT_OFFSET

    // PS3 button bits, dibit order must match BUTTON_BITS in the Python server
    const val SELECT = 0
    const val L3 = 1
    const val R3 = 2
    const val START = 3
    const val UP = 4
    const val RIGHT = 5
    const val DOWN = 6
    const val LEFT = 7
    const val L2 = 8
    const val R2 = 9
    const val L1 = 10
    const val R1 = 11
    const val TRIANGLE = 12
    const val CIRCLE = 13
    const val CROSS = 14
    const val SQUARE = 15
    const val PS = 16

    val MAGIC_BYTES: ByteArray = MAGIC.toByteArray(Charsets.US_ASCII)

    fun header(type: Int, seq: Int, token: Int): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(MAGIC_BYTES)
            put(VERSION.toByte())
            put(type.toByte())
            putShort(seq.toShort())
            putInt(token)
        }.array()
}

/** Mutable controller state shared between the view and the network thread. */
class PadState {
    @Volatile var buttons: Int = 0
    @Volatile var lx: Int = 0
    @Volatile var ly: Int = 0
    @Volatile var rx: Int = 0
    @Volatile var ry: Int = 0
    @Volatile var l2: Int = 0
    @Volatile var r2: Int = 0

    /** Incremented on every change so the sender can skip untouched frames. */
    @Volatile var version: Long = 1

    fun bump() {
        version++
    }
}
