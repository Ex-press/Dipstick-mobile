package earth.dipstick.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * Diagnostic dump of every attached USB device: device/interface/endpoint descriptors plus the
 * raw configuration descriptor bytes. Used to work out how a given endoscope presents itself
 * (UVC video class, vendor-specific bulk, or CDC ethernet) before deciding on a transport.
 */
object UsbProbe {

    private const val TAG = "DipstickUsbProbe"
    private const val ACTION_PERMISSION = "earth.dipstick.app.USB_PROBE_PERMISSION"

    fun dumpAll(context: Context) {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (manager == null) {
            Log.w(TAG, "no UsbManager")
            return
        }
        val devices = manager.deviceList.values.toList()
        Log.i(TAG, "=== ${devices.size} USB device(s) attached ===")
        if (devices.isEmpty()) return

        registerPermissionReceiver(context, manager)
        devices.forEach { device ->
            describe(device)
            if (manager.hasPermission(device)) {
                dumpRaw(manager, device)
            } else {
                Log.i(TAG, "  (no permission yet; requesting)")
                manager.requestPermission(device, permissionIntent(context))
            }
        }
    }

    private fun describe(device: UsbDevice) {
        Log.i(TAG, "device ${device.deviceName}")
        Log.i(TAG, "  VID:PID = 0x%04X:0x%04X".format(device.vendorId, device.productId))
        Log.i(TAG, "  class/sub/proto = ${device.deviceClass}/${device.deviceSubclass}/${device.deviceProtocol}")
        Log.i(TAG, "  manufacturer = ${safe { device.manufacturerName }}")
        Log.i(TAG, "  product      = ${safe { device.productName }}")
        Log.i(TAG, "  serial       = ${safe { device.serialNumber }}")
        Log.i(TAG, "  version      = ${if (Build.VERSION.SDK_INT >= 23) safe { device.version } else "?"}")
        Log.i(TAG, "  interfaces   = ${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            Log.i(
                TAG,
                "  itf[$i] id=${itf.id} alt=${altSetting(itf)} " +
                    "class/sub/proto = ${itf.interfaceClass}/${itf.interfaceSubclass}/${itf.interfaceProtocol} " +
                    "(${className(itf.interfaceClass)}) name=${safe { itf.name }} eps=${itf.endpointCount}"
            )
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                Log.i(
                    TAG,
                    "    ep[$e] addr=0x%02X num=%d %s %s maxPkt=%d interval=%d".format(
                        ep.address, ep.endpointNumber, direction(ep.direction),
                        endpointType(ep.type), ep.maxPacketSize, ep.interval
                    )
                )
            }
        }
    }

    private fun dumpRaw(manager: UsbManager, device: UsbDevice) {
        val connection = manager.openDevice(device)
        if (connection == null) {
            Log.w(TAG, "  openDevice failed (kernel driver may hold it)")
            return
        }
        try {
            val raw = connection.rawDescriptors
            if (raw == null) {
                Log.w(TAG, "  rawDescriptors null")
                return
            }
            Log.i(TAG, "  rawDescriptors (${raw.size} bytes):")
            hexDump(raw).forEach { Log.i(TAG, "    $it") }
        } finally {
            connection.close()
        }
    }

    private fun hexDump(bytes: ByteArray): List<String> {
        val out = ArrayList<String>((bytes.size + 15) / 16)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + 16, bytes.size)
            val hex = (offset until end).joinToString(" ") { "%02X".format(bytes[it]) }
            out += "%04X  %s".format(offset, hex)
            offset = end
        }
        return out
    }

    private fun registerPermissionReceiver(context: Context, manager: UsbManager) {
        if (registered) return
        registered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_PERMISSION) return
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.i(TAG, "permission for 0x%04X:0x%04X granted=$granted".format(device.vendorId, device.productId))
                if (granted) dumpRaw(manager, device)
            }
        }
        val filter = IntentFilter(ACTION_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun permissionIntent(context: Context): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private var registered = false

    private fun altSetting(itf: android.hardware.usb.UsbInterface): Int =
        if (Build.VERSION.SDK_INT >= 21) itf.alternateSetting else 0

    private fun direction(d: Int) = if (d == UsbConstants.USB_DIR_IN) "IN " else "OUT"

    private fun endpointType(t: Int) = when (t) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isoc"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
        else -> "type$t"
    }

    private fun className(c: Int) = when (c) {
        1 -> "audio"
        2 -> "CDC comm"
        3 -> "HID"
        8 -> "mass storage"
        10 -> "CDC data"
        14 -> "video/UVC"
        239 -> "misc/IAD"
        255 -> "vendor"
        else -> "class$c"
    }

    private inline fun <T> safe(block: () -> T): String =
        runCatching { block()?.toString() ?: "null" }.getOrElse { "<err>" }
}
