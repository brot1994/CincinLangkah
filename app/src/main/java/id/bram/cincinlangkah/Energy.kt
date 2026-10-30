package id.bram.cincinlangkah

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Skor energi harian (mirip "Energy Score" QWatch / Body Battery):
 * terisi saat tidur (nyenyak > REM > ringan), terkuras saat bangun oleh aktivitas, olahraga dan stres.
 * Ini model perkiraan buatan aplikasi, bukan hasil ukur alat.
 */
object Energy {
    class Day(
        val date: LocalDate,
        val hourly: Array<Int?>,     // energi di akhir tiap jam (null = belum terjadi)
        val bedEnergy: Int,          // energi saat mulai tidur
        val morning: Int,            // energi saat bangun
        val charged: Int,            // terisi selama tidur
        val consumed: Int,           // terpakai sejak bangun
        val now: Int?,
        val deepMin: Int, val sleepMin: Int, val sleepHr: Int?,
        val wakeHour: Int?,
    ) {
        /** porsi yang terisi dibanding ruang kosong saat mulai tidur */
        val recoveryRate get() = if (bedEnergy >= 100) 100 else (charged * 100 / max(1, 100 - bedEnergy))
    }

    private val cache = HashMap<LocalDate, Day>()
    private const val SEED = 45

    fun clearToday() { cache.remove(LocalDate.now()) }

    /** Isi tidur: nyenyak 0,12/menit, REM 0,08, ringan 0,05, terjaga −0,1. */
    fun charge(deep: Int, rem: Int, light: Int, awake: Int) =
        (deep * 0.12 + rem * 0.08 + light * 0.05 - awake * 0.10).roundToInt().coerceIn(0, 70)

    suspend fun day(ctx: Context, d: LocalDate, depth: Int = 0): Day {
        val today = LocalDate.now()
        if (d != today) cache[d]?.let { return it }
        val z = ZoneId.systemDefault()
        val mid = d.atStartOfDay(z).toInstant().toEpochMilli()
        val n = try { Coach.night(ctx, d) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        // energi saat mulai tidur = energi akhir hari sebelumnya (maks. 3 hari ke belakang)
        val bedE = if (depth >= 3) SEED else try {
            day(ctx, d.minusDays(1), depth + 1).let { p -> p.hourly.lastOrNull { it != null } ?: SEED }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { SEED }
        val sleepMin = n?.sleepMin ?: 0
        val deep = n?.deepMin ?: 0; val rem = n?.remMin ?: 0
        val light = max(0, sleepMin - deep - rem)
        val ch = if (sleepMin > 0) charge(deep, rem, light, n?.awakeMin ?: 0) else 0
        val morning = min(100, bedE + ch)
        val bed = n?.bed; val wake = n?.wake
        val wakeHour = wake?.let { ((it - mid) / 3_600_000L).toInt() }?.takeIf { it in 0..23 }

        val db = Db.get(ctx)
        val rep = withContext(Dispatchers.IO) { Report.build(ctx, d, null) }
        val stress = withContext(Dispatchers.IO) { db.vitals("stress", mid, mid + 86_400_000L) }
        val ex = withContext(Dispatchers.IO) { db.exercises(mid, mid + 86_400_000L) }
        val nowMs = System.currentTimeMillis()
        val out = arrayOfNulls<Int>(24)
        var e = bedE.toDouble()
        for (h in 0 until 24) {
            val hs = mid + h * 3_600_000L; val he = hs + 3_600_000L
            if (hs > nowMs) break
            if (bed != null && wake != null && he > bed && hs < wake) {
                // jam tidur: isi sesuai porsi tidur yang lewat di jam ini
                val part = (min(he, wake) - max(hs, bed)).toDouble() / max(1L, wake - bed)
                e = min(100.0, e + ch * part)
            } else if (wake == null || hs >= wake || (bed != null && he <= bed)) {
                val steps = rep.fused[h]
                val exMin = ex.sumOf { x -> max(0L, min(he, x.end) - max(hs, x.start)) } / 60_000.0
                val st = stress.filter { it.tsMs in hs until he }.map { it.v1 }.takeIf { it.isNotEmpty() }?.average()
                var drain = 2.0 + steps / 1000.0 * 1.5 + exMin * 0.25
                if (st != null) drain += when { st >= 80 -> 3.0; st >= 60 -> 1.5; st < 30 -> -1.0; else -> 0.0 }
                e = (e - max(0.3, drain)).coerceIn(5.0, 100.0)
            }
            out[h] = e.roundToInt()
        }
        val nowE = out.lastOrNull { it != null }
        val consumed = if (wakeHour != null && nowE != null) max(0, morning - nowE) else 0
        val sleepHr = if (bed != null && wake != null) withContext(Dispatchers.IO) {
            (db.heart(bed, wake) + db.bandHr(bed, wake)).map { it.hr }.filter { it in 30..150 }
                .takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        } else null
        val r = Day(d, out, bedE, morning, ch, consumed, if (d == today) nowE else null, deep, sleepMin, sleepHr, wakeHour)
        if (d != today) cache[d] = r
        return r
    }
}
