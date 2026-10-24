package id.bram.cincinlangkah

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private var date: LocalDate = LocalDate.now()
    private var ring: RingClient? = null

    private lateinit var status: TextView
    private lateinit var addr: EditText
    private lateinit var syncBtn: Button
    private lateinit var hcBtn: Button
    private lateinit var dateLbl: TextView
    private lateinit var table: LinearLayout
    private lateinit var totals: TextView
    private lateinit var logView: TextView

    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).roundToInt()

    private val accent = Color.parseColor("#1E8E6E")
    private val night by lazy {
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    private val fg get() = if (night) Color.parseColor("#E8ECEA") else Color.parseColor("#17201C")
    private val muted get() = if (night) Color.parseColor("#93A39C") else Color.parseColor("#5E6B66")
    private val rowAlt get() = if (night) Color.parseColor("#1B2420") else Color.parseColor("#F1F5F3")
    private val warn get() = if (night) Color.parseColor("#4A3B12") else Color.parseColor("#FFF1C7")
    private val bg get() = if (night) Color.parseColor("#0F1513") else Color.WHITE

    // ---------------- izin ----------------
    private val basePerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACTIVITY_RECOGNITION] == true || hasActivityPerm()) startPhoneSampling()
        pendingSync?.let { pendingSync = null; if (hasBtPerms()) it() else toast("Izin Bluetooth ditolak") }
    }
    private var pendingSync: (() -> Unit)? = null

    private val hcPerms = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(HcSteps.PERMS)) { toast("Health Connect diizinkan"); refresh() }
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
        buildUi()
        AppLog.listener = { line -> appendLog(line) }
        AppLog.recentLines().takeLast(60).forEach { appendLog(it) }

        val need = ArrayList<String>()
        if (!hasActivityPerm()) need += Manifest.permission.ACTIVITY_RECOGNITION
        if (!hasBtPerms()) need += btPermList()
        if (need.isNotEmpty()) basePerms.launch(need.toTypedArray()) else startPhoneSampling()
        refresh()
    }

    override fun onDestroy() {
        AppLog.listener = null
        super.onDestroy()
    }

    private fun startPhoneSampling() {
        if (!PhoneSteps.available(this)) {
            AppLog.d("HP ini tidak punya sensor step counter")
            return
        }
        PhoneSteps.schedule(this)
        lifecycleScope.launch {
            PhoneSteps.sample(this@MainActivity, "aplikasi dibuka")
            refresh()
        }
    }

    // ---------------- aksi ----------------
    private fun doSync() {
        if (ring != null) { ring?.cancel(); return }
        if (!hasBtPerms()) { pendingSync = { doSync() }; basePerms.launch(btPermList()); return }
        val a = addr.text.toString().trim().uppercase()
        prefs.edit().putString("addr", a).apply()
        syncBtn.text = "Batalkan"
        status.text = "Menyambung ke cincin… (tutup/putuskan dulu SmartHealth kalau gagal)"
        lifecycleScope.launch { PhoneSteps.sample(this@MainActivity, "sebelum sinkron") }
        ring = RingClient(this) { ok, summary ->
            runOnUiThread {
                ring = null
                syncBtn.text = "Sinkron cincin"
                status.text = (if (ok) "✔ " else "✖ ") + summary
                refresh()
            }
        }.also { it.start(a.ifEmpty { null }) }
    }

    private fun doHc() {
        if (!HcSteps.available(this)) {
            toast("Health Connect tidak tersedia di HP ini")
            return
        }
        lifecycleScope.launch {
            if (HcSteps.granted(this@MainActivity)) refresh() else hcPerms.launch(HcSteps.PERMS)
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Sudah diizinkan jalan di latar"); return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun shareLog() {
        val text = AppLog.tail()
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Log Cincin Langkah")
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Bagikan log"))
    }

    // ---------------- tabel ----------------
    private fun refresh() {
        dateLbl.text = date.format(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", Locale("id", "ID")))
        val d = date
        lifecycleScope.launch {
            var hc: DoubleArray? = null
            if (HcSteps.available(this@MainActivity)) {
                try {
                    if (HcSteps.granted(this@MainActivity)) hc = HcSteps.hourly(this@MainActivity, d)
                } catch (e: Exception) { AppLog.d("Health Connect error: ${e.message}") }
            }
            hcBtn.visibility = if (hc == null) View.VISIBLE else View.GONE
            val day = withContext(Dispatchers.IO) { Report.build(this@MainActivity, d, hc) }
            if (d == date) render(day)
        }
    }

    private fun render(day: Report.Day) {
        table.removeAllViews()
        table.addView(row("Jam", "Cincin", "Cin.kum", "HP", "Samsung", header = true, shade = false, hi = false))
        val now = java.time.LocalDateTime.now()
        for (h in 0 until 24) {
            if (date == now.toLocalDate() && h > now.hour) break
            val r = day.ringSport[h]; val c = day.ringCum[h]; val p = day.phone[h]
            val s = day.hc?.get(h)
            val hi = day.phoneCovered[h] && abs(r - p) >= maxOf(150.0, 0.4 * maxOf(r, p))
            table.addView(row(
                "%02d".format(h), n(r), n(c),
                if (day.phoneCovered[h]) n(p) else "·",
                s?.let { n(it) } ?: "·",
                header = false, shade = h % 2 == 1, hi = hi
            ))
        }
        val tR = day.ringSport.sum(); val tC = day.ringCum.sum(); val tP = day.phone.sum()
        table.addView(row("Total", n(tR), n(tC), n(tP), day.hc?.let { n(it.sum()) } ?: "·",
            header = true, shade = false, hi = false))

        val phoneHours = day.phoneCovered.count { it }
        totals.text = buildString {
            append("Cincin = bucket aktivitas 30 menit dari cincin. ")
            append("Cin.kum = selisih penghitung kumulatif cincin (cek silang). ")
            append("HP = step counter HP yang dicatat aplikasi ini tiap ±15 menit")
            append(if (phoneHours == 0) " — belum ada data, mulai terkumpul sejak aplikasi dipasang. " else " ($phoneHours jam tercakup). ")
            append("Samsung = Health Connect. ")
            append("Baris kuning = cincin dan HP beda jauh.")
        }
    }

    private fun n(v: Double): String = if (v < 0.5) "–" else v.roundToInt().toString()

    private fun row(a: String, b: String, c: String, d: String, e: String, header: Boolean, shade: Boolean, hi: Boolean): View {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(6), px(5), px(6), px(5))
            setBackgroundColor(when { hi -> warn; shade -> rowAlt; header -> Color.TRANSPARENT; else -> Color.TRANSPARENT })
        }
        val weights = floatArrayOf(0.8f, 1.1f, 1.1f, 1.1f, 1.2f)
        listOf(a, b, c, d, e).forEachIndexed { i, t ->
            ll.addView(TextView(this).apply {
                text = t
                textSize = if (header) 13f else 14f
                setTextColor(if (header) fg else if (i == 0) muted else fg)
                typeface = if (header) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
                gravity = if (i == 0) Gravity.START else Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, weights[i])
            })
        }
        return ll
    }

    // ---------------- UI ----------------
    private fun buildUi() {
        window.statusBarColor = bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(18), px(16), px(24))
            setBackgroundColor(bg)
        }
        root.addView(TextView(this).apply {
            text = "Cincin Langkah"; textSize = 24f; setTextColor(fg); typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Uji: langkah cincin R11M vs HP, per jam"; textSize = 14f; setTextColor(muted)
            setPadding(0, px(2), 0, px(14))
        })

        root.addView(label("Alamat Bluetooth cincin"))
        addr = EditText(this).apply {
            setText(prefs.getString("addr", "EA:CE:44:64:06:9B"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            typeface = Typeface.MONOSPACE; setTextColor(fg); textSize = 16f
        }
        root.addView(addr, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        syncBtn = bigButton("Sinkron cincin", primary = true) { doSync() }
        root.addView(syncBtn)
        status = TextView(this).apply {
            text = "Sebelum sinkron: di SmartHealth tekan Disconnect (atau tutup aplikasinya) supaya cincin bebas."
            textSize = 13f; setTextColor(muted); setPadding(0, px(6), 0, px(10))
        }
        root.addView(status)

        hcBtn = bigButton("Ambil data Samsung Health (Health Connect)", primary = false) { doHc() }
        root.addView(hcBtn)

        // navigasi tanggal
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(14), 0, px(6))
        }
        nav.addView(smallButton("◀") { date = date.minusDays(1); refresh() })
        dateLbl = TextView(this).apply {
            textSize = 16f; setTextColor(fg); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener { date = LocalDate.now(); refresh() }
        }
        nav.addView(dateLbl)
        nav.addView(smallButton("▶") { if (date < LocalDate.now()) { date = date.plusDays(1); refresh() } })
        root.addView(nav)

        table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(table)
        totals = TextView(this).apply { textSize = 12f; setTextColor(muted); setPadding(0, px(8), 0, px(14)) }
        root.addView(totals)

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(smallButton("Catat HP sekarang", weight = 1f) {
            lifecycleScope.launch { PhoneSteps.sample(this@MainActivity, "manual"); refresh() }
        })
        tools.addView(smallButton("Izinkan latar", weight = 1f) { askBattery() })
        tools.addView(smallButton("Bagikan log", weight = 1f) { shareLog() })
        root.addView(tools)

        root.addView(label("Log").apply { setPadding(0, px(14), 0, px(4)) })
        logView = TextView(this).apply {
            textSize = 10.5f; typeface = Typeface.MONOSPACE; setTextColor(muted)
            setTextIsSelectable(true)
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(bg) })
    }

    private fun label(t: String) = TextView(this).apply { text = t; textSize = 12f; setTextColor(muted) }

    private fun bigButton(t: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 16f
        setTextColor(if (primary) Color.WHITE else accent)
        background = GradientDrawable().apply {
            cornerRadius = px(12).toFloat()
            if (primary) setColor(accent) else { setColor(Color.TRANSPARENT); setStroke(px(2), accent) }
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, px(52)).apply { topMargin = px(10) }
        setOnClickListener { onClick() }
    }

    private fun smallButton(t: String, weight: Float = 0f, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; setTextColor(accent)
        background = GradientDrawable().apply { cornerRadius = px(10).toFloat(); setStroke(px(1), accent) }
        layoutParams = (if (weight > 0) LinearLayout.LayoutParams(0, px(44), weight) else LinearLayout.LayoutParams(px(52), px(44)))
            .apply { marginEnd = px(6) }
        setOnClickListener { onClick() }
    }

    private fun appendLog(line: String) {
        if (!::logView.isInitialized) return
        val lines = (logView.text.toString() + "\n" + line).trim().lines().takeLast(80)
        logView.text = lines.joinToString("\n")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
