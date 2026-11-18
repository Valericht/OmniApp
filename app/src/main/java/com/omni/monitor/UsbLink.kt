package com.omni.monitor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Jalur USB (OTG) ke robot: serial, satu pesan JSON per baris (`\n`), isinya sama dengan teks WebSocket.
 * API sengaja dibuat sama dengan [WsLink] supaya [RobotLink] bisa memakai keduanya.
 */
class UsbLink(private val ctx: Context, private val onText: (String) -> Unit) {
    companion object {
        private const val ACTION_PERM = "com.omni.monitor.USB_PERMISSION"
        private const val HB = "{\"cmd\":\"hb\"}"
        private const val HB_MS = 100L
        private const val STALE_MS = 2000L
        private const val UP_MS = 1500L
        private const val RETRY_MS = 1000L
        private const val MAX_LINE = 8192
    }

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writer = Executors.newSingleThreadExecutor()
    private var job: Job? = null

    @Volatile var baud = OmniApp.DEF_BAUD
    @Volatile private var running = false
    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var io: SerialInputOutputManager? = null
    @Volatile private var open = false
    @Volatile private var lastMsg = 0L
    @Volatile private var pending = 0L
    @Volatile private var askedPermFor: Int = -1

    /** Keterangan status untuk banner: tidak ada perangkat / menunggu izin / membuka port / error. */
    @Volatile var note: String = "Kabel USB belum terdeteksi."
        private set

    @Volatile var latencyMs = -1f
        private set

    private val line = StringBuilder()

    fun phase(): LinkPhase = when {
        !running -> LinkPhase.DOWN
        open && SystemClock.elapsedRealtime() - lastMsg < UP_MS -> LinkPhase.UP
        open || askedPermFor != -1 -> LinkPhase.CONNECTING
        else -> LinkPhase.DOWN
    }

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != ACTION_PERM) return
            val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (!granted) note = "Izin USB ditolak. Cabut dan pasang kabel, lalu pilih Izinkan."
            // loop supervise mencoba buka port pada iterasi berikutnya
        }
    }

    fun start() {
        if (running) return
        running = true
        val f = IntentFilter(ACTION_PERM)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(permReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(permReceiver, f)
        job = scope.launch { supervise() }
        scope.launch { heartbeat() }
    }

    fun stop() {
        if (!running) return
        running = false
        job?.cancel(); job = null
        runCatching { ctx.unregisterReceiver(permReceiver) }
        close()
        askedPermFor = -1
    }

    fun send(text: String): Boolean {
        val p = port
        if (!open || p == null) return false
        val bytes = (text + "\n").toByteArray()
        writer.execute {
            try { p.write(bytes, 200) } catch (e: Exception) { close() }
        }
        return true
    }

    private fun close() {
        open = false
        io?.listener = null
        runCatching { io?.stop() }
        io = null
        runCatching { port?.close() }
        port = null
        pending = 0L
        latencyMs = -1f
        line.setLength(0)
    }

    private suspend fun supervise() {
        while (scope.isActive && running) {
            if (!open) tryOpen()
            delay(RETRY_MS)
        }
    }

    private fun tryOpen() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
        val driver = drivers.firstOrNull()
        if (driver == null) {
            askedPermFor = -1
            note = "Kabel USB belum terdeteksi. Hubungkan HP ke robot (kabel OTG), lalu tunggu."
            return
        }
        val dev: UsbDevice = driver.device
        if (!usb.hasPermission(dev)) {
            if (askedPermFor != dev.deviceId) {
                askedPermFor = dev.deviceId
                note = "Menunggu izin USB. Pilih Izinkan pada dialog yang muncul."
                val flags = PendingIntent.FLAG_MUTABLE
                val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERM).setPackage(ctx.packageName), flags)
                usb.requestPermission(dev, pi)
            }
            return
        }
        try {
            val conn = usb.openDevice(dev) ?: run { note = "Gagal membuka perangkat USB."; return }
            val p = driver.ports[0]
            p.open(conn)
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = p
            line.setLength(0)
            val m = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) = feed(data)
                override fun onRunError(e: Exception) { note = "USB terputus."; close() }
            })
            io = m
            m.start()
            lastMsg = SystemClock.elapsedRealtime()
            open = true
            askedPermFor = -1
            note = "USB terbuka ($baud baud)."
        } catch (e: Exception) {
            note = "Gagal membuka port USB: ${e.message}"
            close()
        }
    }

    private fun feed(data: ByteArray) {
        for (b in data) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c == '\n') {
                val s = line.toString().trim()
                line.setLength(0)
                if (s.startsWith("{") && s.endsWith("}")) rx(s)   // abaikan log debug non-JSON dari firmware
            } else if (line.length < MAX_LINE) line.append(c)
            else line.setLength(0)
        }
    }

    private fun rx(text: String) {
        lastMsg = SystemClock.elapsedRealtime()
        val p = pending
        if (p != 0L) {
            val rtt = (System.nanoTime() - p) / 1e6f
            latencyMs = if (latencyMs < 0f) rtt else latencyMs * 0.8f + rtt * 0.2f
            pending = 0L
        }
        onText(text)
    }

    private suspend fun heartbeat() {
        var next = SystemClock.elapsedRealtime()
        while (scope.isActive && running) {
            next += HB_MS
            if (open) {
                if (SystemClock.elapsedRealtime() - lastMsg > STALE_MS) close()
                else if (send(HB) && pending == 0L) pending = System.nanoTime()
            }
            val wait = next - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait) else if (wait < -500) next = SystemClock.elapsedRealtime()
        }
    }
}
