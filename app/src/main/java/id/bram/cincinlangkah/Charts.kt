package id.bram.cincinlangkah

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Warna dashboard, terang/gelap. */
class Pal(val night: Boolean) {
    val bg = if (night) Color.parseColor("#0F1513") else Color.parseColor("#F3F6F5")
    val card = if (night) Color.parseColor("#18211E") else Color.WHITE
    val fg = if (night) Color.parseColor("#E8ECEA") else Color.parseColor("#17201C")
    val muted = if (night) Color.parseColor("#93A39C") else Color.parseColor("#66736E")
    val grid = if (night) Color.parseColor("#2A3531") else Color.parseColor("#E3E9E6")
    val ring = if (night) Color.parseColor("#3CC79A") else Color.parseColor("#1E8E6E")   // cincin
    val phone = if (night) Color.parseColor("#6FA8FF") else Color.parseColor("#2F6FDB")  // HP
    val track = if (night) Color.parseColor("#253430") else Color.parseColor("#DCEFE8")
    val heart = if (night) Color.parseColor("#FF7A8A") else Color.parseColor("#E0475B")
    val deep = if (night) Color.parseColor("#7C6CF0") else Color.parseColor("#5B48D6")
    val light = if (night) Color.parseColor("#A99BFF") else Color.parseColor("#9C8CF5")
    val rem = if (night) Color.parseColor("#5CC8E8") else Color.parseColor("#2BA9D1")
    val awake = if (night) Color.parseColor("#F2B84B") else Color.parseColor("#E5A11C")
    val spo2 = if (night) Color.parseColor("#5CC8E8") else Color.parseColor("#1A8FB8")
    val bp = if (night) Color.parseColor("#FF9A6B") else Color.parseColor("#D9622B")
    val hrv = if (night) Color.parseColor("#A99BFF") else Color.parseColor("#6C5BD9")
    val resp = if (night) Color.parseColor("#7CD992") else Color.parseColor("#2E9E4F")
    fun stage(s: Int) = when (s) { 1 -> deep; 2 -> light; 3 -> rem; else -> awake }
}

abstract class BaseChart(ctx: Context, protected val pal: Pal) : View(ctx) {
    protected val d = ctx.resources.displayMetrics.density
    protected fun px(v: Float) = v * d
    protected val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.muted; textSize = px(11f) }
    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    abstract val hDp: Float
    /** tinggi khusus (dp), dipakai untuk versi mini di ubin dashboard */
    var hOverride: Float? = null
    /** versi ringkas: tanpa label sumbu */
    var compact = false
    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), px(hOverride ?: hDp).roundToInt())
    }
    protected fun fmtTime(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        return "%02d:%02d".format(t.hour, t.minute)
    }
}

/** Lingkaran progres langkah. */
class StepRingView(ctx: Context, pal: Pal) : BaseChart(ctx, pal) {
    override val hDp = 220f
    var steps = 0.0
    var goal = 10_000
    var sub = ""
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val sw = if (compact) px(11f) else px(16f)
        val r = min(width, height) / 2f - sw / 2f - px(2f)
        line.strokeWidth = sw; line.strokeCap = Paint.Cap.ROUND
        line.color = pal.track
        c.drawCircle(cx, cy, r, line)
        val frac = (steps / goal).coerceIn(0.0, 1.0).toFloat()
        line.color = pal.ring
        if (frac > 0f) c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * frac, false, line)
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.fg; textSize = r * 0.42f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        val n = "%,d".format(steps.roundToInt()).replace(',', '.')
        if (big.measureText(n) > r * 1.5f) big.textSize *= r * 1.5f / big.measureText(n)
        c.drawText(n, cx, cy + big.textSize * 0.35f, big)
        val small = Paint(text).apply { textAlign = Paint.Align.CENTER; textSize = r * 0.16f }
        c.drawText("langkah", cx, cy - r * 0.38f, small)
        if (sub.isNotEmpty()) c.drawText(sub, cx, cy + r * 0.55f, small)
    }
}

