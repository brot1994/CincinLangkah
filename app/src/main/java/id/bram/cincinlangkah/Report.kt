package id.bram.cincinlangkah

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/** Menghitung langkah per jam (0–23) untuk satu tanggal dari tiap sumber, plus ringkasan dashboard. */
object Report {

    class Day(
        val date: LocalDate,
        val ringSport: DoubleArray,     // cincin: bucket aktivitas 30 menit
        val ringCum: DoubleArray,       // cincin: selisih penghitung kumulatif
        val phone: DoubleArray,         // step counter HP (aplikasi ini)
        val phoneCovered: BooleanArray, // jam yang sudah tercakup sampel HP
        val hc: DoubleArray?,           // Health Connect (Samsung Health)
        val ring: DoubleArray,          // langkah cincin terbaik per jam
        val phoneBest: DoubleArray,     // langkah HP terbaik per jam (Samsung kalau ada, kalau tidak sampler)
        val fused: DoubleArray,         // gabungan: per jam ambil yang lebih besar
        val fromPhone: BooleanArray,    // jam di mana HP yang dipakai
        val distKm: Double,
        val kcal: Double,
        val sleep: List<Ycbt.SleepSeg>,
        val heart: List<Ycbt.HeartRec>,
        val bp: List<Ycbt.Vital>,
        val spo2: List<Ycbt.Vital>,
        val hrv: List<Ycbt.Vital>,
        val resp: List<Ycbt.Vital>,
        val watch: DoubleArray?,        // langkah dari jam/Fitbit (Health Connect)
        val fromWatch: BooleanArray,    // jam di mana data jam yang dipakai
        val band: DoubleArray?,         // langkah dari gelang QC
        val fromBand: BooleanArray,
        val heartBand: List<Ycbt.HeartRec>,
    ) {
        val total get() = fused.sum()
        fun sleepMin(stage: Int) = sleep.filter { it.stage == stage }.sumOf { it.durSec } / 60
        val sleepTotalMin get() = sleep.filter { it.stage in 1..3 }.sumOf { it.durSec } / 60
    }

