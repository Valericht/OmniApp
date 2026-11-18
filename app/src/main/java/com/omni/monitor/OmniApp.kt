package com.omni.monitor

import android.app.Application
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

class OmniApp : Application() {
    companion object {
        const val DEF_HOST = "192.168.4.1"
        const val DEF_PATH = "/ws"
        const val DEF_PORT = 81
        const val DEF_BAUD = 115200
    }

    private lateinit var prefs: SharedPreferences
    lateinit var link: RobotLink
        private set
    val state = RobotState { link.send(it) }
    private var users = 0

    val host: String get() = prefs.getString("host", DEF_HOST) ?: DEF_HOST
    val path: String get() = prefs.getString("path", DEF_PATH) ?: DEF_PATH
    val port: Int get() = prefs.getInt("port", DEF_PORT)
    val baud: Int get() = prefs.getInt("baud", DEF_BAUD)
    val mode: LinkMode get() = runCatching { LinkMode.valueOf(prefs.getString("mode", "AUTO")!!) }.getOrDefault(LinkMode.AUTO)

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("omni", MODE_PRIVATE)
        link = RobotLink(this, state::onText)
        link.configure(host, port, path, baud, mode)
        bindWifi()
    }

    /** Sambung selama ada layar terlihat; semua layar tertutup = putus (robot berhenti lewat failsafe). */
    fun acquire() {
        if (users++ == 0) link.start()
    }

    fun release() {
        if (--users == 0) link.stop()
    }

    fun saveSettings(host: String, path: String, port: Int, baud: Int, mode: LinkMode) {
        prefs.edit().putString("host", host).putString("path", path).putInt("port", port)
            .putInt("baud", baud).putString("mode", mode.name).apply()
        link.reconfigure(host, port, path, baud, mode)
    }

    /**
     * AP robot tidak punya internet, jadi Android bisa mengalihkan lalu lintas app ke data seluler.
     * Ikat proses ke jaringan WiFi supaya ws://192.168.4.1 selalu lewat WiFi. Tidak membaca SSID.
     */
    private fun bindWifi() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        var bound: Network? = null
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                bound = network
                cm.bindProcessToNetwork(network)
            }

            override fun onLost(network: Network) {
                if (network == bound) {
                    bound = null
                    cm.bindProcessToNetwork(null)
                }
            }
        })
    }
}
