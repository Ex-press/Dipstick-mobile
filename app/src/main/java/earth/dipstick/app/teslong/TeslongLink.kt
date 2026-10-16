package earth.dipstick.app.teslong

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Transport for Teslong-style endoscopes that sit behind a USB-ethernet bridge (the NTC series
 * presents a Realtek RTL8152 NIC; the camera itself is a network device on the far side).
 *
 * The phone normally has Wi-Fi as its default network, so a plain DatagramSocket sends camera
 * traffic out of wlan0 and it never arrives. Every socket here is therefore bound to the
 * Ethernet [Network] before use.
 */
class TeslongLink(private val context: Context) {

    /** The camera always hands out 192.168.10.100 by DHCP and answers on .123 itself. */
    var cameraAddress: InetAddress = InetAddress.getByName(DEFAULT_CAMERA_IP)
        private set

    private var ethernet: Network? = null

    /** Our own address on the endoscope's subnet, used to pin the socket to eth0 by source IP. */
    var localAddress: Inet4Address? = null
        private set

    /**
     * How to make a socket's packets actually leave via the endoscope's interface. A phone keeps
     * Wi-Fi as its default network, and a USB-ethernet network is typically "restricted", so
     * Network.bindSocket() is refused with EPERM for an ordinary app. Binding the local source
     * address instead selects the interface through the routing table and needs no privilege.
     */
    enum class BindMode { PROCESS_NETWORK, SOURCE_ADDRESS, NETWORK_OBJECT, UNBOUND }

    /** Locates the USB-ethernet network and the camera's address on it. Returns false if absent. */
    fun resolve(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val props = cm.getLinkProperties(network)
            val local = props?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull()
            Log.i(TAG, "ethernet network=$network iface=${props?.interfaceName} local=${local?.hostAddress}")
            Log.i(TAG, "ethernet capabilities: $caps")
            ethernet = network
            localAddress = local
            // The camera is the gateway of its own little subnet; prefer that over the constant.
            val gateway = props?.routes
                ?.mapNotNull { it.gateway }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isAnyLocalAddress }
            if (gateway != null) {
                cameraAddress = gateway
                Log.i(TAG, "camera address from route gateway: ${gateway.hostAddress}")
            } else if (local != null) {
                // Same /24, host .123 — the fixed address this family of cameras uses.
                val octets = local.address.copyOf()
                octets[3] = 123
                cameraAddress = InetAddress.getByAddress(octets)
                Log.i(TAG, "camera address inferred from local subnet: ${cameraAddress.hostAddress}")
            }
            return true
        }
        Log.w(TAG, "no ethernet transport found; is the endoscope plugged in?")
        return false
    }

    /** Opens a UDP socket whose packets leave via the endoscope's interface. */
    fun openSocket(
        receiveTimeoutMs: Int = RECV_TIMEOUT_MS,
        mode: BindMode = bindMode,
        localPort: Int = 0
    ): DatagramSocket {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            // Video arrives as many small datagrams; a generous buffer absorbs a scheduling
            // hiccup that would otherwise cost whole frames.
            runCatching { receiveBufferSize = RECEIVE_BUFFER_BYTES }
        }
        when (mode) {
            BindMode.PROCESS_NETWORK -> {
                bindProcess()
                socket.bind(InetSocketAddress(localPort))
            }
            BindMode.SOURCE_ADDRESS -> socket.bind(InetSocketAddress(localAddress, localPort))
            BindMode.UNBOUND -> socket.bind(InetSocketAddress(localPort))
            BindMode.NETWORK_OBJECT -> {
                socket.bind(InetSocketAddress(localPort))
                ethernet?.let { network ->
                    runCatching { network.bindSocket(socket) }
                        .onFailure { Log.w(TAG, "bindSocket to ethernet failed: $it") }
                }
            }
        }
        socket.soTimeout = receiveTimeoutMs
        return socket
    }

    /** Set once a strategy is known to work, so later sockets use it without re-probing. */
    var bindMode: BindMode = BindMode.PROCESS_NETWORK

    /**
     * Routes this whole process through the endoscope's network, which is what the vendor app
     * does. Unlike per-socket binding it also covers sockets we do not create ourselves, and it
     * is the only thing that affects native code. Must be undone when the camera goes away or the
     * app loses normal connectivity.
     */
    fun bindProcess(): Boolean {
        if (processBound) return true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = ethernet ?: return false
        val bound = cm.bindProcessToNetwork(network)
        processBound = bound
        Log.i(TAG, "bindProcessToNetwork(ethernet) = $bound")
        return bound
    }

    private var processBound = false

    /** Returns the process to the system default network (Wi-Fi/mobile). */
    fun unbindProcess() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        cm.bindProcessToNetwork(null)
        processBound = false
        Log.i(TAG, "process unbound from ethernet")
    }

    companion object {
        const val TAG = "DipstickTeslong"
        const val DEFAULT_CAMERA_IP = "192.168.10.123"
        const val CONTROL_PORT = 50000
        const val VIDEO_PORT = 8030
        const val RECV_TIMEOUT_MS = 500
        const val RECEIVE_BUFFER_BYTES = 1 shl 20
    }
}

/**
 * The camera's control channel: 14-byte "SETCMD" requests over UDP/50000, answered by "RETCMD".
 *
 *   offset 0  magic   "SETCMD" / "RETCMD"
 *          6  seq     uint32 LE, incrementing
 *         10  command uint16 LE
 *         12  length  uint16 LE   payload bytes that follow the header
 *         14  payload
 *
 * Replies carry a status byte at 12, the payload length at 13 and the payload from 15.
 */
class TeslongControl(private val link: TeslongLink) {

    private var sequence = 1

