package com.abhay.deviceprobe.ios

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

typealias Dict = Map<String, Any?>

/** Base64 without java.util.Base64 (not on Android 7) or android.util (not in unit tests). */
object B64 {
    private const val A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(d: ByteArray, lineLen: Int = 0): String {
        val sb = StringBuilder()
        var i = 0
        var col = 0
        while (i < d.size) {
            val b0 = d[i].toInt() and 0xFF
            val b1 = if (i + 1 < d.size) d[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < d.size) d[i + 2].toInt() and 0xFF else 0
            sb.append(A[b0 shr 2]).append(A[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < d.size) A[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < d.size) A[b2 and 63] else '=')
            i += 3
            col += 4
            if (lineLen > 0 && col >= lineLen && i < d.size) {
                sb.append('\n')
                col = 0
            }
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        var acc = 0
        var bits = 0
        for (c in s) {
            val v = A.indexOf(c)
            if (v < 0) continue
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}

/**
 * Apple property lists. Values are Map, List, String, Long, Double, Boolean and ByteArray
 * (dates come back as text).
 */
object Plist {

    fun toXml(root: Any?): ByteArray {
        val sb = StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                "<plist version=\"1.0\">\n",
        )
        write(sb, root)
        sb.append("\n</plist>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            is Map<*, *> -> {
                sb.append("<dict>")
                for ((k, x) in v) {
                    if (x == null) continue
                    sb.append("<key>").append(esc(k.toString())).append("</key>")
                    write(sb, x)
                }
                sb.append("</dict>")
            }
            is List<*> -> {
                sb.append("<array>")
                for (x in v) write(sb, x)
                sb.append("</array>")
            }
            is String -> sb.append("<string>").append(esc(v)).append("</string>")
            is Boolean -> sb.append(if (v) "<true/>" else "<false/>")
            is Int, is Long -> sb.append("<integer>").append(v).append("</integer>")
            is Number -> sb.append("<real>").append(v.toDouble()).append("</real>")
            is ByteArray -> sb.append("<data>").append(B64.encode(v)).append("</data>")
            else -> throw IllegalArgumentException("Cannot write ${v?.javaClass} to a plist")
        }
    }

    fun parse(bytes: ByteArray): Any? =
        if (bytes.size > 8 && String(bytes, 0, 6, Charsets.US_ASCII) == "bplist") Binary(bytes).root()
        else Xml(String(bytes, Charsets.UTF_8)).root()

    // ------------------------------------------------------------------ XML

    private class Xml(private val s: String) {
        private var i = 0

        /** The next tag name: "dict", "/dict" or "true/". Skips the prolog, comments and text. */
        private fun tag(): String {
            while (true) {
                val a = s.indexOf('<', i)
                if (a < 0) throw IOException("Broken plist")
                if (s.startsWith("<!--", a)) {
                    i = s.indexOf("-->", a).also { if (it < 0) throw IOException("Broken plist") } + 3
                    continue
                }
                val b = s.indexOf('>', a)
                if (b < 0) throw IOException("Broken plist")
                i = b + 1
                if (s[a + 1] == '?' || s[a + 1] == '!') continue
                val body = s.substring(a + 1, b).trim()
                val selfClosing = body.endsWith("/")
                val name = body.removeSuffix("/").trim().substringBefore(' ')
                return if (selfClosing) "$name/" else name
            }
        }

        private fun text(name: String): String {
            val end = s.indexOf("</$name>", i)
            if (end < 0) throw IOException("Broken plist")
            val t = s.substring(i, end)
            i = end + name.length + 3
            return unesc(t)
        }

        private fun unesc(t: String): String {
            if (!t.contains('&')) return t
            return Regex("&(#x?[0-9a-fA-F]+|\\w+);").replace(t) { m ->
                when (val e = m.groupValues[1]) {
                    "lt" -> "<"
                    "gt" -> ">"
                    "amp" -> "&"
                    "quot" -> "\""
                    "apos" -> "'"
                    else -> when {
                        e.startsWith("#x") -> String(Character.toChars(e.substring(2).toInt(16)))
                        e.startsWith("#") -> String(Character.toChars(e.substring(1).toInt()))
                        else -> m.value
                    }
                }
            }
        }

        fun root(): Any? {
            var t = tag()
            if (t == "plist") t = tag()
            return value(t)
        }

        private fun value(t: String): Any? = when (t) {
            "dict" -> {
                val m = LinkedHashMap<String, Any?>()
                while (true) {
                    val k = tag()
                    if (k == "/dict") break
                    if (k != "key") throw IOException("Broken plist")
                    val key = text("key")
                    m[key] = value(tag())
                }
                m
            }
            "array" -> {
                val l = ArrayList<Any?>()
                while (true) {
                    val x = tag()
                    if (x == "/array") break
                    l += value(x)
                }
                l
            }
            "string" -> text("string")
            "date" -> text("date")
            "integer" -> text("integer").trim().let { it.toLongOrNull() ?: it.toULongOrNull()?.toLong() ?: 0L }
            "real" -> text("real").trim().toDoubleOrNull() ?: 0.0
            "data" -> B64.decode(text("data"))
            "true/" -> true
            "false/" -> false
            "dict/" -> emptyMap<String, Any?>()
            "array/" -> emptyList<Any?>()
            "string/" -> ""
            "data/" -> ByteArray(0)
            else -> throw IOException("Unknown plist tag <$t>")
        }
    }

    // --------------------------------------------------------------- binary

    private class Binary(private val d: ByteArray) {
        private val offSize = d[d.size - 26].toInt() and 0xFF
        private val refSize = d[d.size - 25].toInt() and 0xFF
        private val count = uint(d.size - 24, 8).toInt()
        private val top = uint(d.size - 16, 8).toInt()
        private val table = uint(d.size - 8, 8).toInt()

        private fun uint(at: Int, n: Int): Long {
            var v = 0L
            for (k in 0 until n) v = (v shl 8) or (d[at + k].toLong() and 0xFF)
            return v
        }

        fun root(): Any? = obj(top, 0)

        private fun obj(index: Int, depth: Int): Any? {
            if (index !in 0 until count || depth > 64) throw IOException("Broken plist")
            var p = uint(table + index * offSize, offSize).toInt()
            val marker = d[p].toInt() and 0xFF
            val type = marker shr 4
            var n = marker and 0x0F
            p++
            if (n == 0x0F && type in setOf(4, 5, 6, 0xA, 0xC, 0xD)) {
                val w = 1 shl (d[p].toInt() and 0x0F)
                n = uint(p + 1, w).toInt()
                p += 1 + w
            }
            return when (type) {
                0 -> when (marker) {
                    8 -> false
                    9 -> true
                    else -> null
                }
                1 -> uint(p, 1 shl (marker and 0x0F)).let { if ((marker and 0x0F) > 3) uint(p + (1 shl (marker and 0x0F)) - 8, 8) else it }
                2 -> if ((marker and 0x0F) == 2) ByteBuffer.wrap(d, p, 4).float.toDouble() else ByteBuffer.wrap(d, p, 8).double
                3 -> ByteBuffer.wrap(d, p, 8).double.toString()
                4 -> d.copyOfRange(p, p + n)
                5 -> String(d, p, n, Charsets.US_ASCII)
                6 -> String(d, p, n * 2, Charsets.UTF_16BE)
                8 -> uint(p, (marker and 0x0F) + 1)
                0xA, 0xC -> (0 until n).map { obj(uint(p + it * refSize, refSize).toInt(), depth + 1) }
                0xD -> {
                    val m = LinkedHashMap<String, Any?>()
                    for (k in 0 until n) {
                        val key = obj(uint(p + k * refSize, refSize).toInt(), depth + 1)
                        m[key.toString()] = obj(uint(p + (n + k) * refSize, refSize).toInt(), depth + 1)
                    }
                    m
                }
                else -> throw IOException("Unknown plist object type $type")
            }
        }
    }
}

/** How lockdown and its services frame messages: a 4-byte big-endian length, then a plist. */
object PlistIo {
    fun send(out: OutputStream, body: Dict) {
        val xml = Plist.toXml(body)
        out.write(ByteBuffer.allocate(4).putInt(xml.size).array() + xml)
        out.flush()
    }

    @Suppress("UNCHECKED_CAST")
    fun receive(input: InputStream): Dict {
        val len = ByteBuffer.wrap(readFully(input, 4)).int
        if (len <= 0 || len > 32 * 1024 * 1024) throw IOException("The iPhone sent an answer that makes no sense")
        return Plist.parse(readFully(input, len)) as? Dict ?: throw IOException("The iPhone sent an answer that makes no sense")
    }

    private fun readFully(input: InputStream, len: Int): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(out, off, len - off)
            if (n < 0) throw IOException("The iPhone closed the connection")
            off += n
        }
        return out
    }
}
