package earth.dipstick.app.teslong

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.concurrent.thread

/**
 * Receives MJPEG video from a Teslong network endoscope over UDP and hands complete JPEG frames
 * to [onFrame].
 *
 * Each datagram carries a 24-byte header followed by up to 1390 bytes of JPEG data:
 * ```
 *  0  u8   0x66 magic          8  u16  width            16  u32  (unused here)
 *  2  u8   format              10 u16  height           20  u32  timestamp
 *  3  u8   frame id            12 u16  fragment index
 *  4  u32  total frame bytes   14 u16  fragment length  24  payload
 * ```
 * The NTC200AF leaves the width and height fields zero, so callers take the dimensions from the
 * decoded JPEG instead.
 * Frames smaller than [SINGLE_DATAGRAM_LIMIT] arrive whole; larger ones are split across
 * fragments indexed from zero, each holding the same number of bytes as the first.
 */
class TeslongVideo(
    private val link: TeslongLink,
    private val onFrame: (jpeg: ByteArray) -> Unit
) {

    @Volatile
    private var running = false
    private var worker: Thread? = null

    /** Frames completed since the last start, for diagnostics. */
    @Volatile
    var framesDelivered = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun start() {
        if (running) return
        running = true
        framesDelivered = 0
        worker = thread(name = "teslong-video") { receiveLoop() }
    }

    fun stop() {
        running = false
        worker?.join(STOP_JOIN_MS)
        worker = null
    }

    private fun receiveLoop() {
        val socket = runCatching { link.openSocket(RECEIVE_TIMEOUT_MS) }.getOrElse {
            lastError = "socket: $it"
            Log.w(TeslongLink.TAG, "video socket failed: $it")
            return
        }
        // Two slots: a late fragment of the previous frame can arrive after the next one starts.
        val slots = arrayOf(FrameAssembly(), FrameAssembly())
        val buffer = ByteArray(DATAGRAM_BYTES)
        var lastKeepalive = 0L

        socket.use {
            while (running) {
                val now = System.currentTimeMillis()
                if (now - lastKeepalive >= KEEPALIVE_INTERVAL_MS) {
                    lastKeepalive = now
                    sendStart(socket)
                }
                val packet = DatagramPacket(buffer, buffer.size)
                val got = runCatching { socket.receive(packet); true }.getOrElse { false }
                if (!got) continue
                if (packet.length >= HEADER_BYTES) consume(buffer, packet.length, slots)
            }
        }
    }

    /** Tells the camera to stream to us; also serves as the keepalive it expects. */
    private fun sendStart(socket: DatagramSocket) {
        val start = ByteArray(START_PACKET_BYTES)
        start[0] = 0x99.toByte()
        start[1] = 0x99.toByte()
        start[2] = 0x01
        runCatching {
            socket.send(
                DatagramPacket(start, start.size, link.cameraAddress, TeslongLink.VIDEO_PORT)
            )
        }.onFailure {
            lastError = "send: $it"
            Log.w(TeslongLink.TAG, "video keepalive failed: $it")
        }
    }

    private fun consume(buffer: ByteArray, length: Int, slots: Array<FrameAssembly>) {
        if (buffer[0] != FRAME_MAGIC) return

        val frameId = buffer[3].toInt() and 0xFF
        val totalBytes = le32(buffer, 4)
        val width = le16(buffer, 8)
        val height = le16(buffer, 10)
        val index = le16(buffer, 12)
        val fragmentBytes = le16(buffer, 14)

        if (totalBytes <= 0 || totalBytes > MAX_FRAME_BYTES) return
        if (fragmentBytes <= 0 || HEADER_BYTES + fragmentBytes > length) return

        val slot = slots.firstOrNull { it.active && it.frameId == frameId }
            ?: slots.minByOrNull { if (it.active) it.startedAt else 0L }!!

        if (!slot.active || slot.frameId != frameId) {
            slot.begin(frameId, totalBytes, width, height, fragmentBytes)
        }
        if (!slot.addFragment(index, buffer, HEADER_BYTES, fragmentBytes)) return

        val complete = slot.takeIfComplete() ?: return
        framesDelivered++
        onFrame(complete)
        slot.reset()
    }

    /** Accumulates the fragments of one frame. */
    private class FrameAssembly {
        var active = false
            private set
        var frameId = -1
            private set
        var width = 0
            private set
        var height = 0
            private set
        var startedAt = 0L
            private set

        private var total = 0
        private var fragmentSize = 1
        private var received = 0
        private var data = ByteArray(0)
        private val seen = HashSet<Int>()

        fun begin(id: Int, totalBytes: Int, w: Int, h: Int, fragmentBytes: Int) {
            active = true
            frameId = id
            total = totalBytes
            width = w
            height = h
            fragmentSize = fragmentBytes
            received = 0
            startedAt = System.currentTimeMillis()
            seen.clear()
            if (data.size < totalBytes) data = ByteArray(totalBytes)
        }

        /** Returns false for a duplicate or out-of-range fragment. */
        fun addFragment(index: Int, source: ByteArray, offset: Int, count: Int): Boolean {
            if (!active || !seen.add(index)) return false
            val destination = index * fragmentSize
            if (destination < 0 || destination + count > total) return false
            source.copyInto(data, destination, offset, offset + count)
            received += count
            return true
        }

        fun takeIfComplete(): ByteArray? =
            if (active && received == total) data.copyOf(total) else null

        fun reset() {
            active = false
            frameId = -1
            received = 0
            seen.clear()
        }
    }

    private fun le16(b: ByteArray, offset: Int) =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, offset: Int) =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)

    companion object {
        const val FRAME_MAGIC: Byte = 0x66
        const val HEADER_BYTES = 24
        const val START_PACKET_BYTES = 24
        const val DATAGRAM_BYTES = 3151
        const val KEEPALIVE_INTERVAL_MS = 600L
        const val RECEIVE_TIMEOUT_MS = 400
        const val SINGLE_DATAGRAM_LIMIT = 1501
        const val MAX_FRAME_BYTES = 1 shl 20
        const val STOP_JOIN_MS = 1500L
    }
}
