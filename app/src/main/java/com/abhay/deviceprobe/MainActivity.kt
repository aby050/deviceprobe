package com.abhay.deviceprobe

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import androidx.core.content.FileProvider
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.abhay.deviceprobe.adb.AdbConnection
import com.abhay.deviceprobe.adb.AdbCrypto
import com.abhay.deviceprobe.adb.AdbTransport
import com.abhay.deviceprobe.adb.TcpAdbTransport
import com.abhay.deviceprobe.adb.UsbAdbTransport
import com.abhay.deviceprobe.ios.AppleUsbPipe
import com.abhay.deviceprobe.ios.HostIdentity
import com.abhay.deviceprobe.ios.IosInspector
import com.abhay.deviceprobe.ios.Lockdown
import com.abhay.deviceprobe.ios.UsbMux
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Idle : UiState
    /** A phone with USB debugging is plugged in and waiting for Start. */
    data object Ready : UiState
    data object DebuggingOff : UiState
    /** The connected phone is waiting for its owner to do something (iPhone: tap Trust). */
    data class Prompt(val title: String, val body: String) : UiState
    data object Connecting : UiState
    data object WaitingApproval : UiState
    data class Scanning(val step: String) : UiState
    data class Done(val report: Report) : UiState
    data class Error(val message: String) : UiState
}

class MainActivity : ComponentActivity() {

    private val state = MutableStateFlow<UiState>(UiState.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    /** What the running scan holds open; closing it is how a blocked scan is stopped. */
    @Volatile private var link = AtomicReference<Closeable?>()
    private lateinit var usb: UsbManager

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice() ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) scanUsb(device)
                    else state.value = UiState.Error("USB access was not allowed. Unplug the cable, plug it in again and tap OK.")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> intent.usbDevice()?.let { onDevice(it) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (state.value !is UiState.Done) {
                        stopScan()
                        state.value = UiState.Idle
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        setContent {
            AppScreen(
                state = state,
                onStart = { start() },
                onRescan = { rescan() },
                onShare = { share(it) },
                onDevTest = if (BuildConfig.DEBUG) ({ scanTcp("127.0.0.1", 5555) }) else null,
            )
        }

        if (savedInstanceState == null) {
            intent.usbDevice()?.let { onDevice(it) } ?: rescan()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.usbDevice()?.let { onDevice(it) }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        scope.cancel()
        super.onDestroy()
    }

    /** Looks at whatever is plugged in right now. */
    private fun stopScan() {
        scanJob?.cancel()
        val l = link.getAndSet(null)
        if (l != null) scope.launch { try { l.close() } catch (_: Exception) {} }
    }

    private fun rescan() {
        stopScan()
        val devices = usb.deviceList.values
        val target = devices.firstOrNull { UsbAdbTransport.findAdbInterface(it) != null }
            ?: devices.firstOrNull { AppleUsbPipe.isApple(it) }
            ?: devices.firstOrNull { looksLikePhone(it) }
        if (target == null) state.value = UiState.Idle else onDevice(target)
    }

    /** The user tapped Start check. */
    private fun start() {
        val devices = usb.deviceList.values
        val device = devices.firstOrNull { UsbAdbTransport.findAdbInterface(it) != null }
            ?: devices.firstOrNull { AppleUsbPipe.isApple(it) }
        when {
            device == null -> rescan()
            usb.hasPermission(device) -> scanUsb(device)
            else -> askPermission(device)
        }
    }

    private fun onDevice(device: UsbDevice) {
        when {
            UsbAdbTransport.findAdbInterface(device) != null -> {
                state.value = UiState.Ready
            }
            AppleUsbPipe.isApple(device) -> state.value = UiState.Ready
            looksLikePhone(device) -> state.value = UiState.DebuggingOff
        }
    }

    /** Phones without USB debugging show up as a media (MTP/PTP) device. */
    private fun looksLikePhone(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val c = device.getInterface(i).interfaceClass
            if (c == 6 || c == 255) return true
        }
        return false
    }

    private fun askPermission(device: UsbDevice) {
        state.value = UiState.Connecting
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        usb.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
    }

    private fun scanUsb(device: UsbDevice) =
        if (AppleUsbPipe.isApple(device)) scanApple(device) else scanAdb { UsbAdbTransport.open(usb, device) }

    private fun scanTcp(host: String, port: Int) = scanAdb { TcpAdbTransport(host, port) }

    private fun scanAdb(open: () -> AdbTransport) = scan {
        val adb = AdbConnection(open(), AdbCrypto.loadOrCreate(filesDir))
        it.set(Closeable { adb.close() })
        adb.connect(onNeedsApproval = { state.value = UiState.WaitingApproval })
        state.value = UiState.Scanning("Connected")
        DeviceInspector(
            sh = { adb.shell(it) },
            progress = { state.value = UiState.Scanning(it) },
        ).inspect()
    }

    private fun scanApple(device: UsbDevice) = scan {
        val mux = UsbMux(AppleUsbPipe.open(usb, device))
        it.set(mux)
        mux.start()
        Lockdown(mux, HostIdentity.loadOrCreate(filesDir)).use { lockdown ->
            lockdown.connect { title, body -> state.value = UiState.Prompt(title, body) }
            state.value = UiState.Scanning("Connected")
            IosInspector(lockdown, progress = { state.value = UiState.Scanning(it) }).inspect()
        }
    }

    /** Runs one scan in the background; [work] opens the device, registers it for closing and returns the report. */
    private fun scan(work: (AtomicReference<Closeable?>) -> Report) {
        stopScan()
        val held = AtomicReference<Closeable?>()
        link = held
        state.value = UiState.Connecting
        var job: Job? = null
        job = scope.launch {
            try {
                val report = work(held)
                if (job?.isCancelled != true) state.value = UiState.Done(report)
            } catch (e: Exception) {
                if (job?.isCancelled != true) {
                    state.value = UiState.Error(e.message ?: "Something went wrong while reading the device.")
                }
            } finally {
                try { held.getAndSet(null)?.close() } catch (_: Exception) {}
            }
        }
        scanJob = job
    }

    private fun share(report: Report) {
        val subject = "Device report: ${report.deviceName}"
        val send = try {
            val file = PdfReport.write(this, report)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            Intent(Intent.ACTION_SEND)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newUri(contentResolver, file.name, uri) }
        } catch (e: Exception) {
            // The PDF could not be made: send the plain-text report instead.
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, report.toText())
        }
        startActivity(Intent.createChooser(send, "Share report"))
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        private const val ACTION_USB_PERMISSION = "com.abhay.deviceprobe.USB_PERMISSION"
    }
}
