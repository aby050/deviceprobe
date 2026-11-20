package com.abhay.deviceprobe.adb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A minimal ADB client: connect, authenticate, run shell commands one at a time.
 * Not thread-safe; call from a single worker thread.
 */
class AdbConnection(
    private val transport: AdbTransport,
    private val crypto: AdbCrypto,
) : Closeable {

    private class Message(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private var maxData = 4096
    private var nextLocalId = 1

    /** What the phone says about itself on connect, e.g. "device::ro.product.name=...". */
    var banner: String = ""
        private set

    /**
     * Opens the session. [onNeedsApproval] fires when the phone is showing its
     * "Allow USB debugging?" dialog and we are waiting for the tap.
     */
    fun connect(onNeedsApproval: () -> Unit) {
        send(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
        var signatureSent = false
        var waitingForUser = false
        while (true) {
            val m = recv(if (waitingForUser) APPROVAL_TIMEOUT else HANDSHAKE_TIMEOUT)
            when (m.cmd) {
                CNXN -> {
                    maxData = minOf(m.arg1, MAX_DATA).coerceAtLeast(4096)
                    banner = String(m.data).trimEnd('\u0000')
                    return
                }
                AUTH -> {
                    if (m.arg0 != AUTH_TOKEN) continue
                    if (!signatureSent) {
                        signatureSent = true
                        send(AUTH, AUTH_SIGNATURE, 0, crypto.sign(m.data))
                    } else {
                        send(AUTH, AUTH_RSAPUBLICKEY, 0, crypto.adbPublicKey())
                        waitingForUser = true
                        onNeedsApproval()
                    }
                }
                STLS -> throw IOException("This device only accepts encrypted (wireless) debugging")
            }
        }
    }

    /** Runs one shell command on the phone and returns everything it printed. */
    fun shell(command: String, timeoutMs: Int = 20000): String {
        val localId = nextLocalId++
        send(OPEN, localId, 0, "shell:$command\u0000".toByteArray())
        val out = ByteArrayOutputStream()
        while (true) {
            val m = recv(timeoutMs)
            if (m.arg1 != localId) continue // leftover from an earlier stream
            when (m.cmd) {
                OKAY -> Unit
                WRTE -> {
                    out.write(m.data)
                    send(OKAY, localId, m.arg0, EMPTY)
                }
                CLSE -> {
                    if (m.arg0 != 0) send(CLSE, localId, m.arg0, EMPTY)
                    return out.toString("UTF-8")
                }
            }
        }
    }

    private fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        if (data.size > maxData && cmd != CNXN) throw IOException("Command too long")
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(arg0).putInt(arg1)
            .putInt(data.size).putInt(sum).putInt(cmd.inv())
            .array()
        transport.write(header)
        if (data.isNotEmpty()) transport.write(data)
    }

    private fun recv(timeoutMs: Int): Message {
        val h = ByteBuffer.wrap(transport.read(HEADER, timeoutMs)).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int
        val arg0 = h.int
        val arg1 = h.int
        val len = h.int
        h.int // checksum, not verified
        val magic = h.int
        if (magic != cmd.inv()) throw IOException("Garbled reply from the device")
        if (len < 0 || len > MAX_DATA) throw IOException("Oversized reply from the device")
        val data = if (len > 0) transport.read(len, timeoutMs) else EMPTY
        return Message(cmd, arg0, arg1, data)
    }

    override fun close() = transport.close()

    companion object {
        private const val HEADER = 24
        private const val VERSION = 0x01000000
        private const val MAX_DATA = 256 * 1024
        private const val HANDSHAKE_TIMEOUT = 10_000
        private const val APPROVAL_TIMEOUT = 120_000
        private val EMPTY = ByteArray(0)

        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val CLSE = 0x45534c43
        private const val WRTE = 0x45545257
        private const val STLS = 0x534c5453

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
    }
}
