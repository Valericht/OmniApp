package com.omni.monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/** Peta label teks Indonesia: salinan konstanta di tools/monitor.html (MODES, TUNE, TW, SF, DENY, SVW, JOGN, WHY ...). */
object Labels {
    val MODES = arrayOf("Manual", "Otonom", "Stop", "Tes gerak", "Jalan dashboard")
    val TUNE = arrayOf("", "berjalan", "selesai", "gagal")
    val TW = arrayOf(
        "",
        "respons terlalu kecil: cek motor/encoder/ENC_CPR",
        "arah encoder terbalik: balik ENC_SIGN roda ini",
        "respons belum mantap: cek beban, roda harus terangkat",
        "dibatalkan (STOP/koneksi putus)",
    )
    val JOGN = arrayOf("", "Maju", "Mundur", "Kiri", "Kanan", "Putar kiri", "Putar kanan")
    val SF = arrayOf(
        "",
        "Roda berputar kencang tetapi robot tidak bergerak (menabrak atau roda selip)",
        "Robot berputar tidak terkendali",
        "Motor macet (beban terlalu berat atau tertahan)",
        "Robot terangkat saat motor jalan",
    )
    val DENY = arrayOf(
        "",
        "posisi tidak valid: tetapkan titik 0 dulu",
        "rute kosong atau waypoint belum disimpan",
        "pengaman aktif: tekan Lanjut dulu",
        "robot sedang dipakai (otonom, tes gerak, jalan, atau stick)",
        "posisi tidak pasti (tanjakan): tunggu robot datar dan diam",
        "gagal tiba setelah beberapa percobaan ulang",
        "terhalang terlalu lama (rintangan di depan)",
        "sinkron ditolak: WP tanpa referensi dinding, jarak > 30 cm, atau bukan robot diam",
    )
    val SVW = arrayOf("", "waypoint", "PID", "posisi terakhir", "rute", "kecepatan")
    val DIRS = arrayOf("", "Maju", "Mundur", "Kiri", "Kanan")
    val VT = arrayOf("-", "OK", "TERBALIK", "tidak bergerak", "melenceng", "sinyal lemah")
    val WHY = arrayOf(
        "",
        "dibatalkan (STOP, stick, atau tombol B)",
        "app tidak mengirim detak",
        "ada rintangan dekat",
        "",
        "robot sedang otonom",
    )
    val PRE = arrayOf("A", "B", "C", "D")
    val PADB = arrayOf("A", "B", "X", "Y")
    val PS = arrayOf("IDLE", "", "JALAN")
    val WST = arrayOf(
        "tidak dipakai", "koreksi aktif", "fallback encoder (sensor tidak valid / miring samping)",
        "offset melebihi batas", "menghindar (ditahan)",
    )
    val ARRV = arrayOf("tanpa verifikasi", "cocok", "TIDAK COCOK")
}

fun Array<String>.at(i: Int): String = getOrElse(i) { "" }

// ---- pembaca JSON toleran: field hilang / tipe salah = nilai bawaan, tidak pernah melempar ----
private fun JSONObject.f(k: String, d: Float = 0f): Float = when (val o = opt(k)) {
    is Number -> o.toFloat().takeIf { it.isFinite() } ?: d
    is Boolean -> if (o) 1f else 0f
    else -> d
}

private fun JSONObject.i(k: String, d: Int = 0): Int = when (val o = opt(k)) {
    is Number -> o.toInt()
    is Boolean -> if (o) 1 else 0
    else -> d
}

private fun JSONObject.b(k: String): Boolean = when (val o = opt(k)) {
    is Boolean -> o
    is Number -> o.toDouble() != 0.0
    else -> false
}

private fun JSONObject.s(k: String): String {
    val o = opt(k)
    return if (o == null || o == JSONObject.NULL) "" else o.toString()
}

private fun JSONArray?.floats(n: Int, d: Float = 0f) = FloatArray(n) { idx ->
    when (val o = this?.opt(idx)) {
        is Number -> o.toFloat().takeIf { it.isFinite() } ?: d
        is Boolean -> if (o) 1f else 0f
        else -> d
    }
}

private fun JSONArray?.ints(n: Int) = IntArray(n) { idx ->
    when (val o = this?.opt(idx)) {
        is Number -> o.toInt()
        is Boolean -> if (o) 1 else 0
        else -> 0
    }
}

private fun JSONArray?.bools(n: Int) = BooleanArray(n) { idx ->
    when (val o = this?.opt(idx)) {
        is Boolean -> o
        is Number -> o.toDouble() != 0.0
        else -> false
    }
}

