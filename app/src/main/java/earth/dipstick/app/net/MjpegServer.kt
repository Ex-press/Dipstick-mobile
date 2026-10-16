package earth.dipstick.app.net

import android.util.Log
import java.io.BufferedWriter
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A dependency-free MJPEG-over-HTTP server.
 *
 * Chosen over RTSP/WebRTC because the viewer needs nothing installed: any phone, tablet or
 * laptop on the same Wi-Fi opens the URL in a browser and sees the feed. The tradeoff is
 * bandwidth and a second or so of latency - fine for someone watching an inspection, wrong
 * if you ever need frame-accurate remote control.
 *
 * Frames come from the GL pipeline *after* the shader, so remote viewers see the same
 * brightness/levels as the operator.
 */
class MjpegServer(private val port: Int = DEFAULT_PORT) {

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<ClientConnection>()

    private val frameLock = Object()
    private var latestFrame: ByteArray? = null
    private var frameSequence = 0L

    // Delivery counters, sampled by the capture side to decide whether the current
    // resolution is actually making it to viewers.
    private val deliveredFrames = AtomicLong()
    private val deliveredBytes = AtomicLong()
    private val writeStallNanos = AtomicLong()

    val isRunning: Boolean get() = running.get()

    /** Number of connected viewers. Zero means the GL thread can skip readback entirely. */
    val clientCount: Int get() = clients.size

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            serverSocket = ServerSocket(port).apply { reuseAddress = true }
            running.set(true)
            thread(name = "mjpeg-accept", isDaemon = true) { acceptLoop() }
            Log.i(TAG, "listening on :$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "could not bind port $port", e)
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        clients.forEach { it.close() }
        clients.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(frameLock) {
            latestFrame = null
            frameLock.notifyAll()
        }
        Log.i(TAG, "stopped")
    }

    /**
     * How much actually reached viewers since the last call, and how long the sending
     * threads spent blocked on slow sockets. Resets the counters.
     */
    fun sampleDelivery(): Delivery = Delivery(
        frames = deliveredFrames.getAndSet(0),
        bytes = deliveredBytes.getAndSet(0),
        stallNanos = writeStallNanos.getAndSet(0),
        clients = clients.size,
    )

    data class Delivery(
        val frames: Long,
        val bytes: Long,
        val stallNanos: Long,
        val clients: Int,
    )

    /** Publishes a frame. Cheap and non-blocking; viewers pick up the newest one. */
    fun offerFrame(jpeg: ByteArray) {
        if (!running.get()) return
        synchronized(frameLock) {
            latestFrame = jpeg
            frameSequence++
            frameLock.notifyAll()
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                serverSocket?.accept() ?: break
            } catch (e: SocketException) {
                break   // expected on stop()
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "accept failed", e)
                break
            }
            val client = ClientConnection(socket)
            clients += client
            thread(name = "mjpeg-client", isDaemon = true) {
                try {
                    client.serve()
                } catch (e: Exception) {
                    // Viewers close tabs; that is not an error worth shouting about.
                    Log.d(TAG, "client ended: ${e.message}")
                } finally {
                    client.close()
                    clients -= client
                }
            }
        }
    }

    private inner class ClientConnection(private val socket: Socket) {

        fun close() = runCatching { socket.close() }

        fun serve() {
            socket.soTimeout = 0
            socket.tcpNoDelay = true

            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: "/"

            val out = socket.getOutputStream()
            when {
                path.startsWith("/stream") -> serveStream(out)
                path.startsWith("/snapshot") -> serveSnapshot(out)
                else -> serveIndexPage(out)
            }
        }

        private fun serveIndexPage(out: OutputStream) {
            val html = """
                <!doctype html>
                <html><head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Dipstick</title>
                <style>
                  html,body{margin:0;height:100%;background:#0e1a24;color:#cfd8dc;
                    font:14px -apple-system,Segoe UI,Roboto,sans-serif}
                  .wrap{height:100%;display:flex;flex-direction:column;align-items:center;
                    justify-content:center;gap:10px}
                  img{max-width:100%;max-height:85vh;background:#000;border-radius:6px}
                  a{color:#4fc3f7}
                </style>
                </head><body><div class="wrap">
                  <img src="/stream" alt="Dipstick live view">
                  <div>Live from Dipstick · <a href="/snapshot.jpg">still frame</a></div>
                </div></body></html>
            """.trimIndent()

            val body = html.toByteArray()
            val writer = BufferedWriter(out.writer())
            writer.write("HTTP/1.0 200 OK\r\n")
            writer.write("Content-Type: text/html; charset=utf-8\r\n")
            writer.write("Content-Length: ${body.size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.flush()
            out.write(body)
            out.flush()
        }

        private fun serveSnapshot(out: OutputStream) {
            val frame = synchronized(frameLock) { latestFrame }
            val writer = BufferedWriter(out.writer())
            if (frame == null) {
                writer.write("HTTP/1.0 503 Service Unavailable\r\n")
                writer.write("Content-Length: 0\r\n")
                writer.write("Connection: close\r\n\r\n")
                writer.flush()
                return
            }
            writer.write("HTTP/1.0 200 OK\r\n")
            writer.write("Content-Type: image/jpeg\r\n")
            writer.write("Content-Length: ${frame.size}\r\n")
            writer.write("Cache-Control: no-store\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.flush()
            out.write(frame)
            out.flush()
        }

        private fun serveStream(out: OutputStream) {
            val writer = BufferedWriter(out.writer())
            writer.write("HTTP/1.0 200 OK\r\n")
            writer.write("Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n")
            writer.write("Cache-Control: no-store\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.flush()

            var lastSent = -1L
            while (running.get() && !socket.isClosed) {
                // Grab the newest frame under the lock, but do the socket write outside it
                // so a slow viewer cannot stall the GL thread publishing frames.
                var frame: ByteArray? = null
                synchronized(frameLock) {
                    while (running.get() && frameSequence == lastSent) {
                        frameLock.wait(1000)
                    }
                    if (running.get()) {
                        frame = latestFrame
                        lastSent = frameSequence
                    }
                }
                if (!running.get()) return
                val payload = frame ?: continue

                // Time the write: on a congested link this blocks, and that stall is the
                // clearest signal that the current resolution is too much for the network.
                val before = System.nanoTime()
                writer.write("--$BOUNDARY\r\n")
                writer.write("Content-Type: image/jpeg\r\n")
                writer.write("Content-Length: ${payload.size}\r\n\r\n")
                writer.flush()
                out.write(payload)
                out.write("\r\n".toByteArray())
                out.flush()

                writeStallNanos.addAndGet(System.nanoTime() - before)
                deliveredFrames.incrementAndGet()
                deliveredBytes.addAndGet(payload.size.toLong())
            }
        }
    }

    companion object {
        private const val TAG = "DipstickNet"
        private const val BOUNDARY = "dipstickframe"
        const val DEFAULT_PORT = 8080

        /** Every non-loopback IPv4 address, so the UI can show a URL that actually works. */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
                .toList()
        }.getOrDefault(emptyList())
    }
}
