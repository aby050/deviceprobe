package com.abhay.deviceprobe

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0E1512)
private val Card = Color(0xFF17211C)
private val Line = Color(0xFF24322B)
private val Accent = Color(0xFF2EE59D)
private val Warn = Color(0xFFFFB020)
private val Fail = Color(0xFFFF5A5F)
private val TextMain = Color(0xFFE8F0EC)
private val TextDim = Color(0xFF8FA39A)

private fun Status.color(): Color = when (this) {
    Status.PASS -> Accent
    Status.WARN -> Warn
    Status.FAIL -> Fail
    Status.INFO -> TextDim
}

@Composable
fun AppScreen(
    state: StateFlow<UiState>,
    onStart: () -> Unit,
    onRescan: () -> Unit,
    onShare: (Report) -> Unit,
    onDevTest: (() -> Unit)?,
) {
    val s by state.collectAsState()
    Box(
        Modifier.fillMaxSize().background(Bg).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when (val st = s) {
            is UiState.Done -> ResultFlow(st.report, onRescan, onShare)
            UiState.Idle -> Home(connected = false, onStart = onRescan, onDevTest = onDevTest)
            UiState.Ready -> Home(connected = true, onStart = onStart, onDevTest = onDevTest)
            UiState.DebuggingOff -> Message(
                title = "Turn on USB debugging",
                body = "A phone is connected, but it is not letting this app read it yet. On the connected phone:",
                steps = ANDROID_STEPS,
                action = "Try again" to onRescan,
            )
            is UiState.Prompt -> Busy(st.title, st.body)
            UiState.Connecting -> Busy("Connecting", "Keep the cable plugged in.")
            UiState.WaitingApproval -> Busy(
                "Tap Allow on the connected phone",
                "It is asking \"Allow USB debugging?\". Unlock it and tap Allow.",
            )
            is UiState.Scanning -> Busy(st.step, "This takes about half a minute.")
            is UiState.Error -> Message(
                title = "Could not read the device",
                body = st.message,
                action = "Try again" to onRescan,
                onDevTest = onDevTest,
            )
        }
    }
}

private val ANDROID_STEPS = listOf(
    "Open Settings > About phone and tap Build number 7 times.",
    "Open Settings > Developer options and turn on USB debugging.",
    "Connect the cable. If asked, choose this phone as the one in control.",
)

