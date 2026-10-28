package com.alamshowkat.lemogamepad

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.random.Random

/**
 * How the phone reaches the PC. All three carry the same UDP protocol:
 * USB and Bluetooth simply expose a private network interface, so no extra
 * PC-side driver or serial code is needed.
 */
enum class Transport(val label: String, val hint: String) {
    AUTO("AUTO", "Find the PC on any usable connection"),
    WIFI("WI-FI", "Phone and PC on the same Wi-Fi network"),
    BLUETOOTH("BLUETOOTH", "Pair first, then enable Bluetooth tethering"),
    USB("USB", "Enable USB tethering in the phone's hotspot menu"),
}

/**
 * Sends controller state to the PC 100+ times a second and receives ping/rumble
 * datagrams back.
 *
 * Android 10+ pins ordinary sockets to the *default* network, which silently
 * drops traffic to a Bluetooth/USB tether. Every socket this class creates is
 * therefore bound with [Network.bindSocket] to the network that matches the
 * selected [transport]. Sockets are rebuilt whenever the target or link
 * changes, and a watchdog rebuilds them after a drop so a phone can always
 * reconnect without restarting the app.
 */
class Link(private val state: PadState, context: Context? = null) {

    @Volatile var host: String? = null
    @Volatile var port: Int = Proto.PORT

    /** Which link the user asked for; sockets are bound to that network. */
    @Volatile var transport: Transport = Transport.AUTO
        set(value) {
            if (field == value) return
            field = value
            if (running) rebind()
        }

    @Volatile var battery: Int = 255
    @Volatile var rttMs: Long = -1
    @Volatile var lastPongAt: Long = 0
    @Volatile var serverName: String = ""
    @Volatile var connected: Boolean = false

    var onRumble: ((Int, Int) -> Unit)? = null

    private val appContext = context?.applicationContext

    /**
     * Identity of this phone. The PC keys peers, controller slots and rumble
     * routing by this value, so it must survive app restarts — a fresh random
     * token on every launch made the PC think a new phone had arrived, holding
     * the old pad open and pushing the new one to XInput index 1.
     */
    @Volatile private var token: Int = loadToken(appContext)
    @Volatile private var running = false

    /** Bumped on stop/start so old worker loops cannot survive a restart. */
    @Volatile private var generation = 0

    @Volatile private var sock: DatagramSocket? = null
    @Volatile private var viaTransport: Transport? = null
    @Volatile private var lastRebindAt = 0L

    private var sender: Thread? = null
    private var receiver: Thread? = null

    private val sendBuffer = ByteArray(64)
    private val bb = ByteBuffer.wrap(sendBuffer).order(ByteOrder.LITTLE_ENDIAN)
    private var seq = 0

    private var cachedHost: String? = null
    private var cachedAddr: InetAddress? = null
    private var cachedTransportHost: String? = null
    private var cachedTransport: Transport? = null

    // ---------------------------------------------------------------- lifecycle
    fun start() {
        if (running) {
            if (sock == null) rebind()
            return
        }
        running = true
        rebind()
        val gen = ++generation
        sender = thread(name = "vpad-sender", isDaemon = true) { sendLoop(gen) }
        receiver = thread(name = "vpad-receiver", isDaemon = true) { receiveLoop(gen) }
    }

    fun stop() {
        running = false
        generation++
        sender = null
        receiver = null
        closeSocket()
        connected = false
        lastPongAt = 0
        rttMs = -1
    }

    /** Drops the target but keeps the workers alive for a fast reconnect. */
    fun disconnect() {
        host = null
        resetConnection()
    }

    /** Rebuilds the socket now, e.g. after a link change or a failed link. */
    fun reconnect() {
        resetConnection()
        if (running) rebind()
    }

    /** Point the link at a new address and rebuild the socket for it. */
    fun retarget(newHost: String?, newPort: Int) {
        host = newHost
        port = newPort
        resetConnection()
        if (running) rebind()
    }