/** Tiga cincin aktivitas ala Huawei: kalori (luar), langkah (tengah), durasi olahraga (dalam). */
class ActivityRingsView(ctx: Context, pal: Pal) : BaseChart(ctx, pal) {
    override val hDp = 150f
    val kcalColor = Color.parseColor("#FF9F1C")
    val stepColor = Color.parseColor("#2ECC71")
    val exColor = Color.parseColor("#2F80ED")
    /** progres 0..∞ (1 = target tercapai) */
    var kcal = 0f
    var steps = 0f
    var ex = 0f
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val sw = min(width, height) * 0.105f
        val gap = sw * 0.28f
        val r0 = min(width, height) / 2f - sw / 2f - px(1f)
        val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sw * 0.72f; textAlign = Paint.Align.CENTER }
        listOf(Triple(kcal, kcalColor, "🔥"), Triple(steps, stepColor, "👟"), Triple(ex, exColor, "⏱")).forEachIndexed { i, (v, col, ic) ->
            val r = r0 - i * (sw + gap)
            line.strokeWidth = sw; line.strokeCap = Paint.Cap.ROUND
            line.color = col; line.alpha = 55
            c.drawCircle(cx, cy, r, line)
            line.alpha = 255
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            val f = v.coerceIn(0f, 1f)
            // ujung awal selalu terlihat sebagai titik kecil
            c.drawArc(rect, -90f + 8f, maxOf(360f * f - 8f, 0.5f), false, line)
            if (v > 1f) { line.alpha = 140; c.drawArc(rect, -90f, 360f * ((v - 1f).coerceAtMost(1f)), false, line); line.alpha = 255 }
            c.drawText(ic, cx - sw * 0.05f, cy - r + emoji.textSize * 0.36f, emoji)
        }
    }
}

/** Batang per jam: cincin vs HP, berdampingan. */
class HourBarsView(ctx: Context, pal: Pal) : BaseChart(ctx, pal) {
    override val hDp = 170f
    var ring = DoubleArray(24)
    var phone = DoubleArray(24)
    var nowHour = -1
    override fun onDraw(c: Canvas) {
        val left = if (compact) 0f else px(30f)
        val bottom = if (compact) height.toFloat() else height - px(20f)
        val top = if (compact) px(2f) else px(8f)
        val w = (width - left) / 24f
        val maxV = max(100.0, max(ring.maxOrNull() ?: 0.0, phone.maxOrNull() ?: 0.0))
        val nice = niceCeil(maxV)
        // grid
        line.strokeWidth = px(1f); line.color = pal.grid
        for (i in 0..2) {
            val y = bottom - (bottom - top) * i / 2f
            if (compact && i != 0) continue
            c.drawLine(left, y, width.toFloat(), y, line)
            if (!compact) c.drawText(short(nice * i / 2), 0f, y + px(4f), text)
        }
        val bw = w * 0.36f
        for (h in 0 until 24) {
            val x = left + w * h
            if (h == nowHour) {
                fill.color = pal.grid; c.drawRect(x, top, x + w, bottom, fill)
            }
            fun bar(v: Double, off: Float, col: Int) {
                if (v < 0.5) return
                val y = bottom - ((bottom - top) * (v / nice)).toFloat()
                fill.color = col
                c.drawRoundRect(RectF(x + off, y, x + off + bw, bottom), px(2f), px(2f), fill)
            }
            bar(ring[h], w * 0.12f, pal.ring)
            bar(phone[h], w * 0.12f + bw, pal.phone)
            if (!compact && h % 6 == 0) c.drawText("%02d".format(h), x, height - px(4f), text)
        }
    }
}

