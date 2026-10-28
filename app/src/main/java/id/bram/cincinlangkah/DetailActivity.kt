package id.bram.cincinlangkah

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Halaman detail satu metrik dengan pilihan Hari / Minggu / Bulan. */
class DetailActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context, m: Metric, date: LocalDate, mode: Int = 0) {
            ctx.startActivity(Intent(ctx, DetailActivity::class.java)
                .putExtra("metric", m.name).putExtra("date", date.toString()).putExtra("mode", mode))
        }
    }

    private val id = Locale("id", "ID")
    private lateinit var metric: Metric
    private var date: LocalDate = LocalDate.now()
    private var mode = 0 // 0 hari, 1 minggu, 2 bulan
    private var job: Job? = null

    private val night by lazy {
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    private val pal by lazy { Pal(night) }
    private fun px(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    private lateinit var tabs: List<TextView>
    private lateinit var dateLbl: TextView
    private lateinit var nextBtn: TextView
    private lateinit var big: TextView
    private lateinit var sub: TextView
    private lateinit var chartBox: LinearLayout
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        metric = Metric.valueOf(intent.getStringExtra("metric") ?: Metric.STEPS.name)
        date = intent.getStringExtra("date")?.let { LocalDate.parse(it) } ?: LocalDate.now()
        mode = savedInstanceState?.getInt("mode") ?: intent.getIntExtra("mode", 0)
        window.statusBarColor = pal.bg
        build()
        load()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("mode", mode) }

    // ---------------- UI ----------------
    private fun build() {
        val col = metric.color(pal)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(14), px(8), px(14), px(28)); setBackgroundColor(pal.bg)
        }
        // bar atas
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(TextView(this).apply {
            text = "←"; textSize = 24f; setTextColor(pal.fg); setPadding(px(4), px(4), px(16), px(4))
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "${metric.emoji}  ${metric.title}"; textSize = 19f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(10) })

        // Hari | Minggu | Bulan
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(px(4), px(4), px(4), px(4))
            background = GradientDrawable().apply { cornerRadius = px(14).toFloat(); setColor(pal.card) }
        }
        tabs = listOf("Hari", "Minggu", "Bulan").mapIndexed { i, t ->
            TextView(this).apply {
                text = t; textSize = 15f; gravity = Gravity.CENTER; setPadding(0, px(9), 0, px(9))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                setOnClickListener { mode = i; load() }
            }.also { seg.addView(it) }
        }
        root.addView(seg)

        // navigasi tanggal
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(12), 0, px(6)) }
        nav.addView(circleBtn("‹") { step(-1) })
        dateLbl = TextView(this).apply {
            textSize = 15f; setTextColor(pal.fg); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener { date = LocalDate.now(); load() }
        }
        nav.addView(dateLbl)
        nextBtn = circleBtn("›") { step(1) }
        nav.addView(nextBtn)
        root.addView(nav)

        // ringkasan + grafik
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(16), px(14), px(16), px(14))
            background = GradientDrawable().apply {
                cornerRadius = px(20).toFloat()
                setColor(androidx.core.graphics.ColorUtils.blendARGB(pal.card, col, if (night) 0.10f else 0.06f))
            }
        }
        big = TextView(this).apply { textSize = 30f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD }
        sub = TextView(this).apply { textSize = 13f; setTextColor(pal.muted); setPadding(0, px(2), 0, px(10)) }
        chartBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(big); card.addView(sub); card.addView(chartBox)
        root.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(4) })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(10), 0, 0) }
        root.addView(listBox)

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(pal.bg) })
    }

    private fun circleBtn(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 22f; setTextColor(pal.fg); gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.card) }
        layoutParams = LinearLayout.LayoutParams(px(38), px(38))
        setOnClickListener { onClick() }
    }

    private fun step(dir: Int) {
        val n = when (mode) { 0 -> date.plusDays(dir.toLong()); 1 -> date.plusWeeks(dir.toLong()); else -> date.plusMonths(dir.toLong()) }
        date = if (n > LocalDate.now()) LocalDate.now() else n
        load()
    }

    private fun weekStart(d: LocalDate) = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun dates(): List<LocalDate> = when (mode) {
        1 -> (0..6).map { weekStart(date).plusDays(it.toLong()) }
        else -> (1..date.lengthOfMonth()).map { date.withDayOfMonth(it) }
    }

    private fun label(): String = when (mode) {
        0 -> if (date == LocalDate.now()) "Hari ini" else date.format(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", id))
        1 -> {
            val a = weekStart(date); val b = a.plusDays(6)
            if (a.month == b.month) "${a.dayOfMonth} – ${b.format(DateTimeFormatter.ofPattern("d MMM yyyy", id))}"
            else "${a.format(DateTimeFormatter.ofPattern("d MMM", id))} – ${b.format(DateTimeFormatter.ofPattern("d MMM yyyy", id))}"
        }
        else -> date.format(DateTimeFormatter.ofPattern("MMMM yyyy", id))
    }

    // ---------------- data ----------------
    private fun load() {
        tabs.forEachIndexed { i, t ->
            val sel = i == mode
            t.setTextColor(if (sel) pal.fg else pal.muted)
            t.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            t.background = if (sel) GradientDrawable().apply {
                cornerRadius = px(11).toFloat(); setColor(androidx.core.graphics.ColorUtils.blendARGB(pal.card, pal.fg, 0.14f))
            } else null
        }
        dateLbl.text = label()
        val atEnd = when (mode) { 0 -> date >= LocalDate.now(); 1 -> weekStart(date).plusDays(6) >= LocalDate.now()
            else -> date.withDayOfMonth(date.lengthOfMonth()) >= LocalDate.now() }
        nextBtn.alpha = if (atEnd) 0.3f else 1f
        big.text = "…"; sub.text = ""
        job?.cancel()
        job = lifecycleScope.launch {
            if (mode == 0) showDay() else showPeriod()
        }
    }

    private fun fmt(v: Double?, v2: Double? = null): String {
        if (v == null) return "–"
        return when (metric) {
            Metric.STEPS -> "%,d".format(v.roundToInt()).replace(',', '.')
            Metric.KCAL -> "${v.roundToInt()} kkal"
            Metric.SLEEP -> { val m = v.roundToInt(); "${m / 60}j ${m % 60}m" }
            Metric.HEART -> "${v.roundToInt()} bpm"
            Metric.EXERCISE -> "${v.roundToInt()} mnt"
            Metric.SPO2 -> "${v.roundToInt()}%"
            Metric.BP -> "${v.roundToInt()}/${v2?.roundToInt() ?: "–"}"
            Metric.HRV -> "${v.roundToInt()} ms"
            Metric.RESP -> "${v.roundToInt()}/mnt"
        }
    }

    private val tf = SimpleDateFormat("HH:mm", Locale.US)

    private suspend fun showDay() {
        val d = Daily.detail(this, metric, date)
        val r = d.report
        chartBox.removeAllViews(); listBox.removeAllViews()
        val ds = Report.dayStart(date)
        when (metric) {
            Metric.STEPS, Metric.KCAL -> {
                big.text = if (metric == Metric.STEPS) fmt(r.total) else fmt(r.kcal)
                sub.text = "%.2f km".format(id, r.distKm) +
                    "  ·  💍 ${fmtN(r.ring.sum())}  ·  📱 ${fmtN(r.phoneBest.sum())}"
                chartBox.addView(HourBarsView(this, pal).apply {
                    ring = r.ring; phone = r.phoneBest; watch = r.watch; band = r.band
                    nowHour = if (date == LocalDate.now()) java.time.LocalTime.now().hour else -1
                })
                chartBox.addView(legendRow(listOf(pal.ring to "Cincin", pal.phone to "HP", pal.awake to "Jam", pal.band to "Gelang")))
                for (h in 23 downTo 0) if (r.fused[h] >= 0.5) listRow("%02d:00".format(h), fmtN(r.fused[h]) + " langkah", null)
            }
            Metric.SLEEP -> {
                val asleep = d.sleep.filter { it.stage in 1..3 }.sumOf { it.durSec } / 60.0
                big.text = fmt(asleep.takeIf { it > 0 })
                sub.text = if (d.sleep.isEmpty()) "belum ada data tidur"
                    else "${tf.format(Date(d.sleep.first().startMs))} – ${tf.format(Date(d.sleep.maxOf { it.startMs + it.durSec * 1000L }))}" +
                        "  ·  " + (if (d.sleepFromWatch) "dari ${WatchData.sleepSrc(date)} ⌚" else "dari cincin 💍")
                chartBox.addView(SleepView(this, pal).apply { segs = d.sleep })
                fun m(st: Int) = d.sleep.filter { it.stage == st }.sumOf { it.durSec } / 60
                listOf(1 to "Nyenyak", 2 to "Ringan", 3 to "REM", 4 to "Terjaga").forEach { (st, n) ->
                    if (d.sleep.isNotEmpty()) listRow("● $n", "${m(st) / 60}j ${m(st) % 60}m", pal.stage(st))
                }
            }
            Metric.HEART -> {
                val all = (d.heartRing + d.heartWatch + d.heartBand).sortedBy { it.tsMs }
                big.text = if (all.isEmpty()) "–" else fmt(all.map { it.hr }.average())
                sub.text = if (all.isEmpty()) "belum ada data" else
                    "rata² · ${all.minOf { it.hr }}–${all.maxOf { it.hr }} · terakhir ${all.last().hr} (${tf.format(Date(all.last().tsMs))})"
                chartBox.addView(HeartView(this, pal).apply { pts = d.heartRing; watch = d.heartWatch; band = d.heartBand; dayStart = ds; hOverride = 160f })
                chartBox.addView(legendRow(listOf(pal.ring to "Cincin", pal.band to "Gelang", pal.heart to "Jam")))
            }
            Metric.EXERCISE -> {
                val mins = d.sessions.sumOf { ((it.second - it.first) / 60_000L).toInt() }
                big.text = if (d.sessions.isEmpty()) "–" else "$mins mnt"
                sub.text = if (d.sessions.isEmpty()) "belum ada olahraga" else "${d.sessions.size} sesi"
                for (s in d.sessions) listRow(s.third, "${tf.format(Date(s.first))}–${tf.format(Date(s.second))} · ${(s.second - s.first) / 60_000} mnt", null)
            }
            else -> {
                val v = d.vitals
                if (v.isEmpty()) { big.text = "–"; sub.text = "belum ada data" }
                else {
                    val last = v.last()
                    big.text = if (metric == Metric.BP) "${last.v1}/${last.v2}" else fmt(last.v1.toDouble())
                    sub.text = "terakhir ${tf.format(Date(last.tsMs))}" +
                        if (metric != Metric.BP && v.size > 1) " · ${v.minOf { it.v1 }}–${v.maxOf { it.v1 }}" else ""
                }
                chartBox.addView(SparkView(this, pal).apply {
                    color = this@DetailActivity.metric.color(this@DetailActivity.pal); dayStart = ds; hOverride = 130f
                    a = v.map { it.tsMs to it.v1 }
                    b = if (metric == Metric.BP) v.map { it.tsMs to it.v2 } else emptyList()
                })
                for (x in v.reversed()) listRow(tf.format(Date(x.tsMs)),
                    if (metric == Metric.BP) "${x.v1}/${x.v2}" else fmt(x.v1.toDouble()), null)
            }
        }
    }

    private suspend fun showPeriod() {
        val ds = dates()
        val vals = ds.map { Daily.value(this, metric, it) }
        chartBox.removeAllViews(); listBox.removeAllViews()
        val has = vals.filter { it.v != null }
        if (has.isEmpty()) { big.text = "–"; sub.text = "belum ada data" }
        else when {
            metric == Metric.BP -> {
                big.text = fmt(has.map { it.v!! }.average(), has.mapNotNull { it.v2 }.average())
                sub.text = "rata² · ${has.size} hari ada data"
            }
            metric.additive -> {
                big.text = fmt(has.map { it.v!! }.average())
                sub.text = "rata² per hari · total ${fmt(has.sumOf { it.v!! })} · ${has.size} hari"
            }
            else -> {
                big.text = fmt(has.map { it.v!! }.average())
                val lo = has.mapNotNull { it.lo }.minOrNull(); val hi = has.mapNotNull { it.hi }.maxOrNull()
                sub.text = "rata²" + (if (lo != null && hi != null && metric != Metric.SLEEP) " · ${fmt(lo)} – ${fmt(hi)}" else "") +
                    " · ${has.size} hari"
            }
        }
        chartBox.addView(PeriodBarsView(this, pal).apply {
            this.vals = vals; metric = this@DetailActivity.metric; color = this@DetailActivity.metric.color(this@DetailActivity.pal)
            onPick = { d -> date = d; mode = 0; load() }
        })
        chartBox.addView(TextView(this).apply {
            text = "Ketuk batang untuk membuka harinya"; textSize = 11f; setTextColor(pal.muted); setPadding(0, px(4), 0, 0)
        })
        for (v in vals.reversed()) if (v.v != null) {
            listRow(v.date.format(DateTimeFormatter.ofPattern("EEE, d MMM", id)), fmt(v.v, v.v2), null) {
                date = v.date; mode = 0; load()
            }
        }
    }

    private fun fmtN(v: Double) = "%,d".format(v.roundToInt()).replace(',', '.')

    private fun legendRow(items: List<Pair<Int, String>>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, px(6), 0, 0)
        for ((c, t) in items) addView(TextView(this@DetailActivity).apply {
            text = "●  $t"; textSize = 12f; setTextColor(c); setPadding(0, 0, px(16), 0)
        })
    }

    private fun listRow(left: String, right: String, dot: Int?, onClick: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14), px(12), px(14), px(12))
            background = GradientDrawable().apply { cornerRadius = px(14).toFloat(); setColor(pal.card) }
            if (onClick != null) setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = left; textSize = 14f; setTextColor(dot ?: pal.fg)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply { text = right + if (onClick != null) "  ›" else ""; textSize = 14f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD })
        listBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(6) })
    }
}