/** Satu telemetri (immutable). Semua field §5.2 PRD dibaca di sini, di luar UI thread. */
class Snap(j: JSONObject) {
    // pose & gerak
    val x = j.f("x"); val y = j.f("y"); val yaw = j.f("yaw")
    val vx = j.f("vx"); val vy = j.f("vy"); val wz = j.f("wz")
    val mvx = j.f("mvx"); val mvy = j.f("mvy")

    // mode & nav
    val mode = j.i("mode"); val tgt = j.i("tgt"); val spd = j.i("spd")
    val tb = j.b("tb"); val arm = j.b("arm")
    val rt = j.i("rt", -1); val rs = j.i("rs"); val rn = j.i("rn")
    val np = j.i("np"); val nd = j.i("nd"); val jg = j.i("jg"); val rty = j.i("rty")
    val ps = j.optJSONArray("ps").ints(4)
    val pl = j.b("pl"); val pr = j.b("pr"); val sat = j.i("sat", 100)

    // motor
    val rpm = j.optJSONArray("rpm").floats(3)
    val sp = j.optJSONArray("sp").floats(3)
    val d = j.optJSONArray("d").floats(3, -1f)
    val sa = j.optJSONArray("sa").floats(3)
    val kp = j.optJSONArray("kp").floats(3)
    val ki = j.optJSONArray("ki").floats(3)
    val kd = j.optJSONArray("kd").floats(3)

    // ultrasonik: dm median + dv valid; 999 / tidak valid / absen = NaN (tak terbaca)
    private val dm = j.optJSONArray("dm").floats(3, 999f)
    private val dv = j.optJSONArray("dv").bools(3)
    val us = FloatArray(3) { i ->
        val m = dm[i]
        if (j.has("dm")) (if (dv[i] && m >= 0f && m < 998f) m else Float.NaN)
        else (if (d[i] >= 0f) d[i] else Float.NaN)
    }

    // koreksi dinding
    val wl = j.f("wl", -1f); val wr = j.f("wr", -1f); val wlr = j.f("wlr"); val wrr = j.f("wrr")
    val wstat = j.i("wstat"); val ss = j.optJSONArray("ss").ints(4)
    val wlOk = dv[1] && wl >= 0f && wl < 998f
    val wrOk = dv[2] && wr >= 0f && wr < 998f

    // IMU & asisten
    val imu = j.b("imu"); val ia = j.i("ia"); val ic = j.i("ic"); val ndof = j.b("in")
    val hasAssist = j.has("terrain")
    val terrain = j.s("terrain")
    val pitch = j.f("pitch"); val roll = j.f("roll"); val yawError = j.f("yaw_error")
    val stuck = j.b("stuck"); val slip = j.b("slip"); val poseDegraded = j.b("pose_degraded")
    val encTrust = j.f("encoder_trust", 1f)

    // Lapangan v2: slf = faktor tanjakan (sf tetap status pengaman), as = skala hindar, re = RPM efektif, smin = sisi terkecil
    val slf = j.f("slf", 1f); val avoidScale = j.f("as", 1f); val re = j.f("re"); val smin = j.f("smin", -1f)
    val av = j.i("av"); val rst = j.b("rst")
    private val arrO = j.optJSONObject("arr")
    val arrQ = arrO?.i("q") ?: 0                      // naik 1 tiap kejadian tiba
    val arrId = arrO?.i("id") ?: 0
    val arrN = arrO?.i("n") ?: 0                      // jumlah VERIFY
    val arrV = arrO?.i("v") ?: 0                      // 0 tanpa verifikasi, 1 cocok, 2 tidak cocok
    val arrRl = arrO?.f("rl") ?: 0f                   // residual lateral (+ = robot di kanan ref)
    val arrRf = arrO?.f("rf") ?: 0f                   // residual sepanjang arah (+ = robot di depan ref)
    val arrM = arrO?.optJSONArray("m").floats(3, -1f) // ukur kiri, kanan, depan
    val arrR = arrO?.optJSONArray("r").floats(3)      // ref kiri, kanan, depan
    fun arrText(): String {
        fun f(i: Int) = if (arrR[i] > 0f) "${if (arrM[i] < 0f) "-" else fmt(arrM[i])}/${fmt(arrR[i])}" else "-"
        return "WP$arrId · ${Labels.ARRV.at(arrV)} · K ${f(0)} Ka ${f(1)} D ${f(2)} cm · sisa lateral ${fmt(arrRl)} " +
            "sepanjang ${fmt(arrRf)} cm · verifikasi ${arrN}x"
    }
    private val pidO = j.optJSONObject("pid")
    val yawKp = pidO?.f("kp") ?: 0f
    val yawKi = pidO?.f("ki") ?: 0f
    val yawKd = pidO?.f("kd") ?: 0f
    val yawStatus = pidO?.s("status") ?: ""

