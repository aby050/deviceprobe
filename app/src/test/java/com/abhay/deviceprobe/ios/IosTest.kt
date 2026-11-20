package com.abhay.deviceprobe.ios

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A stand-in iPhone: the device end of the USB multiplexer, echoing data in upper case. */
private class FakeUsbDevice : UsbPipe {
    private val toHost = LinkedBlockingQueue<ByteArray>()
    private var v2 = false
    private var seq = 0L
    private var ack = 0L

    private fun reply(proto: Int, body: ByteArray, header16: Boolean) {
        val hs = if (header16) 16 else 8
        val b = ByteBuffer.allocate(hs + body.size).putInt(proto).putInt(hs + body.size)
        if (header16) b.putInt(0xfeedface.toInt()).putShort(0).putShort(0)
        b.put(body)
        // Hand it over in USB-read-sized pieces, as a real transfer would arrive.
        b.array().asList().chunked(UsbMux.READ_SIZE).forEach { toHost.put(it.toByteArray()) }
    }

    private fun tcp(sport: Int, dport: Int, flags: Int, data: ByteArray = ByteArray(0)) {
        val h = ByteBuffer.allocate(20 + data.size)
        h.putShort(sport.toShort()).putShort(dport.toShort()).putInt(seq.toInt()).putInt(ack.toInt())
        h.put(0x50).put(flags.toByte()).putShort(512).putShort(0).putShort(0).put(data)
        reply(6, h.array(), true)
    }

    override fun write(data: ByteArray) {
        val b = ByteBuffer.wrap(data)
        val proto = b.int
        assertEquals(data.size, b.int)
        val hs = if (v2) 16 else 8
        if (v2) assertEquals(0xfeedface.toInt(), b.getInt(8))
        when (proto) {
            0 -> { assertEquals(2, b.getInt(8)); reply(0, ByteBuffer.allocate(12).putInt(2).array(), false); v2 = true }
            2 -> assertEquals(7, data[hs].toInt())
            6 -> {
                val hostPort = b.getShort(hs).toInt() and 0xFFFF
                val devPort = b.getShort(hs + 2).toInt() and 0xFFFF
                val flags = data[hs + 13].toInt()
                val payload = data.copyOfRange(hs + 20, data.size)
                when {
                    flags == 0x02 && devPort == 9 -> tcp(devPort, hostPort, 0x04) // closed port
                    flags == 0x02 -> { ack = 1; tcp(devPort, hostPort, 0x12); seq = 1 }
                    payload.isNotEmpty() -> {
                        assertEquals(ack, b.getInt(hs + 4).toLong())
                        ack += payload.size
                        val out = String(payload).uppercase().toByteArray()
                        tcp(devPort, hostPort, 0x10, out)
                        seq += out.size
                    }
                }
            }
        }
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val p = toHost.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return -1
        System.arraycopy(p, 0, buf, 0, p.size)
        return p.size
    }

    override fun close() {}
}

/** Two ends of an in-memory connection. */
private class Wire {
    private class End(val rx: LinkedBlockingQueue<ByteArray>, val tx: LinkedBlockingQueue<ByteArray>) : MuxStream {
        override var readTimeoutMs = 10000
        private var cur = ByteArray(0)
        private var pos = 0
        override val input = object : InputStream() {
            override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xFF }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                while (pos >= cur.size) {
                    cur = (if (readTimeoutMs > 0) rx.poll(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS) else rx.take())
                        ?: throw IOException("timed out")
                    pos = 0
                    if (cur.isEmpty()) return -1
                }
                val n = minOf(len, cur.size - pos)
                System.arraycopy(cur, pos, b, off, n)
                pos += n
                return n
            }
        }
        override val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) { if (len > 0) tx.put(b.copyOfRange(off, off + len)) }
        }
        override fun close() { tx.put(ByteArray(0)) }
    }

    private val a = LinkedBlockingQueue<ByteArray>()
    private val b = LinkedBlockingQueue<ByteArray>()
    val host: MuxStream = End(a, b)
    val device: MuxStream = End(b, a)
}

/** A stand-in lockdownd that insists on Trust once, then serves a session over TLS. */
private class FakeIphone : MuxLink {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private var deviceCert: ByteArray? = null
    private var pairAttempts = 0
    var sawClientCert = false

    private fun tls(s: MuxStream) = Tls.wrap(s, key.private, PairCerts.parse(deviceCert!!), { sawClientCert = true }, client = false)

    override fun connect(port: Int): MuxStream {
        val w = Wire()
        thread(isDaemon = true) {
            try { if (port == Lockdown.PORT) lockdown(w.device) else diagnostics(tls(w.device)) } catch (_: Exception) {}
        }
        return w.host
    }

