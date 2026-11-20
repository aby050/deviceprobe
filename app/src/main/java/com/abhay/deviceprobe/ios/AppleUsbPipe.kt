package com.abhay.deviceprobe.ios

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/** The USB side of talking to an iPhone or iPad (this phone is the USB host). */
class AppleUsbPipe private constructor(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : UsbPipe {

    override fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = conn.bulkTransfer(epOut, data, off, minOf(data.size - off, 16384), 5000)
            if (n < 0) throw IOException("Could not send to the iPhone (cable unplugged?)")
            off += n
        }
        // A packet that ends exactly on a USB packet boundary needs an empty one to finish it.
        if (data.isNotEmpty() && data.size % epOut.maxPacketSize == 0) {
            conn.bulkTransfer(epOut, ByteArray(0), 0, 5000)
        }
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int = conn.bulkTransfer(epIn, buf, buf.size, timeoutMs)

    override fun close() {
        try { conn.releaseInterface(iface) } catch (_: Exception) {}
        try { conn.close() } catch (_: Exception) {}
    }

    companion object {
        const val APPLE_VENDOR_ID = 0x05AC

        fun isApple(device: UsbDevice) = device.vendorId == APPLE_VENDOR_ID

        /**
         * An iPhone starts in a camera-only USB mode. Its data interface ("Apple USB
         * Multiplexor", class 255 / subclass 254 / protocol 2) lives in another configuration.
         */
        private fun find(device: UsbDevice): Pair<UsbConfiguration, UsbInterface>? {
            for (c in 0 until device.configurationCount) {
                val cfg = device.getConfiguration(c)
                for (i in 0 until cfg.interfaceCount) {
                    val f = cfg.getInterface(i)
                    if (f.interfaceClass == 255 && f.interfaceSubclass == 254 && f.interfaceProtocol == 2) return cfg to f
                }
            }
            return null
        }

        fun open(manager: UsbManager, device: UsbDevice): AppleUsbPipe {
            val (cfg, iface) = find(device) ?: throw IOException(
                "The iPhone is not offering a data connection. Unlock it, then unplug the cable and plug it in again.",
            )
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn == null || epOut == null) throw IOException("The iPhone's data connection could not be used")
            val conn = manager.openDevice(device) ?: throw IOException("Could not open the USB device")
            conn.setConfiguration(cfg)
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                throw IOException("Could not take over the iPhone's data connection. Unplug the cable and plug it in again.")
            }
            return AppleUsbPipe(conn, iface, epIn, epOut)
        }
    }
}
