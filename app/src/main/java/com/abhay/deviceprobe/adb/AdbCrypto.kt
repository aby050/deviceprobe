package com.abhay.deviceprobe.adb

import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * The RSA key this app identifies itself with to the phone being checked.
 * The phone shows "Allow USB debugging?" the first time it sees this key.
 */
class AdbCrypto private constructor(
    private val privateKey: PrivateKey,
    private val publicKey: RSAPublicKey,
) {
    /** Signs the 20-byte challenge the phone sends (treated as a SHA-1 digest). */
    fun sign(token: ByteArray): ByteArray {
        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(privateKey)
        sig.update(SHA1_DIGEST_INFO)
        sig.update(token)
        return sig.sign()
    }

    /** Public key in the layout adbd expects (Android's RSAPublicKey struct, base64). */
    fun adbPublicKey(): ByteArray {
        val words = KEY_BITS / 32
        val n = publicKey.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        val rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n)

        val buf = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv.toInt())
        for (i in 0 until words) buf.putInt(n.shiftRight(32 * i).toInt())
        for (i in 0 until words) buf.putInt(rr.shiftRight(32 * i).toInt())
        buf.putInt(publicKey.publicExponent.toInt())

        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return "$b64 deviceprobe@android\u0000".toByteArray(Charsets.US_ASCII)
    }

    companion object {
        private const val KEY_BITS = 2048
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        /** Loads the saved key pair from the app's private files, or makes one on first run. */
        fun loadOrCreate(dir: File): AdbCrypto {
            val privFile = File(dir, "adbkey.pk8")
            val pubFile = File(dir, "adbkey.x509")
            val kf = KeyFactory.getInstance("RSA")
            if (privFile.exists() && pubFile.exists()) {
                try {
                    val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                    val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes())) as RSAPublicKey
                    return AdbCrypto(priv, pub)
                } catch (_: Exception) {
                    // fall through and make a new pair
                }
            }
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(KEY_BITS)
            val pair = gen.generateKeyPair()
            privFile.writeBytes(pair.private.encoded)
            pubFile.writeBytes(pair.public.encoded)
            return AdbCrypto(pair.private, pair.public as RSAPublicKey)
        }
    }
}