/** Garis waktu tidur per tahap. */
class SleepView(ctx: Context, pal: Pal) : BaseChart(ctx, pal) {
    override val hDp = 74f
    var segs: List<Ycbt.SleepSeg> = emptyList()
    override fun onDraw(c: Canvas) {
        if (segs.isEmpty()) {
            if (!compact) c.drawText("Belum ada data tidur", 0f, px(30f), text); return
        }
        val s0 = segs.first().startMs
        val s1 = segs.maxOf { it.startMs + it.durSec * 1000L }
        val span = max(1L, s1 - s0).toFloat()
        val top = if (compact) 0f else px(6f)
        val bh = if (compact) height.toFloat() else px(36f)
        for (s in segs) {
            val x0 = width * (s.startMs - s0) / span
            val x1 = width * (s.startMs + s.durSec * 1000L - s0) / span
            fill.color = pal.stage(s.stage)
            // tahap lebih dalam digambar lebih tinggi
            val inset = when (s.stage) { 1 -> 0f; 3 -> bh * 0.2f; 2 -> bh * 0.35f; else -> bh * 0.55f }
            c.drawRect(x0, top + inset, max(x1, x0 + 1f), top + bh, fill)
        }
        if (compact) return
        c.drawText(fmtTime(s0), 0f, height - px(6f), text)
        val endTxt = fmtTime(s1)
        c.drawText(endTxt, width - text.measureText(endTxt), height - px(6f), text)
    }
}

/** Detak jantung sepanjang hari. */
class HeartView(ctx: Context, pal: Pal) : BaseChart(ctx, pal) {
    override val hDp = 110f
    /** titik dari cincin */
    var pts: List<Ycbt.HeartRec> = emptyList()
    /** garis dari jam */
    var watch: List<Ycbt.HeartRec> = emptyList()
    var dayStart = 0L
    override fun onDraw(c: Canvas) {
        val all = pts + watch
        if (all.isEmpty()) { if (!compact) c.drawText("Belum ada data detak jantung", 0f, px(30f), text); return }
        val left = if (compact) 0f else px(26f)
        val top = px(4f)
        val bottom = if (compact) height - px(4f) else height - px(18f)
        val lo = ((all.minOf { it.hr } - 5) / 10) * 10
        val hi = ((all.maxOf { it.hr } + 14) / 10) * 10
        line.strokeWidth = px(1f); line.color = pal.grid
        if (!compact) for (v in listOf(lo, hi)) {
            val y = bottom - (bottom - top) * (v - lo) / (hi - lo).toFloat()
            c.drawLine(left, y, width.toFloat(), y, line)
            c.drawText("$v", 0f, y + px(4f), text)
        }
        val day = 86_400_000f
        fun x(r: Ycbt.HeartRec) = left + (width - left) * ((r.tsMs - dayStart) / day)
        fun y(r: Ycbt.HeartRec) = bottom - (bottom - top) * (r.hr - lo) / (hi - lo).toFloat()
        if (watch.isNotEmpty()) {
            val p = Path()
            var prev: Ycbt.HeartRec? = null
            for (r in watch) {
                // putus garis kalau ada celah > 30 menit
                if (prev == null || r.tsMs - prev.tsMs > 30 * 60_000L) p.moveTo(x(r), y(r)) else p.lineTo(x(r), y(r))
                prev = r
            }
            line.color = pal.heart; line.strokeWidth = px(1.6f); line.strokeJoin = Paint.Join.ROUND
            c.drawPath(p, line)
        }
        if (pts.isNotEmpty()) {
            if (watch.isEmpty() && pts.size > 1) {
                val p = Path()
                pts.forEachIndexed { i, r -> if (i == 0) p.moveTo(x(r), y(r)) else p.lineTo(x(r), y(r)) }
                line.color = pal.ring; line.strokeWidth = px(1.6f); c.drawPath(p, line)
            }
            fill.color = pal.ring
            for (r in pts) c.drawCircle(x(r), y(r), px(if (compact) 2.5f else 3.5f), fill)
        }
        if (!compact) for (h in listOf(0, 6, 12, 18)) c.drawText("%02d".format(h), left + (width - left) * h / 24f, height - px(4f), text)
    }
}

