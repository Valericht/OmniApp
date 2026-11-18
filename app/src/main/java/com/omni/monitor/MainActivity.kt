package com.omni.monitor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.LinearLayout
import android.widget.TextView
import android.os.SystemClock
import kotlin.math.abs
import android.view.View
import com.omni.monitor.databinding.ActivityMainBinding
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var b: ActivityMainBinding
    private val app get() = application as OmniApp
    private val st get() = app.state
    private val onSnap: (Snap) -> Unit = { render(it) }
    private var shownEvents = -1
    private var wpAll = false
    private var wpKey = Int.MIN_VALUE
    private val presetButtons by lazy { listOf(b.bPre0, b.bPre1, b.bPre2, b.bPre3) }
    private var held = -1               // arah jog yang sedang ditahan, -1 = tidak ada
    private var recDirty = false        // robot baru digerakkan manual (untuk simpan otomatis saat STOP)
    private var seeking = false
    private val jogButtons by lazy { listOf(null, b.bJog1, b.bJog2, b.bJog3, b.bJog4, b.bJog5, b.bJog6) }
    private var badgeKey = ""
    private var safetyClickAt = 0L
    private val kvImu by lazy { Kv(b.kvImu) }
    private val kvUs by lazy { Kv(b.kvUs) }
    private val kvSafe by lazy { Kv(b.kvSafe) }
    private val kvAsst by lazy { Kv(b.kvAsst) }
    private val kvLink by lazy { Kv(b.kvLink) }
    private val kvWall by lazy { Kv(b.kvWall) }
    private val badSince = LongArray(3)
    private val revSince = LongArray(3)
    private val motors by lazy { listOf(b.motor1, b.motor2, b.motor3) }
    private val motorColors = intArrayOf(R.color.omni_motor1, R.color.omni_motor2, R.color.omni_motor3)
    private val ui = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            linkTick()
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        fitSystemBars(b.root)
        b.bAdv.setOnClickListener { startActivity(Intent(this, AdvancedActivity::class.java)) }
        b.bStop.setOnClickListener { doStop(false) }
        setupControls()
        b.rpmChart.state = st
        b.tvLegend.text = sp {
            put("M1", color = color(R.color.omni_motor1), bold = true)
            put("  ")
            put("M2", color = color(R.color.omni_motor2), bold = true)
            put("  ")
            put("M3", color = color(R.color.omni_motor3), bold = true)
            put("   garis tipis = target")
        }
        b.cbSafety.setOnClickListener {
            safetyClickAt = SystemClock.elapsedRealtime()
            st.send("safety", "on" to b.cbSafety.isChecked)
        }
        b.bResume.setOnClickListener { st.send("resume") }
        b.bZero.setOnClickListener { st.send("odom_reset"); st.clearTrail(); b.mapView.invalidate() }
        b.bClearTrail.setOnClickListener { st.clearTrail(); b.mapView.invalidate() }
    }

    override fun onStart() {
        super.onStart()
        app.acquire()
        st.addListener(onSnap)
        st.snap?.let { render(it) }
        tick.run()
    }

    override fun onPause() {
        jogEnd()            // lifecycle pause = jog 0, tidak ada tombol lengket
        super.onPause()
    }

    override fun onStop() {
        ui.removeCallbacks(tick)
        st.removeListener(onSnap)
        app.release()
        super.onStop()
    }

    /** Bilah atas: chip WiFi + latensi, banner "belum tersambung", kartu meredup saat putus. */
    private fun linkTick() {
        val link = app.link
        val phase = link.phase()
        when (phase) {
            LinkPhase.UP -> b.cWifi.chip("${link.activeName}: terhubung", R.color.omni_ok)
            LinkPhase.CONNECTING -> b.cWifi.chip("Menyambung", R.color.omni_warn)
            LinkPhase.DOWN -> b.cWifi.chip("Putus", R.color.omni_bad)
        }
        val lat = link.latencyMs
        b.cLat.setIfChanged(if (lat < 0f) "- ms" else "${lat.roundToInt()} ms")
        val up = phase == LinkPhase.UP
        b.banner.visibility = if (up) View.GONE else View.VISIBLE
        if (!up) {
            b.banner.setIfChanged(
                if (link.unreachable) link.hint()
                else "Belum tersambung ke robot. Menyambung ke ${link.target} ..."
            )
        }
        b.content.alpha = if (up) 1f else 0.45f
        renderEvents()
    }

    // ---- R8: kontrol manual ----
    private fun setupControls() {
        for (d in 1..6) {
            val btn = jogButtons[d] ?: continue
            btn.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; jogStart(d) }
                    MotionEvent.ACTION_UP -> { v.isPressed = false; v.performClick(); if (held == d) jogEnd() }
                    MotionEvent.ACTION_CANCEL -> { v.isPressed = false; if (held == d) jogEnd() }
                }
                true
            }
        }
        presetButtons.forEachIndexed { i, btn ->
            btn.setOnClickListener {
                st.send("preset", "i" to i)       // tekan pertama langsung jalan; STOP = batal
                st.log("Preset ${Labels.PRE[i]}: jalan")
            }
        }
        b.bWpMore.setOnClickListener { wpAll = !wpAll; wpKey = Int.MIN_VALUE; st.snap?.let { renderWaypoints(it) } }
        b.bPadStop.setOnClickListener { doStop(true) }
        b.bStopSave.setOnClickListener { doStop(true, force = true) }

        b.spJogWp.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            (1..Snap.MAX_WP).map { "WP$it" + (if (it <= 4) " (preset ${Labels.PRE[it - 1]})" else "") },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        b.sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) b.tvSpeed.text = "Kecepatan manual: ${snap10(progress)} RPM"
            }

            override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }

            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                val v = snap10(sb.progress)
                st.send("speed", "man" to v, "loc" to v)
                st.log("Kecepatan manual $v RPM")
            }
        })
    }

    private fun snap10(p: Int) = (Math.round(p / 10f) * 10).coerceIn(30, 500)

    private fun jogStart(d: Int) {
        if (held == d) return
        held = d
        recDirty = true
        st.send("jog", "d" to d)
    }

    private fun jogEnd() {
        if (held == -1) return
        held = -1
        st.send("jog", "d" to 0)
        ui.postDelayed({ if (held == -1) st.send("jog", "d" to 0) }, 80)   // ulang sekali: jaga dari frame hilang
    }

    /** STOP bilah bawah: hanya berhenti. STOP di pad / tombol simpan: berhenti lalu simpan posisi ke WP terpilih. */
    private fun doStop(save: Boolean, force: Boolean = false) {
        held = -1
        st.send("stop")
        val s = st.snap
        if (save && s != null && !s.pl && (force || (b.cbAutoSave.isChecked && recDirty))) {
            val id = b.spJogWp.selectedItemPosition + 1
            st.send("stop_save", "id" to id)
            st.log("STOP: posisi dan arah disimpan ke WP$id")
        }
        recDirty = false
    }

    /** R9: kartu preset A-D (status per preset, tombol jalan). */
    private fun renderPresets(s: Snap) {
        val anyRun = s.ps.contains(2)
        val mut = color(R.color.omni_mut)
        for (i in 0..3) {
            val st = s.ps[i]
            val rt = s.rts[i]
            val status = when {
                st == 2 -> when {
                    s.np == 2 -> "Memutar badan"
                    s.np == 3 -> "Verifikasi posisi"
                    s.av != 0 -> "Menghindar"
                    else -> "Jalan ke WP${s.tgt}"
                }
                rt.isNotEmpty() -> "Idle · " + rt.joinToString("→") { "WP$it" }
                else -> "Idle · kosong"
            }
            val run = st == 2
            val btn = presetButtons[i]
            val tc = if (run) color(R.color.omni_run_text) else color(R.color.omni_fg)
            btn.setIfChanged(
                sp {
                    put(Labels.PRE[i] + "\n", color = tc, bold = true, size = 1.5f)
                    put("gamepad ${Labels.PADB[i]}\n", color = if (run) tc else mut, size = 0.7f)
                    put(status, color = tc, size = 0.75f)
                }
            )
            btn.setBg(if (run) R.drawable.bg_btn_run else R.drawable.bg_btn)
            btn.enable(!(s.sf != 0 || s.jg != 0 || s.mode == 3 || (st == 0 && rt.isEmpty()) || (anyRun && !run)))
        }
        val r = s.ps.indexOf(2)
        if (r >= 0) b.preInfo.chip(
            when {
                s.np == 2 -> "ROTATING: memutar ke arah waypoint"
                s.np == 3 -> "VERIFY: mencocokkan bacaan dinding"
                s.av != 0 -> "AVOID: menghindar rintangan depan"
                else -> "MOVING: menuju titik"
            }, R.color.omni_ok,
        )
        else b.preInfo.chip("IDLE", R.color.omni_mut)
    }

    /** R9: tabel waypoint (x, y, yaw, dinding) dengan tombol Jalan dan Di sini. */
    private fun renderWaypoints(s: Snap) {
        val key = s.wpSig * 31 + (if (wpAll) 1 else 0)
        b.bWpMore.setIfChanged(if (wpAll) "Sembunyikan WP6 – WP${Snap.MAX_WP}" else "Tampilkan WP6 – WP${Snap.MAX_WP} ▾")
        b.bWpMore.visibility = View.VISIBLE
        if (key == wpKey) return
        wpKey = key
        val box = b.wpTable
        box.removeAllViews()
        val mut = color(R.color.omni_mut)
        for (i in 0 until (if (wpAll) Snap.MAX_WP else 5)) {
            val id = i + 1
            val w = s.wp[i]
            val used = (0..3).filter { s.rts[it].contains(id) }.joinToString("") { Labels.PRE[it] }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                if (s.tgt == id) setBackgroundResource(R.drawable.bg_tile)
            }
            val info = tv(
                sp {
                    put("WP$id", bold = true)
                    if (used.isNotEmpty()) put("  [$used]", color = mut, size = 0.85f)
                    put("\n")
                    if (s.wpValid(i)) {
                        put(
                            "x ${fmt(w[1])}  y ${fmt(w[2])}  yaw " + (if (s.wpHasYaw(i)) fmt(w[3]) + "°" else "-") +
                                (if (s.wpHasRef(i)) "\ndinding ${fmt(w[5])} / ${fmt(w[6])} / depan ${fmt(w[7])}" else "\ntanpa referensi"),
                            color = mut, size = 0.9f,
                        )
                    } else put("belum disimpan", color = mut, size = 0.9f)
                }, 14f,
            )
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val go = Button(this, null, 0, R.style.Btn).apply {
                text = "Jalan"
                setBackgroundResource(R.drawable.bg_btn_go)
                setTextColor(color(R.color.omni_white))
                enable(s.wpValid(i))
                setOnClickListener { st.send("goto", "id" to id) }
            }
            val here = Button(this, null, 0, R.style.Btn).apply {
                text = "Di sini"
                setOnClickListener { st.send("wp_here", "id" to id) }
            }
            val sync = Button(this, null, 0, R.style.Btn).apply {
                text = "Sinkron"                       // VERIFY manual: robot diam <= 30 cm dari WP, WP punya referensi dinding
                enable(s.wpValid(i) && s.wpHasRef(i))
                setOnClickListener { st.send("wp_sync", "id" to id) }
            }
            row.addView(go, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            row.addView(sync, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            row.addView(here, LinearLayout.LayoutParams(-2, -2))
            box.addView(row)
        }
    }

    /** Tombol jog: sorot arah aktif, nonaktif bila pengaman/otonom/tes gerak; slider ikut kecepatan robot. */
    private fun renderControls(s: Snap) {
        val locked = s.sf != 0 || s.mode == 1 || s.mode == 3
        for (d in 1..6) {
            val btn = jogButtons[d] ?: continue
            val on = s.jg == d
            btn.setBg(if (on) R.drawable.bg_btn_go else R.drawable.bg_btn)
            btn.setTextColor(color(if (on) R.color.omni_white else R.color.omni_fg))
            btn.enable(!locked)
            if (locked && held == d) held = -1       // tombol dinonaktifkan saat ditahan: jangan lengket
        }
        if (s.jg != 0) b.jgStat.chip("jalan: ${Labels.JOGN.at(s.jg)} (${s.cs[3]} RPM)", R.color.omni_warn)
        else b.jgStat.chip("diam", R.color.omni_mut)
        if (!seeking) {
            val v = s.cs[0].coerceIn(30, 500)
            if (b.sbSpeed.progress != v) b.sbSpeed.progress = v
            b.tvSpeed.setIfChanged("Kecepatan manual: $v RPM")
        }
    }

    /** R4: bilah atas, alert pengaman, kartu Kejadian. */
    private fun render(s: Snap) {
        b.cMode.chip(
            "Mode " + Labels.MODES.at(s.mode) + (if (s.tgt != 0) " ke WP${s.tgt}" else "") +
                (if (s.mode == 4) ": " + Labels.JOGN.at(s.jg) else "") + (if (s.tb) " TURBO" else ""),
            if (s.sf != 0) R.color.omni_bad else R.color.omni_fg,
        )
        b.cPad.chip("Gamepad " + (if (s.pad) s.pt else "-"), if (s.pad) R.color.omni_ok else R.color.omni_bad)

        b.aSafe.visibility = if (s.sf != 0) View.VISIBLE else View.GONE
        if (s.sf != 0) {
            b.tvSafe.setIfChanged(
                sp {
                    put("ROBOT DIBERHENTIKAN PENGAMAN", bold = true)
                    put("\n" + Labels.SF.at(s.sf) + ". Periksa robot dulu. Di gamepad: tekan Start.")
                }
            )
        }
        renderPosition(s)
        renderMotors(s)
        renderWall(s)
        renderSensors(s)
        renderAssist(s)
        renderLinks(s)
        renderControls(s)
        renderPresets(s)
        renderWaypoints(s)
        renderEvents()
    }

    private fun col(id: Int) = color(id)

    /** R7: IMU, ultrasonik, pengaman. */
    private fun renderSensors(s: Snap) {
        // IMU
        if (s.imu) {
            val c = s.ic
            kvImu.set(
                listOf(
                    "Alamat" to "0x" + s.ia.toString(16),
                    "Yaw" to fmt(s.yaw) + "°",
                    "Mode" to (if (s.ndof) "NDOF" else "IMU"),
                    "Kalibrasi" to "SYS ${c shr 6 and 3}, GYR ${c shr 4 and 3}, ACC ${c shr 2 and 3}, MAG ${c and 3}",
                    "Pitch / Roll" to fmt(s.pitch) + "° / " + fmt(s.roll) + "°",
                    "Medan" to s.terrain.ifEmpty { "-" },
                )
            )
            b.tvImuNote.visibility = View.GONE
        } else {
            kvImu.set(listOf("Status" to sp { put("tidak terdeteksi", color = col(R.color.omni_bad), bold = true) }))
            b.tvImuNote.visibility = View.VISIBLE
            b.tvImuNote.setIfChanged("Cek 3V3, SDA GPIO8, SCL GPIO9. Heading lock dan putar ke arah waypoint mati.")
        }

        // ultrasonik: Depan / Kiri / Kanan (999 atau tidak valid = tak terbaca)
        val names = arrayOf("Depan", "Kiri", "Kanan")
        kvUs.set(
            (0..2).map { i ->
                val v = s.us[i]
                val label = "${names[i]} (${fmt(s.sa[i])}°)"
                label to if (v.isNaN()) sp { put("tak terbaca", color = col(R.color.omni_mut)) }
                else sp { put(Math.round(v).toString() + " cm", color = if (v < 25f) col(R.color.omni_bad) else null) }
            }
        )
        val near = s.us.filter { !it.isNaN() }.minOrNull()
        b.tvUsHint.setIfChanged(if (near == null) "Tidak ada rintangan terdeteksi" else "Rintangan terdekat ${Math.round(near)} cm")

        // pengaman
        if (SystemClock.elapsedRealtime() - safetyClickAt > 700) b.cbSafety.isChecked = s.so
        if (s.sf != 0) b.safeChip.chip("TERKUNCI", R.color.omni_bad)
        else if (s.so) b.safeChip.chip("normal", R.color.omni_ok)
        else b.safeChip.chip("MATI", R.color.omni_warn)
        kvSafe.set(
            listOf(
                "Duty terbesar" to fmt(s.sfv[3]) + " %",
                "Pembatas daya" to
                    (if (s.sat < 100) sp { put("aktif ${s.sat} %", color = col(R.color.omni_warn)) } else "tidak aktif"),
                "Gyro" to fmt(s.sfv[2]) + " °/s",
                "Baterai" to (if (s.bat < 0f) "tidak dipasang" else fmt(s.bat) + " V"),
                "Detail sfv" to s.sfv.joinToString(" / ") { fmt(it) },
            )
        )
    }

    /** R7: medan, selip, PID yaw, kepercayaan encoder. */
    private fun renderAssist(s: Snap) {
        if (!s.hasAssist) {
            kvAsst.set(listOf("Asisten IMU" to "data tidak tersedia"))
            b.barTrust.visibility = View.GONE
            return
        }
        val key = "${s.terrain}|${s.stuck}|${s.slip}|${s.poseDegraded}"
        if (key != badgeKey) {
            badgeKey = key
            b.rowAsst.removeAllViews()
            val tc = when (s.terrain) { "FLAT" -> R.color.omni_badge_g; "SLOPE" -> R.color.omni_badge_y; else -> R.color.omni_badge_r }
            addBadge(s.terrain.ifEmpty { "-" }, tc)
            if (s.stuck) addBadge("STUCK", R.color.omni_badge_r)
            if (s.slip) addBadge("SLIP", R.color.omni_badge_r)
            if (s.poseDegraded) addBadge("POSE TIDAK PASTI", R.color.omni_badge_y)
        }
        val tr = s.encTrust.coerceIn(0f, 1f)
        kvAsst.set(
            listOf(
                "Pitch / Roll" to fmt(s.pitch) + "° / " + fmt(s.roll) + "°",
                "Galat yaw" to fmt(s.yawError) + "°",
                "Kepercayaan encoder" to Math.round(tr * 100).toString() + " %",
                "PID yaw" to "Kp ${fmt(s.yawKp, 2)} · Ki ${fmt(s.yawKi, 2)} · Kd ${fmt(s.yawKd, 2)}",
                "Auto-tune" to sp {
                    put(s.yawStatus.ifEmpty { "-" }, color = col(if (s.yawStatus == "DONE") R.color.omni_ok else R.color.omni_warn))
                },
            )
        )
        b.barTrust.visibility = View.VISIBLE
        b.barTrust.set(tr, col(if (tr < 0.3f) R.color.omni_bad else if (tr < 1f) R.color.omni_warn else R.color.omni_ok), false)
    }

    private fun addBadge(t: String, colorRes: Int) {
        val v = TextView(this).apply {
            text = t
            textSize = 12f
            setTextColor(col(R.color.omni_white))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(3), dp(12), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(99).toFloat()
                setColor(col(colorRes))
            }
        }
        b.rowAsst.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
    }

    /** Kartu Sambungan: status tiap bagian robot (padanan topologi di dashboard). */
    private fun renderLinks(s: Snap) {
        fun node(t: String, c: Int) = sp { put(t, color = col(c), bold = true) }
        val move = s.rpm.map { abs(it) >= 1f }
        val us = s.us.joinToString(" / ") { if (it.isNaN()) "-" else Math.round(it).toString() }
        kvLink.set(
            listOf(
                "Gamepad" to (if (s.pad) node(s.pt + " tersambung", R.color.omni_ok) else node("tidak terdeteksi", R.color.omni_bad)),
                "Master ESP32-S3" to node("AP Omni3X · batas ${s.spd} RPM", R.color.omni_ok),
                "Motor 1" to node("${Math.round(s.rpm[0])} RPM", if (move[0]) R.color.omni_ok else R.color.omni_warn),
                "Motor 2" to node("${Math.round(s.rpm[1])} RPM", if (move[1]) R.color.omni_ok else R.color.omni_warn),
                "Motor 3" to node("${Math.round(s.rpm[2])} RPM", if (move[2]) R.color.omni_ok else R.color.omni_warn),
                "IMU BNO055" to (if (s.imu) node("0x" + s.ia.toString(16) + " · yaw " + Math.round(s.yaw) + "°", R.color.omni_ok)
                else node("tidak terdeteksi", R.color.omni_bad)),
                "Ultrasonik ×3" to node("$us cm", if (s.us.any { !it.isNaN() }) R.color.omni_ok else R.color.omni_warn),
            )
        )
    }

    /** R6: tiga kartu motor + grafik RPM. */
    private fun renderMotors(s: Snap) {
        val now = SystemClock.elapsedRealtime()
        for (i in 0..2) {
            val m = motors[i]
            val c = color(motorColors[i])
            m.tvTitle.setIfChanged(sp { put("● ", color = c); put("MOTOR ${i + 1}") })
            m.tvRpm.setIfChanged(Math.round(s.rpm[i]).toString())
            m.tvSp.setIfChanged("RPM · target ${Math.round(s.sp[i])}")
            m.bar.set(s.rpm[i] / 500f, c, true)
            val (lv, tx) = diag(i, s, now)
            m.tvDiag.setIfChanged("● $tx")
            m.tvDiag.setTextColor(color(lv))
        }
        b.rpmChart.invalidate()
    }

    /** Diagnosa roda: sama dengan diag(i) di monitor.html (RPM 0 atau arah terbalik bertahan > 1,5 dtk). */
    private fun diag(i: Int, s: Snap, now: Long): Pair<Int, String> {
        val sp = s.sp[i]
        val r = s.rpm[i]
        if (s.tune == 1) return R.color.omni_warn to "auto-tune berjalan"
        if (abs(sp) >= 15f && abs(r) < 2f) {
            if (badSince[i] == 0L) badSince[i] = now
            if (now - badSince[i] > 1500) return R.color.omni_bad to "target ada tapi RPM 0: cek kabel motor atau encoder"
        } else badSince[i] = 0L
        if (abs(sp) >= 15f && abs(r) >= 5f && r * sp < 0f) {
            if (revSince[i] == 0L) revSince[i] = now
            if (now - revSince[i] > 1500) return R.color.omni_bad to "arah terbalik: cek MOTOR_SIGN dan ENC_SIGN"
        } else revSince[i] = 0L
        return R.color.omni_ok to (if (abs(sp) >= 1f) "berputar normal" else "siap")
    }

    /** R6: koreksi dinding (terukur vs referensi waypoint aktif). */
    private fun renderWall(s: Snap) {
        fun meas(ok: Boolean, v: Float) = if (ok) Math.round(v).toString() else "-"
        fun ref(v: Float) = if (v > 0f) Math.round(v).toString() else "-"
        val stat = Labels.WST.at(s.wstat).ifEmpty { "-" }
        val sc = if (s.wstat == 1) R.color.omni_ok else if (s.wstat != 0) R.color.omni_bad else R.color.omni_fg
        kvWall.set(
            listOf(
                "Kiri ukur / ref" to "${meas(s.wlOk, s.wl)} / ${ref(s.wlr)} cm",
                "Kanan ukur / ref" to "${meas(s.wrOk, s.wr)} / ${ref(s.wrr)} cm",
                "Status" to sp { put(stat, color = color(sc)) },
                "Retry leg" to s.rty.toString(),
                "Referensi" to sp { if (s.rst) put("tidak cocok, rekam ulang", color = color(R.color.omni_bad)) else put("-") },
                "Kecepatan efektif" to "batas ${s.spd} → sat ${s.sat}% → lereng ${fmt(s.slf, 2)} → hindar ${fmt(s.avoidScale, 2)} → ${fmt(s.re)} RPM",
                "Jarak sisi terkecil" to sp {
                    put(if (s.smin < 0f) "-" else fmt(s.smin) + " cm", color = color(if (s.smin in 0f..7.99f) R.color.omni_bad else R.color.omni_fg))
                },
                "Tiba terakhir" to (if (s.arrQ != 0) s.arrText() else "-"),
                "Sesi tiba / gagal / retry / koreksi" to s.ss.joinToString(" / "),
            )
        )
    }

    /** R5: peta, tile posisi, peringatan posisi tidak valid / dipulihkan (di bawah peta). */
    private fun renderPosition(s: Snap) {
        b.mapView.snap = s
        b.mapView.trail = st.trail
        b.mapView.invalidate()
        val mut = color(R.color.omni_mut)
        fun tile(label: String, value: String) = sp {
            put(label + "\n", color = mut, size = 0.8f)
            put(value, bold = true, size = 1.15f)
        }
        b.tPos0.setIfChanged(tile("Posisi x", fmt(s.x) + " cm"))
        b.tPos1.setIfChanged(tile("Posisi y", fmt(s.y) + " cm"))
        b.tPos2.setIfChanged(tile("Yaw", fmt(s.yaw) + "°"))
        b.tPos3.setIfChanged(tile("Target", if (s.tgt != 0) "WP${s.tgt}" + (if (s.np == 2) " · putar" else if (s.np == 3) " · verifikasi" else "") else "-"))
        b.tPos4.setIfChanged(tile("Kecepatan badan", fmt(s.vx) + " / " + fmt(s.vy)))
        b.tPos5.setIfChanged(tile("Putaran", fmt(s.wz, 2) + " rad/s"))
        b.tvTrail.setIfChanged(
            "Jejak X, Y, Yaw: ${st.trail.size} titik (direkam saat robot digerakkan manual). " +
                "Hijau = jejak, garis pendek di WP = arah hadap."
        )
        b.tvPoseWarn.visibility = if (s.pl || s.pr) View.VISIBLE else View.GONE
        if (s.pl) {
            b.tvPoseWarn.setTextColor(color(R.color.omni_bad))
            b.tvPoseWarn.setIfChanged(
                "Posisi tidak valid. Robot terdeteksi diangkat atau dipindah, atau IMU ter-reset. " +
                    "Letakkan robot di titik 0 dengan arah hadap seperti saat waypoint direkam, lalu tekan " +
                    "Set titik 0. Preset dan navigasi ditolak sampai titik 0 ditetapkan."
            )
        } else if (s.pr) {
            b.tvPoseWarn.setTextColor(color(R.color.omni_warn))
            b.tvPoseWarn.setIfChanged(
                "Posisi dipulihkan dari memori. Benar hanya bila robot tidak dipindah dan menghadap arah yang " +
                    "sama saat dimatikan. Bila ragu, letakkan di titik 0 lalu tekan Set titik 0."
            )
        }
    }

    private fun renderEvents() {
        if (shownEvents == st.eventsVersion) return
        shownEvents = st.eventsVersion
        val mut = color(R.color.omni_mut)
        b.tvEvents.text = if (st.events.isEmpty()) "-" else sp {
            st.events.forEachIndexed { i, e ->
                if (i > 0) append("\n")
                put(e.first + "  ", color = mut)
                put(e.second)
            }
        }
    }
}