    private fun resetConnection() {
        connected = false
        lastPongAt = 0
        rttMs = -1
        cachedHost = null
        cachedAddr = null
        cachedTransportHost = null
        cachedTransport = null
        serverName = ""
    }

    // ------------------------------------------------------------------ sockets
    private fun closeSocket() {
        closeQuietly(sock)
        sock = null
    }

    private fun closeQuietly(s: DatagramSocket?) {
        try {
            s?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Creates a fresh socket bound to the link for the current transport.
     * Tether interfaces often have no ConnectivityManager Network, so binding
     * to the interface's own address is the fallback that makes USB and
     * Bluetooth work on those ROMs.
     */
    private fun rebind() {
        if (!running) return
        lastRebindAt = SystemClock.uptimeMillis()
        val choice = pickProbe(probes(transport), host)
        val fresh = try {
            DatagramSocket()
        } catch (_: Exception) {
            null
        }
        if (fresh == null) return
        var bound = false
        if (choice?.network != null) {
            try {
                choice.network.bindSocket(fresh)
                bound = true
            } catch (_: Exception) {
                // Network vanished between lookup and bind; try the address.
            }
        }
        if (!bound && choice?.local != null) {
            try {
                fresh.bind(InetSocketAddress(choice.local, 0))
            } catch (_: Exception) {
                // The socket still works on the default route.
            }
        }
        val old = sock
        sock = fresh
        viaTransport = choice?.transport
        connected = false
        lastPongAt = 0
        rttMs = -1
        try {
            old?.close()
        } catch (_: Exception) {
        }
    }

    /** The network the current control socket uses, for the PiP TCP stream. */
    fun boundNetwork(): Network? = pickProbe(probes(transport), host)?.network

    private fun connectivity(): ConnectivityManager? = try {
        appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    } catch (_: Exception) {
        null
    }

    private fun transportOf(caps: NetworkCapabilities): Transport? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
        ) {
            return Transport.BLUETOOTH
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return Transport.USB
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)
        ) {
            return Transport.USB
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return Transport.WIFI
        return null
    }

    // ------------------------------------------------------------------ sender
    private fun sendLoop(gen: Int) {
        var lastVersion = -1L
        var lastInputAt = 0L
        var lastPingAt = 0L

        while (running && generation == gen) {
            val s = sock
            if (s == null) {
                SystemClock.sleep(40)
                continue
            }
            val h = host
            val now = SystemClock.uptimeMillis()

            if (h != null) {
                val addr = addressOf(h)
                if (addr != null) {
                    try {
                        // Input: on every change, plus a keepalive twice a second.
                        val v = state.version
                        if (v != lastVersion || now - lastInputAt > 500) {
                            val n = buildInput()
                            s.send(DatagramPacket(sendBuffer, n, addr, port))
                            lastVersion = v
                            lastInputAt = now
                        }
                        if (now - lastPingAt > 700) {
                            val n = buildPing(now)
                            s.send(DatagramPacket(sendBuffer, n, addr, port))
                            lastPingAt = now
                        }
                    } catch (_: Exception) {
                        // Link hiccup - the watchdog below rebuilds the socket.
                    }
                }
            }

            // After a drop (or a link change we could not see), try a clean
            // socket every few seconds. Skipped while the PC is answering.
            if (h != null && now - lastRebindAt > REBIND_AFTER_MS &&
                (lastPongAt == 0L || now - lastPongAt > REBIND_AFTER_MS)
            ) {
                rebind()
            }

            SystemClock.sleep(7)
        }
    }

    private fun addressOf(h: String): InetAddress? {
        if (h == cachedHost) return cachedAddr
        val a = try {
            InetAddress.getByName(h)
        } catch (_: Exception) {
            null
        }
        if (a != null) {
            cachedHost = h
            cachedAddr = a
        }
        return a
    }

    private fun begin(type: Int): ByteBuffer {
        bb.clear()
        bb.put(Proto.MAGIC_BYTES)
        bb.put(Proto.VERSION.toByte())
        bb.put(type.toByte())
        bb.putShort((seq++ and 0xFFFF).toShort())
        bb.putInt(token)
        return bb
    }

