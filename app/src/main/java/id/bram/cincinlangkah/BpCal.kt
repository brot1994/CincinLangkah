package id.bram.cincinlangkah

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Kalibrasi tensi pribadi: pasangan (tensimeter manset, detak & HRV gelang saat itu).
 * Dari ≥10 pasangan dibuat rumus linier SYS/DIA = a + b·detak (b "diciutkan" ke 0 kalau datanya
 * tidak mendukung), lalu diuji dengan leave-one-out supaya ketahuan rata-rata melesetnya.
 */
object BpCal {
    const val MIN_PAIRS = 10
    const val MAX_MAE = 10.0     // rumus dianggap layak kalau rata-rata meleset sistolik ≤ 10 mmHg

    class Pair(val ts: Long, val sys: Int, val dia: Int, val hr: Int?, val rmssd: Int?, val ringSys: Int?, val ringDia: Int?) {
        fun json() = JSONObject().put("t", ts).put("s", sys).put("d", dia).put("h", hr ?: -1).put("r", rmssd ?: -1)
            .put("rs", ringSys ?: -1).put("rd", ringDia ?: -1)
        companion object {
            fun of(j: JSONObject) = Pair(j.getLong("t"), j.getInt("s"), j.getInt("d"),
                j.optInt("h", -1).takeIf { it > 0 }, j.optInt("r", -1).takeIf { it > 0 },
                j.optInt("rs", -1).takeIf { it > 0 }, j.optInt("rd", -1).takeIf { it > 0 })
        }
    }

    class Line(val a: Double, val b: Double) { fun at(x: Double) = a + b * x }
    class Model(val n: Int, val sys: Line, val dia: Line, val maeSys: Double, val maeDia: Double,
                val maeMeanSys: Double, val ringMae: Double?, val ringN: Int) {
        val ok get() = n >= MIN_PAIRS && maeSys <= MAX_MAE
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences("bpcal", Context.MODE_PRIVATE)

    fun pairs(ctx: Context): List<Pair> = try {
        val a = JSONArray(p(ctx).getString("pairs", "[]"))
        (0 until a.length()).map { Pair.of(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    private fun save(ctx: Context, l: List<Pair>) {
        p(ctx).edit().putString("pairs", JSONArray().apply { l.forEach { put(it.json()) } }.toString()).apply()
    }

    /** Simpan hasil manset (+ data gelang & cincin terdekat). Hasil manset juga masuk ke data tensi. */
    fun add(ctx: Context, sys: Int, dia: Int, hr: Int?, rmssd: Int?) {
        val now = System.currentTimeMillis()
        val db = Db.get(ctx)
        // tensi cincin terdekat (±90 menit)
        val ring = db.vitals("bp", now - 90 * 60_000L, now + 60_000L).filter { it.v1 in 70..230 }
            .minByOrNull { abs(it.tsMs - now) }
        save(ctx, pairs(ctx) + Pair(now, sys, dia, hr, rmssd, ring?.v1, ring?.v2))
        db.putVitals(listOf(Ycbt.Vital(now, "bpcuff", sys, dia)))
    }

    fun removeLast(ctx: Context) { val l = pairs(ctx); if (l.isNotEmpty()) save(ctx, l.dropLast(1)) }

    /** regresi linier satu peubah dengan penyusutan kemiringan (ridge) */
    private fun fit(x: List<Double>, y: List<Double>, lambda: Double = 30.0): Line {
        val mx = x.average(); val my = y.average()
        val sxy = x.indices.sumOf { (x[it] - mx) * (y[it] - my) }
        val sxx = x.sumOf { (it - mx) * (it - mx) }
        val b = sxy / (sxx + lambda * x.size)
        return Line(my - b * mx, b)
    }

    private fun loo(x: List<Double>, y: List<Double>): Double = x.indices.map { i ->
        val xs = x.filterIndexed { j, _ -> j != i }; val ys = y.filterIndexed { j, _ -> j != i }
        abs(fit(xs, ys).at(x[i]) - y[i])
    }.average()

    private fun looMean(y: List<Double>): Double = y.indices.map { i -> abs(y.filterIndexed { j, _ -> j != i }.average() - y[i]) }.average()

    fun model(ctx: Context): Model? {
        val all = pairs(ctx)
        val l = all.filter { it.hr != null }
        val rp = all.filter { it.ringSys != null }
        val ringMae = rp.takeIf { it.isNotEmpty() }?.map { abs(it.ringSys!! - it.sys).toDouble() }?.average()
        if (l.size < 3) return null
        val x = l.map { it.hr!!.toDouble() }
        val ys = l.map { it.sys.toDouble() }; val yd = l.map { it.dia.toDouble() }
        return Model(l.size, fit(x, ys), fit(x, yd), loo(x, ys), loo(x, yd), looMean(ys), ringMae, rp.size)
    }

    /** perkiraan tensi per jam dari detak gelang (hanya kalau rumusnya layak) */
    fun estimates(ctx: Context, from: Long, to: Long): List<Ycbt.Vital> {
        val m = model(ctx)?.takeIf { it.ok } ?: return emptyList()
        val hr = Db.get(ctx).bandHr(from, to).filter { it.hr in 35..180 }
        val z = ZoneId.systemDefault()
        return hr.groupBy { Instant.ofEpochMilli(it.tsMs).atZone(z).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli() }
            .map { (h, l) ->
                val x = l.map { it.hr }.average()
                Ycbt.Vital(h + 1_800_000L, "bpest", m.sys.at(x).roundToInt(), m.dia.at(x).roundToInt())
            }.sortedBy { it.tsMs }
    }
}