    fun getVersion(): ByteArray? = request(CMD_GET_VERSION)

    /**
     * The reply is a count byte followed by one 5-byte entry per supported mode:
     * width (2, LE), height (2, LE), fps (1).
     */
    fun getSupportedModes(): List<Mode> {
        val payload = request(CMD_GET_RESOLUTION) ?: return emptyList()
        if (payload.isEmpty()) return emptyList()
        val count = payload[0].toInt() and 0xFF
        val modes = ArrayList<Mode>(count)
        var lastFps = DEFAULT_FPS
        for (i in 0 until count) {
            val base = i * 5 + 1
            // Width and height are required; the camera truncates the final entry's fps byte.
            if (base + 3 >= payload.size) break
            val fps = if (base + 4 < payload.size) payload[base + 4].toInt() and 0xFF else lastFps
            if (fps > 0) lastFps = fps
            modes += Mode(le16(payload, base), le16(payload, base + 2), fps)
        }
        return modes
    }

    data class Mode(val width: Int, val height: Int, val fps: Int) {
        override fun toString() = "${width}x$height@${fps}"
    }

    fun setResolution(width: Int, height: Int, fps: Int = 30): Boolean {
        val payload = byteArrayOf(
            (width and 0xFF).toByte(), ((width shr 8) and 0xFF).toByte(),
            (height and 0xFF).toByte(), ((height shr 8) and 0xFF).toByte(),
            (fps and 0xFF).toByte()
        )
        return request(CMD_SET_RESOLUTION, payload) != null
    }

    /** Sends one command, retrying like the vendor library does, and returns the reply payload. */
    fun request(command: Int, payload: ByteArray = ByteArray(0)): ByteArray? {
        val socket = runCatching { link.openSocket() }.getOrElse {
            Log.w(TeslongLink.TAG, "control socket failed: $it")
            return null
        }
        socket.use {
            repeat(RETRIES) { attempt ->
                val packet = buildRequest(command, payload)
                val reply = exchange(socket, packet) ?: return@repeat
                val parsed = parseReply(reply, command)
                if (parsed != null) return parsed
                Log.i(TeslongLink.TAG, "cmd $command attempt ${attempt + 1}: unusable reply")
            }
        }
        Log.w(TeslongLink.TAG, "cmd $command got no valid reply after $RETRIES attempts")
        return null
    }

    private fun buildRequest(command: Int, payload: ByteArray): ByteArray {
        val seq = sequence++
        val buffer = ByteArray(HEADER_SIZE + payload.size)
        MAGIC_REQUEST.toByteArray(Charsets.US_ASCII).copyInto(buffer, 0)
        buffer[6] = (seq and 0xFF).toByte()
        buffer[7] = ((seq shr 8) and 0xFF).toByte()
        buffer[8] = ((seq shr 16) and 0xFF).toByte()
        buffer[9] = ((seq shr 24) and 0xFF).toByte()
        buffer[10] = (command and 0xFF).toByte()
        buffer[11] = ((command shr 8) and 0xFF).toByte()
        buffer[12] = (payload.size and 0xFF).toByte()
        buffer[13] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(buffer, HEADER_SIZE)
        return buffer
    }

    private fun exchange(socket: DatagramSocket, request: ByteArray): ByteArray? {
        return runCatching {
            socket.send(
                DatagramPacket(request, request.size, link.cameraAddress, TeslongLink.CONTROL_PORT)
            )
            val buffer = ByteArray(REPLY_BUFFER_BYTES)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            Log.i(
                TeslongLink.TAG,
                "reply ${response.length} bytes from ${response.address?.hostAddress}: " +
                    buffer.copyOf(minOf(response.length, 32)).joinToString(" ") { "%02X".format(it) }
            )
            buffer.copyOf(response.length)
        }.onFailure {
            Log.i(TeslongLink.TAG, "no reply (${it.javaClass.simpleName})")
        }.getOrNull()
    }

    /**
     * Commands that only need acknowledging come back as a bare 12-byte header; commands that
     * return data add a status byte, a length and the payload. The camera also under-sends the
     * declared length by a byte on at least one command, so the payload is clamped to what
     * actually arrived rather than trusted.
     */
    private fun parseReply(reply: ByteArray, command: Int): ByteArray? {
        if (reply.size < ACK_SIZE) return null
        val magic = String(reply, 0, 6, Charsets.US_ASCII)
        if (magic != MAGIC_REPLY) {
            Log.w(TeslongLink.TAG, "unexpected reply magic '$magic'")
            return null
        }
        if (le16(reply, 10) != command) return null
        if (reply.size < PAYLOAD_OFFSET) return ByteArray(0)
        val declared = le16(reply, 13)
        val available = reply.size - PAYLOAD_OFFSET
        if (declared > available) {
            Log.i(TeslongLink.TAG, "cmd $command: declared $declared payload bytes, got $available")
        }
        val length = minOf(declared, available)
        if (length <= 0) return ByteArray(0)
        return reply.copyOfRange(PAYLOAD_OFFSET, PAYLOAD_OFFSET + length)
    }

    private fun le16(b: ByteArray, offset: Int) =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    companion object {
        const val MAGIC_REQUEST = "SETCMD"
        const val MAGIC_REPLY = "RETCMD"
        const val HEADER_SIZE = 14
        const val ACK_SIZE = 12
        const val PAYLOAD_OFFSET = 15
        const val DEFAULT_FPS = 30
        const val REPLY_BUFFER_BYTES = 1024
        const val RETRIES = 3

        const val CMD_SET_RESOLUTION = 8
        const val CMD_GET_RESOLUTION = 9
        const val CMD_GET_VERSION = 0x1002
    }
}