@Composable
private fun Message(
    title: String,
    body: String,
    steps: List<String> = emptyList(),
    action: Pair<String, () -> Unit>,
    onDevTest: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("DeviceProbe", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(title, color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
        Spacer(Modifier.height(10.dp))
        Text(body, color = TextDim, fontSize = 16.sp, lineHeight = 23.sp)
        if (steps.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                steps.forEachIndexed { i, step ->
                    Row {
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(Line),
                            contentAlignment = Alignment.Center,
                        ) { Text("${i + 1}", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(12.dp))
                        Text(step, color = TextMain, fontSize = 15.sp, lineHeight = 21.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = action.second,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(action.first, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        if (onDevTest != null) {
            TextButton(onClick = onDevTest, modifier = Modifier.fillMaxWidth()) {
                Text("Developer test: scan over TCP 127.0.0.1:5555", color = TextDim, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Busy(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Accent)
        Spacer(Modifier.height(24.dp))
        Text(title, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = TextDim, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp)
    }
}

@Composable
private fun Home(connected: Boolean, onStart: () -> Unit, onDevTest: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("DeviceProbe", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(
            if (connected) "Phone connected" else "Connect a phone to check",
            color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (connected) "Tap Start check. Keep the cable in for about half a minute."
            else "Plug the phone you want to check into this one with a USB cable (OTG).",
            color = TextDim, fontSize = 16.sp, lineHeight = 23.sp,
        )
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (connected) Accent else TextDim))
            Spacer(Modifier.width(12.dp))
            Text(
                if (connected) "Ready to check" else "No phone found yet",
                color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        if (!connected) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Android phone", color = TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                ANDROID_STEPS.forEachIndexed { i, step ->
                    Row {
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(Line),
                            contentAlignment = Alignment.Center,
                        ) { Text("${i + 1}", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(12.dp))
                        Text(step, color = TextMain, fontSize = 15.sp, lineHeight = 21.sp)
                    }
                }
                Text("iPhone or iPad", color = TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Unlock it and connect the cable. When it asks \"Trust This Computer?\", tap Trust and enter its passcode.",
                    color = TextMain, fontSize = 15.sp, lineHeight = 21.sp,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onStart,
            colors = if (connected) ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg)
            else ButtonDefaults.buttonColors(containerColor = Line, contentColor = TextMain),
            modifier = Modifier.fillMaxWidth().height(if (connected) 60.dp else 52.dp),
        ) {
            Text(
                if (connected) "Start check" else "Look again",
                fontSize = if (connected) 18.sp else 16.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        if (onDevTest != null) {
            TextButton(onClick = onDevTest, modifier = Modifier.fillMaxWidth()) {
                Text("Developer test: scan over TCP 127.0.0.1:5555", color = TextDim, fontSize = 13.sp)
            }
        }
    }
}

/** The result first; the detailed report is its own screen behind a button. */
@Composable
private fun ResultFlow(report: Report, onRescan: () -> Unit, onShare: (Report) -> Unit) {
    var detail by remember(report) { mutableStateOf(false) }
    if (detail) {
        BackHandler { detail = false }
        DetailScreen(report, onBack = { detail = false }, onShare = onShare)
    } else {
        ResultScreen(report, onRescan = onRescan, onDetail = { detail = true })
    }
}

@Composable
private fun ResultScreen(report: Report, onRescan: () -> Unit, onDetail: () -> Unit) {
    var selected by remember(report) { mutableStateOf(report.verdict) }
    var open by remember(report) { mutableStateOf<Item?>(null) }
    val rows = report.sections.flatMap { sec -> sec.items.map { sec.title to it } }
    val shown = rows.filter { it.second.status == selected }
    val unknown = rows.filter { it.second.status == Status.INFO }

    Column(Modifier.fillMaxSize()) {
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text("Check result", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(report.deviceName, color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
        }

        val vc = report.verdict.color()
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(vc))
                Spacer(Modifier.width(10.dp))
                Text(report.verdictText, color = vc, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    Triple(Status.PASS, "Original", report.passes),
                    Triple(Status.WARN, "Warnings", report.warns),
                    Triple(Status.FAIL, "Problems", report.fails),
                ).forEach { (status, label, n) ->
                    val on = selected == status
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Bg)
                            .border(1.5.dp, if (on) status.color() else Bg, RoundedCornerShape(12.dp))
                            .clickable { selected = status; open = null }
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("$n", color = status.color(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(label, color = if (on) TextMain else TextDim, fontSize = 12.sp)
                    }
                }
            }
        }

        val title = when (selected) {
            Status.FAIL -> "Problems"
            Status.WARN -> "Warnings"
            else -> "Original and working"
        }
        SectionCard(title) {
            if (shown.isEmpty()) {
                Text("Nothing in this group.", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
            }
            shown.forEachIndexed { i, (section, item) ->
                if (i > 0) HorizontalDivider(color = Line)
                ResultRow(section, item, open == item) { open = if (open == item) null else item }
            }
        }

        if (unknown.isNotEmpty()) {
            SectionCard("Could not confirm") {
                unknown.forEachIndexed { i, (section, item) ->
                    if (i > 0) HorizontalDivider(color = Line)
                    ResultRow(section, item, open == item) { open = if (open == item) null else item }
                }
            }
        }

    }
        // Always in reach, however long the list is.
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onRescan, modifier = Modifier.weight(1f).height(50.dp)) {
                Text("Check another", color = TextMain, fontSize = 15.sp)
            }
            Button(
                onClick = onDetail,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
                modifier = Modifier.weight(1f).height(50.dp),
            ) { Text("Detailed report", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** One result line. Tapping it opens what was checked and what it means. */
@Composable
private fun ResultRow(section: String, item: Item, open: Boolean, onTap: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(item.status.color()))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.label, color = TextDim, fontSize = 13.sp)
                Text(
                    item.value,
                    color = if (item.status == Status.INFO || item.status == Status.PASS) TextMain else item.status.color(),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp,
                )
            }
            Text(if (open) "Hide" else "Details", color = Accent, fontSize = 13.sp)
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Bg).padding(12.dp)) {
                Text(
                    item.note ?: "Checked and found normal.",
                    color = TextMain, fontSize = 14.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text("Checked under: $section", color = TextDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DetailScreen(report: Report, onBack: () -> Unit, onShare: (Report) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ Back to result", color = Accent, fontSize = 15.sp) }
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text("Detailed report", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(report.deviceName, color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
            Text(
                SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(report.time)),
                color = TextDim, fontSize = 13.sp,
            )
        }

        SectionCard("Device information") {
            report.overview.forEachIndexed { i, (k, v) ->
                if (i > 0) HorizontalDivider(color = Line)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(k, color = TextDim, fontSize = 14.sp, modifier = Modifier.weight(0.42f))
                    Text(
                        v, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End, modifier = Modifier.weight(0.58f),
                    )
                }
            }
        }

        for (section in report.details) {
            SectionCard(section.title) {
                section.items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(color = Line)
                    ItemRow(item)
                }
            }
        }

        Text(
            "Read from the device over USB. Software checks cannot see every repair: " +
                "a part that reports itself correctly will pass.",
            color = TextDim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Button(
            onClick = { onShare(report) },
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text("Share as PDF", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Count(n: Int, label: String, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Bg).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$n", color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun ItemRow(item: Item) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Box(Modifier.padding(top = 5.dp).size(9.dp).clip(CircleShape).background(item.status.color()))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.label, color = TextDim, fontSize = 13.sp)
            Text(
                item.value,
                color = if (item.status == Status.INFO || item.status == Status.PASS) TextMain else item.status.color(),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp,
            )
            if (item.note != null) {
                Spacer(Modifier.height(2.dp))
                Text(item.note, color = TextDim, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}