    fun dayStart(d: LocalDate): Long = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun build(ctx: Context, date: LocalDate, hc: DoubleArray?): Day {
        val db = Db.get(ctx)
        val zone = ZoneId.systemDefault()
        val from = dayStart(date)
        val to = dayStart(date.plusDays(1))
        val hourStarts = LongArray(25) { h ->
            if (h == 24) to else date.atStartOfDay(zone).plusHours(h.toLong()).toInstant().toEpochMilli()
        }

        // 1) Bucket aktivitas cincin
        val sport = DoubleArray(24)
        val sportRecs = db.sport(from - 3_600_000L, to)
        for (r in sportRecs) spread(sport, hourStarts, r.startMs, maxOf(r.endMs, r.startMs + 1), r.steps.toDouble())

        // 2) Penghitung kumulatif cincin (reset tiap tengah malam)
        val cum = DoubleArray(24)
        val allRecs = db.all(from, to)
        var prevTs = from
        var prevSteps = 0
        for (r in allRecs) {
            val delta = if (r.steps >= prevSteps) r.steps - prevSteps else r.steps
            if (delta > 0) spread(cum, hourStarts, prevTs, maxOf(r.tsMs, prevTs + 1), delta.toDouble())
            prevTs = r.tsMs; prevSteps = r.steps
        }

        // 3) Step counter HP
        val phone = DoubleArray(24)
        val covered = BooleanArray(24)
        val samples = db.phone(from, to)
        for (i in 1 until samples.size) {
            val a = samples[i - 1]; val b = samples[i]
            val sameBoot = kotlin.math.abs(a.boot - b.boot) < 120_000L && b.counter >= a.counter
            val delta: Double
            val start: Long
            if (sameBoot) { delta = (b.counter - a.counter).toDouble(); start = a.ts }
            else { delta = b.counter.toDouble(); start = maxOf(a.ts, b.boot) } // HP restart
            if (b.ts - start > 6 * 3_600_000L) continue // celah terlalu lebar, abaikan
            spread(phone, hourStarts, start, maxOf(b.ts, start + 1), delta)
            for (h in 0 until 24) if (hourStarts[h + 1] > start && hourStarts[h] < b.ts) covered[h] = true
        }

        // 4) Gabungan per jam
        val useHc = hc != null && hc.sum() > 0
        // Bucket aktivitas lebih rinci; penghitung kumulatif hanya cadangan kalau bucket kosong
        val ring = if (sport.sum() > 0) sport.copyOf() else cum.copyOf()
        val phoneBest = DoubleArray(24) { if (useHc) hc!![it] else if (covered[it]) phone[it] else 0.0 }
        // wearable: jam via Health Connect + gelang QC (per jam diambil yang terbesar)
        val band = DoubleArray(24)
        for ((ts, st) in db.bandSteps(from, to)) {
            val h = ((ts - from) / 3_600_000L).toInt()
            if (h in 0..23) band[h] += st.toDouble()
        }
        val hcW = HcSteps.watchFor(date)
        val watch = hcW?.takeIf { it.sum() > 0 }
        val bandOrNull = band.takeIf { it.sum() > 0 }
        val w = DoubleArray(24) { watch?.get(it) ?: 0.0 }
        val fromBand = BooleanArray(24) { band[it] > ring[it] && band[it] > phoneBest[it] && band[it] > w[it] }
        val fromWatch = BooleanArray(24) { !fromBand[it] && w[it] > ring[it] && w[it] > phoneBest[it] }
        val fromPhone = BooleanArray(24) { !fromWatch[it] && !fromBand[it] && phoneBest[it] > ring[it] }
        val fused = DoubleArray(24) { maxOf(ring[it], phoneBest[it], w[it], band[it]) }

        // Jarak & kalori: pakai rasio dari cincin kalau ada, kalau tidak perkiraan umum
        val rSteps = sportRecs.filter { it.startMs >= from }.sumOf { it.steps }
        val rDist = sportRecs.filter { it.startMs >= from }.sumOf { it.distM }
        val rKcal = sportRecs.filter { it.startMs >= from }.sumOf { it.kcal }
        val mPerStep = if (rSteps > 200 && rDist > 0) rDist.toDouble() / rSteps else 0.72
        val kcalPerStep = if (rSteps > 200 && rKcal > 0) rKcal.toDouble() / rSteps else 0.04
        val total = fused.sum()

        // Tidur: malam sebelumnya 18:00 s/d siang 14:00
        val sleepFrom = date.minusDays(1).atStartOfDay(zone).plusHours(18).toInstant().toEpochMilli()
        val sleepTo = date.atStartOfDay(zone).plusHours(14).toInstant().toEpochMilli()
        val sleep = db.sleep(sleepFrom, sleepTo)

        // Detak jantung hari ini (riwayat HR, kalau kosong pakai data gabungan)
        var heart = db.heart(from, to)
        if (heart.isEmpty()) heart = allRecs.filter { it.hr in 25..230 }.map { Ycbt.HeartRec(it.tsMs, it.hr) }

        return Day(date, sport, cum, phone, covered, hc, ring, phoneBest, fused, fromPhone,
            total * mPerStep / 1000.0, total * kcalPerStep, sleep, heart,
            db.vitals("bp", from, to), db.vitals("spo2", from, to),
            db.vitals("hrv", from, to), db.vitals("resp", from, to), watch, fromWatch,
            bandOrNull, fromBand, db.bandHr(from, to))
    }

    /** Total langkah gabungan untuk beberapa hari (untuk grafik mingguan). */
    fun week(ctx: Context, end: LocalDate, hcByDay: Map<LocalDate, DoubleArray>): List<Pair<LocalDate, Double>> =
        (6 downTo 0).map { i ->
            val d = end.minusDays(i.toLong())
            d to build(ctx, d, hcByDay[d]).total
        }

    /** Bagi `value` secara proporsional ke jam-jam yang tumpang tindih dengan [s, e). */
    private fun spread(out: DoubleArray, hs: LongArray, s: Long, e: Long, value: Double) {
        val len = (e - s).toDouble()
        for (h in 0 until 24) {
            val ov = minOf(e, hs[h + 1]) - maxOf(s, hs[h])
            if (ov > 0) out[h] += value * ov / len
        }
    }
}
