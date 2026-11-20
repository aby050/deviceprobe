package com.abhay.deviceprobe.ios

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class LockdownException(val code: String) : IOException("The iPhone answered: $code")

/** Runs TLS over a connection that is not a real socket, by bridging it through a local one. */
object Tls {
    fun wrap(
        inner: MuxStream,
        key: PrivateKey,
        cert: X509Certificate,
        /** Throws when the other side's certificate is not the one expected. */
        checkPeer: (X509Certificate) -> Unit,
        client: Boolean = true,
    ): MuxStream {
        val loopback = InetAddress.getLoopbackAddress()
        val server = ServerSocket(0, 1, loopback)
        val near: Socket
        val far: Socket
        try {
            near = Socket(loopback, server.localPort)
            far = server.accept()
        } finally {
            server.close()
        }
        if (far.port != near.localPort) {
            near.close()
            far.close()
            throw IOException("Could not set up a secure connection")
        }
        inner.readTimeoutMs = 0
        fun shut() {
            try { far.close() } catch (_: Exception) {}
            try { inner.close() } catch (_: Exception) {}
        }
        thread(isDaemon = true, name = "tls-up") {
            try {
                val buf = ByteArray(16384)
                val src = far.getInputStream()
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    inner.output.write(buf, 0, n)
                    inner.output.flush()
                }
            } catch (_: Exception) {
            } finally { shut() }
        }
        thread(isDaemon = true, name = "tls-down") {
            try {
                val buf = ByteArray(16384)
                val dst = far.getOutputStream()
                while (true) {
                    val n = inner.input.read(buf)
                    if (n < 0) break
                    dst.write(buf, 0, n)
                    dst.flush()
                }
            } catch (_: Exception) {
            } finally { shut() }
        }

        val keys = object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(t: Array<out String>?, i: Array<out Principal>?, s: Socket?) = "id"
            override fun chooseServerAlias(t: String?, i: Array<out Principal>?, s: Socket?) = "id"
            override fun chooseEngineClientAlias(t: Array<out String>?, i: Array<out Principal>?, e: SSLEngine?) = "id"
            override fun chooseEngineServerAlias(t: String?, i: Array<out Principal>?, e: SSLEngine?) = "id"
            override fun getClientAliases(t: String?, i: Array<out Principal>?) = arrayOf("id")
            override fun getServerAliases(t: String?, i: Array<out Principal>?) = arrayOf("id")
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun getPrivateKey(alias: String?) = key
        }
        // The other side is not checked against public certificate authorities (an iPhone has no
        // such certificate): it must present exactly the certificate made for it at pairing.
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) = checkPeer(chain[0])
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) = checkPeer(chain[0])
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        try {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(arrayOf(keys), arrayOf(trust), null)
            near.soTimeout = 30000
            val ssl = ctx.socketFactory.createSocket(near, "127.0.0.1", near.port, true) as SSLSocket
            ssl.useClientMode = client
            if (!client) ssl.needClientAuth = true
            ssl.startHandshake()
            return object : MuxStream {
                override val input: InputStream = ssl.inputStream
                override val output: OutputStream = ssl.outputStream
                override var readTimeoutMs: Int
                    get() = near.soTimeout
                    set(v) { near.soTimeout = v }

                override fun close() {
                    try { ssl.close() } catch (_: Exception) {}
                    shut()
                }
            }
        } catch (e: Exception) {
            try { near.close() } catch (_: Exception) {}
            shut()
            throw IOException("Could not set up a secure connection with the iPhone (${e.message})", e)
        }
    }
}

/** Talks to lockdownd, the service on every iPhone that answers questions about the device. */
class Lockdown(private val mux: MuxLink, private val id: HostIdentity) : Closeable {

    private var io: MuxStream? = null
    private val hostCert = PairCerts.parse(id.hostCert)
    private val rootKey = PairCerts.parse(id.rootCert).publicKey

