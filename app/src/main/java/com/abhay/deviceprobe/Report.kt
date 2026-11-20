package com.abhay.deviceprobe

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Status { PASS, WARN, FAIL, INFO }

/** One line of the report: what was checked, what was found, and what it means. */
data class Item(
    val label: String,
    val value: String,
    val status: Status = Status.INFO,
    val note: String? = null,
)

data class Section(val title: String, val items: List<Item>)

data class Report(
    val deviceName: String,
    /** The device-info sheet: plain facts, shown as a two-column grid. */
    val overview: List<Pair<String, String>>,
    /** The checks, each with a verdict. */
    val sections: List<Section>,
    /** Every check that was run, for the detailed report and the PDF. */
    val details: List<Section> = sections,
    val time: Long = System.currentTimeMillis(),
) {
    val all: List<Item> get() = sections.flatMap { it.items }
    val fails: Int get() = all.count { it.status == Status.FAIL }
    val warns: Int get() = all.count { it.status == Status.WARN }
    val passes: Int get() = all.count { it.status == Status.PASS }

    val verdict: Status
        get() = when {
            fails > 0 -> Status.FAIL
            warns > 0 -> Status.WARN
            else -> Status.PASS
        }

    val verdictText: String
        get() = when (verdict) {
            Status.FAIL -> "Problems found"
            Status.WARN -> "Check these before buying"
            else -> "No problems found"
        }

    fun toText(): String = buildString {
        val date = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(time))
        appendLine("DEVICE VERIFICATION REPORT")
        appendLine(deviceName)
        appendLine(date)
        appendLine()
        appendLine("Result: $verdictText ($passes passed, $warns warnings, $fails problems)")
        appendLine()
        appendLine("DEVICE INFORMATION")
        for ((k, v) in overview) appendLine("  $k: $v")
        for (s in details) {
            appendLine()
            appendLine(s.title.uppercase(Locale.US))
            for (i in s.items) {
                val mark = when (i.status) {
                    Status.PASS -> "[OK]"
                    Status.WARN -> "[WARNING]"
                    Status.FAIL -> "[PROBLEM]"
                    Status.INFO -> "[-]"
                }
                appendLine("  $mark ${i.label}: ${i.value}")
                if (i.note != null) appendLine("      ${i.note}")
            }
        }
    }
}
