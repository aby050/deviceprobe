package com.abhay.deviceprobe.ios

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The raw USB bulk pipe to the iPhone's "Apple USB Multiplexor" interface. */
interface UsbPipe : Closeable {
    /** Sends one whole packet. */
    fun write(data: ByteArray)
    /** Reads whatever arrives next into [buf]; returns the byte count, or -1 when nothing came in time. */
    fun read(buf: ByteArray, timeoutMs: Int): Int
}

/** One connection to a port on the iPhone. */
interface MuxStream : Closeable {
    val input: InputStream
    val output: OutputStream
    /** How long a read waits before failing. 0 waits for ever. */
    var readTimeoutMs: Int
}

/** Something that can open connections to ports on the iPhone. */
interface MuxLink {
    fun connect(port: Int): MuxStream
}

/**
 * Apple's USB multiplexing protocol, spoken straight to the device (the job usbmuxd does on a
 * computer). It carries a cut-down TCP over the USB pipe so several services can be open at once.
 */
class UsbMux(private val pipe: UsbPipe) : MuxLink, Closeable {

    private val writeLock = Any()
    @Volatile private var version = 0
    private var txSeq = 0
    @Volatile private var rxSeq = 0xFFFF
    private val conns = ConcurrentHashMap<Int, Conn>()
    private var nextPort = 1
    @Volatile private var closed = false
    private val ready = CountDownLatch(1)
    private val reader = Thread(::readLoop, "usbmux-reader").apply { isDaemon = true }

    /** Agrees the protocol version with the device. Call once before [connect]. */
    fun start() {
        reader.start()
        sendPacket(PROTO_VERSION, ByteBuffer.allocate(12).putInt(2).putInt(0).putInt(0).array(), EMPTY)
        if (!ready.await(5, TimeUnit.SECONDS)) {
            throw IOException("The iPhone did not answer. Unlock it, then unplug the cable and plug it in again.")
        }
    }

    override fun connect(port: Int): MuxStream {
        val conn = synchronized(conns) {
            var sport: Int
            do {
                sport = nextPort
                nextPort = if (nextPort >= 0xFFFF) 1 else nextPort + 1
            } while (conns.containsKey(sport))
            Conn(sport, port).also { conns[sport] = it }
        }
        conn.open()
        return conn
    }

    override fun close() {
        closed = true
        for (c in conns.values.toList()) try { c.close() } catch (_: Exception) {}
        try { pipe.close() } catch (_: Exception) {}
    }

    private fun sendPacket(proto: Int, header: ByteArray, payload: ByteArray) {
        synchronized(writeLock) {
            if (closed) throw IOException("The cable was unplugged")
            val v2 = version >= 2
            val total = (if (v2) 16 else 8) + header.size + payload.size
            val buf = ByteBuffer.allocate(total)
            buf.putInt(proto).putInt(total)
            if (v2) {
                buf.putInt(MAGIC)
                if (proto == PROTO_SETUP) {
                    txSeq = 0
                    rxSeq = 0xFFFF
                }
                buf.putShort(txSeq.toShort()).putShort(rxSeq.toShort())
                txSeq = (txSeq + 1) and 0xFFFF
            }
            buf.put(header).put(payload)
            pipe.write(buf.array())
        }
    }

    private fun readLoop() {
        val buf = ByteArray(READ_SIZE)
        var acc = ByteArray(0)
        while (!closed) {
            val n = try { pipe.read(buf, 1000) } catch (e: Exception) { -1 }
            if (n <= 0) continue
            acc += buf.copyOf(n)
            // A packet larger than one read arrives in pieces; its header says how long it is.
            while (acc.size >= 8) {
                val len = ByteBuffer.wrap(acc, 4, 4).int
                if (len < 8 || len > MAX_PACKET) {
                    acc = ByteArray(0)
                    break
                }
                if (acc.size < len) break
                try { handle(acc.copyOf(len)) } catch (_: Exception) {}
                acc = acc.copyOfRange(len, acc.size)
            }
        }
    }

    private fun handle(pkt: ByteArray) {
        val b = ByteBuffer.wrap(pkt)
        val proto = b.int
        val hs = if (version >= 2) 16 else 8
        if (version >= 2 && pkt.size >= 16) rxSeq = b.getShort(14).toInt() and 0xFFFF
        when (proto) {
            PROTO_VERSION -> {
                if (pkt.size < hs + 4) return
                version = b.getInt(hs)
                if (version >= 2) sendPacket(PROTO_SETUP, EMPTY, byteArrayOf(7))
                ready.countDown()
            }
            PROTO_TCP -> {
                if (pkt.size < hs + 20) return
                val sport = b.getShort(hs + 2).toInt() and 0xFFFF // the device's destination = our port
                val seq = b.getInt(hs + 4).toLong() and MASK
                val ack = b.getInt(hs + 8).toLong() and MASK
                val flags = pkt[hs + 13].toInt() and 0xFF
                val win = (b.getShort(hs + 14).toLong() and 0xFFFF) shl 8
                conns[sport]?.onPacket(flags, seq, ack, win, pkt.copyOfRange(hs + 20, pkt.size))
            }
        }
    }