    private fun buildInput(): Int {
        val b = begin(Proto.MSG_INPUT)
        b.putInt(state.buttons)
        b.putShort(state.lx.toShort())
        b.putShort(state.ly.toShort())
        b.putShort(state.rx.toShort())
        b.putShort(state.ry.toShort())
        b.put(state.l2.toByte())
        b.put(state.r2.toByte())
        b.put(battery.toByte())
        b.put(0) // virtual pad type hint (0 = xbox 360)
        return Proto.INPUT_SIZE
    }

    private fun buildPing(now: Long): Int {
        val b = begin(Proto.MSG_PING)
        b.putInt((now and 0xFFFFFFFFL).toInt())
        return Proto.HEADER_SIZE + 4
    }

    // ---------------------------------------------------------------- receiver
    private fun receiveLoop(gen: Int) {
        val buf = ByteArray(512)

        while (running && generation == gen) {
            val s = sock
            if (s == null) {
                SystemClock.sleep(40)
                continue
            }
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (_: Exception) {
                if (!running || generation != gen) break
                SystemClock.sleep(60)
                continue
            }
            if (s !== sock) continue

            val len = packet.length
            if (len < Proto.HEADER_SIZE) continue

            val b = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            b.get(magic)
            if (String(magic, Charsets.US_ASCII) != Proto.MAGIC) continue
            b.get() // version
            val type = b.get().toInt()
            b.position(b.position() + 2) // seq
            val packetToken = b.int
            // Ignore traffic meant for another phone on the same PC.
            if (packetToken != token) continue

            when (type) {
                Proto.MSG_PONG -> {
                    if (len >= Proto.HEADER_SIZE + 4) {
                        val sent = b.int.toLong() and 0xFFFFFFFFL
                        val now = SystemClock.uptimeMillis()
                        rttMs = (now - sent) and 0xFFFFFFFFL
                        lastPongAt = now
                        connected = true
                    }
                }
                Proto.MSG_RUMBLE -> {
                    if (len >= Proto.HEADER_SIZE + 2) {
                        val small = b.get().toInt() and 0xFF
                        val large = b.get().toInt() and 0xFF
                        onRumble?.invoke(small, large)
                    }
                }
            }
        }
    }

    /** Marks the link as dead when the PC stops answering pings. */
    fun refreshConnectionState() {
        if (lastPongAt == 0L) return
        if (SystemClock.uptimeMillis() - lastPongAt > 3000) connected = false
    }

    // --------------------------------------------------------------- discovery
    private data class Offer(val ip: String, val name: String, val token: Int, val kind: Transport?)

    /**
     * One usable way out of the phone: a ConnectivityManager network and/or a
     * raw interface address. Tether interfaces (`bnep*`, `rndis*`, `ncm*`) are
     * often invisible to ConnectivityManager, so the address is the important
     * part.
     */
    private data class Probe(
        val transport: Transport?,
        val network: Network?,
        val local: Inet4Address?,
        val prefix: Int,
        val name: String,
    )

    /** Result of the last scan, so the settings sheet can explain itself. */
    @Volatile var lastScan: String = ""
        private set

    @Volatile var lastScanFound: Boolean = false
        private set

    /** Link the last successful scan came in on, so the sheet can follow it. */
    @Volatile var lastScanTransport: Transport? = null
        private set