/** Grafik mini untuk tanda vital (satu atau dua garis). */
class SparkView(ctx: Context, pal: Pal) : BaseChart(ctx, pal) {
    override val hDp = 44f
    var dayStart = 0L
    var a: List<Pair<Long, Int>> = emptyList()
    var b: List<Pair<Long, Int>> = emptyList()
    var color = pal.ring
    override fun onDraw(c: Canvas) {
        val all = a + b
        if (all.isEmpty()) return
        val lo = all.minOf { it.second } - 3; val hi = all.maxOf { it.second } + 3
        val top = px(4f); val bottom = height - px(4f)
        fun xy(p: Pair<Long, Int>) = Pair(
            width * ((p.first - dayStart) / 86_400_000f).coerceIn(0f, 1f),
            bottom - (bottom - top) * (p.second - lo) / (hi - lo).toFloat())
        for ((series, alpha) in listOf(a to 255, b to 130)) {
            if (series.isEmpty()) continue
            line.color = color; line.alpha = alpha; line.strokeWidth = px(2f)
            fill.color = color; fill.alpha = alpha
            val path = Path()
            series.forEachIndexed { i, p -> val (x, y) = xy(p); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            if (series.size > 1) c.drawPath(path, line)
            for (p in series) { val (x, y) = xy(p); c.drawCircle(x, y, px(2.5f), fill) }
        }
        line.alpha = 255; fill.alpha = 255
    }
}

/** Total 7 hari terakhir. */
class WeekView(ctx: Context, pal: Pal) : BaseChart(ctx, pal) {
    override val hDp = 150f
    var days: List<Pair<LocalDate, Double>> = emptyList()
    var goal = 10_000
    var selected: LocalDate? = null
    var onPick: ((LocalDate) -> Unit)? = null
    init {
        setOnTouchListener { _, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP && days.isNotEmpty()) {
                val i = (e.x / (width / days.size.toFloat())).toInt().coerceIn(0, days.size - 1)
                onPick?.invoke(days[i].first)
            }
            true
        }
    }
    override fun onDraw(c: Canvas) {
        if (days.isEmpty()) return
        val top = if (compact) px(2f) else px(18f)
        val bottom = height - if (compact) px(14f) else px(22f)
        val maxV = (if (compact) max(1.0, days.maxOf { it.second }) else max(goal.toDouble(), days.maxOf { it.second })) * 1.05
        val w = width / days.size.toFloat()
        // garis target
        val gy = bottom - ((bottom - top) * goal / maxV).toFloat()
        line.color = pal.muted; line.strokeWidth = px(1f)
        line.pathEffect = DashPathEffect(floatArrayOf(px(4f), px(4f)), 0f)
        if (!compact) c.drawLine(0f, gy, width.toFloat(), gy, line)
        line.pathEffect = null
        val center = Paint(text).apply { textAlign = Paint.Align.CENTER }
        days.forEachIndexed { i, (d, v) ->
            val x = w * i
            val y = bottom - ((bottom - top) * v / maxV).toFloat()
            fill.color = if (d == selected) pal.ring else pal.track
            if (v >= 0.5) c.drawRoundRect(RectF(x + w * 0.2f, y, x + w * 0.8f, bottom), px(4f), px(4f), fill)
            center.color = if (d == selected) pal.fg else pal.muted
            val dn = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("id", "ID"))
            if (compact) { center.textSize = px(9f); c.drawText(dn.take(1), x + w / 2, height - px(2f), center) }
            else {
                c.drawText(dn, x + w / 2, height - px(5f), center)
                if (v >= 0.5) c.drawText(short(v), x + w / 2, y - px(4f), center)
            }
        }
    }
}

private fun niceCeil(v: Double): Double {
    val steps = doubleArrayOf(100.0, 200.0, 500.0, 1000.0, 1500.0, 2000.0, 3000.0, 5000.0, 10000.0, 20000.0)
    return steps.firstOrNull { it >= v } ?: v
}

private fun short(v: Double): String =
    if (v >= 1000) "%.1frb".format(v / 1000).replace(".0rb", "rb").replace('.', ',') else v.roundToInt().toString()

