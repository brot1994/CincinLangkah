package id.bram.cincinlangkah

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Metrik yang punya halaman detail. */
enum class Metric(val title: String, val emoji: String) {
    STEPS("Langkah", "👟"),
    KCAL("Kalori", "🔥"),
    SLEEP("Tidur", "🌙"),
    HEART("Detak jantung", "❤"),
    EXERCISE("Olahraga", "🏓"),
    SPO2("SpO₂", "🫁"),
    BP("Tensi", "🩺"),
    HRV("HRV", "〰"),
    RESP("Laju napas", "🌬"),
    STRESS("Stres", "😣");

    fun color(p: Pal): Int = when (this) {
        STEPS -> p.ring; KCAL -> android.graphics.Color.parseColor("#FF9F1C"); SLEEP -> p.deep
        HEART -> p.heart; EXERCISE -> android.graphics.Color.parseColor("#2F80ED"); SPO2 -> p.spo2
        BP -> p.bp; HRV -> p.hrv; RESP -> p.resp; STRESS -> p.bp
    }

    /** true = nilai harian dijumlah (langkah, kalori, menit); false = dirata-rata */
    val additive get() = this == STEPS || this == KCAL || this == EXERCISE

    /** grafik periode memakai rentang min–maks */
    val ranged get() = this == HEART || this == SPO2 || this == HRV || this == RESP || this == BP || this == STRESS
}

/** Satu nilai per hari. Untuk BP: v = sistolik rata², v2 = diastolik rata². */
data class DayVal(val date: LocalDate, val v: Double?, val lo: Double? = null, val hi: Double? = null, val v2: Double? = null)

object Daily {
    private val cache = HashMap<Pair<Metric, LocalDate>, DayVal>()

    fun clearToday() { cache.keys.removeAll { it.second == LocalDate.now() } }

    private fun bounds(d: LocalDate): Pair<Long, Long> {
        val z = ZoneId.systemDefault()
        return d.atStartOfDay(z).toInstant().toEpochMilli() to d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
    }

    private suspend fun safe(block: suspend () -> Unit) {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
    }

    /** Data lengkap satu hari untuk tampilan "Hari". */
    class DayDetail(
        val report: Report.Day,
        val heartRing: List<Ycbt.HeartRec>, val heartWatch: List<Ycbt.HeartRec>, val heartBand: List<Ycbt.HeartRec>,
        val sleep: List<Ycbt.SleepSeg>, val sleepFromWatch: Boolean,
        val vitals: List<Ycbt.Vital>,
        val sessions: List<Triple<Long, Long, String>>,
    )

    suspend fun detail(ctx: Context, m: Metric, d: LocalDate): DayDetail {
        val (from, to) = bounds(d)
        var hc: DoubleArray? = null
        safe { if (HcSteps.available(ctx) && HcSteps.granted(ctx)) hc = HcSteps.hourly(ctx, d) }
        val rep = withContext(Dispatchers.IO) { Report.build(ctx, d, hc) }
        var wHr = emptyList<Ycbt.HeartRec>()
        var wSleep = emptyList<Ycbt.SleepSeg>()
        var wSpo2 = emptyList<Ycbt.Vital>()
        var wSess = emptyList<WatchData.Session>()
        if (m == Metric.HEART || m == Metric.EXERCISE) safe { wHr = WatchData.heart(ctx, from, to) }
        if (m == Metric.SLEEP) safe { wSleep = WatchData.sleep(ctx, d) }
        if (m == Metric.SPO2) safe { wSpo2 = WatchData.spo2(ctx, from, to) }
        if (m == Metric.EXERCISE || m == Metric.KCAL) safe { wSess = WatchData.sessions(ctx, from, to) }

        fun asleep(l: List<Ycbt.SleepSeg>) = l.filter { it.stage in 1..3 }.sumOf { it.durSec }
        val useW = asleep(wSleep) > asleep(rep.sleep)
        val vit = when (m) {
            Metric.SPO2 -> (rep.spo2 + wSpo2).sortedBy { it.tsMs }
            Metric.BP -> (rep.bp + withContext(Dispatchers.IO) {
                Db.get(ctx).vitals("bpcuff", from, to) + BpCal.estimates(ctx, from, to) }).sortedBy { it.tsMs }
            Metric.HRV -> rep.hrv; Metric.RESP -> rep.resp
            Metric.STRESS -> withContext(Dispatchers.IO) { Db.get(ctx).vitals("stress", from, to) }
            else -> emptyList()
        }
        val autos = withContext(Dispatchers.IO) { Db.get(ctx).exercises(from, to) }
            .filter { e -> wSess.none { it.start < e.end && it.end > e.start } }
        val sess = (autos.map { Triple(it.start, it.end, "${Exercise.emoji(it.type)} ${Exercise.label(it.type)}") } +
            wSess.map { Triple(it.start, it.end, "${it.type?.let { t -> Exercise.emoji(t) } ?: "🏅"} ${it.label} ⌚") })
            .sortedBy { it.first }
        return DayDetail(rep, rep.heart, wHr, rep.heartBand, Ycbt.withGaps(if (useW) wSleep else rep.sleep), useW, vit, sess)
    }