    /**
     * Finds the PC without a typed address.
     *
     * Three passes, cheapest first: broadcast on the selected link, broadcast
     * on every other link, then a unicast sweep of each subnet. The sweep is
     * what rescues networks whose router drops broadcast traffic, and USB /
     * Bluetooth tethers whose interface Android never exposes as a Network.
     */
    fun discover(timeoutMs: Int = 2600, callback: (String?, String?) -> Unit) {
        thread(name = "vpad-discover", isDaemon = true) {
            val started = SystemClock.uptimeMillis()
            val deadline = started + timeoutMs
            val want = transport

            val wanted = probes(want)
            val everything = if (want == Transport.AUTO) wanted else probes(Transport.AUTO)
            val offers = ArrayList<Offer>()

            var sent = broadcast(wanted, offers, min(deadline, SystemClock.uptimeMillis() + 780))

            // The chip may say BLUETOOTH while the PC is actually on Wi-Fi
            // (very common after fiddling with transports). Widen before
            // giving up instead of reporting "nothing found".
            var widened = false
            if (offers.isEmpty() && want != Transport.AUTO && everything.size > wanted.size) {
                widened = true
                sent += broadcast(
                    everything.filter { p -> wanted.none { it.local?.hostAddress == p.local?.hostAddress } },
                    offers,
                    min(deadline, SystemClock.uptimeMillis() + 560),
                )
            }

            // Broadcast unanswered: try every host in each subnet directly.
            var swept = false
            if (offers.isEmpty()) {
                swept = true
                val all = LinkedHashMap<String, Probe>()
                for (p in wanted + everything) {
                    all[p.local?.hostAddress ?: p.name] = p
                }
                sent += sweep(all.values.toList(), offers, deadline)
            }

            val best = offers.firstOrNull { want != Transport.AUTO && it.kind == want }
                ?: offers.firstOrNull()
            if (best != null) {
                token = best.token
                saveToken(appContext, best.token)
                cachedHost = null
                cachedAddr = null
            }
            lastScanFound = best != null
            lastScanTransport = best?.kind
            lastScan = describeScan(want, wanted, sent, widened, swept, offers)

            callback(best?.ip, best?.name ?: "")
        }
    }

    private fun describeScan(
        want: Transport,
        links: List<Probe>,
        sent: Int,
        widened: Boolean,
        swept: Boolean,
        offers: List<Offer>,
    ): String {
        val label = when (want) {
            Transport.AUTO -> "all links"
            else -> want.label.lowercase()
        }
        val interfaceCount = if (links.isEmpty()) "no interface" else {
            "${links.size} link" + (if (links.size == 1) "" else "s")
        }
        return buildString {
            append(label)
            append(" · ")
            append(interfaceCount)
            append(" · ")
            append("$sent probes")
            if (widened) append(" · widened")
            if (swept) append(" · swept")
            append(" · ")
            append(if (offers.isEmpty()) "no reply" else "${offers.size} reply")
        }
    }

