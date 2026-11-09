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

    // 1.6.9: ask the player who owns a pad before swapping seats with them.
    // Older apps and servers ignore unknown types, so the protocol stays
    // wire-compatible; a request to an old client simply times out.
    const val MSG_SLOT_REQUEST = 8
    const val MSG_SLOT_ASK = 9
    const val MSG_SLOT_VOTE = 10
    const val MSG_SLOT_RESULT = 11

    const val SLOT_ACCEPTED = 1
    const val SLOT_DECLINED = 2
    const val SLOT_TIMEOUT = 3
    const val SLOT_UNAVAILABLE = 4
    const val SLOT_CANCELLED = 5

    const val HEADER_SIZE = 12
    /**
     * 28 bytes of pad state plus one optional byte requesting a pad number
     * (0 = automatic, 1..MAX_PLAYERS). Old servers ignore the extra byte.
     */
    const val INPUT_SIZE = 29

    /** Highest virtual pad the PC server exposes (XInput itself stops at 4). */
    const val MAX_PLAYERS = 8

    /** PONG length when the server also reports the pad it assigned. */
    const val PONG_WITH_SLOT_SIZE = HEADER_SIZE + 7

    /** The PC screen stream listens on the UDP port + 1 (9877 by default). */
    const val VIDEO_PORT_OFFSET = 1

    /**
     * Clamp the control port first: 65535 would make the video port 65536,
     * which InetSocketAddress rejects and the stream would retry forever.
     */
    fun videoPort(controlPort: Int): Int = controlPort.coerceIn(1, 65534) + VIDEO_PORT_OFFSET

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

/**
 * A player asking to swap pads. [fromSlot] is the requester's current pad, so
 * the popup can say "P2 wants to swap with you" without trusting a free-form
 * name from the network.
 */
data class SlotAsk(val id: Int, val fromSlot: Int)

/** Outcome of a swap request, including the pad the receiver now owns. */
data class SlotResult(val id: Int, val code: Int, val slot: Int)

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

    /**
     * Woken directly when the pad changes. Without this the sender had to poll
     * every few milliseconds, so a busy UI thread could delay a stick sample
     * by a whole frame or more. Unparking is cheap enough to do on the UI
     * thread; the actual network write stays on the sender thread.
     */
    @Volatile var onChange: (() -> Unit)? = null

    fun bump() {
        version++
        onChange?.invoke()
    }
}
