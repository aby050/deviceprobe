package com.abhay.deviceprobe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Draws a [Report] as an A4 PDF that can be printed or sent to a buyer. */
object PdfReport {

    fun write(context: Context, report: Report): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val safe = report.deviceName.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifBlank { "device" }
        val file = File(dir, "Device-report-$safe.pdf")
        val doc = PdfDocument()
        try {
            Writer(doc, report).draw()
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return file
    }

    private const val W = 595
    private const val H = 842
    private const val M = 40f
    private const val BOTTOM = H - 56f

    private const val INK = 0xFF14201A.toInt()
    private const val DIM = 0xFF5F6B65.toInt()
    private const val LINE = 0xFFE1E6E3.toInt()
    private const val GREEN = 0xFF12A36B.toInt()
    private const val AMBER = 0xFFC77700.toInt()
    private const val RED = 0xFFD93036.toInt()
    private const val GREY = 0xFF9AA39E.toInt()

    private fun Status.ink(): Int = when (this) {
        Status.PASS -> GREEN
        Status.WARN -> AMBER
        Status.FAIL -> RED
        Status.INFO -> GREY
    }

    private class Writer(private val doc: PdfDocument, private val report: Report) {
        private var page: PdfDocument.Page? = null
        private lateinit var canvas: Canvas
        private var pageNo = 0
        private var y = M
        private val shape = Paint(Paint.ANTI_ALIAS_FLAG)

        private fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        private fun layout(text: String, p: TextPaint, width: Float): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt().coerceAtLeast(10)).build()

        private fun put(l: StaticLayout, x: Float, top: Float) {
            canvas.save()
            canvas.translate(x, top)
            l.draw(canvas)
            canvas.restore()
        }

        private fun newPage() {
            finishPage()
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            page = p
            canvas = p.canvas
            y = M
        }

        private fun finishPage() {
            val p = page ?: return
            val small = paint(8f, DIM)
            shape.color = LINE
            canvas.drawRect(M, H - 44f, W - M, H - 43.2f, shape)
            canvas.drawText(
                "Read from the device over USB. Software checks cannot see every repair: a part that reports itself correctly will pass.",
                M, H - 30f, small,
            )
            val n = "Page $pageNo"
            canvas.drawText(n, W - M - small.measureText(n), H - 18f, small)
            canvas.drawText("DeviceProbe", M, H - 18f, small)
            doc.finishPage(p)
            page = null
        }

        private fun ensure(height: Float) {
            if (y + height > BOTTOM) newPage()
        }

        private fun divider() {
            shape.color = LINE
            canvas.drawRect(M, y, W - M, y + 0.8f, shape)
        }

        fun draw() {
            newPage()
            header()
            verdict()
            sectionTitle("Device information")
            report.overview.forEachIndexed { i, (k, v) -> overviewRow(k, v, i > 0) }
            for (s in report.details) {
                y += 18f
                sectionTitle(s.title)
                s.items.forEachIndexed { i, item -> itemRow(item, i > 0) }
            }
            finishPage()
        }

        private fun header() {
            canvas.drawText("DEVICE VERIFICATION REPORT", M, y + 9f, paint(9f, GREEN, true))
            y += 18f
            val name = layout(report.deviceName, paint(22f, INK, true), W - 2 * M)
            put(name, M, y)
            y += name.height + 4f
            val date = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(report.time))
            canvas.drawText(date, M, y + 10f, paint(10f, DIM))
            y += 26f
        }

        private fun verdict() {
            val color = report.verdict.ink()
            val box = RectF(M, y, W - M, y + 62f)
            shape.color = (color and 0x00FFFFFF) or 0x14000000
            canvas.drawRoundRect(box, 8f, 8f, shape)
            shape.color = color
            canvas.drawRoundRect(RectF(M, y, M + 4f, y + 62f), 2f, 2f, shape)
            canvas.drawText(report.verdictText, M + 18f, y + 27f, paint(17f, color, true))
            val counts = "${report.passes} passed    ${report.warns} warning${if (report.warns == 1) "" else "s"}" +
                "    ${report.fails} problem${if (report.fails == 1) "" else "s"}"
            canvas.drawText(counts, M + 18f, y + 47f, paint(10.5f, INK))
            y += 62f + 22f
        }

        private fun sectionTitle(title: String) {
            ensure(70f)
            canvas.drawText(title, M, y + 13f, paint(13.5f, INK, true))
            y += 22f
            shape.color = INK
            canvas.drawRect(M, y, W - M, y + 1.2f, shape)
            y += 1.2f
        }

        private fun overviewRow(key: String, value: String, line: Boolean) {
            val keyW = 170f
            val k = layout(key, paint(10f, DIM), keyW)
            val v = layout(value, paint(10f, INK, true), W - 2 * M - keyW - 12f)
            val h = maxOf(k.height, v.height) + 12f
            if (y + h > BOTTOM) newPage() else if (line) divider()
            put(k, M, y + 6f)
            put(v, M + keyW + 12f, y + 6f)
            y += h
        }

        private fun itemRow(item: Item, line: Boolean) {
            val x = M + 18f
            val width = W - M - x
            val label = layout(item.label, paint(9f, DIM), width)
            val valueColor = if (item.status == Status.WARN || item.status == Status.FAIL) item.status.ink() else INK
            val value = layout(item.value, paint(11f, valueColor, true), width)
            val note = item.note?.let { layout(it, paint(9f, DIM), width) }
            val h = 8f + label.height + 1f + value.height + (note?.let { it.height + 2f } ?: 0f) + 8f
            if (y + h > BOTTOM) newPage() else if (line) divider()
            shape.color = item.status.ink()
            canvas.drawCircle(M + 5f, y + 8f + label.height + 1f + 7.5f, 3.2f, shape)
            var top = y + 8f
            put(label, x, top); top += label.height + 1f
            put(value, x, top); top += value.height + 2f
            if (note != null) put(note, x, top)
            y += h
        }
    }
}
