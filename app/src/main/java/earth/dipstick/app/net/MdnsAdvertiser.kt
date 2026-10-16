package earth.dipstick.app.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.concurrent.thread

/**
 * Publishes `<hostName>.local` and an `_http._tcp` record for the stream, so viewers can
 * type a stable name instead of chasing whatever IP the router handed out today.
 *
 * Android does publish its own mDNS hostname, but it is a generic "Android-N.local" that an
 * app has no way to rename - hence running our own responder.
 */
class MdnsAdvertiser(
    context: Context,
    private val hostName: String,
    private val port: Int,
) {
    private val appContext = context.applicationContext
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * The name viewers should type, e.g. `dipstick-7f3a.local`.
     *
     * Read back from jmDNS rather than reassembled from what we asked for: if another unit
     * on the network already claims the name, mDNS conflict resolution renames us (usually
     * by appending a number), and showing the requested name would send viewers to the wrong
     * device - or to no device at all.
     */
    @Volatile
    var localHostname: String = "$hostName.local"
        private set

    /** What we asked for, so the UI can say when the network refused it. */
    val requestedHostname: String = "$hostName.local"

    /** True when mDNS conflict resolution handed us a different name than we requested. */
    @Volatile
    var nameWasChanged: Boolean = false
        private set

    @Volatile
    var isPublished: Boolean = false
        private set

    /**
     * Starts advertising. Runs on a worker because JmDNS does blocking network setup that
     * can take a second or more, which must not land on the main thread.
     */
    fun start(onReady: (Boolean) -> Unit) {
        if (jmdns != null) return

        // Android filters multicast to save power; without this lock the responder never
        // hears the queries it is meant to answer.
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("dipstick-mdns")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        thread(name = "dipstick-mdns", isDaemon = true) {
            val ok = runCatching {
                val address = localAddress() ?: error("no IPv4 address to advertise on")
                val instance = JmDNS.create(address, hostName)
                val service = ServiceInfo.create(
                    "_http._tcp.local.",
                    SERVICE_NAME,
                    port,
                    "seaCell Dipstick live view",
                )
                instance.registerService(service)
                jmdns = instance

                // What jmDNS settled on after probing for collisions.
                val actual = instance.hostName.removeSuffix(".")
                localHostname = if (actual.endsWith(".local")) actual else "$actual.local"
                nameWasChanged = !localHostname.equals(requestedHostname, ignoreCase = true)
                if (nameWasChanged) {
                    Log.w(TAG, "name collision: asked for $requestedHostname, got $localHostname")
                }
                Log.i(TAG, "advertising $localHostname:$port on ${address.hostAddress}")
                true
            }.getOrElse {
                Log.e(TAG, "mDNS advertise failed", it)
                releaseLock()
                false
            }
            isPublished = ok
            onReady(ok)
        }
    }

    fun stop() {
        val instance = jmdns ?: run { releaseLock(); return }
        jmdns = null
        isPublished = false
        // close() blocks while it sends goodbye packets.
        thread(name = "dipstick-mdns-stop", isDaemon = true) {
            runCatching {
                instance.unregisterAllServices()
                instance.close()
            }.onFailure { Log.e(TAG, "mDNS shutdown", it) }
            releaseLock()
            Log.i(TAG, "stopped advertising")
        }
    }

    private fun releaseLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    private fun localAddress(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
    }.getOrNull()

    private companion object {
        const val TAG = "DipstickMdns"
        const val SERVICE_NAME = "Dipstick"
    }
}