    @Suppress("UNCHECKED_CAST")
    private fun lockdown(first: MuxStream) {
        var s = first
        while (true) {
            val q = PlistIo.receive(s.input)
            val base = mapOf("Request" to q["Request"])
            when (q["Request"]) {
                "QueryType" -> PlistIo.send(s.output, base + ("Type" to "com.apple.mobile.lockdown"))
                "GetValue" -> PlistIo.send(
                    s.output,
                    base + (
                        "Value" to when {
                            q["Key"] == "DevicePublicKey" -> PairCerts.pem(PairCerts.pkcs1(key.public as RSAPublicKey), "RSA PUBLIC KEY")
                            q["Domain"] == "com.apple.fmip" -> mapOf("IsAssociated" to true)
                            q["Domain"] == "com.apple.mobile.chaperone" -> mapOf("DeviceIsChaperoned" to false)
                            q["Domain"] == "com.apple.disk_usage" -> mapOf("TotalDiskCapacity" to 128_000_000_000L)
                            q["Domain"] != null -> mapOf<String, Any?>()
                            else -> mapOf(
                                "DeviceClass" to "iPhone", "ProductType" to "iPhone14,5", "ProductVersion" to "18.1",
                                "BuildVersion" to "22B83", "ModelNumber" to "FLPF3", "RegionInfo" to "HN/A",
                                "SerialNumber" to "F2LXXXXXXX", "InternationalMobileEquipmentIdentity" to "350000000000006",
                                "ActivationState" to "Activated", "PasswordProtected" to true, "BasebandVersion" to "3.10.01",
                                "WiFiAddress" to "aa:bb:cc:dd:ee:ff", "BluetoothAddress" to "aa:bb:cc:dd:ee:00",
                                "TelephonyCapability" to true, "DeviceName" to "Test <&> iPhone",
                            )
                        }
                        ),
                )
                "Pair" -> if (pairAttempts++ == 0) {
                    PlistIo.send(s.output, base + ("Error" to "PairingDialogResponsePending"))
                } else {
                    deviceCert = PairCerts.unpem((q["PairRecord"] as Dict)["DeviceCertificate"] as ByteArray).first
                    PlistIo.send(s.output, base + ("EscrowBag" to ByteArray(4)))
                }
                "StartSession" -> if (deviceCert == null) {
                    PlistIo.send(s.output, base + ("Error" to "InvalidHostID"))
                } else {
                    PlistIo.send(s.output, base + mapOf("EnableSessionSSL" to true, "SessionID" to "1"))
                    s = tls(s)
                }
                "StartService" -> if (q["Service"] == "com.apple.afc2") {
                    PlistIo.send(s.output, base + ("Error" to "InvalidService"))
                } else {
                    PlistIo.send(s.output, base + mapOf("Port" to 49152, "EnableServiceSSL" to true))
                }
            }
        }
    }

    private fun diagnostics(s: MuxStream) {
        PlistIo.receive(s.input)
        PlistIo.send(
            s.output,
            mapOf(
                "Status" to "Success",
                "Diagnostics" to mapOf(
                    "IORegistry" to mapOf(
                        "CycleCount" to 612, "DesignCapacity" to 3227, "AppleRawMaxCapacity" to 2710,
                        "Temperature" to 2950, "Voltage" to 4012, "Serial" to "F8Y1234",
                    ),
                ),
            ),
        )
    }
}

class IosTest {

    @Test
    fun plistRoundTrip() {
        val src = mapOf(
            "s" to "a <b> & c", "i" to 42L, "t" to true, "f" to false, "d" to byteArrayOf(1, 2, 3, -1),
            "a" to listOf("x", 7L), "m" to mapOf("k" to "v"), "r" to 1.5,
        )
        @Suppress("UNCHECKED_CAST")
        val back = Plist.parse(Plist.toXml(src)) as Dict
        assertEquals("a <b> & c", back["s"])
        assertEquals(42L, back["i"])
        assertEquals(true, back["t"])
        assertEquals(false, back["f"])
        assertArrayEquals(byteArrayOf(1, 2, 3, -1), back["d"] as ByteArray)
        assertEquals(listOf("x", 7L), back["a"])
        assertEquals(mapOf("k" to "v"), back["m"])
        assertEquals(1.5, back["r"])
    }

    @Test
    fun certificatesAreValidAndChain() {
        val id = HostIdentity.create()
        val root = PairCerts.parse(id.rootCert)
        val host = PairCerts.parse(id.hostCert)
        root.verify(root.publicKey)
        host.verify(root.publicKey)
        host.checkValidity()
        assertEquals(Int.MAX_VALUE, root.basicConstraints) // a CA with no path limit
        assertEquals(-1, host.basicConstraints)
        assertTrue(host.keyUsage[0] && host.keyUsage[2])

        val device = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = PairCerts.pem(PairCerts.pkcs1(device.public as RSAPublicKey), "RSA PUBLIC KEY")
        val cert = PairCerts.parse(PairCerts.deviceCertificate(pem, id))
        cert.verify(root.publicKey)
        assertEquals(device.public, cert.publicKey)
    }

    @Test
    fun muxConnectsAndCarriesData() {
        val mux = UsbMux(FakeUsbDevice())
        mux.start()
        val s = mux.connect(62078)
        val big = ByteArray(40_000) { ('a' + it % 26).code.toByte() }
        thread { s.output.write(big) }
        val got = ByteArray(big.size)
        var off = 0
        while (off < got.size) off += s.input.read(got, off, got.size - off)
        assertEquals(String(big).uppercase(), String(got))
        try {
            mux.connect(9)
            throw AssertionError("a closed port should refuse")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("refused"))
        }
        mux.close()
    }

    @Test
    fun pairsThenReadsReportOverTls() {
        val phone = FakeIphone()
        val prompts = ArrayList<String>()
        val report = Lockdown(phone, HostIdentity.create()).use { ld ->
            ld.connect { title, _ -> prompts += title }
            IosInspector(ld) {}.inspect()
        }
        println(report.toText())
        assertEquals(listOf("Tap Trust on the iPhone"), prompts)
        assertTrue(phone.sawClientCert)
        assertEquals("Apple iPhone 13", report.deviceName)
        val text = report.toText()
        assertTrue(text.contains("Battery life: 83%"))
        assertTrue(text.contains("[WARNING] Charge cycles: 612"))
        assertTrue(text.contains("Refurbished by Apple"))
        assertTrue(text.contains("Find My is on"))
        assertTrue(text.contains("Sold for: India"))
        assertTrue(text.contains("Storage: 128 GB"))
        assertTrue(text.contains("Name on device: Test <&> iPhone"))
    }
}
