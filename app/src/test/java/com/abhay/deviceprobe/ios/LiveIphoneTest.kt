package com.abhay.deviceprobe.ios

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel

/**
 * Runs the real pairing and checks against an iPhone plugged into this Mac, through the Mac's
 * own usbmuxd. Only runs with IPHONE_LIVE=1. It does not exercise UsbMux (the Mac does that part).
 */
private class MacUsbmuxd : MuxLink {
    private fun open(): SocketChannel = SocketChannel.open(
        // Looked up by name: the Android class list this compiles against does not have it.
        Class.forName("java.net.UnixDomainSocketAddress").getMethod("of", String::class.java)
            .invoke(null, "/var/run/usbmuxd") as SocketAddress,
    )

    @Suppress("UNCHECKED_CAST")
    private fun call(ch: SocketChannel, body: Dict): Dict {
        val xml = Plist.toXml(body + mapOf("ClientVersionString" to "deviceprobe", "ProgName" to "deviceprobe"))
        val out = ByteBuffer.allocate(16 + xml.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(16 + xml.size).putInt(1).putInt(8).putInt(1).put(xml).flip()
        while (out.hasRemaining()) ch.write(out)
        val head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        while (head.hasRemaining()) if (ch.read(head) < 0) throw IOException("usbmuxd closed")
        val rest = ByteBuffer.allocate(head.getInt(0) - 16)
        while (rest.hasRemaining()) if (ch.read(rest) < 0) throw IOException("usbmuxd closed")
        return Plist.parse(rest.array()) as Dict
    }

    @Suppress("UNCHECKED_CAST")
    private val deviceId: Long = open().use { ch ->
        val list = call(ch, mapOf("MessageType" to "ListDevices"))["DeviceList"] as List<Dict>
        (list.first { (it["Properties"] as Dict)["ConnectionType"] == "USB" }["DeviceID"] as Number).toLong()
    }

    override fun connect(port: Int): MuxStream {
        val ch = open()
        val swapped = ((port and 0xFF) shl 8) or (port shr 8)
        val r = call(ch, mapOf("MessageType" to "Connect", "DeviceID" to deviceId, "PortNumber" to swapped))
        if ((r["Number"] as Number).toInt() != 0) {
            ch.close()
            throw IOException("The iPhone refused the connection (port $port)")
        }
        return object : MuxStream {
            // Not Channels.newInputStream: its reads and writes share one lock and block each other.
            override val input: InputStream = object : InputStream() {
                override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xFF }
                override fun read(b: ByteArray, off: Int, len: Int): Int = ch.read(ByteBuffer.wrap(b, off, len))
            }
            override val output: OutputStream = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    val buf = ByteBuffer.wrap(b, off, len)
                    while (buf.hasRemaining()) ch.write(buf)
                }
            }
            override var readTimeoutMs = 0
            override fun close() = ch.close()
        }
    }
}

class LiveIphoneTest {
    @Test
    fun scanRealIphone() {
        assumeTrue(System.getenv("IPHONE_LIVE") == "1")
        val id = HostIdentity.loadOrCreate(File(System.getProperty("java.io.tmpdir")))
        val report = Lockdown(MacUsbmuxd(), id).use { ld ->
            ld.connect { title, body -> println("PROMPT: $title - $body") }
            IosInspector(ld) { println("STEP: $it") }.inspect()
        }
        println(report.toText())
    }
}
