package id.bram.cincinlangkah

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pelatih ala Whoop: skor Pemulihan, skor Tidur, Beban harian, dan komentar/saran.
 * Semua dibandingkan dengan "normal"-mu sendiri (rata-rata 28 malam terakhir).
 */
object Coach {
    private val ID = Locale("id", "ID")
    private val zone get() = ZoneId.systemDefault()

    // ---------------- satu malam ----------------

    class Night(
        val date: LocalDate,            // tanggal bangun
        val sleepMin: Int, val deepMin: Int, val remMin: Int, val awakeMin: Int, val wakeups: Int,
        val bed: Long?, val wake: Long?, val hrv: Double?, val rhr: Double?, val resp: Double?, val spo2Min: Int?,
        val fromWatch: Boolean,
    ) {
        fun json() = JSONObject().apply {
            put("d", date.toString()); put("s", sleepMin); put("dp", deepMin); put("r", remMin); put("a", awakeMin)
            put("w", wakeups); put("b", bed ?: 0L); put("k", wake ?: 0L); put("hv", hrv ?: -1.0); put("rh", rhr ?: -1.0)
            put("rs", resp ?: -1.0); put("o", spo2Min ?: -1); put("fw", fromWatch)
        }.toString()
        companion object {
            fun parse(s: String): Night = JSONObject(s).let { j ->
                fun dn(k: String) = j.getDouble(k).takeIf { it >= 0 }
                Night(LocalDate.parse(j.getString("d")), j.getInt("s"), j.getInt("dp"), j.getInt("r"), j.getInt("a"),
                    j.getInt("w"), j.getLong("b").takeIf { it > 0 }, j.getLong("k").takeIf { it > 0 },
                    dn("hv"), dn("rh"), dn("rs"), j.getInt("o").takeIf { it >= 0 }, j.getBoolean("fw"))
            }
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("coach", Context.MODE_PRIVATE)
    private fun ms(d: LocalDate, h: Int) = d.atStartOfDay(zone).plusHours(h.toLong()).toInstant().toEpochMilli()

    /** Malam yang berakhir pagi tanggal d. Disimpan untuk tanggal yang sudah lewat. */
    suspend fun night(ctx: Context, d: LocalDate): Night {
        val today = LocalDate.now()
        // simpan hanya malam yang sudah ≥2 hari lewat (data cincin/jam bisa telat masuk)
        val final = d.isBefore(today.minusDays(1))
        if (final) prefs(ctx).getString("n_$d", null)?.let { return try { Night.parse(it) } catch (_: Exception) { compute(ctx, d) } }
        val n = compute(ctx, d)
        if (final) prefs(ctx).edit().putString("n_$d", n.json()).apply()
        return n
    }

    private suspend fun safe(block: suspend () -> Unit) {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
    }

    private suspend fun compute(ctx: Context, d: LocalDate): Night {
        val db = Db.get(ctx)
        val wFrom = ms(d.minusDays(1), 18); val wTo = ms(d, 14)
        val ring = withContext(Dispatchers.IO) { db.sleep(wFrom, wTo) }
        var watch = emptyList<Ycbt.SleepSeg>()
        safe { watch = WatchData.sleep(ctx, d) }
        fun asleep(l: List<Ycbt.SleepSeg>) = l.filter { it.stage in 1..3 }.sumOf { it.durSec } / 60
        val useW = asleep(watch) > asleep(ring)
        val segs = (if (useW) watch else ring).sortedBy { it.startMs }
        val sl = segs.filter { it.stage in 1..3 }
        val bed = sl.firstOrNull()?.startMs
        val wake = sl.lastOrNull()?.let { it.startMs + it.durSec * 1000L }
        val awake = segs.filter { it.stage == 4 && bed != null && wake != null && it.startMs in bed..wake }
        // terbangun = segmen "terjaga" + celah > 5 menit di antara segmen tidur
        var gaps = 0; var gapMin = 0L
        for (i in 1 until sl.size) {
            val prevEnd = sl[i - 1].startMs + sl[i - 1].durSec * 1000L
            val g = sl[i].startMs - prevEnd
            // celah tanpa segmen "terjaga" (mis. cincin mencatat 2 sesi tidur terpisah) = terjaga juga
            if (g > 5 * 60_000L) { gaps++; if (awake.none { it.startMs in prevEnd until sl[i].startMs }) gapMin += g / 60_000L }
        }
        val from = bed ?: ms(d.minusDays(1), 22)
        val to = wake ?: ms(d, 7)
        val hrv = withContext(Dispatchers.IO) { db.vitals("hrv", from, to) }.map { it.v1 }.filter { it in 5..250 }
        val resp = withContext(Dispatchers.IO) { db.vitals("resp", from, to) }.map { it.v1 }.filter { it in 6..40 }
        var hr = withContext(Dispatchers.IO) { db.heart(from, to) + db.bandHr(from, to) }.map { it.hr }
        safe { hr = hr + WatchData.heart(ctx, from, to).map { it.hr } }
        hr = hr.filter { it in 30..150 }.sorted()
        // detak istirahat = rata-rata 10% terendah selama tidur
        val rhr = if (hr.size >= 3) hr.take(max(3, hr.size / 10)).average() else null
        var spo = withContext(Dispatchers.IO) { db.vitals("spo2", from, to) }.map { it.v1 }
        safe { spo = spo + WatchData.spo2(ctx, from, to).map { it.v1 } }
        return Night(d, asleep(segs),
            segs.filter { it.stage == 1 }.sumOf { it.durSec } / 60, segs.filter { it.stage == 3 }.sumOf { it.durSec } / 60,
            awake.sumOf { it.durSec } / 60 + gapMin.toInt(), awake.size + gaps, bed, wake,
            hrv.takeIf { it.isNotEmpty() }?.average(), rhr, resp.takeIf { it.isNotEmpty() }?.average(),
            spo.filter { it in 70..100 }.minOrNull(), useW)
    }

    // ---------------- normal (baseline) ----------------

    class Base(val hrv: Double?, val rhr: Double?, val resp: Double?, val sleep: Double?, val nHrv: Int, val nRhr: Int,
               val bedMin: Double?, val bedSd: Double?, val wakeMin: Double?) {
        val learning get() = max(nHrv, nRhr) < 7
    }

    private fun minuteOfNight(t: Long): Double {
        // menit sejak 18:00 (supaya jam 23:00 dan 01:00 berurutan)
        val lt = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalTime()
        val m = lt.hour * 60 + lt.minute - 18 * 60
        return (if (m < 0) m + 1440 else m).toDouble()
    }

    private fun trimmedMean(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        if (v.size < 5) return v.average()
        val s = v.sorted(); val k = s.size / 10 + 1
        return s.subList(k, s.size - k).average()
    }

    suspend fun baseline(ctx: Context, d: LocalDate, days: Int = 28): Base {
        val ns = (1..days).map { night(ctx, d.minusDays(it.toLong())) }
        val hv = ns.mapNotNull { it.hrv }; val rh = ns.mapNotNull { it.rhr }; val rs = ns.mapNotNull { it.resp }
        val sl = ns.filter { it.sleepMin >= 120 }
        val beds = ns.take(7).mapNotNull { it.bed }.map { minuteOfNight(it) }
        val wakes = ns.take(7).mapNotNull { it.wake }.map { minuteOfNight(it) }
        val bedAvg = beds.takeIf { it.isNotEmpty() }?.average()
        val bedSd = if (beds.size >= 3) sqrt(beds.sumOf { (it - bedAvg!!) * (it - bedAvg) } / beds.size) else null
        return Base(trimmedMean(hv), trimmedMean(rh), trimmedMean(rs), sl.takeIf { it.isNotEmpty() }?.map { it.sleepMin.toDouble() }?.average(),
            hv.size, rh.size, bedAvg, bedSd, wakes.sortedBy { it }.getOrNull(wakes.size / 2))
    }

    // ---------------- skor ----------------

    class Day(
        val date: LocalDate, val night: Night, val base: Base,
        val sleepNeed: Int, val sleepScore: Int?, val recovery: Int?, val zone: Int, // 2 hijau, 1 kuning, 0 merah, -1 ?
        val steps: Double, val kcal: Double, val exMin: Double, val strain: Double, val strainLo: Double, val strainHi: Double,
        val yStrain: Double, val plan: Program.Plan?,
        val morning: List<String>, val now: List<String>, val week: List<String>,
        val history: List<Hist>,
    ) {
        val zoneEmoji get() = when (zone) { 2 -> "🟢"; 1 -> "🟡"; 0 -> "🔴"; else -> "⚪" }
    }

    class Hist(val date: LocalDate, val sleepMin: Int, val hrv: Double?, val rhr: Double?, val steps: Double?, val kcal: Double?, val exMin: Double?)

    fun strainOf(kcal: Double, exMin: Double) = 21 * (1 - exp(-(kcal + exMin * 3) / 700.0))

    private fun hm(min: Int) = if (min >= 60) "${min / 60}j ${min % 60}m" else "${min}m"
    private fun hmClock(minOfNight: Double): String {
        var m = (minOfNight.roundToInt() + 18 * 60) % 1440; if (m < 0) m += 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }
    private fun n(v: Double) = "%,d".format(v.roundToInt()).replace(',', '.')
    private fun f1(v: Double) = "%.1f".format(ID, v)

    suspend fun day(ctx: Context, d: LocalDate = LocalDate.now()): Day {
        val nt = night(ctx, d)
        val base = baseline(ctx, d)
        val plan = Program.today(ctx, d)

        suspend fun v(m: Metric, dd: LocalDate) = try { Daily.value(ctx, m, dd).v } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        if (d == LocalDate.now()) Daily.clearToday()
        val steps = v(Metric.STEPS, d) ?: 0.0
        val kcal = v(Metric.KCAL, d) ?: 0.0
        val exMin = v(Metric.EXERCISE, d) ?: 0.0
        val y = d.minusDays(1)
        val yStrain = strainOf(v(Metric.KCAL, y) ?: 0.0, v(Metric.EXERCISE, y) ?: 0.0)

        // kebutuhan tidur: 7j30m + beban kemarin + utang tidur 3 malam terakhir
        val prev3 = (1..3).map { night(ctx, d.minusDays(it.toLong())) }.filter { it.sleepMin >= 120 }
        val debt = prev3.sumOf { max(0, 450 - it.sleepMin) } / 2
        val need = 450 + (if (yStrain > 14) 30 else if (yStrain > 10) 15 else 0) + min(60, debt)

        // skor tidur
        val sleepScore = if (nt.sleepMin < 60) null else {
            val dur = min(1.0, nt.sleepMin.toDouble() / need) * 60
            val deepP = nt.deepMin.toDouble() / nt.sleepMin; val remP = nt.remMin.toDouble() / nt.sleepMin
            val stages = (min(1.0, deepP / 0.15) * 0.5 + min(1.0, remP / 0.20) * 0.5) * 20
            val dist = max(0.0, 1 - nt.wakeups / 8.0 - nt.awakeMin / 90.0) * 10
            val cons = base.bedMin?.let { b -> nt.bed?.let { 10 * max(0.0, 1 - abs(minuteOfNight(it) - b) / 120.0) } } ?: 7.0
            (dur + stages + dist + cons).roundToInt().coerceIn(0, 100)
        }

        // pemulihan
        val parts = ArrayList<Pair<Double, Double>>() // (nilai, bobot)
        if (nt.hrv != null && base.hrv != null && base.nHrv >= 3)
            parts += (50 + (nt.hrv / base.hrv - 1) * 250).coerceIn(0.0, 100.0) to 0.45
        if (nt.rhr != null && base.rhr != null && base.nRhr >= 3)
            parts += (50 - (nt.rhr - base.rhr) * 8).coerceIn(0.0, 100.0) to 0.25
        if (sleepScore != null) parts += sleepScore.toDouble() to 0.30
        var recovery = if (parts.size >= 2 || (parts.size == 1 && sleepScore != null))
            (parts.sumOf { it.first * it.second } / parts.sumOf { it.second }) else null
        val respUp = nt.resp != null && base.resp != null && nt.resp - base.resp >= 2
        if (recovery != null && respUp) recovery -= 10
        val rec = recovery?.roundToInt()?.coerceIn(1, 99)
        val zoneV = when { rec == null -> -1; rec >= 67 -> 2; rec >= 34 -> 1; else -> 0 }
        val (lo, hi) = when (zoneV) { 2 -> 12.0 to 16.0; 1 -> 8.0 to 12.0; 0 -> 4.0 to 8.0; else -> 8.0 to 12.0 }
        val strain = strainOf(kcal, exMin)

        // ---------- komentar pagi ----------
        val mo = ArrayList<String>()
        val nh = ArrayList<String>()
        if (rec != null) {
            mo += "${when (zoneV) { 2 -> "🟢"; 1 -> "🟡"; else -> "🔴" }} Pemulihan $rec% — " +
                when (zoneV) { 2 -> "tubuh siap, boleh latihan penuh."; 1 -> "cukup, latihan dengan intensitas sedang."; else -> "tubuh butuh istirahat hari ini." }
        } else mo += "⚪ Pemulihan belum bisa dihitung — data tidur/HRV semalam belum ada. Sinkronkan cincin dulu."
        val det = ArrayList<String>()
        nt.hrv?.let { det += "HRV ${it.roundToInt()}" + (base.hrv?.let { b -> " (normal ${b.roundToInt()})" } ?: "") }
        nt.rhr?.let { det += "detak istirahat ${it.roundToInt()}" + (base.rhr?.let { b -> " (normal ${b.roundToInt()})" } ?: "") }
        if (nt.sleepMin > 0) det += "tidur ${hm(nt.sleepMin)} dari kebutuhan ${hm(need)}"
        if (det.isNotEmpty()) mo += det.joinToString(" · ")
        if (base.learning) mo += "ℹ Masih belajar pola normalmu (${max(base.nHrv, base.nRhr)}/7 malam) — skor makin akurat setelah seminggu."

        val ses = plan?.today
        when (zoneV) {
            2 -> mo += if (ses != null && !ses.rest) "🏋 Jadwal ${ses.emoji} ${ses.name} ${ses.minutes}' — jalankan, boleh lebih lama/lebih cepat." else "🏋 Hari yang bagus untuk olahraga, mis. pingpong atau jalan cepat 45 menit."
            1 -> mo += if (ses != null && !ses.rest) "🏋 Tetap jalankan ${ses.emoji} ${ses.name} ${ses.minutes}', intensitas sedang — jangan dipaksa sampai habis napas." else "🏋 Olahraga sedang: jalan cepat 30 menit sudah pas."
            0 -> mo += "🛌 Ganti latihan berat dengan jalan santai 20–30 menit + peregangan. " + (if (ses != null && !ses.rest) "${ses.name} digeser ke besok." else "")
        }
        // tidur
        if (nt.sleepMin in 60 until need - 45) mo += "😴 Kurang tidur ${hm(need - nt.sleepMin)}. Tidur siang 20 menit (sebelum jam 15) bisa membantu, dan malam ini tidur lebih awal."
        if (sleepScore != null && nt.sleepMin >= 180 && nt.deepMin.toDouble() / nt.sleepMin < 0.10)
            mo += "🌙 Tidur nyenyakmu sedikit (${hm(nt.deepMin)}). Hindari kopi/teh setelah jam 14, makan berat < 3 jam sebelum tidur, dan HP 30 menit sebelum tidur."
        if (nt.wakeups >= 4) mo += "🔁 Sering terbangun (${nt.wakeups}×)."
        if ((nt.spo2Min != null && nt.spo2Min < 90) || (nt.wakeups >= 5 && plan != null && Program.bmi(plan.curW, plan.cfg.heightCm) >= 35))
            mo += "⚕ " + (nt.spo2Min?.takeIf { it < 90 }?.let { "SpO₂ malam sempat $it%. " } ?: "") +
                "Kalau sering ngorok, terbangun tersedak, atau mengantuk berat siang hari, cek kemungkinan sleep apnea ke dokter — sering terjadi pada berat badan tinggi dan membaik saat berat turun."
        if (base.bedSd != null && base.bedSd > 60) mo += "⏰ Jam tidurmu berubah-ubah (selisih ±${base.bedSd.roundToInt()} menit). Jam tidur yang konsisten bikin tidur lebih berkualitas."
        if (respUp && nt.rhr != null && base.rhr != null && nt.rhr - base.rhr >= 4)
            mo += "🤒 Laju napas & detak istirahat naik dari biasanya — kadang tanda kelelahan atau mau sakit. Banyak minum, kurangi beban hari ini."

        // ---------- komentar sekarang ----------
        val now = LocalTime.now()
        val isToday = d == LocalDate.now()
        val goalSteps = plan?.steps ?: ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getInt("goal", 10_000)
        val goalEx = plan?.exMin ?: ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getInt("goal_ex", 30)
        nh += "⚡ Beban ${f1(strain)} — saran hari ini ${lo.roundToInt()}–${hi.roundToInt()}" +
            when { strain < lo -> " (masih kurang)"; strain > hi + 2 -> " (sudah melewati, cukup)"; strain >= lo -> " ✅"; else -> "" }
        if (steps < goalSteps) {
            val gap = goalSteps - steps
            nh += "👟 ${n(steps)} langkah, kurang ${n(gap)} ≈ ${(gap / 100).roundToInt()} menit jalan." +
                if (isToday && now.hour >= 17 && gap > 1500) " Jalan 15 menit setelah makan malam juga bagus untuk gula darah." else ""
        } else nh += "👟 ${n(steps)} langkah — target ${n(goalSteps.toDouble())} tercapai ✅"
        nh += "🔥 Kalori aktif ±${n(kcal)} kkal" + (plan?.let { " dari target ${n(it.activeKcal.toDouble())}" } ?: "")
        if (exMin < goalEx) nh += "⏱ Olahraga ${exMin.roundToInt()} dari $goalEx menit."
        else nh += "⏱ Olahraga ${exMin.roundToInt()} menit ✅"
        if (plan != null) {
            val ck = Program.checkCount(ctx, d)
            if (isToday && now.hour >= 18) nh += "🍽 Checklist makan $ck/${Program.CHECKS.size}" + if (ck < 5) " — isi dulu sebelum tidur." else " — bagus."
        }
        if (isToday && now.hour >= 17) {
            val wakeMin = base.wakeMin
            if (wakeMin != null) {
                val bedTarget = wakeMin - need - 15
                nh += "🛏 Bangunmu biasanya ${hmClock(wakeMin)}. Supaya tidur ${hm(need)}, mulai tidur sekitar ${hmClock(bedTarget)}."
            }
        }

        // ---------- minggu ----------
        val hist = ArrayList<Hist>()
        for (i in 13 downTo 0) {
            val dd = d.minusDays(i.toLong())
            val nn = night(ctx, dd)
            hist += Hist(dd, nn.sleepMin, nn.hrv, nn.rhr, v(Metric.STEPS, dd), v(Metric.KCAL, dd), v(Metric.EXERCISE, dd))
        }
        val wk = ArrayList<String>()
        val cur = hist.takeLast(7); val prev = hist.take(7)
        fun avg(l: List<Hist>, f: (Hist) -> Double?) = l.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
        val sC = avg(cur) { it.sleepMin.toDouble().takeIf { x -> x >= 120 } }; val sP = avg(prev) { it.sleepMin.toDouble().takeIf { x -> x >= 120 } }
        val stC = avg(cur) { it.steps }; val stP = avg(prev) { it.steps }
        val exC = cur.sumOf { it.exMin ?: 0.0 }
        fun delta(a: Double?, b: Double?) = if (a != null && b != null && b > 0) " (${if (a >= b) "+" else ""}${((a / b - 1) * 100).roundToInt()}%)" else ""
        sC?.let { wk += "😴 Tidur rata² ${hm(it.roundToInt())}${delta(sC, sP)} · ${cur.count { h -> h.sleepMin in 120 until 360 }} malam < 6 jam" }
        stC?.let { wk += "👟 Langkah rata² ${n(it)}/hari${delta(stC, stP)}" }
        wk += "⏱ Olahraga ${exC.roundToInt()} menit seminggu" + if (exC >= 150) " ✅ (≥150 menit, sesuai anjuran WHO)" else " — anjuran minimal 150 menit"
        avg(cur) { it.hrv }?.let { a -> wk += "💓 HRV rata² ${a.roundToInt()}" + delta(a, avg(prev) { it.hrv }) }
        if (plan != null) {
            val wNow = plan.curW
            val wWeek = Db.get(ctx).latestWeight(ms(d.minusDays(6), 0))?.second
            if (wWeek != null) wk += "⚖ Berat ${f1(wNow)} kg (${if (wNow <= wWeek) "−" else "+"}${f1(abs(wNow - wWeek))} kg dari minggu lalu)"
        }
        // fokus minggu depan: bagian terlemah
        val focus = when {
            sC != null && sC < 360 -> "Fokus minggu depan: TIDUR. Rata-rata di bawah 6 jam bikin lapar & susah turun berat — targetkan tidur 30 menit lebih awal."
            stC != null && stC < goalSteps * 0.7 -> "Fokus minggu depan: LANGKAH. Pecah jadi 3× jalan 10–15 menit setelah makan."
            exC < 120 -> "Fokus minggu depan: OLAHRAGA. Kunci 3 slot di kalender (mis. pingpong 2× + jalan cepat 1×)."
            else -> "Minggu yang solid. Pertahankan, dan naikkan sedikit saja (+10%) minggu depan."
        }
        wk += "🎯 $focus"

        return Day(d, nt, base, need, sleepScore, rec, zoneV, steps, kcal, exMin, strain, lo, hi, yStrain, plan, mo, nh, wk, hist)
    }

    // ---------------- ringkasan untuk AI ----------------

    fun aiContext(ctx: Context, c: Day): String = buildString {
        val pc = Program.cfg(ctx)
        val p = c.plan
        appendLine("Tanggal: ${c.date} (${c.date.dayOfWeek.getDisplayName(TextStyle.FULL, ID)}), jam ${LocalTime.now().toString().take(5)}")
        appendLine("Profil: ${if (pc.male) "pria" else "wanita"}, umur ${Program.age(pc.birth)} th, tinggi ${pc.heightCm} cm" +
            (p?.let { ", berat ${f1(it.curW)} kg, BMI ${f1(Program.bmi(it.curW, pc.heightCm))}" } ?: "") + ". Suka main pingpong.")
        if (p != null) appendLine("Program turun berat: ${f1(pc.w0)} → ${f1(pc.wT)} kg sampai ${pc.end}, minggu ${p.week}, fase ${p.phase.name}. " +
            "Target: makan ±${p.intake} kkal, protein ${p.protein} g, ${p.steps} langkah, olahraga ${p.exMin} mnt. Jadwal hari ini: ${p.today.name} ${p.today.minutes} mnt. " +
            "Garis target hari ini ${f1(p.lineW)} kg, perkiraan akhir ${f1(p.projEnd)} kg. Checklist makan ${Program.checkCount(ctx, c.date)}/${Program.CHECKS.size}.")
        val nt = c.night; val b = c.base
        appendLine("Semalam: tidur ${nt.sleepMin} mnt (kebutuhan ${c.sleepNeed}), nyenyak ${nt.deepMin}, REM ${nt.remMin}, terjaga ${nt.awakeMin} mnt, terbangun ${nt.wakeups}×, " +
            "HRV ${nt.hrv?.roundToInt() ?: "-"} (normal ${b.hrv?.roundToInt() ?: "-"}), detak istirahat ${nt.rhr?.roundToInt() ?: "-"} (normal ${b.rhr?.roundToInt() ?: "-"}), " +
            "napas ${nt.resp?.let { f1(it) } ?: "-"} (normal ${b.resp?.let { f1(it) } ?: "-"}), SpO2 min ${nt.spo2Min ?: "-"}. Sumber tidur: ${if (nt.fromWatch) WatchData.sleepSrc(c.date) else "cincin"}.")
        appendLine("Skor: pemulihan ${c.recovery ?: "-"}%, tidur ${c.sleepScore ?: "-"}%, beban hari ini ${f1(c.strain)} (saran ${c.strainLo.roundToInt()}–${c.strainHi.roundToInt()}), beban kemarin ${f1(c.yStrain)}." +
            if (b.learning) " (Baseline masih sedikit datanya.)" else "")
        appendLine("Hari ini sejauh ini: ${c.steps.roundToInt()} langkah, kalori aktif ${c.kcal.roundToInt()} kkal, olahraga ${c.exMin.roundToInt()} mnt.")
        appendLine("14 hari terakhir (tanggal | tidur mnt | HRV | RHR | langkah | kkal aktif | olahraga mnt):")
        for (h in c.history) appendLine("${h.date} | ${h.sleepMin} | ${h.hrv?.roundToInt() ?: "-"} | ${h.rhr?.roundToInt() ?: "-"} | ${h.steps?.roundToInt() ?: "-"} | ${h.kcal?.roundToInt() ?: "-"} | ${h.exMin?.roundToInt() ?: "-"}")
        val ws = Db.get(ctx).weights(ms(c.date.minusDays(60), 0), Long.MAX_VALUE)
        if (ws.isNotEmpty()) appendLine("Berat (60 hari): " + ws.takeLast(12).joinToString { "${Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate()}=${f1(it.second)}" })
    }

    // ---------------- notifikasi pagi ----------------

    private const val CH = "pelatih"

    /** Dipanggil worker: setelah jam 6 pagi dan ada data tidur, kirim skor pagi sekali sehari; Senin + rangkuman minggu. */
    suspend fun remind(ctx: Context) {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (!p.getBoolean("coach_notif", true)) return
        val now = LocalTime.now(); val d = LocalDate.now()
        if (now.hour !in 6..11 || p.getString("coach_pagi", "") == d.toString()) return
        val c = day(ctx, d)
        if (c.night.sleepMin < 60 && now.hour < 10) return // tunggu sinkron cincin
        p.edit().putString("coach_pagi", d.toString()).apply()
        val title = if (c.recovery != null) "${c.zoneEmoji} Pemulihan ${c.recovery}% · Tidur ${c.sleepScore ?: "-"}%" else "☀ Ringkasan pagi"
        var text = c.morning.drop(1).joinToString("\n")
        if (d.dayOfWeek == DayOfWeek.MONDAY) text += "\n\n📅 Minggu lalu:\n" + c.week.joinToString("\n")
        notify(ctx, 7101, title, text)
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Pelatih (skor pagi)", NotificationManager.IMPORTANCE_DEFAULT))
        val b = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title).setContentText(text.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, id, Intent(ctx, CoachActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        try { NotificationManagerCompat.from(ctx).notify(id, b.build()) } catch (_: SecurityException) {}
    }
}
