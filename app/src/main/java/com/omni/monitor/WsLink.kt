package com.omni.monitor

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class LinkPhase { CONNECTING, UP, DOWN }

/**
 * WebSocket ke robot (ws://host:81/ws): detak `hb` tiap 100 ms, sambung ulang mundur (1 dtk, maks 5 dtk),
 * dan ukur latensi (hb terkirim -> telemetri berikutnya). Robot berhenti sendiri ≤ 300 ms bila detak putus.
 */
class WsLink(private val onText: (String) -> Unit) {
    companion object {
        private const val HB = "{\"cmd\":\"hb\"}"
        private const val HB_MS = 100L
        private const val STALE_MS = 2000L      // tersambung tetapi tanpa data: putuskan paksa lalu sambung ulang
        private const val UP_MS = 1500L         // data lebih tua dari ini = belum "terhubung"
        private const val UNREACHABLE_MS = 5000L
        private const val BACKOFF_START = 1000L
        private const val BACKOFF_MAX = 5000L
    }

    private class Attempt {
        @Volatile var opened = false
        val closed = CompletableDeferred<Unit>()
    }

    @Volatile private var host = OmniApp.DEF_HOST
    @Volatile private var port = OmniApp.DEF_PORT
    @Volatile private var path = OmniApp.DEF_PATH

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Volatile private var running = false
    @Volatile private var socket: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var connecting = false
    @Volatile private var lastMsg = 0L
    @Volatile private var lastAlive = 0L
    @Volatile private var pending = 0L

    /** Latensi rata-rata (ms), -1 bila belum terukur. */
    @Volatile var latencyMs = -1f
        private set

    val target: String get() = "$host:$port$path"

    /** Host tak terjangkau ≥ 5 dtk: UI menampilkan instruksi menyambung WiFi Omni3X. */
    val unreachable: Boolean
        get() = running && !open && SystemClock.elapsedRealtime() - lastAlive > UNREACHABLE_MS

    fun phase(): LinkPhase = when {
        !running -> LinkPhase.DOWN
        open && SystemClock.elapsedRealtime() - lastMsg < UP_MS -> LinkPhase.UP
        open || connecting -> LinkPhase.CONNECTING
        else -> LinkPhase.DOWN
    }

    fun configure(host: String, port: Int, path: String) {
        this.host = host
        this.port = port
        this.path = if (path.startsWith("/")) path else "/$path"
    }

    /** Ganti alamat saat berjalan: putuskan, lalu loop sambung ulang memakai alamat baru. */
    fun reconfigure(host: String, port: Int, path: String) {
        configure(host, port, path)
        lastAlive = SystemClock.elapsedRealtime()
        socket?.cancel()
    }

    fun start() {
        if (running) return
        running = true
        lastAlive = SystemClock.elapsedRealtime()
        job = scope.launch { supervise() }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        socket?.cancel()
        socket = null
        open = false
        connecting = false
        pending = 0L
        latencyMs = -1f
    }

    fun send(text: String): Boolean {
        val s = socket
        return open && s != null && s.send(text)
    }

    private suspend fun supervise() = coroutineScope {
        launch { heartbeat() }
        var backoff = BACKOFF_START
        while (isActive) {
            val attempt = Attempt()
            connecting = true
            try {
                val req = Request.Builder().url("ws://$host:$port$path").build()
                socket = client.newWebSocket(req, Listener(attempt))
            } catch (e: IllegalArgumentException) {
                attempt.closed.complete(Unit)   // host/port tidak valid
            }
            attempt.closed.await()
            open = false
            connecting = false
            socket = null
            pending = 0L
            latencyMs = -1f
            if (attempt.opened) backoff = BACKOFF_START
            delay(backoff)
            if (!attempt.opened) backoff = minOf(backoff * 2, BACKOFF_MAX)
        }
    }

    private suspend fun CoroutineScope.heartbeat() {
        var next = SystemClock.elapsedRealtime()
        while (isActive) {
            next += HB_MS
            if (open) {
                if (SystemClock.elapsedRealtime() - lastMsg > STALE_MS) socket?.cancel()
                else if (send(HB) && pending == 0L) pending = System.nanoTime()
            }
            val wait = next - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait) else if (wait < -500) next = SystemClock.elapsedRealtime()
        }
    }

    private fun rx(text: String) {
        val now = SystemClock.elapsedRealtime()
        lastMsg = now
        lastAlive = now
        val p = pending
        if (p != 0L) {
            val rtt = (System.nanoTime() - p) / 1e6f
            latencyMs = if (latencyMs < 0f) rtt else latencyMs * 0.8f + rtt * 0.2f
            pending = 0L
        }
        onText(text)
    }

    private inner class Listener(private val a: Attempt) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val now = SystemClock.elapsedRealtime()
            lastMsg = now
            lastAlive = now
            a.opened = true
            open = true
            connecting = false
        }

        override fun onMessage(webSocket: WebSocket, text: String) = rx(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            open = false
            a.closed.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            open = false
            a.closed.complete(Unit)
        }
    }
}