/** Grafik minggu/bulan: batang (jumlah/rata²) atau rentang min–maks per hari. */
class PeriodBarsView(ctx: Context, pal: Pal) : BaseChart(ctx, pal) {
    override val hDp = 200f
    var vals: List<DayVal> = emptyList()
    var metric: Metric = Metric.STEPS
    var color = pal.ring
    var selected: LocalDate? = null
    var onPick: ((LocalDate) -> Unit)? = null
    init {
        setOnTouchListener { _, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP && vals.isNotEmpty()) {
                val left = px(32f)
                val i = ((e.x - left) / ((width - left) / vals.size.toFloat())).toInt().coerceIn(0, vals.size - 1)
                if (vals[i].v != null) onPick?.invoke(vals[i].date)
            }
            true
        }
    }

    override fun onDraw(c: Canvas) {
        if (vals.isEmpty()) return
        val left = px(32f); val top = px(10f); val bottom = height - px(20f)
        val w = (width - left) / vals.size
        val has = vals.filter { it.v != null }
        val ranged = metric.ranged
        var lo: Double; var hi: Double
        if (has.isEmpty()) { lo = 0.0; hi = 1.0 }
        else if (ranged) {
            lo = has.minOf { it.lo ?: it.v!! }; hi = has.maxOf { it.hi ?: it.v!! }
            val pad = maxOf(3.0, (hi - lo) * 0.15); lo -= pad; hi += pad
            if (metric == Metric.SPO2) hi = minOf(hi, 101.0)
        } else { lo = 0.0; hi = has.maxOf { it.v!! } * 1.12 }
        if (hi - lo < 1e-6) hi = lo + 1
        fun y(v: Double) = bottom - ((bottom - top) * (v - lo) / (hi - lo)).toFloat()

        // grid + label sumbu
        line.strokeWidth = px(1f); line.color = pal.grid; line.pathEffect = null
        for (i in 0..2) {
            val v = lo + (hi - lo) * i / 2
            val yy = y(v)
            c.drawLine(left, yy, width.toFloat(), yy, line)
            c.drawText(axis(v), 0f, yy + px(4f), text)
        }
        // garis rata-rata
        if (has.isNotEmpty() && !ranged) {
            val avg = has.map { it.v!! }.average()
            line.color = color; line.alpha = 160
            line.pathEffect = DashPathEffect(floatArrayOf(px(4f), px(4f)), 0f)
            c.drawLine(left, y(avg), width.toFloat(), y(avg), line)
            line.pathEffect = null; line.alpha = 255
        }
        val center = Paint(text).apply { textAlign = Paint.Align.CENTER; textSize = px(10f) }
        val bw = minOf(w * 0.6f, px(22f))
        vals.forEachIndexed { i, dv ->
            val cx = left + w * i + w / 2
            val sel = dv.date == selected
            if (sel) { fill.color = pal.grid; c.drawRoundRect(RectF(cx - w / 2 + 1, top, cx + w / 2 - 1, bottom), px(6f), px(6f), fill) }
            val v = dv.v
            if (v != null) {
                fill.color = color; fill.alpha = if (sel || selected == null) 255 else 200
                if (!ranged) {
                    c.drawRoundRect(RectF(cx - bw / 2, y(v), cx + bw / 2, bottom), bw / 3, bw / 3, fill)
                } else {
                    val l = dv.lo ?: v; val h = dv.hi ?: v
                    fill.alpha = 90
                    c.drawRoundRect(RectF(cx - bw / 3, y(h) - px(2f), cx + bw / 3, y(l) + px(2f)), bw / 3, bw / 3, fill)
                    fill.alpha = 255
                    c.drawCircle(cx, y(v), px(3.5f), fill)
                    dv.v2?.let { c.drawCircle(cx, y(it), px(3.5f), fill) }
                }
                fill.alpha = 255
            }
            // label tanggal
            val lbl = if (vals.size <= 7) dv.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("id", "ID"))
            else if (dv.date.dayOfMonth == 1 || dv.date.dayOfMonth % 5 == 0) dv.date.dayOfMonth.toString() else ""
            center.color = if (sel) pal.fg else pal.muted
            if (lbl.isNotEmpty()) c.drawText(lbl, cx, height - px(5f), center)
        }
    }

    private fun axis(v: Double): String = when {
        metric == Metric.SLEEP -> "${(v / 60).roundToInt()}j"
        v >= 1000 -> "%.1frb".format(v / 1000).replace(".0rb", "rb").replace('.', ',')
        else -> v.roundToInt().toString()
    }
}
