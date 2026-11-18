package com.omni.monitor

import android.content.Context

enum class LinkMode { AUTO, WIFI, USB }

/**
 * Menggabungkan jalur WiFi ([WsLink]) dan USB ([UsbLink]) di belakang satu API.
 * AUTO: USB dipakai bila hidup, selain itu WiFi (perpindahan otomatis, tidak perlu restart app).
 * Hanya jalur aktif yang dikirimi perintah dan yang telemetrinya diteruskan, jadi tidak ada data ganda.
 */
class RobotLink(ctx: Context, private val onText: (String) -> Unit) {
    private val ws: WsLink = WsLink { if (!usbWins()) onText(it) }
    private val usb: UsbLink = UsbLink(ctx) { if (usbWins()) onText(it) }

    @Volatile var mode = LinkMode.AUTO
    @Volatile private var running = false

    private fun useWs(): Boolean = mode != LinkMode.USB
    private fun useUsb(): Boolean = mode != LinkMode.WIFI
    private fun usbWins(): Boolean = useUsb() && (mode == LinkMode.USB || usb.phase() == LinkPhase.UP)

    /** "USB", "WiFi", atau "-" bila belum ada yang hidup. */
    val activeName: String
        get() = when {
            useUsb() && usb.phase() == LinkPhase.UP -> "USB"
            useWs() && ws.phase() == LinkPhase.UP -> "WiFi"
            else -> "-"
        }

    val latencyMs: Float get() = if (usbWins()) usb.latencyMs else ws.latencyMs

    val target: String get() = if (mode == LinkMode.USB) "USB serial" else ws.target

    fun phase(): LinkPhase {
        val a = if (useUsb()) usb.phase() else LinkPhase.DOWN
        val b = if (useWs()) ws.phase() else LinkPhase.DOWN
        return when {
            a == LinkPhase.UP || b == LinkPhase.UP -> LinkPhase.UP
            a == LinkPhase.CONNECTING || b == LinkPhase.CONNECTING -> LinkPhase.CONNECTING
            else -> LinkPhase.DOWN
        }
    }

    /** Dua jalur yang diizinkan sama-sama tak terjangkau: UI menampilkan petunjuk menyambung. */
    val unreachable: Boolean
        get() = (!useWs() || ws.unreachable) && (!useUsb() || usb.phase() == LinkPhase.DOWN)

    /** Petunjuk untuk banner, sesuai mode. */
    fun hint(): String {
        val wifi = "WiFi: sambungkan WiFi Omni3X (sandinya omni3x1234), biarkan tetap terhubung walau tanpa internet."
        val usbH = "USB: ${usb.note}"
        return when (mode) {
            LinkMode.WIFI -> "Belum tersambung ke robot (${ws.target}). $wifi"
            LinkMode.USB -> "Belum tersambung lewat USB. ${usb.note}"
            LinkMode.AUTO -> "Belum tersambung ke robot. Pilih salah satu. $usbH  $wifi App tersambung sendiri."
        }
    }

    fun configure(host: String, port: Int, path: String, baud: Int, mode: LinkMode) {
        ws.configure(host, port, path)
        usb.baud = baud
        this.mode = mode
    }

    fun reconfigure(host: String, port: Int, path: String, baud: Int, mode: LinkMode) {
        this.mode = mode
        usb.baud = baud
        if (running) {
            // mulai ulang kedua jalur supaya mode/baud baru langsung berlaku
            usb.stop(); ws.stop()
            ws.configure(host, port, path)
            startSelected()
        } else ws.configure(host, port, path)
    }

    private fun startSelected() {
        if (useWs()) ws.start()
        if (useUsb()) usb.start()
    }

    fun start() {
        if (running) return
        running = true
        startSelected()
    }

    fun stop() {
        running = false
        ws.stop()
        usb.stop()
    }

    fun send(text: String): Boolean = if (usbWins()) usb.send(text) else if (useWs()) ws.send(text) else false
}
