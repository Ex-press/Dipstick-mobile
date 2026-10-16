package earth.dipstick.app.teslong

import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Finds out what a Teslong-style camera is actually listening on, rather than assuming. Scans the
 * candidate ports used across this vendor's product line and probes each one that answers.
 */
object TeslongScan {

    private const val TAG = TeslongLink.TAG

    private val TCP_PORTS = intArrayOf(
        80, 81, 443, 554, 1935, 3333, 5000, 6000, 7070, 8000, 8030, 8080, 8081,
        8090, 8192, 8554, 8888, 9999, 10000, 20000, 34567, 50000
    )

    private val UDP_PORTS = intArrayOf(5000, 8030, 8070, 8080, 8888, 9999, 20000, 50000)

    fun run(link: TeslongLink) {
        val camera = link.cameraAddress
        Log.i(TAG, "--- scanning ${camera.hostAddress} ---")

        val openTcp = TCP_PORTS.filter { port -> tcpOpen(link, camera, port) }
        Log.i(TAG, "open TCP ports: ${if (openTcp.isEmpty()) "none" else openTcp.joinToString()}")
        openTcp.forEach { probeTcp(link, camera, it) }

        UDP_PORTS.forEach { port -> udpProbe(link, camera, port) }

        // Some units only answer a subnet broadcast until they know who to talk to.
        broadcastProbe(link)
    }

    private fun tcpOpen(link: TeslongLink, camera: InetAddress, port: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                link.localAddress?.let { socket.bind(InetSocketAddress(it, 0)) }
                socket.connect(InetSocketAddress(camera, port), TCP_TIMEOUT_MS)
                Log.i(TAG, "TCP $port OPEN")
                true
            }
        }.getOrDefault(false)

    /** Ask an open port to identify itself: RTSP first, then HTTP. */
    private fun probeTcp(link: TeslongLink, camera: InetAddress, port: Int) {
        val requests = listOf(
            "RTSP" to "OPTIONS rtsp://${camera.hostAddress}:$port/ RTSP/1.0\r\nCSeq: 1\r\n\r\n",
            "HTTP" to "GET / HTTP/1.0\r\nHost: ${camera.hostAddress}\r\n\r\n"
        )
        for ((label, request) in requests) {
            runCatching {
                Socket().use { socket ->
                    link.localAddress?.let { socket.bind(InetSocketAddress(it, 0)) }
                    socket.connect(InetSocketAddress(camera, port), TCP_TIMEOUT_MS)
                    socket.soTimeout = TCP_TIMEOUT_MS
                    socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                    val buffer = ByteArray(512)
                    val read = socket.getInputStream().read(buffer)
                    if (read > 0) {
                        val text = String(buffer, 0, read, Charsets.ISO_8859_1)
                            .lineSequence().take(4).joinToString(" | ")
                        Log.i(TAG, "TCP $port $label reply: $text")
                    } else {
                        Log.i(TAG, "TCP $port $label: closed with no data")
                    }
                }
            }.onFailure { Log.i(TAG, "TCP $port $label: $it") }
        }
    }

    /** Send both known UDP request shapes and report anything that answers. */
    private fun udpProbe(link: TeslongLink, camera: InetAddress, port: Int) {
        val probes = linkedMapOf(
            "SETCMD/get_resolution" to setcmd(9),
            "SETCMD/get_version" to setcmd(0x1002),
            "stream-start-0x9999" to streamStart()
        )
        for ((label, payload) in probes) {
            runCatching {
                link.openSocket(receiveTimeoutMs = UDP_TIMEOUT_MS).use { socket ->
                    socket.send(DatagramPacket(payload, payload.size, camera, port))
                    val buffer = ByteArray(2048)
                    val reply = DatagramPacket(buffer, buffer.size)
                    socket.receive(reply)
                    val head = buffer.copyOf(minOf(reply.length, 32))
                        .joinToString(" ") { "%02X".format(it) }
                    Log.i(TAG, "UDP $port $label -> ${reply.length} bytes: $head")
                }
            }.onFailure { /* silence: no answer is the normal case while scanning */ }
        }
    }

    private fun broadcastProbe(link: TeslongLink) {
        val local = link.localAddress ?: return
        val octets = local.address.copyOf()
        octets[3] = 255.toByte()
        val broadcast = InetAddress.getByAddress(octets)
        val payload = setcmd(0x1002)
        runCatching {
            link.openSocket(receiveTimeoutMs = BROADCAST_TIMEOUT_MS).use { socket ->
                socket.broadcast = true
                socket.send(
                    DatagramPacket(payload, payload.size, broadcast, TeslongLink.CONTROL_PORT)
                )
                val buffer = ByteArray(2048)
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                Log.i(
                    TAG,
                    "broadcast reply from ${reply.address?.hostAddress}: " +
                        buffer.copyOf(minOf(reply.length, 32)).joinToString(" ") { "%02X".format(it) }
                )
            }
        }.onFailure { Log.i(TAG, "broadcast ${broadcast.hostAddress}: no answer") }
    }

    private fun setcmd(command: Int): ByteArray {
        val buffer = ByteArray(14)
        "SETCMD".toByteArray(Charsets.US_ASCII).copyInto(buffer, 0)
        buffer[6] = 1
        buffer[10] = (command and 0xFF).toByte()
        buffer[11] = ((command shr 8) and 0xFF).toByte()
        return buffer
    }

    private fun streamStart(): ByteArray {
        val buffer = ByteArray(24)
        buffer[0] = 0x99.toByte()
        buffer[1] = 0x99.toByte()
        buffer[2] = 0x01
        return buffer
    }

    private const val TCP_TIMEOUT_MS = 600
    private const val UDP_TIMEOUT_MS = 350
    private const val BROADCAST_TIMEOUT_MS = 800
}