    private fun secure(s: MuxStream): MuxStream =
        Tls.wrap(s, id.hostKey, hostCert, checkPeer = { it.verify(rootKey) })

    private fun open() {
        close()
        io = mux.connect(PORT)
        val type = request(mapOf("Request" to "QueryType"))["Type"]
        if (type != "com.apple.mobile.lockdown") throw IOException("This does not look like an iPhone or iPad")
    }

    fun request(body: Dict): Dict {
        val s = io ?: throw IOException("Not connected to the iPhone")
        PlistIo.send(s.output, body + ("Label" to LABEL))
        val r = PlistIo.receive(s.input)
        (r["Error"] as? String)?.let { throw LockdownException(it) }
        return r
    }

    /** One value, a whole domain (key = null), or null when the iPhone will not give it. */
    fun value(domain: String? = null, key: String? = null): Any? = try {
        request(mapOf("Request" to "GetValue", "Domain" to domain, "Key" to key))["Value"]
    } catch (e: LockdownException) {
        null
    }

    private fun startSession(): Boolean = try {
        val r = request(mapOf("Request" to "StartSession", "HostID" to id.hostId, "SystemBUID" to id.buid))
        if (r["EnableSessionSSL"] == true) io = secure(io!!)
        true
    } catch (e: LockdownException) {
        false
    }

    /**
     * Opens a trusted session, pairing first when this iPhone has not trusted the app before.
     * [prompt] is called with what the person holding the iPhone has to do.
     */
    fun connect(prompt: (title: String, body: String) -> Unit) {
        open()
        if (startSession()) return

        val deviceKey = value(null, "DevicePublicKey") as? ByteArray
            ?: throw IOException("The iPhone did not share its key. Unlock it and try again.")
        val record = mapOf(
            "DeviceCertificate" to PairCerts.pem(PairCerts.deviceCertificate(deviceKey, id)),
            "HostCertificate" to PairCerts.pem(id.hostCert),
            "RootCertificate" to PairCerts.pem(id.rootCert),
            "HostID" to id.hostId,
            "SystemBUID" to id.buid,
        )
        val deadline = System.currentTimeMillis() + 180_000
        while (true) {
            try {
                request(
                    mapOf(
                        "Request" to "Pair", "PairRecord" to record, "ProtocolVersion" to "2",
                        "PairingOptions" to mapOf("ExtendedPairingErrors" to true),
                    ),
                )
                break
            } catch (e: LockdownException) {
                when (e.code) {
                    "PairingDialogResponsePending" -> prompt(
                        "Tap Trust on the iPhone",
                        "It is asking \"Trust This Computer?\". Tap Trust, then enter the iPhone's passcode.",
                    )
                    "PasswordProtected" -> prompt("Unlock the iPhone", "Enter its passcode so it can show the Trust question.")
                    "UserDeniedPairing" -> throw IOException("Don't Trust was tapped on the iPhone. Unplug the cable, plug it in again and tap Trust.")
                    else -> throw e
                }
            }
            if (System.currentTimeMillis() > deadline) throw IOException("Nobody tapped Trust on the iPhone. Plug it in again to retry.")
            Thread.sleep(1500)
        }
        if (!startSession()) {
            // Some iOS versions want a fresh connection after pairing.
            open()
            if (!startSession()) throw IOException("The iPhone accepted Trust but would not open a session. Unplug it and try again.")
        }
    }

    /** Starts one of the iPhone's services and connects to it. */
    fun service(name: String): MuxStream {
        val r = request(mapOf("Request" to "StartService", "Service" to name))
        val port = (r["Port"] as? Number)?.toInt() ?: throw IOException("The iPhone did not start $name")
        val s = mux.connect(port)
        return if (r["EnableServiceSSL"] == true) secure(s) else s
    }

    override fun close() {
        try { io?.close() } catch (_: Exception) {}
        io = null
    }

    companion object {
        const val PORT = 62078
        private const val LABEL = "DeviceProbe"
    }
}