    // tes gerak
    private val mcO = j.optJSONObject("mc")
    val mcS = mcO?.i("s") ?: 0
    val mcDir = mcO?.i("dir") ?: 0
    val mcWhy = mcO?.i("why") ?: 0
    val mcO_ = mcO?.i("o") ?: 0
    val mcI = mcO?.i("i") ?: 0
    val mcYi = mcO?.b("yi") ?: false
    val mcDx = mcO?.f("dx") ?: 0f
    val mcDy = mcO?.f("dy") ?: 0f
    val mcIx = mcO?.f("ix") ?: 0f
    val mcIy = mcO?.f("iy") ?: 0f
    val mcDyaw = mcO?.f("dyaw") ?: 0f

    // pengaman & sistem
    val sf = j.i("sf"); val so = j.b("so")
    val sfv = j.optJSONArray("sfv").floats(4)
    val cs = j.optJSONArray("cs").ints(4)
    val pad = j.b("pad"); val pt = j.s("pt"); val bat = j.f("bat", -1f)
    val tune = j.i("tune"); val tw = j.i("tw")
    val sv = j.optJSONArray("sv").ints(3)
    val dn = j.optJSONArray("dn").ints(2)

    // waypoint [valid,x,y,yaw,hasYaw,wallL,wallR,wallF] x16 dan isi 4 rute
    val wp: Array<FloatArray> = run {
        val a = j.optJSONArray("wp")
        Array(MAX_WP) { i -> a?.optJSONArray(i).floats(8) }
    }
    val rts: Array<IntArray> = run {
        val a = j.optJSONArray("rts")
        Array(4) { r ->
            val ra = a?.optJSONArray(r)
            IntArray(ra?.length() ?: 0) { k -> ra?.optInt(k) ?: 0 }
        }
    }
    val wpSig: Int = wp.contentDeepHashCode() * 31 + rts.contentDeepHashCode() * 17 + tgt

    fun wpValid(i: Int) = wp[i][0] != 0f
    fun wpHasYaw(i: Int) = wp[i][4] != 0f
    fun wpHasRef(i: Int) = wp[i][5] > 0f || wp[i][6] > 0f || wp[i][7] > 0f

    companion object {
        const val MAX_WP = 16

        /** null bila bukan JSON objek atau bukan telemetri (tanpa `rpm`): pesan rusak diabaikan. */
        fun parse(text: String): Snap? = try {
            val j = JSONObject(text)
            if (j.has("rpm")) Snap(j) else null
        } catch (e: Exception) {
            null
        }
    }
}

/** Kejadian yang diturunkan dari selisih dua telemetri; urutan dan teks mengikuti render() di monitor.html. */
object Events {
    fun diff(p: Snap?, s: Snap): List<String> {
        if (p == null) return emptyList()
        val e = ArrayList<String>()
        if (p.pad != s.pad) e += if (s.pad) "Gamepad ${s.pt} tersambung" else "Gamepad terputus"
        if (p.mode != s.mode) e += "Mode: " + Labels.MODES.at(s.mode)
        for (i in 0..3) if (p.ps[i] != s.ps[i]) e += "Preset ${Labels.PRE[i]}: " + Labels.PS.at(s.ps[i])
        if (p.np != s.np && s.np == 2) e += "Tiba di titik, memutar badan ke arah waypoint"
        if (p.nd != s.nd) e += "Waypoint tuntas: posisi dan arah sudah sesuai"
        if (p.tune != s.tune && s.tune != 0) {
            e += "Auto-tune: " + Labels.TUNE.at(s.tune) +
                (if (s.tune == 3 && s.tw != 0) " - " + Labels.TW.at(s.tw) else "")
        }
        if (p.imu != s.imu) e += if (s.imu) "IMU normal" else "IMU tidak terdeteksi"
        if (p.sf != s.sf && s.sf != 0) e += "PENGAMAN: " + Labels.SF.at(s.sf)
        if (p.stuck != s.stuck && s.stuck) e += "Robot tertahan (stuck): berhenti"
        if (p.poseDegraded != s.poseDegraded) {
            e += if (s.poseDegraded) "Posisi tidak pasti (tanjakan): navigasi baru ditolak, kecepatan dibatasi"
            else "Posisi kembali pasti"
        }
        if (p.slip != s.slip && s.slip) e += "Roda selip: kepercayaan encoder diturunkan"
        if (p.terrain != s.terrain && s.terrain.isNotEmpty() && s.terrain != "FLAT") {
            e += "Medan: ${s.terrain} (pitch ${fmt(s.pitch)}°, roll ${fmt(s.roll)}°)"
        }
        if (p.sf != 0 && s.sf == 0) e += "Pengaman dibuka (Lanjut)"
        if (p.pl != s.pl) e += if (s.pl) "Robot terdeteksi diangkat atau dipindah: posisi tidak valid" else "Titik 0 ditetapkan"
        if (s.sv[0] != 0 && p.sv[2] != s.sv[2]) {
            val w = Labels.SVW.at(s.sv[1])
            e += if (s.sv[0] == 1) "Tersimpan ke flash: $w" else "GAGAL menyimpan ke flash: $w. Cek log serial [NVS]"
        }
        if (p.np != s.np && s.np == 3) e += "Memverifikasi posisi terhadap referensi dinding"
        if (p.av != s.av && s.av != 0) e += "Menghindar rintangan depan"
        if (p.rst != s.rst && s.rst) e += "Referensi dinding tidak cocok: rekam ulang waypoint"
        if (p.arrQ != s.arrQ && s.arrQ != 0) e += "Tiba: " + s.arrText()
        if (p.dn[1] != s.dn[1] && s.dn[0] != 0) e += "Perintah ditolak: " + Labels.DENY.at(s.dn[0])
        if (p.rty != s.rty && s.rty > 0) e += "Percobaan ulang ke WP${s.tgt}: ke-${s.rty}"
        return e
    }
}