    /** Nilai ringkas satu hari untuk grafik minggu/bulan (di-cache, kecuali hari ini). */
    suspend fun value(ctx: Context, m: Metric, d: LocalDate): DayVal {
        if (d > LocalDate.now()) return DayVal(d, null)
        cache[m to d]?.let { if (d != LocalDate.now()) return it }
        val (from, to) = bounds(d)
        val db = Db.get(ctx)
        val out: DayVal = when (m) {
            Metric.STEPS, Metric.KCAL -> {
                var hc: DoubleArray? = null
                safe { if (HcSteps.available(ctx) && HcSteps.granted(ctx)) hc = HcSteps.hourly(ctx, d) }
                val r = withContext(Dispatchers.IO) { Report.build(ctx, d, hc) }
                val v = if (m == Metric.STEPS) r.total else r.kcal
                DayVal(d, v.takeIf { it >= 0.5 })
            }
            Metric.SLEEP -> {
                val ring = withContext(Dispatchers.IO) {
                    val z = ZoneId.systemDefault()
                    db.sleep(d.minusDays(1).atStartOfDay(z).plusHours(18).toInstant().toEpochMilli(),
                        d.atStartOfDay(z).plusHours(14).toInstant().toEpochMilli())
                }
                var w = emptyList<Ycbt.SleepSeg>()
                safe { w = WatchData.sleep(ctx, d) }
                fun asleep(l: List<Ycbt.SleepSeg>) = l.filter { it.stage in 1..3 }.sumOf { it.durSec } / 60.0
                val segs = if (asleep(w) > asleep(ring)) w else ring
                val tot = asleep(segs)
                DayVal(d, tot.takeIf { it > 0 }, v2 = segs.filter { it.stage == 1 }.sumOf { it.durSec } / 60.0)
            }
            Metric.HEART -> {
                val ring = withContext(Dispatchers.IO) { db.heart(from, to) + db.bandHr(from, to) }
                var w = emptyList<Ycbt.HeartRec>()
                safe { w = WatchData.heart(ctx, from, to) }
                val all = (ring + w).map { it.hr.toDouble() }
                if (all.isEmpty()) DayVal(d, null) else DayVal(d, all.average(), all.min(), all.max())
            }
            Metric.EXERCISE -> {
                var ws = emptyList<WatchData.Session>()
                safe { ws = WatchData.sessions(ctx, from, to) }
                val autos = withContext(Dispatchers.IO) { db.exercises(from, to) }
                    .filter { e -> ws.none { it.start < e.end && it.end > e.start } }
                val min = autos.sumOf { (it.end - it.start) / 60_000.0 } + ws.sumOf { (it.end - it.start) / 60_000.0 }
                DayVal(d, min.takeIf { it >= 1 })
            }
            Metric.SPO2, Metric.HRV, Metric.RESP, Metric.STRESS -> {
                val kind = when (m) { Metric.SPO2 -> "spo2"; Metric.HRV -> "hrv"; Metric.STRESS -> "stress"; else -> "resp" }
                var list = withContext(Dispatchers.IO) { db.vitals(kind, from, to) }
                if (m == Metric.SPO2) safe { list = list + WatchData.spo2(ctx, from, to) }
                val v = list.map { it.v1.toDouble() }
                if (v.isEmpty()) DayVal(d, null) else DayVal(d, v.average(), v.min(), v.max())
            }
            Metric.BP -> {
                val list = withContext(Dispatchers.IO) { db.vitals("bp", from, to) + db.vitals("bpcuff", from, to) }
                if (list.isEmpty()) DayVal(d, null)
                else DayVal(d, list.map { it.v1 }.average(), list.minOf { it.v2 }.toDouble(),
                    list.maxOf { it.v1 }.toDouble(), list.map { it.v2 }.average())
            }
        }
        cache[m to d] = out
        return out
    }
}
