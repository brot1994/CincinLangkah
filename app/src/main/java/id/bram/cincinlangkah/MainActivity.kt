package id.bram.cincinlangkah

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private var date: LocalDate = LocalDate.now()
    private var ring: RingClient? = null
    private val hcCache = HashMap<LocalDate, DoubleArray>()
    private var refreshJob: kotlinx.coroutines.Job? = null

    private val night by lazy {
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    private val pal by lazy { Pal(night) }
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).roundToInt()

    // tampilan
    private lateinit var dateLbl: TextView
    private lateinit var status: TextView
    private lateinit var syncBtn: Button
    private lateinit var hcBtn: Button
    private lateinit var stepRing: StepRingView
    private lateinit var statKm: TextView
    private lateinit var statKcal: TextView
    private lateinit var statSrc: TextView
    private lateinit var hourBars: HourBarsView
    private lateinit var sleepBig: TextView
    private lateinit var sleepView: SleepView
    private lateinit var sleepLegend: TextView
    private lateinit var heartBig: TextView
    private lateinit var heartView: HeartView
    private lateinit var week: WeekView
    private class Tile(val value: TextView, val sub: TextView, val spark: SparkView)
    private lateinit var tSpo2: Tile
    private lateinit var tBp: Tile
    private lateinit var tHrv: Tile
    private lateinit var tResp: Tile
    private lateinit var table: LinearLayout
    private lateinit var tableBox: LinearLayout
    private lateinit var settingsBox: LinearLayout
    private lateinit var addr: EditText
    private lateinit var goalEdit: EditText
    private lateinit var logView: TextView

    // ---------------- izin ----------------
    private val basePerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasActivityPerm()) startPhoneSampling()
        if (hasBtPerms()) SyncWorker.schedule(this)
        pendingSync?.let { pendingSync = null; if (hasBtPerms()) it() else toast("Izin Bluetooth ditolak") }
    }
    private var pendingSync: (() -> Unit)? = null

    private val hcPerms = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(HcSteps.PERMS)) { toast("Health Connect diizinkan"); hcCache.clear(); refresh() }
        else toast("Izin Health Connect belum diberikan")
    }

    private fun btPermList(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasBtPerms() = btPermList().all { granted(it) }
    private fun hasActivityPerm() = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACTIVITY_RECOGNITION)

    // ---------------- siklus hidup ----------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        if (prefs.getString("addr", null) == null) prefs.edit().putString("addr", "EA:CE:44:64:06:9B").apply()
        buildUi()
        AppLog.listener = { line -> appendLog(line) }
        AppLog.recentLines().takeLast(60).forEach { appendLog(it) }

        val need = ArrayList<String>()
        if (!hasActivityPerm()) need += Manifest.permission.ACTIVITY_RECOGNITION
        if (!hasBtPerms()) need += btPermList()
        if (need.isNotEmpty()) basePerms.launch(need.toTypedArray())
        else { startPhoneSampling(); SyncWorker.schedule(this) }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) { updateStatus(); refresh() }
    }

    override fun onDestroy() {
        AppLog.listener = null
        super.onDestroy()
    }

    private fun startPhoneSampling() {
        if (!PhoneSteps.available(this)) { AppLog.d("HP ini tidak punya sensor step counter"); return }
        PhoneSteps.schedule(this)
        lifecycleScope.launch { PhoneSteps.sample(this@MainActivity, "aplikasi dibuka"); refresh() }
    }

    // ---------------- aksi ----------------
    private fun doSync() {
        if (ring != null) { ring?.cancel(); return }
        if (SyncWorker.running) { toast("Sinkron otomatis sedang berjalan, tunggu sebentar"); return }
        if (!hasBtPerms()) { pendingSync = { doSync() }; basePerms.launch(btPermList()); return }
        val a = addr.text.toString().trim().uppercase()
        prefs.edit().putString("addr", a).apply()
        syncBtn.text = "Batalkan"
        status.text = "Menyambung ke cincin…"
        lifecycleScope.launch { PhoneSteps.sample(this@MainActivity, "sebelum sinkron") }
        SyncWorker.running = true
        ring = RingClient(this) { ok, summary ->
            runOnUiThread {
                ring = null
                SyncWorker.running = false
                syncBtn.text = "Sinkron sekarang"
                if (!ok) toast(summary)
                updateStatus(if (ok) null else "✖ $summary")
                refresh()
            }
        }.also { it.start(a.ifEmpty { null }) }
    }

    private fun doHc() {
        if (!HcSteps.available(this)) { toast("Health Connect tidak tersedia di HP ini"); return }
        lifecycleScope.launch {
            if (HcSteps.granted(this@MainActivity)) { hcCache.clear(); refresh() } else hcPerms.launch(HcSteps.PERMS)
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("Sudah diizinkan jalan di latar"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun shareLog() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Log Cincin Langkah")
            putExtra(Intent.EXTRA_TEXT, AppLog.tail())
        }, "Bagikan log"))
    }

    private fun updateStatus(err: String? = null) {
        val tf = SimpleDateFormat("HH:mm", Locale.US)
        val df = SimpleDateFormat("d MMM HH:mm", Locale("id", "ID"))
        val parts = ArrayList<String>()
        if (err != null) parts += err
        val last = prefs.getLong("last_sync", 0)
        parts += if (last == 0L) "Belum pernah sinkron"
        else "Sinkron terakhir " + (if (System.currentTimeMillis() - last < 20 * 3_600_000L) tf else df).format(Date(last))
        val bat = prefs.getInt("baterai", -1)
        if (bat >= 0) parts += "baterai cincin $bat%"
        parts += "otomatis tiap ±1 jam"
        status.text = parts.joinToString(" · ")
    }

    // ---------------- data ----------------
    private suspend fun hcFor(d: LocalDate): DoubleArray? {
        if (d != LocalDate.now()) hcCache[d]?.let { return it }  // hari ini selalu dibaca ulang
        if (!HcSteps.available(this)) return null
        return try {
            if (!HcSteps.granted(this)) null
            else HcSteps.hourly(this, d).also { hcCache[d] = it }
        } catch (e: Exception) { AppLog.d("Health Connect error: ${e.message}"); null }
    }

    private fun refresh() {
        val d = date
        dateLbl.text = if (d == LocalDate.now()) "Hari ini"
        else d.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale("id", "ID")))
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            val hc = hcFor(d)
            hcBtn.visibility = if (hc == null && HcSteps.available(this@MainActivity)) View.VISIBLE else View.GONE
            val weekHc = HashMap<LocalDate, DoubleArray>()
            for (i in 0..6) { val wd = d.minusDays(i.toLong()); hcFor(wd)?.let { weekHc[wd] = it } }
            val (day, wk) = withContext(Dispatchers.IO) {
                Report.build(this@MainActivity, d, hc) to Report.week(this@MainActivity, d, weekHc)
            }
            if (d == date) render(day, wk)
        }
    }

    private fun render(day: Report.Day, wk: List<Pair<LocalDate, Double>>) {
        val goal = prefs.getInt("goal", 10_000)
        stepRing.steps = day.total
        stepRing.goal = goal
        stepRing.sub = "target ${fmtN(goal.toDouble())} · ${(day.total * 100 / goal).roundToInt()}%"
        stepRing.invalidate()
        statKm.text = "%.2f\nkm".format(Locale("id", "ID"), day.distKm)
        statKcal.text = "${day.kcal.roundToInt()}\nkkal"
        val ringT = day.ring.sum(); val phoneT = day.phoneBest.sum()
        statSrc.text = "${fmtN(ringT)} / ${fmtN(phoneT)}\ncincin / HP"

        val isToday = day.date == LocalDate.now()
        hourBars.ring = day.ring; hourBars.phone = day.phoneBest
        hourBars.nowHour = if (isToday) java.time.LocalTime.now().hour else -1
        hourBars.invalidate()

        // tidur
        val tot = day.sleepTotalMin
        sleepBig.text = if (tot > 0) "${tot / 60} j ${tot % 60} m" else "–"
        sleepView.segs = day.sleep; sleepView.invalidate()
        sleepLegend.text = if (day.sleep.isEmpty()) ""
        else "● Nyenyak ${hm(day.sleepMin(1))}   ● Ringan ${hm(day.sleepMin(2))}   ● REM ${hm(day.sleepMin(3))}   ● Terjaga ${hm(day.sleepMin(4))}"

        // detak jantung
        val hr = day.heart
        heartBig.text = if (hr.isEmpty()) "–" else
            "${hr.last().hr} bpm   ·   ${hr.minOf { it.hr }}–${hr.maxOf { it.hr }}"
        heartView.pts = hr; heartView.dayStart = Report.dayStart(day.date); heartView.invalidate()

        week.days = wk; week.goal = goal; week.selected = day.date; week.invalidate()

        val ds = Report.dayStart(day.date)
        fun fill(t: Tile, list: List<Ycbt.Vital>, unit: String, two: Boolean = false) {
            if (list.isEmpty()) { t.value.text = "–"; t.sub.text = "belum ada data"; t.spark.a = emptyList(); t.spark.b = emptyList() }
            else {
                val last = list.last()
                t.value.text = (if (two) "${last.v1}/${last.v2}" else "${last.v1}") + unit
                val tf = SimpleDateFormat("HH:mm", Locale.US).format(Date(last.tsMs))
                t.sub.text = if (two) "$tf · ${list.size}× hari ini"
                else "$tf · ${list.minOf { it.v1 }}–${list.maxOf { it.v1 }}"
                t.spark.a = list.map { it.tsMs to it.v1 }
                t.spark.b = if (two) list.map { it.tsMs to it.v2 } else emptyList()
            }
            t.spark.dayStart = ds; t.spark.invalidate()
        }
        fill(tSpo2, day.spo2, "%")
        fill(tBp, day.bp, "", two = true)
        fill(tHrv, day.hrv, " ms")
        fill(tResp, day.resp, "/mnt")

        renderTable(day)
    }

    private fun hm(min: Int) = if (min >= 60) "${min / 60}j${min % 60}m" else "${min}m"
    private fun fmtN(v: Double) = "%,d".format(v.roundToInt()).replace(',', '.')

    private fun renderTable(day: Report.Day) {
        table.removeAllViews()
        table.addView(row("Jam", "Cincin", "Cin.kum", "HP", "Samsung", "Gab.", header = true, shade = false, hi = false))
        val now = java.time.LocalDateTime.now()
        for (h in 0 until 24) {
            if (day.date == now.toLocalDate() && h > now.hour) break
            val r = day.ringSport[h]; val p = day.phoneBest[h]
            val hi = p > 0 && abs(r - p) >= maxOf(150.0, 0.4 * maxOf(r, p))
            table.addView(row("%02d".format(h), n(r), n(day.ringCum[h]),
                if (day.phoneCovered[h]) n(day.phone[h]) else "·",
                day.hc?.let { n(it[h]) } ?: "·",
                n(day.fused[h]) + if (day.fromPhone[h]) "ᴴ" else "",
                header = false, shade = h % 2 == 1, hi = hi))
        }
        table.addView(row("Total", n(day.ringSport.sum()), n(day.ringCum.sum()), n(day.phone.sum()),
            day.hc?.let { n(it.sum()) } ?: "·", n(day.total), header = true, shade = false, hi = false))
    }

    private fun n(v: Double): String = if (v < 0.5) "–" else v.roundToInt().toString()

    private fun row(vararg cols: String, header: Boolean, shade: Boolean, hi: Boolean): View {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(4), px(4), px(4), px(4))
            setBackgroundColor(when {
                hi -> if (night) Color.parseColor("#4A3B12") else Color.parseColor("#FFF1C7")
                shade -> if (night) Color.parseColor("#1F2925") else Color.parseColor("#F1F5F3")
                else -> Color.TRANSPARENT
            })
        }
        val weights = floatArrayOf(0.8f, 1.1f, 1.1f, 1f, 1.2f, 1.1f)
        cols.forEachIndexed { i, t ->
            ll.addView(TextView(this).apply {
                text = t
                textSize = if (header) 11.5f else 12.5f
                setTextColor(if (i == 0 && !header) pal.muted else pal.fg)
                typeface = if (header) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
                gravity = if (i == 0) Gravity.START else Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, weights[i])
            })
        }
        return ll
    }

    // ---------------- UI ----------------
    private fun buildUi() {
        window.statusBarColor = pal.bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(14), px(14), px(28))
            setBackgroundColor(pal.bg)
        }

        // Judul + navigasi tanggal
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(navBtn("‹") { date = date.minusDays(1); refresh() })
        dateLbl = TextView(this).apply {
            textSize = 20f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener { date = LocalDate.now(); refresh() }
        }
        head.addView(dateLbl)
        head.addView(navBtn("›") { if (date < LocalDate.now()) { date = date.plusDays(1); refresh() } })
        root.addView(head)
        status = TextView(this).apply {
            textSize = 12f; setTextColor(pal.muted); gravity = Gravity.CENTER; setPadding(0, px(2), 0, px(10))
        }
        root.addView(status)
        updateStatus()

        // Kartu langkah
        val c1 = card(root)
        stepRing = StepRingView(this, pal)
        c1.addView(stepRing)
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(6), 0, 0) }
        statKm = stat(stats); statKcal = stat(stats); statSrc = stat(stats)
        c1.addView(stats)

        // Per jam
        val c2 = card(root)
        c2.addView(title("Langkah per jam"))
        hourBars = HourBarsView(this, pal)
        c2.addView(hourBars)
        c2.addView(legend(listOf(pal.ring to "Cincin", pal.phone to "HP / Samsung Health")))
        c2.addView(note("Total di atas = gabungan: tiap jam diambil yang lebih besar, karena masing-masing bisa terlewat " +
            "(HP ditinggal di meja, atau tangan diam saat jalan)."))

        // Tidur
        val c3 = card(root)
        c3.addView(title("Tidur"))
        sleepBig = big(c3)
        sleepView = SleepView(this, pal)
        c3.addView(sleepView)
        sleepLegend = note("")
        c3.addView(sleepLegend)

        // Detak jantung
        val c4 = card(root)
        c4.addView(title("Detak jantung"))
        heartBig = big(c4)
        heartView = HeartView(this, pal)
        c4.addView(heartView)

        // Tanda vital
        val cv = card(root)
        cv.addView(title("Tanda vital"))
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(12), 0, 0) }
        tSpo2 = tile(r1, "SpO₂", pal.spo2)
        tBp = tile(r1, "Tekanan darah", pal.bp)
        tHrv = tile(r2, "HRV", pal.hrv)
        tResp = tile(r2, "Laju napas", pal.resp)
        cv.addView(r1); cv.addView(r2)
        cv.addView(note("Diukur otomatis oleh cincin (sensor cahaya). Tekanan darah & SpO₂ dari cincin adalah " +
            "perkiraan, bukan pengganti alat medis."))

        // Mingguan
        val c5 = card(root)
        c5.addView(title("7 hari terakhir"))
        week = WeekView(this, pal).apply { onPick = { d -> date = d; refresh() } }
        c5.addView(week)
        c5.addView(note("Ketuk batang untuk membuka hari itu. Garis putus-putus = target."))

        // Tombol
        syncBtn = bigButton("Sinkron sekarang", primary = true) { doSync() }
        root.addView(syncBtn)
        hcBtn = bigButton("Hubungkan Samsung Health (Health Connect)", primary = false) { doHc() }
        root.addView(hcBtn)

        // Detail per jam (lipat)
        tableBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(toggle("Detail per jam (perbandingan sumber)", tableBox))
        val c6 = card(tableBox)
        table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c6.addView(table)
        c6.addView(note("Cincin = bucket 30 menit · Cin.kum = penghitung kumulatif cincin · HP = step counter HP " +
            "(dicatat aplikasi ini tiap ±15 menit) · Samsung = Health Connect · Gab. = dipakai di dashboard " +
            "(ᴴ = dari HP). Baris kuning = cincin dan HP beda jauh."))
        root.addView(tableBox)

        // Pengaturan & log (lipat)
        settingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(toggle("Pengaturan & log", settingsBox))
        val c7 = card(settingsBox)
        c7.addView(note("Alamat Bluetooth cincin"))
        addr = edit(prefs.getString("addr", "") ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        c7.addView(addr)
        c7.addView(note("Target langkah per hari"))
        goalEdit = edit(prefs.getInt("goal", 10_000).toString(), InputType.TYPE_CLASS_NUMBER)
        goalEdit.setOnFocusChangeListener { _, has ->
            if (!has) {
                val g = goalEdit.text.toString().toIntOrNull()?.coerceIn(1000, 100_000) ?: 10_000
                prefs.edit().putInt("goal", g).apply(); refresh()
            }
        }
        c7.addView(goalEdit)
        c7.addView(Switch(this).apply {
            text = "Hapus riwayat di cincin setelah tersimpan di HP (seperti SmartHealth, supaya memori cincin tidak penuh)"
            setTextColor(pal.fg); textSize = 13f
            isChecked = prefs.getBoolean("hapus_di_cincin", true)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("hapus_di_cincin", v).apply() }
            setPadding(0, px(8), 0, px(8))
        })
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(smallButton("Catat HP") { lifecycleScope.launch { PhoneSteps.sample(this@MainActivity, "manual"); refresh() } })
        tools.addView(smallButton("Izinkan latar") { askBattery() })
        tools.addView(smallButton("Bagikan log") { shareLog() })
        c7.addView(tools)
        logView = TextView(this).apply {
            textSize = 10f; typeface = Typeface.MONOSPACE; setTextColor(pal.muted); setTextIsSelectable(true)
            setPadding(0, px(10), 0, 0)
        }
        c7.addView(logView)
        root.addView(settingsBox)

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(pal.bg) })
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(12))
            background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(pal.card) }
            elevation = if (night) 0f else px(1).toFloat()
        }
        parent.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(12) })
        return c
    }

    private fun tile(row: LinearLayout, label: String, color: Int): Tile {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = px(10) }
        }
        box.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD })
        val v = TextView(this).apply { textSize = 22f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD }
        val sub = TextView(this).apply { textSize = 11f; setTextColor(pal.muted) }
        val sp = SparkView(this, pal).apply { this.color = color }
        box.addView(v); box.addView(sub); box.addView(sp)
        row.addView(box)
        return Tile(v, sub, sp)
    }

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, px(6))
    }

    private fun big(parent: LinearLayout) = TextView(this).apply {
        textSize = 26f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, px(6))
    }.also { parent.addView(it) }

    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 11.5f; setTextColor(pal.muted); setPadding(0, px(6), 0, 0)
    }

    private fun stat(parent: LinearLayout) = TextView(this).apply {
        textSize = 15f; setTextColor(pal.fg); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
    }.also { parent.addView(it) }

    private fun legend(items: List<Pair<Int, String>>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, px(4), 0, 0)
        for ((col, t) in items) addView(TextView(this@MainActivity).apply {
            text = "●  $t"; textSize = 12f; setTextColor(col); setPadding(0, 0, px(16), 0)
        })
    }

    private fun toggle(t: String, box: View) = TextView(this).apply {
        fun label() = (if (box.visibility == View.VISIBLE) "▾  " else "▸  ") + t
        text = label(); textSize = 15f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD
        setPadding(px(4), px(14), px(4), px(10))
        setOnClickListener {
            box.visibility = if (box.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            text = label()
        }
    }

    private fun edit(v: String, type: Int) = EditText(this).apply {
        setText(v); inputType = type; typeface = Typeface.MONOSPACE; setTextColor(pal.fg); textSize = 15f
    }

    private fun navBtn(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 30f; setTextColor(pal.ring); gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(px(48), px(48))
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

    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; setTextColor(pal.ring)
        background = GradientDrawable().apply { cornerRadius = px(10).toFloat(); setStroke(px(1), pal.ring) }
        layoutParams = LinearLayout.LayoutParams(0, px(44), 1f).apply { marginEnd = px(6) }
        setOnClickListener { onClick() }
    }

    private fun appendLog(line: String) {
        if (!::logView.isInitialized) return
        val lines = (logView.text.toString() + "\n" + line).trim().lines().takeLast(80)
        logView.text = lines.joinToString("\n")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