    private inner class Conn(val sport: Int, val dport: Int) : MuxStream {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private var state = CONNECTING
        private var txSeq = 0L
        private var txAck = 0L
        private var rxAck = 0L
        private var rxWin = 0L
        private val queue = LinkedBlockingQueue<ByteArray>()
        override var readTimeoutMs = 20000

        private fun tcp(flags: Int, data: ByteArray = EMPTY) {
            val h = ByteBuffer.allocate(20)
            h.putShort(sport.toShort()).putShort(dport.toShort())
            h.putInt(txSeq.toInt()).putInt(txAck.toInt())
            h.put(0x50).put(flags.toByte())
            h.putShort((OUR_WINDOW shr 8).toShort()).putShort(0).putShort(0)
            sendPacket(PROTO_TCP, h.array(), data)
        }

        fun open() {
            lock.withLock {
                tcp(SYN)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (state == CONNECTING) {
                    val left = deadline - System.nanoTime()
                    if (left <= 0) break
                    changed.awaitNanos(left)
                }
                if (state != CONNECTED) {
                    val refused = state == CLOSED
                    state = CLOSED
                    conns.remove(sport)
                    throw IOException(
                        if (refused) "The iPhone refused the connection (port $dport)"
                        else "The iPhone did not answer. Unlock it and try again.",
                    )
                }
            }
        }

        fun onPacket(flags: Int, seq: Long, ack: Long, win: Long, payload: ByteArray) {
            lock.withLock {
                rxAck = ack
                rxWin = win
                when (state) {
                    CONNECTING -> if (flags == (SYN or ACK)) {
                        txSeq = 1
                        txAck = (seq + 1) and MASK
                        state = CONNECTED
                        tcp(ACK)
                    } else {
                        state = CLOSED
                    }
                    CONNECTED -> if (flags and (RST or FIN) != 0) {
                        state = CLOSED
                        conns.remove(sport)
                        queue.put(EOF)
                    } else if (payload.isNotEmpty()) {
                        txAck = (txAck + payload.size) and MASK
                        queue.put(payload)
                        tcp(ACK) // the device wants data acknowledged promptly
                    }
                }
                changed.signalAll()
            }
        }

        override val input: InputStream = object : InputStream() {
            private var cur = EMPTY
            private var pos = 0
            private var eof = false

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (pos >= cur.size) {
                    if (eof) return -1
                    val t = readTimeoutMs
                    val next = if (t > 0) {
                        queue.poll(t.toLong(), TimeUnit.MILLISECONDS) ?: throw IOException("No answer from the iPhone (timed out)")
                    } else queue.take()
                    if (next === EOF) {
                        eof = true
                        return -1
                    }
                    cur = next
                    pos = 0
                }
                val n = minOf(len, cur.size - pos)
                System.arraycopy(cur, pos, b, off, n)
                pos += n
                return n
            }
        }

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                var at = off
                val end = off + len
                while (at < end) {
                    val n = minOf(end - at, MAX_PAYLOAD)
                    lock.withLock {
                        // Wait until the device has room for this much.
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                        while (state == CONNECTED && ((txSeq - rxAck) and MASK) + n > rxWin) {
                            val left = deadline - System.nanoTime()
                            if (left <= 0) throw IOException("The iPhone stopped accepting data")
                            changed.awaitNanos(left)
                        }
                        if (state != CONNECTED) throw IOException("The iPhone closed the connection")
                        tcp(ACK, b.copyOfRange(at, at + n))
                        txSeq = (txSeq + n) and MASK
                    }
                    at += n
                }
            }
        }

        override fun close() {
            lock.withLock {
                if (state == CONNECTED) try { tcp(RST) } catch (_: Exception) {}
                state = CLOSED
                changed.signalAll()
            }
            conns.remove(sport)
            queue.put(EOF)
        }
    }

    companion object {
        private const val PROTO_VERSION = 0
        private const val PROTO_SETUP = 2
        private const val PROTO_TCP = 6
        private const val MAGIC = 0xfeedface.toInt()
        private const val FIN = 0x01
        private const val SYN = 0x02
        private const val RST = 0x04
        private const val ACK = 0x10
        private const val CONNECTING = 0
        private const val CONNECTED = 1
        private const val CLOSED = 2
        private const val MASK = 0xFFFFFFFFL
        private const val OUR_WINDOW = 131072
        private const val MAX_PAYLOAD = 8192
        private const val MAX_PACKET = 1 shl 20
        const val READ_SIZE = 16384
        private val EMPTY = ByteArray(0)
        private val EOF = ByteArray(0)
    }
}