    /** Broadcast probes on each link and listen briefly for an OFFER. */
    private fun broadcast(list: List<Probe>, offers: MutableList<Offer>, windowEnd: Long): Int {
        var sent = 0
        val probe = Proto.header(Proto.MSG_DISCOVER, 0, token)
        for (p in list) {
            if (offers.isNotEmpty() || SystemClock.uptimeMillis() >= windowEnd) break
            val s = openSocket(p) ?: continue
            try {
                val targets = broadcastTargets(p)
                val end = min(windowEnd, SystemClock.uptimeMillis() + 430)
                var lastSend = 0L
                while (SystemClock.uptimeMillis() < end) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastSend > 150) {
                        lastSend = now
                        for (a in targets) {
                            if (send(s, probe, a)) sent++
                        }
                    }
                    readOffer(s, p, offers)
                }
            } finally {
                closeQuietly(s)
            }
        }
        return sent
    }

    /**
     * Sends DISCOVER to every address in each /24 and waits for OFFERs. One
     * packet per host: at 254 hosts this is a few kilobytes, not a flood.
     */
    private fun sweep(list: List<Probe>, offers: MutableList<Offer>, deadline: Long): Int {
        if (list.isEmpty()) return 0
        var sent = 0
        val probe = Proto.header(Proto.MSG_DISCOVER, 0, token)
        for (p in list) {
            if (offers.isNotEmpty() || deadline - SystemClock.uptimeMillis() < 220) break
            val s = openSocket(p) ?: continue
            try {
                val hosts = subnetHosts(p)
                if (hosts.isEmpty()) continue
                for (a in hosts) {
                    if (!send(s, probe, a)) break
                    sent++
                }
                val remain = deadline - SystemClock.uptimeMillis()
                val share = (remain / list.size).coerceIn(260L, 700L)
                val end = min(deadline, SystemClock.uptimeMillis() + share)
                while (SystemClock.uptimeMillis() < end && offers.isEmpty()) {
                    readOffer(s, p, offers)
                }
            } finally {
                closeQuietly(s)
            }
        }
        return sent
    }

    /** 1-254 of the /24 that contains this link's address. */
    private fun subnetHosts(p: Probe): List<InetAddress> {
        val local = p.local ?: return emptyList()
        val ip = local.address
        if (ip.size != 4) return emptyList()
        val self = ip[3].toInt() and 0xFF
        val out = ArrayList<InetAddress>(254)
        for (i in 1..254) {
            if (i == self) continue
            val bytes = byteArrayOf(ip[0], ip[1], ip[2], i.toByte())
            out.add(InetAddress.getByAddress(bytes))
        }
        return out
    }

    private fun send(s: DatagramSocket, probe: ByteArray, addr: InetAddress): Boolean = try {
        s.send(DatagramPacket(probe, probe.size, addr, port))
        true
    } catch (_: Exception) {
        false
    }

    private fun openSocket(p: Probe?): DatagramSocket? {
        val s = try {
            DatagramSocket()
        } catch (_: Exception) {
            return null
        }
        try {
            // A real Network object is the cleanest binding, but tethers rarely
            // have one - binding the socket to the interface's own address is
            // what makes USB / Bluetooth work at all on those ROMs.
            var bound = false
            if (p?.network != null) {
                try {
                    p.network.bindSocket(s)
                    bound = true
                } catch (_: Exception) {
                }
            }
            if (!bound && p?.local != null) {
                try {
                    s.bind(InetSocketAddress(p.local, 0))
                } catch (_: Exception) {
                }
            }
            s.broadcast = true
            s.soTimeout = 110
            return s
        } catch (_: Exception) {
            closeQuietly(s)
            return null
        }
    }

    private fun readOffer(s: DatagramSocket, p: Probe, offers: MutableList<Offer>) {
        val buf = ByteArray(320)
        val reply = DatagramPacket(buf, buf.size)
        try {
            s.receive(reply)
        } catch (_: Exception) {
            return
        }
        if (reply.length < Proto.HEADER_SIZE) return
        val b = ByteBuffer.wrap(buf, 0, reply.length).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4)
        b.get(magic)
        if (String(magic, Charsets.US_ASCII) != Proto.MAGIC) return
        b.get()
        if (b.get().toInt() != Proto.MSG_OFFER) return
        b.position(b.position() + 2)
        val offeredToken = b.int
        var name = ""
        if (b.remaining() >= 1) {
            val nameLen = b.get().toInt() and 0xFF
            if (b.remaining() >= nameLen) {
                val nameBytes = ByteArray(nameLen)
                b.get(nameBytes)
                name = String(nameBytes, Charsets.UTF_8)
            }
        }
        if (b.remaining() >= 1) b.get() // pad type

        // Servers that support it list every local address. Prefer one on the
        // subnet we probed, so a PC with VPN / virtual adapters still pairs
        // over the right link instead of a dead address.
        var chosen = reply.address?.hostAddress ?: return
        if (b.remaining() >= 1) {
            val count = b.get().toInt() and 0xFF
            for (i in 0 until count) {
                if (b.remaining() < 4) break
                val raw = ByteArray(4)
                b.get(raw)
                val candidate = try {
                    InetAddress.getByAddress(raw) as Inet4Address
                } catch (_: Exception) {
                    null
                } ?: continue
                if (p.local != null && sameSubnet(p.local.address, candidate.address, p.prefix)) {
                    chosen = candidate.hostAddress ?: chosen
                    break
                }
            }
        }

        val kind = p.transport ?: transportFor(chosen)
        if (offers.none { it.ip == chosen }) {
            offers.add(Offer(chosen, name, offeredToken, kind))
        }
    }

    /** Broadcast addresses for a link: its subnet broadcast, plus tether ranges. */
    private fun broadcastTargets(p: Probe): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        if (p.local != null) {
            out.add(subnetBroadcast(p.local, p.prefix))
        }
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        if (p.transport == null || p.transport == Transport.BLUETOOTH || p.transport == Transport.USB) {
            for (extra in TETHER_BROADCASTS) {
                try {
                    out.add(InetAddress.getByName(extra))
                } catch (_: Exception) {
                }
            }
        }
        return out.toList()
    }

    private fun subnetBroadcast(ip: Inet4Address, prefix: Int): InetAddress {
        val bytes = ip.address
        val p = prefix.coerceIn(0, 32)
        for (i in 0 until 4) {
            val bits = (p - i * 8).coerceIn(0, 8)
            val mask = if (bits == 0) 0 else (0xFF shl (8 - bits)) and 0xFF
            bytes[i] = (bytes[i].toInt() or (mask.inv() and 0xFF)).toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    /**
     * Every usable link, optionally filtered to one transport. Deduplicated by
     * address, preferring the entry that has a ConnectivityManager Network.
     */
    @Suppress("DEPRECATION")
    private fun probes(want: Transport): List<Probe> {
        val byIp = LinkedHashMap<String, Probe>()
        val cm = connectivity()
        if (cm != null) {
            try {
                for (net in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(net) ?: continue
                    val kind = transportOf(caps) ?: continue
                    val lp = cm.getLinkProperties(net) ?: continue
                    for (la in lp.linkAddresses) {
                        val ip = la.address
                        if (ip !is Inet4Address || ip.isLinkLocalAddress || ip.isLoopbackAddress) continue
                        val key = ip.hostAddress ?: continue
                        byIp[key] = Probe(kind, net, ip, la.prefixLength, lp.interfaceName ?: "")
                    }
                }
            } catch (_: Exception) {
            }
        }
        try {
            for (nif in java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                val kind = classifyInterface(nif.name) ?: continue
                for (info in nif.interfaceAddresses) {
                    val ip = info.address
                    if (ip !is Inet4Address || ip.isLinkLocalAddress || ip.isLoopbackAddress) continue
                    val key = ip.hostAddress ?: continue
                    if (byIp.containsKey(key)) continue
                    byIp[key] = Probe(kind, null, ip, info.networkPrefixLength.toInt(), nif.name)
                }
            }
        } catch (_: Exception) {
        }
        val all = byIp.values.toList()
        if (want == Transport.AUTO) return all
        return all.filter { it.transport == want }
    }

    /** The link the control socket should leave through, for [host]. */
    private fun pickProbe(list: List<Probe>, target: String?): Probe? {
        if (list.isEmpty()) return null
        val addr = target?.let { parseAddress(it) }
        if (addr is Inet4Address) {
            for (p in list) {
                val local = p.local ?: continue
                if (sameSubnet(local.address, addr.address, p.prefix)) return p
            }
        }
        return list.firstOrNull { it.network != null } ?: list.first()
    }

    /** The local address of the link in use, so TCP can bind it too. */
    fun boundLocalAddress(): Inet4Address? = pickProbe(probes(transport), host)?.local

    /** True when the phone currently has an interface for this transport. */
    fun transportReady(t: Transport): Boolean {
        if (t == Transport.AUTO) return true
        return probes(t).isNotEmpty()
    }

    /** Readiness for every transport from a single interface scan. */
    fun readiness(): Map<Transport, Boolean> {
        val seen = probes(Transport.AUTO).mapNotNull { it.transport }.toSet()
        return Transport.values().associateWith { it == Transport.AUTO || it in seen }
    }

    /**
     * Works out which physical link a destination IP belongs to, so the HUD can
     * say USB / BLUETOOTH / WI-FI instead of just showing an address.
     */
    fun transportName(): String {
        val h = host ?: return ""
        viaTransport?.let { return it.label }
        if (h != cachedTransportHost) {
            cachedTransportHost = h
            cachedTransport = transportFor(h)
        }
        return cachedTransport?.label ?: ""
    }

    private fun transportFor(target: String): Transport? {
        val addr = parseAddress(target) ?: return null
        if (addr !is Inet4Address) return null
        val tb = addr.address
        return try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                val kind = classifyInterface(nif.name) ?: continue
                for (info in nif.interfaceAddresses) {
                    val ab = info.address?.address ?: continue
                    if (ab.size == 4 && sameSubnet(ab, tb, info.networkPrefixLength.toInt())) return kind
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Local addresses per link, shown in settings so manual entry is easy. */
    fun localSummary(): String {
        val out = LinkedHashSet<String>()
        for (p in probes(Transport.AUTO)) {
            val ip = p.local?.hostAddress ?: continue
            val kind = p.transport ?: continue
            out += "${labelOf(kind)} $ip"
        }
        return out.joinToString("   ")
    }

    private fun labelOf(t: Transport): String = when (t) {
        Transport.WIFI -> "Wi-Fi"
        Transport.BLUETOOTH -> "BT"
        Transport.USB -> "USB"
        else -> "LAN"
    }

    private fun parseAddress(value: String): InetAddress? = try {
        InetAddress.getByName(value)
    } catch (_: Exception) {
        null
    }

    private fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
        val p = prefix.coerceIn(0, 32)
        val full = p / 8
        for (i in 0 until full) {
            if (a[i] != b[i]) return false
        }
        val rest = p % 8
        if (rest > 0 && full < 4) {
            val mask = (0xFF shl (8 - rest)) and 0xFF
            if ((a[full].toInt() and mask) != (b[full].toInt() and mask)) return false
        }
        return true
    }

    private companion object {
        /** Rebuild a dead socket after this long without a pong. */
        const val REBIND_AFTER_MS = 5000L

        /** Which token this install keeps for good; see [token]. */
        fun loadToken(context: Context?): Int {
            val prefs = context?.getSharedPreferences(PrefsKeys.FILE, Context.MODE_PRIVATE)
            val saved = prefs?.getInt(PrefsKeys.TOKEN, 0) ?: 0
            if (saved != 0) return saved
            // 0 means "please assign one" in the protocol, so never use it.
            var fresh = Random.nextInt()
            if (fresh == 0) fresh = 1
            prefs?.edit()?.putInt(PrefsKeys.TOKEN, fresh)?.apply()
            return fresh
        }

        fun saveToken(context: Context?, value: Int) {
            if (value == 0) return
            context?.getSharedPreferences(PrefsKeys.FILE, Context.MODE_PRIVATE)
                ?.edit()?.putInt(PrefsKeys.TOKEN, value)?.apply()
        }

        /**
         * Android tether subnets in case a ROM hides the interface from
         * ConnectivityManager: USB is 192.168.42.x, Bluetooth PAN 44.x,
         * some vendors use 43.x or the Windows ICS 137.x range.
         */
        val TETHER_BROADCASTS = arrayOf(
            "192.168.42.255",
            "192.168.43.255",
            "192.168.44.255",
            "192.168.137.255",
        )
    }
}

/** Interface names Android uses for Wi-Fi, Bluetooth PAN and USB tethering. */
internal fun classifyInterface(name: String): Transport? {
    val n = name.lowercase()
    return when {
        n.startsWith("bnep") || n.startsWith("bt-pan") || n.startsWith("bt0") -> Transport.BLUETOOTH
        n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> Transport.USB
        n.startsWith("wlan") || n.startsWith("wl") || n.startsWith("ap") ||
            n.startsWith("swlan") || n.startsWith("p2p") ||
            n.startsWith("eth") || n.startsWith("en") -> Transport.WIFI
        else -> null
    }
}
