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
    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), px(hDp).roundToInt())
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
        val r = min(width, height) / 2f - px(14f)
        val sw = px(16f)
        line.strokeWidth = sw; line.strokeCap = Paint.Cap.ROUND
        line.color = pal.track
        c.drawCircle(cx, cy, r, line)
        val frac = (steps / goal).coerceIn(0.0, 1.0).toFloat()
        line.color = pal.ring
        if (frac > 0f) c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * frac, false, line)
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.fg; textSize = px(44f); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        c.drawText("%,d".format(steps.roundToInt()).replace(',', '.'), cx, cy + px(12f), big)
        val small = Paint(text).apply { textAlign = Paint.Align.CENTER; textSize = px(13f) }
        c.drawText("langkah", cx, cy - px(34f), small)
        c.drawText(sub, cx, cy + px(40f), small)
    }
}

/** Batang per jam: cincin vs HP, berdampingan. */
class HourBarsView(ctx: Context, pal: Pal) : BaseChart(ctx, pal) {
    override val hDp = 170f
    var ring = DoubleArray(24)
    var phone = DoubleArray(24)
    var nowHour = -1
    override fun onDraw(c: Canvas) {
        val left = px(30f); val bottom = height - px(20f); val top = px(8f)
        val w = (width - left) / 24f
        val maxV = max(100.0, max(ring.maxOrNull() ?: 0.0, phone.maxOrNull() ?: 0.0))
        val nice = niceCeil(maxV)
        // grid
        line.strokeWidth = px(1f); line.color = pal.grid
        for (i in 0..2) {
            val y = bottom - (bottom - top) * i / 2f
            c.drawLine(left, y, width.toFloat(), y, line)
            c.drawText(short(nice * i / 2), 0f, y + px(4f), text)
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
            if (h % 6 == 0) c.drawText("%02d".format(h), x, height - px(4f), text)
        }
    }
}

/** Garis waktu tidur per tahap. */
class SleepView(ctx: Context, pal: Pal) : BaseChart(ctx, pal) {
    override val hDp = 74f
    var segs: List<Ycbt.SleepSeg> = emptyList()
    override fun onDraw(c: Canvas) {
        if (segs.isEmpty()) {
            c.drawText("Belum ada data tidur", 0f, px(30f), text); return
        }
        val s0 = segs.first().startMs
        val s1 = segs.maxOf { it.startMs + it.durSec * 1000L }
        val span = max(1L, s1 - s0).toFloat()
        val top = px(6f); val bh = px(36f)
        for (s in segs) {
            val x0 = width * (s.startMs - s0) / span
            val x1 = width * (s.startMs + s.durSec * 1000L - s0) / span
            fill.color = pal.stage(s.stage)
            // tahap lebih dalam digambar lebih tinggi
            val inset = when (s.stage) { 1 -> 0f; 3 -> bh * 0.2f; 2 -> bh * 0.35f; else -> bh * 0.55f }
            c.drawRect(x0, top + inset, max(x1, x0 + 1f), top + bh, fill)
        }
        c.drawText(fmtTime(s0), 0f, height - px(6f), text)
        val endTxt = fmtTime(s1)
        c.drawText(endTxt, width - text.measureText(endTxt), height - px(6f), text)
    }
}

/** Detak jantung sepanjang hari. */
class HeartView(ctx: Context, pal: Pal) : BaseChart(ctx, pal) {
    override val hDp = 110f
    var pts: List<Ycbt.HeartRec> = emptyList()
    var dayStart = 0L
    override fun onDraw(c: Canvas) {
        if (pts.isEmpty()) { c.drawText("Belum ada data detak jantung", 0f, px(30f), text); return }
        val left = px(26f); val top = px(8f); val bottom = height - px(18f)
        val lo = ((pts.minOf { it.hr } - 5) / 10) * 10
        val hi = ((pts.maxOf { it.hr } + 14) / 10) * 10
        line.strokeWidth = px(1f); line.color = pal.grid
        for (v in listOf(lo, hi)) {
            val y = bottom - (bottom - top) * (v - lo) / (hi - lo).toFloat()
            c.drawLine(left, y, width.toFloat(), y, line)
            c.drawText("$v", 0f, y + px(4f), text)
        }
        val day = 86_400_000f
        val p = Path()
        pts.forEachIndexed { i, r ->
            val x = left + (width - left) * ((r.tsMs - dayStart) / day)
            val y = bottom - (bottom - top) * (r.hr - lo) / (hi - lo).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        line.color = pal.heart; line.strokeWidth = px(2f); line.strokeJoin = Paint.Join.ROUND
        c.drawPath(p, line)
        for (h in listOf(0, 6, 12, 18)) c.drawText("%02d".format(h), left + (width - left) * h / 24f, height - px(4f), text)
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
        val top = px(18f); val bottom = height - px(22f)
        val maxV = max(goal.toDouble(), days.maxOf { it.second }) * 1.05
        val w = width / days.size.toFloat()
        // garis target
        val gy = bottom - ((bottom - top) * goal / maxV).toFloat()
        line.color = pal.muted; line.strokeWidth = px(1f)
        line.pathEffect = DashPathEffect(floatArrayOf(px(4f), px(4f)), 0f)
        c.drawLine(0f, gy, width.toFloat(), gy, line)
        line.pathEffect = null
        val center = Paint(text).apply { textAlign = Paint.Align.CENTER }
        days.forEachIndexed { i, (d, v) ->
            val x = w * i
            val y = bottom - ((bottom - top) * v / maxV).toFloat()
            fill.color = if (d == selected) pal.ring else pal.track
            if (v >= 0.5) c.drawRoundRect(RectF(x + w * 0.2f, y, x + w * 0.8f, bottom), px(4f), px(4f), fill)
            center.color = if (d == selected) pal.fg else pal.muted
            c.drawText(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("id", "ID")), x + w / 2, height - px(5f), center)
            if (v >= 0.5) c.drawText(short(v), x + w / 2, y - px(4f), center)
        }
    }
}

private fun niceCeil(v: Double): Double {
    val steps = doubleArrayOf(100.0, 200.0, 500.0, 1000.0, 1500.0, 2000.0, 3000.0, 5000.0, 10000.0, 20000.0)
    return steps.firstOrNull { it >= v } ?: v
}

private fun short(v: Double): String =
    if (v >= 1000) "%.1frb".format(v / 1000).replace(".0rb", "rb").replace('.', ',') else v.roundToInt().toString()
