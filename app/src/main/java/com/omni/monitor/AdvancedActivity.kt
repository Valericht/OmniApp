package com.omni.monitor

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.app.AlertDialog
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.hypot
import com.omni.monitor.databinding.ActivityAdvancedBinding

class AdvancedActivity : Activity() {
    private lateinit var b: ActivityAdvancedBinding
    private val app get() = application as OmniApp
    private val st get() = app.state
    private val onSnap: (Snap) -> Unit = { render(it) }
    private val ui = Handler(Looper.getMainLooper())

    // R9: rute preset + edit waypoint
    private val routeEdits = ArrayList<SyncEdit>()
    private val routeStatus = ArrayList<TextView>()
    private val routeRun = ArrayList<Button>()
    private val wpX = ArrayList<SyncEdit>()
    private val wpY = ArrayList<SyncEdit>()
    private val wpA = ArrayList<SyncEdit>()
    private var wpSigShown = Int.MIN_VALUE

    // R10: tes gerak, kecepatan, PID
    private class MRes(s: Snap) {
        val dir = s.mcDir; val why = s.mcWhy; val st = s.mcS; val o = s.mcO_; val i = s.mcI; val yi = s.mcYi
        val dx = s.mcDx; val dy = s.mcDy; val ix = s.mcIx; val iy = s.mcIy; val dyaw = s.mcDyaw
    }
    private val mres = HashMap<Int, MRes>()
    private val mq = ArrayDeque<Int>()
    private var mPrev = -1
    private val mRows = ArrayList<TextView>()
    private val kvMove by lazy { Kv(b.kvMove) }
    private val mtButtons by lazy { listOf(b.mtF, b.mtB, b.mtL, b.mtR, b.mtAll) }
    private lateinit var spTurbo: SyncEdit
    private lateinit var spNav: SyncEdit
    private lateinit var spLoc: SyncEdit
    private val pidEdits = ArrayList<List<SyncEdit>>()    // baris 0..2 = roda, baris 3 = semua roda

    private val tick = object : Runnable {
        override fun run() {
            when (app.link.phase()) {
                LinkPhase.UP -> b.cWifi.chip("${app.link.activeName}: terhubung", R.color.omni_ok)
                LinkPhase.CONNECTING -> b.cWifi.chip("Menyambung", R.color.omni_warn)
                LinkPhase.DOWN -> b.cWifi.chip("Putus", R.color.omni_bad)
            }
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAdvancedBinding.inflate(layoutInflater)
        setContentView(b.root)
        fitSystemBars(b.root)
        b.bBack.setOnClickListener { finish() }
        setupApp()
        setupRoutes()
        setupWpEdit()
        setupMoveTest()
        setupSpeed()
        setupPid()
    }

    private fun edit(hint: String, type: Int, w: Int = 0, weight: Float = 1f) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setSingleLine()
        textSize = 14f
        importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        layoutParams = LinearLayout.LayoutParams(w, -2, weight)
    }

    private fun btn(label: String, go: Boolean = false, onClick: () -> Unit) = Button(this, null, 0, R.style.Btn).apply {
        text = label
        if (go) {
            setBackgroundResource(R.drawable.bg_btn_go)
            setTextColor(color(R.color.omni_white))
        }
        setOnClickListener { onClick() }
    }

    private val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED

    // ---- R9: rute tiap preset ----
    private fun setupRoutes() {
        for (r in 0..3) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(tv("Preset ${Labels.PRE[r]} (gamepad ${Labels.PADB[r]})", 14f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            val status = tv("", 12f, R.color.omni_mut)
            head.addView(status)
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val et = edit("mis. 1,2,3", InputType.TYPE_CLASS_TEXT)
            val run = btn("Jalankan", go = true) {
                st.send("preset", "i" to r)
                st.log("Preset ${Labels.PRE[r]}: jalan")
            }
            line.addView(et)
            line.addView(btn("Simpan") { saveRoute(r) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            line.addView(run, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            col.addView(head)
            col.addView(line)
            b.routeBox.addView(col)
            routeEdits.add(SyncEdit(et))
            routeStatus.add(status)
            routeRun.add(run)
        }
    }

    private fun saveRoute(r: Int) {
        val ids = routeEdits[r].e.text.toString().split(Regex("[^0-9]+"))
            .filter { it.isNotEmpty() }.map { it.toInt() }.filter { it in 1..Snap.MAX_WP }.take(Snap.MAX_WP)
        st.send("route_set", "r" to r, "ids" to JSONArray(ids))
        routeEdits[r].clean()
        st.log("Rute preset ${Labels.PRE[r]} dikirim: " + ids.joinToString(",").ifEmpty { "kosong" })
    }

    // ---- R9: edit waypoint (x, y, yaw), hapus, tanda ramp ----
    private fun setupWpEdit() {
        for (i in 0 until Snap.MAX_WP) {
            val id = i + 1
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            val line1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line1.addView(tv("WP$id", 14f, bold = true), LinearLayout.LayoutParams(dp(48), -2))
            val ex = edit("x", decimal); val ey = edit("y", decimal); val ea = edit("yaw", decimal)
            line1.addView(ex); line1.addView(ey); line1.addView(ea)
            val line2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val ramp = CheckBox(this).apply {
                text = "Ramp"
                textSize = 13f
                setOnClickListener { st.send("ramp_mark", "id" to id, "on" to isChecked) }
            }
            line2.addView(ramp, LinearLayout.LayoutParams(0, -2, 1f))
            line2.addView(btn("Simpan") { saveWp(i) })
            line2.addView(btn("✕") { st.send("wp_del", "id" to id) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            col.addView(line1)
            col.addView(line2)
            b.wpEditBox.addView(col)
            wpX.add(SyncEdit(ex)); wpY.add(SyncEdit(ey)); wpA.add(SyncEdit(ea))
        }
    }

    private fun saveWp(i: Int) {
        val x = wpX[i].e.text.toString().toFloatOrNull()
        val y = wpY[i].e.text.toString().toFloatOrNull()
        val a = wpA[i].e.text.toString().toFloatOrNull()
        if (x == null || y == null) {
            Toast.makeText(this, "Isi x dan y (cm).", Toast.LENGTH_SHORT).show()
            return
        }
        // yaw kosong = tanpa arah (robot tidak memutar badan di waypoint itu)
        if (a == null) st.send("wp_set", "id" to i + 1, "x" to x, "y" to y, "noyaw" to true)
        else st.send("wp_set", "id" to i + 1, "x" to x, "y" to y, "yaw" to a)
        wpX[i].clean(); wpY[i].clean(); wpA[i].clean()
    }

    // ---- R10: tes gerak (maju, mundur, kiri, kanan) ----
    private fun mTest(d: Int) {
        st.send("mtest", "dir" to d)
    }

    private fun setupMoveTest() {
        b.mtF.setOnClickListener { mTest(1) }
        b.mtB.setOnClickListener { mTest(2) }
        b.mtL.setOnClickListener { mTest(3) }
        b.mtR.setOnClickListener { mTest(4) }
        b.mtAll.setOnClickListener { mq.clear(); mq.addAll(listOf(2, 3, 4)); mTest(1) }
        for (d in 1..4) {
            val t = tv("", 13f)
            t.setPadding(0, dp(6), 0, dp(6))
            mRows.add(t)
            b.mTab.addView(t)
        }
    }

    private fun vcls(v: Int) = when (v) { 1 -> R.color.omni_ok; 0 -> R.color.omni_mut; 4, 5 -> R.color.omni_warn; else -> R.color.omni_bad }

    /** Diagnosa hasil tes: teks sama dengan diagnose() di monitor.html. */
    private fun diagnose(r: MRes): Pair<Int, String> {
        val o = r.o
        val i = r.i
        return when (o) {
            3 -> if (i == 1) R.color.omni_bad to "Robot bergerak (IMU setuju) tetapi encoder diam: cek kabel encoder"
            else R.color.omni_bad to "Robot tidak bergerak: cek daya baterai, kabel motor, driver"
            4 -> R.color.omni_warn to "Arah melenceng: roda tidak seimbang, ada slip, atau tanda kinematika salah"
            1 -> when (i) {
                1 -> R.color.omni_ok to "OK, odometri dan IMU searah"
                2 -> R.color.omni_warn to "Robot benar, IMU terbalik: ubah IMU_ACC_FWD_SIGN, IMU_ACC_RIGHT_SIGN, atau IMU_ACC_FWD_AXIS di config.h"
                0 -> R.color.omni_mut to "OK menurut odometri (IMU mati)"
                5 -> R.color.omni_warn to "OK menurut odometri, sinyal IMU lemah (kalibrasi akselerometer belum penuh?)"
                else -> R.color.omni_warn to "OK menurut odometri, IMU melenceng: cek pemasangan sumbu IMU"
            }
            2 -> when (i) {
                1 -> R.color.omni_bad to "Encoder terbalik: balik ENC_SIGN di config.h (robot sebenarnya bergerak benar)"
                2 -> R.color.omni_bad to "Robot bergerak terbalik (odometri dan IMU sepakat): balik MOTOR_SIGN dan ENC_SIGN semua roda bersamaan"
                else -> R.color.omni_bad to "Arah terbalik menurut odometri, IMU tidak bisa memastikan: cek MOTOR_SIGN dan ENC_SIGN"
            }
            else -> R.color.omni_mut to "-"
        }
    }

    private fun renderMove(s: Snap) {
        val sp = hypot(s.mvx, s.mvy)
        val lv = if (sp < 3f) "DIAM" else if (abs(s.mvy) >= abs(s.mvx)) (if (s.mvy > 0) "MAJU" else "MUNDUR") else (if (s.mvx > 0) "KANAN" else "KIRI")
        b.mLive.chip(lv, if (sp >= 3f) R.color.omni_acc else R.color.omni_mut)
        kvMove.set(
            listOf(
                "Kecepatan terukur x (kanan)" to fmt(s.mvx) + " cm/s",
                "Kecepatan terukur y (depan)" to fmt(s.mvy) + " cm/s",
                "Yaw" to fmt(s.yaw) + "°",
            )
        )
        val m = MRes(s)
        if (mPrev == -1) mPrev = m.st
        else if (m.st != mPrev) {
            when (m.st) {
                1 -> st.log("Tes gerak ${Labels.DIRS.at(m.dir)} mulai")
                2 -> {
                    mres[m.dir] = m
                    st.log("Tes gerak ${Labels.DIRS.at(m.dir)}: odometri ${Labels.VT.at(m.o)}, IMU ${Labels.VT.at(m.i)}")
                    if (mq.isNotEmpty()) { val d = mq.removeFirst(); ui.postDelayed({ mTest(d) }, 1200) }
                }
                3 -> {
                    mres[m.dir] = m
                    mq.clear()
                    st.log("Tes gerak ${Labels.DIRS.at(m.dir)} batal: ${Labels.WHY.at(m.why)}")
                }
            }
            mPrev = m.st
        }
        val busy = m.st == 1 || s.mode == 1 || s.mode == 4 || s.sf != 0
        mtButtons.forEach { it.enable(!busy) }
        when (m.st) {
            1 -> b.mStat.chip("berjalan: ${Labels.DIRS.at(m.dir)}", R.color.omni_warn)
            2 -> b.mStat.chip("selesai: ${Labels.DIRS.at(m.dir)}", R.color.omni_ok)
            3 -> b.mStat.chip("batal", R.color.omni_bad)
            else -> b.mStat.chip("siap", R.color.omni_mut)
        }
        val mut = color(R.color.omni_mut)
        for (d in 1..4) {
            val r = if (m.st == 1 && m.dir == d) m else mres[d]
            val name = Labels.DIRS.at(d)
            mRows[d - 1].setIfChanged(
                when {
                    r == null -> sp { put(name, bold = true); put("  belum dites", color = mut) }
                    r.st == 1 -> sp {
                        put(name, bold = true)
                        put("  sedang berjalan", color = color(R.color.omni_warn))
                        put("\nodometri ${fmt(r.dx)} / ${fmt(r.dy)}   IMU ${fmt(r.ix)} / ${fmt(r.iy)}", color = mut, size = 0.9f)
                    }
                    r.st == 3 -> sp { put(name, bold = true); put("  batal: " + Labels.WHY.at(r.why), color = color(R.color.omni_bad)) }
                    else -> {
                        val dg = diagnose(r)
                        sp {
                            put(name, bold = true)
                            put("\nOdometri x / y: ${fmt(r.dx)} / ${fmt(r.dy)}  ", size = 0.9f)
                            put(Labels.VT.at(r.o), color = color(vcls(r.o)), bold = true, size = 0.9f)
                            put("\nIMU x / y: " + (if (r.i != 0) "${fmt(r.ix)} / ${fmt(r.iy)}  " else "-  "), size = 0.9f)
                            put(if (r.i != 0) Labels.VT.at(r.i) else "IMU mati", color = color(vcls(r.i)), bold = true, size = 0.9f)
                            put(
                                "\nPutar: ${fmt(r.dyaw)}°" + (if (r.yi) "" else " (odometri)"),
                                color = if (abs(r.dyaw) > 10f) color(R.color.omni_warn) else null, size = 0.9f,
                            )
                            put("\n" + dg.second, color = color(dg.first), size = 0.9f)
                        }
                    }
                }
            )
        }
    }

    // ---- R10: kecepatan lanjutan (turbo / navigasi / jalan dashboard) ----
    private fun setupSpeed() {
        spTurbo = SyncEdit(b.etTurbo)
        spNav = SyncEdit(b.etNav)
        spLoc = SyncEdit(b.etLoc)
        b.bSaveSpeed.setOnClickListener {
            val t = b.etTurbo.text.toString().toIntOrNull()
            val n = b.etNav.text.toString().toIntOrNull()
            val l = b.etLoc.text.toString().toIntOrNull()
            if (t == null || n == null || l == null) {
                Toast.makeText(this, "Isi ketiga kecepatan (30-500 RPM).", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            st.send("speed", "turbo" to t.coerceIn(30, 500), "nav" to n.coerceIn(30, 500), "loc" to l.coerceIn(30, 500))
            spTurbo.clean(); spNav.clean(); spLoc.clean()
            st.log("Kecepatan turbo, navigasi, dan jalan dikirim")
        }
    }

    // ---- R10: PID per roda + auto-tune ----
    private fun plain(v: Float): String = BigDecimal(v.toString()).stripTrailingZeros().toPlainString()

    private fun setupPid() {
        for (row in 0..3) {
            val m = if (row == 3) -1 else row
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            col.addView(tv(if (m < 0) "Semua roda" else "Roda ${m + 1}", 14f, bold = true))
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val e = listOf(edit("Kp", decimal), edit("Ki", decimal), edit("Kd", decimal))
            e.forEach { line.addView(it) }
            val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            acts.addView(btn(if (m < 0) "Terapkan ke semua" else "Simpan") { savePid(m, row) })
            acts.addView(btn("Auto-tune" + (if (m < 0) " semua" else "")) { autoTune(m) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            col.addView(line)
            col.addView(acts)
            b.pidBox.addView(col)
            pidEdits.add(e.map { SyncEdit(it) })
        }
    }

    private fun savePid(m: Int, row: Int) {
        val v = pidEdits[row].map { it.e.text.toString().toFloatOrNull() }
        if (v.any { it == null }) {
            Toast.makeText(this, "Isi Kp, Ki, dan Kd.", Toast.LENGTH_SHORT).show()
            return
        }
        st.send("pid", "m" to m, "kp" to v[0], "ki" to v[1], "kd" to v[2])
        (if (m < 0) 0..2 else row..row).forEach { r -> pidEdits[r].forEach { it.clean() } }   // lalu isi ulang dari telemetri
        st.log("PID " + (if (m < 0) "semua roda" else "roda ${m + 1}") + " dikirim")
    }

    private fun autoTune(m: Int) {
        val what = if (m < 0) "semua roda" else "roda ${m + 1}"
        AlertDialog.Builder(this)
            .setMessage("Angkat $what dari lantai dulu. Motor akan berputar. Lanjut?")
            .setPositiveButton("Lanjut") { _, _ -> st.send("autotune", "m" to m) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun renderTune(s: Snap) {
        for (w in 0..2) {
            pidEdits[w][0].push(plain(s.kp[w]))
            pidEdits[w][1].push(plain(s.ki[w]))
            pidEdits[w][2].push(plain(s.kd[w]))
        }
        b.tuneChip.visibility = if (s.tune != 0) android.view.View.VISIBLE else android.view.View.GONE
        if (s.tune != 0) {
            b.tuneChip.chip(
                "auto-tune: " + Labels.TUNE.at(s.tune) + (if (s.tune == 3 && s.tw != 0) " - " + Labels.TW.at(s.tw) else ""),
                if (s.tune == 2) R.color.omni_ok else if (s.tune == 3) R.color.omni_bad else R.color.omni_warn,
            )
        }
        spTurbo.push(s.cs[1].toString())
        spNav.push(s.cs[2].toString())
        spLoc.push(s.cs[3].toString())
    }

    private fun render(s: Snap) {
        renderMove(s)
        renderTune(s)
        // rute
        for (r in 0..3) {
            routeEdits[r].push(s.rts[r].joinToString(","))
            val running = s.ps[r] == 2
            routeStatus[r].chip(
                if (running) "jalan" else if (s.rts[r].isNotEmpty()) "${s.rts[r].size} titik" else "kosong",
                if (running) R.color.omni_ok else R.color.omni_mut,
            )
            routeRun[r].enable(s.rts[r].isNotEmpty() && !s.ps.contains(2))
        }
        // waypoint: isi ulang kolom hanya saat data robot berubah (tidak menimpa ketikan)
        if (s.wpSig != wpSigShown) {
            wpSigShown = s.wpSig
            for (i in 0 until Snap.MAX_WP) {
                val v = s.wpValid(i)
                wpX[i].push(if (v) fmt(s.wp[i][1]) else "")
                wpY[i].push(if (v) fmt(s.wp[i][2]) else "")
                wpA[i].push(if (v && s.wpHasYaw(i)) fmt(s.wp[i][3]) else "")
            }
        }
    }

    // ---- R11: pengaturan app (host/path/port robot) ----
    private fun setupApp() {
        b.etHost.setText(app.host)
        b.etPath.setText(app.path)
        b.etPort.setText(app.port.toString())
        b.etBaud.setText(app.baud.toString())
        b.rgMode.check(when (app.mode) { LinkMode.WIFI -> R.id.rbWifi; LinkMode.USB -> R.id.rbUsb; else -> R.id.rbAuto })
        b.bSaveApp.setOnClickListener {
            val host = b.etHost.text.toString().trim()
            val path = b.etPath.text.toString().trim().ifEmpty { OmniApp.DEF_PATH }
            val port = b.etPort.text.toString().trim().toIntOrNull() ?: -1
            if (host.isEmpty() || host.any { it.isWhitespace() || it == '/' } || port !in 1..65535) {
                b.tvAppNote.text = "Host kosong/tidak valid atau port di luar 1-65535."
                return@setOnClickListener
            }
            val baud = b.etBaud.text.toString().trim().toIntOrNull() ?: -1
            if (baud !in 1200..3_000_000) {
                b.tvAppNote.text = "Baud tidak valid (1200-3000000)."
                return@setOnClickListener
            }
            val mode = when (b.rgMode.checkedRadioButtonId) { R.id.rbWifi -> LinkMode.WIFI; R.id.rbUsb -> LinkMode.USB; else -> LinkMode.AUTO }
            app.saveSettings(host, path, port, baud, mode)
            b.tvAppNote.text = "Tersimpan. Menyambung ke ${app.link.target}"
        }
    }

    override fun onStart() {
        super.onStart()
        app.acquire()
        st.addListener(onSnap)
        st.snap?.let { render(it) }
        tick.run()
    }

    override fun onStop() {
        ui.removeCallbacks(tick)
        st.removeListener(onSnap)
        app.release()
        super.onStop()
    }
}
