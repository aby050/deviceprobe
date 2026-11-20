package com.abhay.deviceprobe.adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** A byte pipe to the phone's adbd. */
interface AdbTransport : Closeable {
    fun write(data: ByteArray)
    /** Reads exactly [len] bytes or throws. */
    fun read(len: Int, timeoutMs: Int): ByteArray
}

/** ADB over a USB cable (this phone is the USB host). */
class UsbAdbTransport private constructor(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : AdbTransport {

    override fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = conn.bulkTransfer(epOut, data, off, minOf(data.size - off, CHUNK), WRITE_TIMEOUT)
            if (n < 0) throw IOException("USB write failed")
            off += n
        }
        // A transfer that ends exactly on a packet boundary needs a zero-length packet to finish it.
        if (data.isNotEmpty() && data.size % epOut.maxPacketSize == 0) {
            conn.bulkTransfer(epOut, ByteArray(0), 0, WRITE_TIMEOUT)
        }
    }

    override fun read(len: Int, timeoutMs: Int): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = conn.bulkTransfer(epIn, out, off, minOf(len - off, CHUNK), timeoutMs)
            if (n < 0) throw IOException("No answer from the device (cable unplugged or timed out)")
            off += n
        }
        return out
    }

    override fun close() {
        try { conn.releaseInterface(iface) } catch (_: Exception) {}
        try { conn.close() } catch (_: Exception) {}
    }

    companion object {
        private const val CHUNK = 16384
        private const val WRITE_TIMEOUT = 5000
        const val ADB_CLASS = 0xFF
        const val ADB_SUBCLASS = 0x42
        const val ADB_PROTOCOL = 0x01

        /** The ADB interface of a device, or null when USB debugging is off. */
        fun findAdbInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val f = device.getInterface(i)
                if (f.interfaceClass == ADB_CLASS && f.interfaceSubclass == ADB_SUBCLASS &&
                    f.interfaceProtocol == ADB_PROTOCOL
                ) return f
            }
            return null
        }

        fun open(manager: UsbManager, device: UsbDevice): UsbAdbTransport {
            val iface = findAdbInterface(device) ?: throw IOException("USB debugging is off on the connected device")
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn == null || epOut == null) throw IOException("Device has no usable ADB endpoints")
            val conn = manager.openDevice(device) ?: throw IOException("Could not open the USB device")
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                throw IOException("Could not claim the ADB interface")
            }
            return UsbAdbTransport(conn, iface, epIn, epOut)
        }
    }
}

/** ADB over TCP. Only used by debug builds to test on an emulator. */
class TcpAdbTransport(host: String, port: Int) : AdbTransport {
    private val socket = Socket()
    private val input: InputStream
    private val output: OutputStream

    init {
        socket.connect(InetSocketAddress(host, port), 5000)
        socket.tcpNoDelay = true
        input = socket.getInputStream()
        output = socket.getOutputStream()
    }

    override fun write(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    override fun read(len: Int, timeoutMs: Int): ByteArray {
        socket.soTimeout = timeoutMs
        val out = ByteArray(len)
        var off = 0
        try {
            while (off < len) {
                val n = input.read(out, off, len - off)
                if (n < 0) throw IOException("Connection closed by the device")
                off += n
            }
        } catch (e: SocketTimeoutException) {
            throw IOException("No answer from the device (timed out)")
        }
        return out
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}
