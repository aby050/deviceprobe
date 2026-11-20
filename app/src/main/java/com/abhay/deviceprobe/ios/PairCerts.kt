package com.abhay.deviceprobe.ios

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Just enough DER to write an X.509 certificate. */
internal object Der {
    fun tlv(tag: Int, vararg parts: ByteArray): ByteArray {
        val len = parts.sumOf { it.size }
        val out = ByteArrayOutputStream()
        out.write(tag)
        when {
            len < 0x80 -> out.write(len)
            len < 0x100 -> { out.write(0x81); out.write(len) }
            len < 0x10000 -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xFF) }
            else -> { out.write(0x83); out.write(len shr 16); out.write((len shr 8) and 0xFF); out.write(len and 0xFF) }
        }
        for (p in parts) out.write(p)
        return out.toByteArray()
    }

    fun seq(vararg parts: ByteArray) = tlv(0x30, *parts)
    fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun int(v: Long) = int(BigInteger.valueOf(v))
    fun octets(v: ByteArray) = tlv(0x04, v)
    fun bits(v: ByteArray, unused: Int = 0) = tlv(0x03, byteArrayOf(unused.toByte()), v)
    val NULL = byteArrayOf(0x05, 0x00)
    val TRUE = byteArrayOf(0x01, 0x01, 0xFF.toByte())

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (a in arcs.drop(2)) {
            val chunk = ArrayList<Int>()
            var v = a
            do {
                chunk.add(0, (v and 0x7F).toInt())
                v = v shr 7
            } while (v > 0)
            for (k in chunk.indices) out.write(if (k < chunk.size - 1) chunk[k] or 0x80 else chunk[k])
        }
        return tlv(0x06, out.toByteArray())
    }

    fun utc(ms: Long): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, f.format(Date(ms)).toByteArray(Charsets.US_ASCII))
    }

    fun name(cn: String) = seq(tlv(0x31, seq(oid("2.5.4.3"), tlv(0x0C, cn.toByteArray(Charsets.UTF_8)))))
}

/**
 * This app's identity towards iPhones: the keys and certificates an iPhone stores when its
 * owner taps Trust. Made once and kept, so a phone that was trusted before is not asked again.
 */
class HostIdentity(
    val hostId: String,
    val buid: String,
    val rootKey: PrivateKey,
    val rootCert: ByteArray,
    val hostKey: PrivateKey,
    val hostCert: ByteArray,
) {
    companion object {
        fun create(): HostIdentity {
            val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            val root = gen.generateKeyPair()
            val host = gen.generateKeyPair()
            val rootCert = PairCerts.certificate(
                PairCerts.pkcs1(root.public as RSAPublicKey), "DeviceProbe Root", "DeviceProbe Root", root.private, ca = true,
            )
            val hostCert = PairCerts.certificate(
                PairCerts.pkcs1(host.public as RSAPublicKey), "DeviceProbe Host", "DeviceProbe Root", root.private, ca = false,
            )
            return HostIdentity(
                UUID.randomUUID().toString().uppercase(Locale.US), UUID.randomUUID().toString().uppercase(Locale.US),
                root.private, rootCert, host.private, hostCert,
            )
        }

        @Synchronized
        fun loadOrCreate(dir: File): HostIdentity {
            val file = File(dir, "ios_host.plist")
            if (file.exists()) {
                try {
                    val d = Plist.parse(file.readBytes()) as Dict
                    val kf = KeyFactory.getInstance("RSA")
                    return HostIdentity(
                        d["HostID"] as String, d["SystemBUID"] as String,
                        kf.generatePrivate(PKCS8EncodedKeySpec(d["RootKey"] as ByteArray)), d["RootCert"] as ByteArray,
                        kf.generatePrivate(PKCS8EncodedKeySpec(d["HostKey"] as ByteArray)), d["HostCert"] as ByteArray,
                    )
                } catch (_: Exception) {
                    // Unreadable: make a new one. Phones trusted before will simply ask again.
                }
            }
            val id = create()
            file.writeBytes(
                Plist.toXml(
                    mapOf(
                        "HostID" to id.hostId, "SystemBUID" to id.buid,
                        "RootKey" to id.rootKey.encoded, "RootCert" to id.rootCert,
                        "HostKey" to id.hostKey.encoded, "HostCert" to id.hostCert,
                    ),
                ),
            )
            return id
        }
    }
}

object PairCerts {
    private const val RSA = "1.2.840.113549.1.1.1"
    private const val SHA256_RSA = "1.2.840.113549.1.1.11"

    /** RSAPublicKey (PKCS#1), the form iPhones use for their own key. */
    fun pkcs1(key: RSAPublicKey): ByteArray = Der.seq(Der.int(key.modulus), Der.int(key.publicExponent))

    fun pem(der: ByteArray, what: String = "CERTIFICATE"): ByteArray =
        "-----BEGIN $what-----\n${B64.encode(der, 64)}\n-----END $what-----\n".toByteArray(Charsets.US_ASCII)

    /** Reads the body of a PEM block and says whether it was a bare "RSA PUBLIC KEY". */
    fun unpem(pem: ByteArray): Pair<ByteArray, Boolean> {
        val text = String(pem, Charsets.US_ASCII)
        val body = text.lines().filter { !it.startsWith("-----") }.joinToString("")
        return B64.decode(body) to text.contains("BEGIN RSA PUBLIC KEY")
    }

    fun parse(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    /** The certificate the iPhone will present, made from the public key it sent us. */
    fun deviceCertificate(devicePublicKeyPem: ByteArray, id: HostIdentity): ByteArray {
        val (body, bare) = unpem(devicePublicKeyPem)
        return certificate(body, "Device", "DeviceProbe Root", id.rootKey, ca = false, subjectIsSpki = !bare)
    }

    fun certificate(
        subjectKey: ByteArray, subject: String, issuer: String, signer: PrivateKey, ca: Boolean, subjectIsSpki: Boolean = false,
    ): ByteArray {
        val alg = Der.seq(Der.oid(SHA256_RSA), Der.NULL)
        val spki = if (subjectIsSpki) subjectKey else Der.seq(Der.seq(Der.oid(RSA), Der.NULL), Der.bits(subjectKey))
        val keyId = MessageDigest.getInstance("SHA-1").digest(subjectKey)
        fun ext(oid: String, critical: Boolean, value: ByteArray) =
            if (critical) Der.seq(Der.oid(oid), Der.TRUE, Der.octets(value)) else Der.seq(Der.oid(oid), Der.octets(value))
        val extensions = if (ca) {
            Der.seq(ext("2.5.29.19", true, Der.seq(Der.TRUE)))
        } else {
            Der.seq(
                ext("2.5.29.19", true, Der.seq()),
                ext("2.5.29.14", false, Der.octets(keyId)),
                ext("2.5.29.15", true, Der.bits(byteArrayOf(0xA0.toByte()), 5)), // digitalSignature, keyEncipherment
            )
        }
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val tbs = Der.seq(
            Der.tlv(0xA0, Der.int(2)),
            Der.int(BigInteger(63, java.security.SecureRandom()).add(BigInteger.ONE)),
            alg,
            Der.name(issuer),
            Der.seq(Der.utc(now - day), Der.utc(now + 3650 * day)),
            Der.name(subject),
            spki,
            Der.tlv(0xA3, extensions),
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(signer)
            update(tbs)
            sign()
        }
        return Der.seq(tbs, alg, Der.bits(sig))
    }
}
