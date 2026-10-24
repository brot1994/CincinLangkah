package id.bram.cincinlangkah

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/** Menghitung langkah per jam (0–23) untuk satu tanggal dari tiap sumber. */
object Report {

    class Day(
        val ringSport: DoubleArray,     // cincin: bucket aktivitas 30 menit
        val ringCum: DoubleArray,       // cincin: selisih penghitung kumulatif
        val phone: DoubleArray,         // step counter HP (aplikasi ini)
        val phoneCovered: BooleanArray, // jam yang sudah tercakup sampel HP
        val hc: DoubleArray?,           // Health Connect (Samsung Health)
    )

    fun dayStart(d: LocalDate): Long = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun build(ctx: Context, date: LocalDate, hc: DoubleArray?): Day {
        val db = Db.get(ctx)
        val from = dayStart(date)
        val to = dayStart(date.plusDays(1))
        val zone = ZoneId.systemDefault()
        val hourStarts = LongArray(25) { h ->
            if (h == 24) to else date.atStartOfDay(zone).plusHours(h.toLong()).toInstant().toEpochMilli()
        }

        // 1) Bucket aktivitas cincin
        val sport = DoubleArray(24)
        for (r in db.sport(from - 3_600_000L, to)) spread(sport, hourStarts, r.startMs, maxOf(r.endMs, r.startMs + 1), r.steps.toDouble())

        // 2) Penghitung kumulatif cincin (reset tiap tengah malam)
        val cum = DoubleArray(24)
        var prevTs = from
        var prevSteps = 0
        for (r in db.all(from, to)) {
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
        return Day(sport, cum, phone, covered, hc)
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
