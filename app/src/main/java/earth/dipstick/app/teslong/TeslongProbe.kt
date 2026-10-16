package earth.dipstick.app.teslong

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.concurrent.thread

/**
 * One-shot diagnostic: talk to a Teslong network endoscope and log what comes back, including the
 * raw headers of the first video datagrams so the fragment format can be confirmed against
 * hardware rather than inferred.
 */
object TeslongProbe {

    private const val TAG = TeslongLink.TAG

    fun run(context: Context) {
        thread(name = "teslong-probe") {
            val link = TeslongLink(context)
            if (!link.resolve()) return@thread
            Log.i(TAG, "probing camera at ${link.cameraAddress.hostAddress}")

            // Control test: is this one network refusing us, or all outbound UDP?
            for (target in listOf("192.168.10.123", "192.168.1.1", "8.8.8.8")) {
                for (mode in TeslongLink.BindMode.entries) {
                    val addr = java.net.InetAddress.getByName(target)
                    val ok = runCatching {
                        link.openSocket(mode = mode).use { s ->
                            s.send(DatagramPacket(ByteArray(1), 1, addr, 50000))
                        }
                        true
                    }.getOrElse { err ->
                        Log.i(TAG, "sendto $target via $mode -> $err")
                        false
                    }
                    if (ok) Log.i(TAG, "sendto $target via $mode -> OK")
                }
            }

            // Which socket-binding strategy can actually reach the camera is device dependent;
            // find out before trusting any "no reply" as evidence about the camera itself.
            val working = TeslongLink.BindMode.entries.firstOrNull { mode ->
                link.bindMode = mode
                val reachable = canSend(link, mode)
                Log.i(TAG, "bind mode $mode -> ${if (reachable) "send OK" else "send failed"}")
                reachable
            }
            if (working == null) {
                Log.w(TAG, "no socket binding could send to the camera")
                return@thread
            }
            link.bindMode = working
            Log.i(TAG, "using bind mode $working")

            TeslongScan.run(link)

            val control = TeslongControl(link)

            val version = control.getVersion()
            Log.i(TAG, "get_version -> ${version?.joinToString(" ") { "%02X".format(it) } ?: "no reply"}")

            val modes = control.getSupportedModes()
            Log.i(TAG, "supported modes -> ${if (modes.isEmpty()) "no reply" else modes.joinToString()}")

            sniffVideo(link)
        }
    }

    /** Can we put a datagram on the wire towards the camera at all under this binding? */
    private fun canSend(link: TeslongLink, mode: TeslongLink.BindMode): Boolean =
        runCatching {
            link.openSocket(mode = mode).use { socket ->
                val probe = ByteArray(1)
                socket.send(DatagramPacket(probe, 1, link.cameraAddress, TeslongLink.CONTROL_PORT))
            }
            true
        }.onFailure { Log.i(TAG, "  $mode send error: $it") }.getOrDefault(false)

    /** Runs the real receiver briefly and reports whether decodable JPEG frames arrive. */
    private fun sniffVideo(link: TeslongLink) {
        var frames = 0
        var firstDescription: String? = null
        val video = TeslongVideo(link) { jpeg ->
            frames++
            if (firstDescription == null) {
                val isJpeg = jpeg.size > 3 &&
                    (jpeg[0].toInt() and 0xFF) == 0xFF && (jpeg[1].toInt() and 0xFF) == 0xD8
                val bounds = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                firstDescription = "${bounds.outWidth}x${bounds.outHeight}, ${jpeg.size} bytes, " +
                    if (isJpeg) "valid JPEG (SOI present)" else
                        "NOT a JPEG: starts ${jpeg.copyOf(4).joinToString(" ") { "%02X".format(it) }}"
            }
        }
        video.start()
        Thread.sleep(SNIFF_DURATION_MS)
        video.stop()
        Log.i(TAG, "video: $frames frame(s) in ${SNIFF_DURATION_MS}ms; first = ${firstDescription ?: "none"}")
        video.lastError?.let { Log.w(TAG, "video error: $it") }
    }

    private const val SNIFF_DURATION_MS = 5000L
}
