package id.bram.cincinlangkah

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class ProgramActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, ProgramActivity::class.java))
        val WEIGHT_PERM = HealthPermission.getReadPermission(WeightRecord::class)
    }

    private val ID = Locale("id", "ID")
    private val night by lazy {
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    private val pal by lazy { Pal(night) }
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).roundToInt()
    private lateinit var content: LinearLayout
    private var editing = false

    private val weightPerm = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { g ->
        if (g.contains(WEIGHT_PERM)) { toast("Berat dari Health Connect diizinkan"); load() }
        else toast("Izin berat belum diberikan")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = pal.bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(14), px(8), px(14), px(28)); setBackgroundColor(pal.bg)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "‹"; textSize = 28f; setTextColor(pal.ring); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(px(36), px(44)); setOnClickListener { finish() }
        })
        head.addView(TextView(this).apply {
            text = "🎯 Program turun berat"; textSize = 19f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(8) })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(pal.bg) })
    }

    override fun onResume() { super.onResume(); load() }

    // ---------------- data ----------------

    private class Data(
        val weights: List<Pair<Long, Double>>, val steps: Double?, val exMin: Double?,
        val hcGranted: Boolean, val hcAvail: Boolean,
    )

    private fun load() {
        lifecycleScope.launch {
            val c = Program.cfg(this@ProgramActivity)
            var granted = false
            val avail = HcSteps.available(this@ProgramActivity)
            if (avail) try {
                granted = HealthConnectClient.getOrCreate(this@ProgramActivity).permissionController
                    .getGrantedPermissions().contains(WEIGHT_PERM)
                if (granted) importHc()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
            var steps: Double? = null; var ex: Double? = null
            if (c.active) try {
                Daily.clearToday()
                steps = Daily.value(this@ProgramActivity, Metric.STEPS, LocalDate.now()).v
                ex = Daily.value(this@ProgramActivity, Metric.EXERCISE, LocalDate.now()).v
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
            val ws = withContext(Dispatchers.IO) { Db.get(this@ProgramActivity).weights(0, Long.MAX_VALUE) }
            render(Data(ws, steps, ex, granted, avail))
        }
    }

    /** Ambil berat dari Health Connect (timbangan pintar, Samsung Health, Fitbit, dll). */
    private suspend fun importHc() {
        val client = HealthConnectClient.getOrCreate(this)
        var token: String? = null
        val db = Db.get(this)
        var n = 0
        do {
            val r = client.readRecords(ReadRecordsRequest(WeightRecord::class,
                TimeRangeFilter.after(Instant.now().minus(400, ChronoUnit.DAYS)), pageToken = token))
            withContext(Dispatchers.IO) {
                for (w in r.records) { db.putWeight(w.time.toEpochMilli(), w.weight.inKilograms, w.metadata.dataOrigin.packageName); n++ }
            }
            token = r.pageToken
        } while (!token.isNullOrEmpty())
        if (n > 0) syncBerat()
    }

    /** Berat untuk perkiraan kalori olahraga di dashboard = berat terakhir. */
    private fun syncBerat() {
        Db.get(this).latestWeight(Long.MAX_VALUE)?.let {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putInt("berat", it.second.roundToInt()).apply()
        }
    }

    // ---------------- tampilan ----------------

    private fun render(d: Data) {
        content.removeAllViews()
        val c = Program.cfg(this)
        if (!c.active || editing) renderSetup(c) else renderActive(c, d)
    }

    private fun renderSetup(c: Program.Cfg) {
        val card = card()
        card.addView(title(if (editing) "Ubah data & target" else "Data diri & target"))
        val r1 = row(); val r2 = row(); val r3 = row()
        card.addView(r1); card.addView(r2); card.addView(r3)
        val eW0 = field(r1, "Berat sekarang (kg)", fmt1(if (c.active) Program.currentW(this, c) else c.w0), true)
        val eWT = field(r1, "Target (kg)", fmt1(c.wT), true, last = true)
        val eMo = field(r2, "Durasi (bulan)", c.months.toString(), false)
        val eH = field(r2, "Tinggi (cm)", c.heightCm.toString(), false, last = true)
        val eB = field(r3, "Tanggal lahir (dd-mm-yyyy)", c.birth.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")), false, text = true)
        val sexBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        sexBox.addView(note("Jenis kelamin"))
        val sexRow = row()
        sexBox.addView(sexRow)
        r3.addView(sexBox)
        var male = c.male
        val bL = chip("Pria") {}; val bP = chip("Wanita") {}
        sexRow.addView(bL); sexRow.addView(bP)

        val preview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(preview)

        fun current(): Program.Cfg? {
            val w0 = num(eW0) ?: return null; val wT = num(eWT) ?: return null
            val mo = eMo.text.toString().toIntOrNull() ?: return null
            val h = eH.text.toString().toIntOrNull() ?: return null
            val b = try { LocalDate.parse(eB.text.toString().trim(), DateTimeFormatter.ofPattern("d-M-yyyy")) } catch (_: Exception) { return null }
            if (w0 !in 30.0..300.0 || wT !in 30.0..300.0 || mo !in 1..24 || h !in 100..230) return null
            return Program.Cfg(c.active, if (c.active) c.start else LocalDate.now(),
                if (c.active) c.w0 else w0, wT, mo, h, b, male, c.remind)
        }
        fun update() {
            styleChip(bL, male); styleChip(bP, !male)
            preview.removeAllViews()
            val cc = current() ?: run { preview.addView(note("Lengkapi data di atas.")); return }
            val w = num(eW0)!!
            renderPreview(preview, cc, w)
        }
        bL.setOnClickListener { male = true; update() }
        bP.setOnClickListener { male = false; update() }
        val tw = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
            override fun afterTextChanged(s: Editable?) { update() }
        }
        listOf(eW0, eWT, eMo, eH, eB).forEach { it.addTextChangedListener(tw) }
        update()

        content.addView(bigButton(if (editing) "Simpan perubahan" else "▶  Mulai program hari ini", true) {
            val cc = current() ?: run { toast("Periksa lagi isian datanya"); return@bigButton }
            val w = num(eW0)!!
            val nc = Program.Cfg(true, cc.start, cc.w0, cc.wT, cc.months, cc.heightCm, cc.birth, cc.male, cc.remind)
            Program.save(this, nc)
            val db = Db.get(this)
            val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val last = db.latestWeight(Long.MAX_VALUE)
            if (last == null || last.first < todayStart || abs(last.second - w) > 0.05) db.putWeight(System.currentTimeMillis(), w, "manual")
            syncBerat()
            editing = false
            toast(if (cc.active) "Tersimpan" else "Program dimulai — semangat!")
            load()
        })
        if (editing) content.addView(bigButton("Batal", false) { editing = false; load() })
    }

    private fun renderPreview(box: LinearLayout, c: Program.Cfg, w: Double) {
        val p = Program.plan(c, w, if (c.active) LocalDate.now() else c.start)
        val bmi = Program.bmi(w, c.heightCm)
        val cls = when {
            bmi >= 40 -> "obesitas kelas III"; bmi >= 35 -> "obesitas kelas II"; bmi >= 30 -> "obesitas kelas I"
            bmi >= 25 -> "berlebih"; bmi >= 18.5 -> "normal"; else -> "kurang"
        }
        val bmiT = Program.bmi(c.wT, c.heightCm)
        box.addView(stat("Usia ${p.age} th · BMI ${fmt1(bmi)} ($cls) → target BMI ${fmt1(bmiT)}"))
        box.addView(stat("Kebutuhan tanpa olahraga ±${n(p.tdee)} kkal/hari"))
        box.addView(stat("🍽 Makan ±${n(p.intake)} kkal/hari · 🥚 protein ±${p.protein} g"))
        box.addView(stat("🔥 Defisit ±${n(p.deficit)} kkal/hari ≈ ${fmt2(p.weeklyLoss)} kg/minggu"))
        val need = (w - c.wT) / max(1.0, ChronoUnit.DAYS.between(p.date, c.end) / 7.0)
        box.addView(stat("Butuh ${fmt2(need)} kg/minggu untuk tepat ${fmt1(c.wT)} kg pada ${dfmt(c.end)}"))
        val ok = p.projEnd <= c.wT + 0.5
        box.addView(TextView(this).apply {
            text = (if (ok) "✅ " else "⚠ ") + "Perkiraan di akhir program: ${fmt1(p.projEnd)} kg" +
                if (!ok) {
                    val why = if (p.floorHit) "asupan sudah di batas minimum ${Program.floorKcal(c.male)} kkal"
                        else if (p.capHit) "laju dibatasi maks 1% berat/minggu supaya otot tidak ikut hilang" else ""
                    "\nTarget ${fmt1(c.wT)} kg sedikit terlalu cepat ($why). Perkiraan tercapai sekitar ${reachText(c, w, p)}. " +
                        "Program tetap jalan; tiap timbang angka dihitung ulang. Turun lebih cepat di awal (air) itu normal."
                } else ""
            textSize = 14f; setTextColor(if (ok) pal.ring else pal.bp); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, px(8), 0, 0)
        })
        box.addView(note("⚕ Dengan BMI di atas 35 dan usia di atas 50, sebaiknya cek ke dokter dulu sebelum mulai " +
            "(tensi, gula darah, kolesterol, kondisi lutut), terutama kalau minum obat rutin — obat diabetes/tensi " +
            "sering perlu disesuaikan saat berat turun."))
    }

    /** Simulasi kapan target tercapai dengan asupan & fase yang sama, sampai 2 tahun. */
    private fun reachText(c: Program.Cfg, w0: Double, p: Program.Plan): String {
        var w = w0; var d = p.date
        repeat(104) {
            val wk = (ChronoUnit.DAYS.between(c.start, d) / 7 + 1).toInt()
            val out = Program.bmr(w, c.heightCm, p.age, c.male) * 1.2 + Program.phaseOf(wk).activeKcal
            w -= max(0.0, out - p.intake) * 7 / 7700
            d = d.plusDays(7)
            if (w <= c.wT) return dfmt(d)
        }
        return "lebih dari 2 tahun"
    }

    private fun renderActive(c: Program.Cfg, d: Data) {
        val today = LocalDate.now()
        val p = Program.plan(c, Program.currentW(this, c, today), today)

        // ===== ringkasan berat =====
        val s = card()
        s.addView(note(if (p.done) "Program selesai · ${dfmt(c.start)} – ${dfmt(c.end)}"
            else "Minggu ${p.week} dari ${c.totalWeeks} · hari ke-${p.day} · fase ${p.phase.name}").apply { setPadding(0, 0, 0, 0) })
        s.addView(TextView(this).apply {
            text = "${fmt1(p.curW)} kg"; textSize = 30f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD
        })
        val lost = c.w0 - p.curW
        val diff = p.curW - p.lineW
        s.addView(stat((if (lost >= 0) "turun ${fmt1(lost)} kg" else "naik ${fmt1(-lost)} kg") + " dari ${fmt1(c.w0)} · sisa ${fmt1(max(0.0, p.curW - c.wT))} kg"))
        s.addView(TextView(this).apply {
            text = when {
                diff <= -0.3 -> "✅ ${fmt1(-diff)} kg lebih cepat dari garis target (${fmt1(p.lineW)})"
                diff <= 0.5 -> "✅ Sesuai garis target (${fmt1(p.lineW)})"
                else -> "⚠ ${fmt1(diff)} kg di atas garis target (${fmt1(p.lineW)}) — targetnya sudah disesuaikan"
            }
            textSize = 13f; setTextColor(if (diff <= 0.5) pal.ring else pal.bp); typeface = Typeface.DEFAULT_BOLD
        })
        s.addView(WeightChartView(this, pal).apply {
            start = c.start; end = c.end; w0 = c.w0; wT = c.wT; pts = d.weights.filter { it.first >= c.start.minusDays(14).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            if (!p.done) { projFrom = today to p.curW; projEnd = p.projEnd }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(8) }
        })
        s.addView(legend(listOf(pal.ring to "Berat", pal.muted to "Garis target", pal.awake to "Perkiraan")))
        s.addView(note("Perkiraan di akhir (${dfmt(c.end)}): ${fmt1(p.projEnd)} kg"))

        val wr = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(8), 0, 0) }
        val eW = EditText(this).apply {
            hint = "berat hari ini (kg)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(pal.fg); setHintTextColor(pal.muted); textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        wr.addView(eW)
        wr.addView(smallBtn("⚖ Simpan") {
            val v = num(eW)
            if (v == null || v !in 30.0..300.0) { toast("Isi berat dalam kg, misal 118,4"); return@smallBtn }
            Db.get(this).putWeight(System.currentTimeMillis(), v, "manual"); syncBerat()
            toast("Tersimpan ${fmt1(v)} kg"); load()
        })
        s.addView(wr)
        if (d.hcAvail && !d.hcGranted) s.addView(linkText("Pakai timbangan pintar? Ambil berat dari Health Connect ›") {
            weightPerm.launch(setOf(WEIGHT_PERM))
        })
        val recent = d.weights.takeLast(5).reversed()
        if (recent.isNotEmpty()) {
            val tf = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", ID)
            s.addView(note("Catatan terakhir (tekan lama untuk hapus):"))
            for (w in recent) s.addView(TextView(this).apply {
                text = "• ${Instant.ofEpochMilli(w.first).atZone(ZoneId.systemDefault()).format(tf)}  —  ${fmt1(w.second)} kg"
                textSize = 13f; setTextColor(pal.fg); setPadding(0, px(3), 0, px(3))
                setOnLongClickListener {
                    AlertDialog.Builder(this@ProgramActivity).setMessage("Hapus catatan ${fmt1(w.second)} kg?")
                        .setPositiveButton("Hapus") { _, _ -> Db.get(this@ProgramActivity).deleteWeight(w.first); syncBerat(); load() }
                        .setNegativeButton("Batal", null).show()
                    true
                }
            })
        }
        if (p.done) { renderSettings(c); return }

        // ===== hari ini =====
        val t = card(Color.parseColor("#2F80ED"))
        val dayName = today.dayOfWeek.getDisplayName(TextStyle.FULL, ID)
        t.addView(note("Hari ini · $dayName").apply { setPadding(0, 0, 0, 0) })
        t.addView(TextView(this).apply {
            text = "${p.today.emoji}  ${p.today.name}" + if (p.today.rest) "" else " · ${p.today.minutes} menit"
            textSize = 20f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD
        })
        t.addView(note(p.today.desc))
        fun prog(label: String, v: Double?, goal: Int, unit: String): TextView {
            val x = v ?: 0.0
            return stat("$label ${n(x.roundToInt())} / ${n(goal)} $unit" + if (x >= goal) "  ✅" else "")
        }
        t.addView(prog("👟 Langkah", d.steps, p.steps, ""))
        t.addView(prog("⏱ Olahraga", d.exMin, p.exMin, "menit"))
        t.addView(stat("🍽 Makan ±${n(p.intake)} kkal  ·  🥚 protein ±${p.protein} g"))
        val mr = row().apply { setPadding(0, px(8), 0, 0) }
        mr.addView(smallBtn("💪 Kekuatan 20'") { addStrength(20) })
        mr.addView(smallBtn("💪 Kekuatan 30'") { addStrength(30) })
        t.addView(mr)
        t.addView(note("Jalan, lari & pingpong terekam otomatis. Latihan kekuatan di rumah dicatat dengan tombol di atas."))

        // ===== checklist makan =====
        val k = card(Color.parseColor("#FF9F1C"))
        val cnt = Program.checkCount(this, today)
        val kt = title("Makan hari ini · $cnt/${Program.CHECKS.size}")
        k.addView(kt)
        val bits = Program.checks(this, today)
        Program.CHECKS.forEachIndexed { i, label ->
            k.addView(CheckBox(this).apply {
                text = label; textSize = 14f; setTextColor(pal.fg); isChecked = bits and (1 shl i) != 0
                setOnCheckedChangeListener { _, on ->
                    Program.setCheck(this@ProgramActivity, today, i, on)
                    kt.text = "Makan hari ini · ${Program.checkCount(this@ProgramActivity, today)}/${Program.CHECKS.size}"
                }
            })
        }

        // ===== jadwal minggu ini =====
        val j = card()
        j.addView(title("Jadwal minggu ini · ${p.phase.name}"))
        j.addView(note(p.phase.goal).apply { setPadding(0, 0, 0, px(6)) })
        val mon = today.minusDays((today.dayOfWeek.value - 1).toLong())
        for (i in 0..6) {
            val dd = mon.plusDays(i.toLong())
            val ses = p.phase.week[i]
            val isT = dd == today
            j.addView(TextView(this).apply {
                text = "${dd.dayOfWeek.getDisplayName(TextStyle.SHORT, ID)}   ${ses.emoji} ${ses.name}" +
                    if (ses.rest) "" else " · ${ses.minutes}'"
                textSize = 14f; setTextColor(if (isT) pal.ring else pal.fg)
                typeface = if (isT) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(0, px(4), 0, px(4))
                setOnClickListener {
                    AlertDialog.Builder(this@ProgramActivity).setTitle("${ses.emoji} ${ses.name}").setMessage(ses.desc)
                        .setPositiveButton("OK", null).show()
                }
            })
        }
        j.addView(note("Ketuk jadwal untuk melihat isinya. Hari bisa ditukar sesuai jadwal kerja/pingpong — yang penting total seminggu."))

        // ===== panduan makan =====
        val g = card()
        g.addView(title("Panduan makan (±${n(p.intake)} kkal)"))
        g.addView(body("Piring tiap makan: ½ sayur · ¼ lauk protein (sebesar telapak tangan) · ¼ nasi (±1 centong, 100 g)."))
        g.addView(sub("Contoh sehari"))
        g.addView(body("• Pagi (±400): 2 telur rebus/orak-arik sedikit minyak + tempe bacem 1 ptg + pepaya, atau oatmeal + susu rendah lemak.\n" +
            "• Siang (±550): nasi 1 centong + ayam bakar/pepes ikan + tahu + sayur bening/lalapan banyak.\n" +
            "• Camilan (±150): buah (apel, jeruk, pepaya) atau yogurt plain atau kacang rebus segenggam.\n" +
            "• Malam (±400): ikan/ayam tanpa kulit 1–2 ptg + tumis sayur + nasi ½ centong (atau tanpa nasi)."))
        g.addView(sub("Tukar yang sering bikin naik"))
        g.addView(body("• Teh manis / kopi susu gula aren → teh tawar, kopi hitam, atau pemanis nol kalori\n" +
            "• Gorengan → rebus, kukus, bakar, pepes\n" +
            "• Nasi padang/porsi warteg → minta nasi setengah, perbanyak sayur\n" +
            "• Mi instan malam → telur rebus + sayur, atau sup ayam bening\n" +
            "• Martabak/roti manis → buah"))
        g.addView(note("Protein cukup + latihan kekuatan menjaga otot, jadi yang turun lemak, bukan otot. " +
            "Kalau sangat lapar terus, pusing, atau lemas, naikkan asupan 100–200 kkal — konsisten lebih penting dari cepat."))

        // ===== fase =====
        val f = card()
        f.addView(title("Tahapan program"))
        for (ph in Program.PHASES) {
            val to = if (ph.toWeek > c.totalWeeks) c.totalWeeks else ph.toWeek
            if (ph.fromWeek > c.totalWeeks) continue
            val cur = ph == p.phase
            f.addView(TextView(this).apply {
                text = "${if (cur) "▶" else "•"} Minggu ${ph.fromWeek}–$to · ${ph.name}"
                textSize = 14f; setTextColor(if (cur) pal.ring else pal.fg); typeface = Typeface.DEFAULT_BOLD
                setPadding(0, px(6), 0, 0)
            })
            f.addView(note("${n(ph.steps)} langkah/hari · olahraga ${ph.exMin} mnt/hari · ${ph.goal}").apply { setPadding(0, px(2), 0, 0) })
        }
        renderSettings(c)
    }

    private fun renderSettings(c: Program.Cfg) {
        val st = card()
        st.addView(title("Pengaturan program"))
        st.addView(Switch(this).apply {
            text = "Pengingat: rencana hari ini (pagi) & timbang mingguan (tiap ${c.start.dayOfWeek.getDisplayName(TextStyle.FULL, ID)})"
            setTextColor(pal.fg); textSize = 13f; isChecked = c.remind
            setOnCheckedChangeListener { _, v ->
                val x = Program.cfg(this@ProgramActivity)
                Program.save(this@ProgramActivity, Program.Cfg(x.active, x.start, x.w0, x.wT, x.months, x.heightCm, x.birth, x.male, v))
            }
            setPadding(0, px(6), 0, px(6))
        })
        st.addView(note("Selama program aktif, target cincin di dashboard (kalori aktif, langkah, olahraga) mengikuti fase program."))
        val r = row().apply { setPadding(0, px(8), 0, 0) }
        r.addView(smallBtn("✎ Ubah target") { editing = true; load() })
        r.addView(smallBtn("⏹ Hentikan") {
            AlertDialog.Builder(this).setTitle("Hentikan program?")
                .setMessage("Catatan berat tetap tersimpan. Target dashboard kembali ke pengaturan biasa.")
                .setPositiveButton("Hentikan") { _, _ ->
                    val x = Program.cfg(this)
                    Program.save(this, Program.Cfg(false, x.start, x.w0, x.wT, x.months, x.heightCm, x.birth, x.male, x.remind))
                    load()
                }.setNegativeButton("Batal", null).show()
        })
        st.addView(r)
    }

    private fun addStrength(min: Int) {
        val end = (System.currentTimeMillis() / 60_000L) * 60_000L
        val start = end - min * 60_000L
        Db.get(this).putExercise(Db.Exercise(start, end, Exercise.KEKUATAN, Exercise.KEKUATAN, 0, true, true))
        AppLog.d("💪 Latihan kekuatan $min menit dicatat")
        toast("💪 $min menit dicatat"); load()
    }

    // ---------------- helper ----------------

    private fun num(e: EditText) = e.text.toString().trim().replace(',', '.').toDoubleOrNull()
    private fun fmt1(v: Double) = "%.1f".format(ID, v)
    private fun fmt2(v: Double) = "%.2f".format(ID, v)
    private fun n(v: Int) = "%,d".format(v).replace(',', '.')
    private fun dfmt(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("d MMM yyyy", ID))

    private fun tinted(color: Int?) = if (color == null) pal.card
        else androidx.core.graphics.ColorUtils.blendARGB(pal.card, color, if (night) 0.16f else 0.10f)

    private fun card(tint: Int? = null): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(12))
            background = GradientDrawable().apply { cornerRadius = px(20).toFloat(); setColor(tinted(tint)) }
        }
        content.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(10) })
        return c
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, px(6))
    }
    private fun sub(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD; setPadding(0, px(8), 0, px(2))
    }
    private fun body(t: String) = TextView(this).apply { text = t; textSize = 13.5f; setTextColor(pal.fg); setLineSpacing(0f, 1.15f) }
    private fun note(t: String) = TextView(this).apply { text = t; textSize = 11.5f; setTextColor(pal.muted); setPadding(0, px(6), 0, 0) }
    private fun stat(t: String) = TextView(this).apply { text = t; textSize = 14f; setTextColor(pal.fg); setPadding(0, px(3), 0, px(1)) }
    private fun linkText(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(pal.ring); setPadding(0, px(8), 0, px(4)); setOnClickListener { onClick() }
    }
    private fun legend(items: List<Pair<Int, String>>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, px(4), 0, 0)
        for ((col, t) in items) addView(TextView(this@ProgramActivity).apply {
            text = "●  $t"; textSize = 12f; setTextColor(col); setPadding(0, 0, px(14), 0)
        })
    }
    private fun field(r: LinearLayout, label: String, v: String, decimal: Boolean, last: Boolean = false, text: Boolean = false): EditText {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (!last) marginEnd = px(10) }
        }
        col.addView(note(label))
        val e = EditText(this).apply {
            setText(v); textSize = 16f; setTextColor(pal.fg); typeface = Typeface.MONOSPACE
            inputType = when {
                text -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
                decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else -> InputType.TYPE_CLASS_NUMBER
            }
        }
        col.addView(e); r.addView(col)
        return e
    }
    private fun chip(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, px(38), 1f).apply { marginEnd = px(6) }
        setOnClickListener { onClick() }
    }
    private fun styleChip(b: Button, sel: Boolean) {
        b.setTextColor(if (sel) Color.WHITE else pal.ring)
        b.background = GradientDrawable().apply { cornerRadius = px(18).toFloat(); if (sel) setColor(pal.ring) else setStroke(px(1), pal.ring) }
    }
    private fun smallBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; setTextColor(pal.ring)
        background = GradientDrawable().apply { cornerRadius = px(10).toFloat(); setStroke(px(1), pal.ring) }
        layoutParams = LinearLayout.LayoutParams(0, px(44), 1f).apply { marginEnd = px(6) }
        setOnClickListener { onClick() }
    }
    private fun bigButton(t: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 16f
        setTextColor(if (primary) Color.WHITE else pal.ring)
        background = GradientDrawable().apply {
            cornerRadius = px(14).toFloat()
            if (primary) setColor(pal.ring) else { setColor(Color.TRANSPARENT); setStroke(px(2), pal.ring) }
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, px(52)).apply { bottomMargin = px(10) }
        setOnClickListener { onClick() }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