/**
 * Snapshot terbaru + perintah ke robot. Parse berjalan di coroutine Default (inbox conflated: telemetri
 * lama dibuang bila UI tertinggal), hasilnya dikirim ke UI thread lewat [accept].
 */
class RobotState(private val sender: (String) -> Boolean) {
    companion object {
        const val HIST = 300        // 30 dtk pada 10 Hz
        const val MAX_EVENTS = 50
        private const val MAX_TRAIL = 600
    }

    var snap: Snap? = null
        private set
    val trail = ArrayList<FloatArray>()                   // [x, y, yaw]
    val events = ArrayList<Pair<String, String>>()        // (jam, teks), terbaru di atas
    var eventsVersion = 0
        private set

    // riwayat RPM (ring buffer tetap: tidak tumbuh)
    private val hist = Array(3) { FloatArray(HIST) }
    private val histT = Array(3) { FloatArray(HIST) }
    var histCount = 0
        private set
    private var histHead = 0

    private val listeners = ArrayList<(Snap) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inbox = Channel<String>(Channel.CONFLATED)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale("id", "ID"))

    init {
        scope.launch {
            for (m in inbox) {
                val s = Snap.parse(m) ?: continue
                withContext(Dispatchers.Main) { accept(s) }
            }
        }
    }

    /** Dipanggil dari thread jaringan. */
    fun onText(t: String) {
        inbox.trySend(t)
    }

    fun addListener(l: (Snap) -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: (Snap) -> Unit) {
        listeners.remove(l)
    }

    /** UI thread. */
    fun accept(s: Snap) {
        Events.diff(snap, s).forEach { log(it) }
        snap = s
        recTrail(s)
        for (i in 0..2) {
            hist[i][histHead] = s.rpm[i]
            histT[i][histHead] = s.sp[i]
        }
        histHead = (histHead + 1) % HIST
        if (histCount < HIST) histCount++
        for (l in ArrayList(listeners)) l(s)
    }

    /** Titik ke-k (0 = tertua) dari riwayat roda i. */
    fun rpmAt(i: Int, k: Int): Float = hist[i][(histHead - histCount + k + HIST) % HIST]
    fun spAt(i: Int, k: Int): Float = histT[i][(histHead - histCount + k + HIST) % HIST]

    fun log(text: String) {
        events.add(0, clock.format(Date()) to text)
        while (events.size > MAX_EVENTS) events.removeAt(events.size - 1)
        eventsVersion++
    }

    fun clearTrail() {
        trail.clear()
    }

    // jejak direkam saat robot digerakkan manual (aturan sama dengan recTrail() dashboard)
    private fun recTrail(s: Snap) {
        val moving = s.mode == 4 || (s.mode == 0 && hypot(s.mvx, s.mvy) > 3f)
        if (!moving) return
        val l = trail.lastOrNull()
        if (l == null || hypot(s.x - l[0], s.y - l[1]) > 2f || abs(s.yaw - l[2]) > 3f) {
            trail.add(floatArrayOf(s.x, s.y, s.yaw))
            if (trail.size > MAX_TRAIL) trail.removeAt(0)
        }
    }

    /** Kirim satu perintah JSON (§5.1). false bila tidak tersambung. */
    fun send(cmd: String, vararg kv: Pair<String, Any?>): Boolean {
        val o = JSONObject().put("cmd", cmd)
        for ((k, v) in kv) o.put(k, v)
        return sender(o.toString())
    }
}
