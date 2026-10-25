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
import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Program turun berat badan: target harian (kalori, protein, langkah, olahraga),
 * jadwal latihan bertahap per fase, timbang mingguan, dan proyeksi yang jujur.
 *
 * Perhitungan:
 *  - BMR Mifflin-St Jeor, kebutuhan dasar = BMR × 1,2 (aktivitas sehari-hari tanpa olahraga)
 *  - 1 kg lemak ≈ 7.700 kkal
 *  - defisit dibatasi maks 1% berat badan per minggu; asupan tidak di bawah 1.500 (pria) / 1.200 (wanita)
 *  - dihitung ulang dari berat terakhir & sisa waktu, jadi target menyesuaikan tiap kali timbang
 */
object Program {

    class Cfg(
        val active: Boolean, val start: LocalDate, val w0: Double, val wT: Double, val months: Int,
        val heightCm: Int, val birth: LocalDate, val male: Boolean, val remind: Boolean,
    ) {
        val end: LocalDate get() = start.plusMonths(months.toLong())
        val totalDays: Long get() = ChronoUnit.DAYS.between(start, end)
        val totalWeeks: Int get() = ceil(totalDays / 7.0).toInt()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun cfg(ctx: Context): Cfg {
        val p = prefs(ctx)
        return Cfg(
            active = p.getBoolean("prog_active", false),
            start = p.getString("prog_start", null)?.let { LocalDate.parse(it) } ?: LocalDate.now(),
            w0 = p.getFloat("prog_w0", 120f).toDouble(),
            wT = p.getFloat("prog_wt", 90f).toDouble(),
            months = p.getInt("prog_months", 6),
            heightCm = p.getInt("prog_height", 170),
            birth = p.getString("prog_birth", null)?.let { LocalDate.parse(it) } ?: LocalDate.of(1975, 7, 4),
            male = p.getBoolean("prog_male", true),
            remind = p.getBoolean("prog_remind", true),
        )
    }

    fun save(ctx: Context, c: Cfg) {
        prefs(ctx).edit()
            .putBoolean("prog_active", c.active).putString("prog_start", c.start.toString())
            .putFloat("prog_w0", c.w0.toFloat()).putFloat("prog_wt", c.wT.toFloat())
            .putInt("prog_months", c.months).putInt("prog_height", c.heightCm)
            .putString("prog_birth", c.birth.toString()).putBoolean("prog_male", c.male)
            .putBoolean("prog_remind", c.remind)
            .apply()
    }

    fun age(birth: LocalDate, on: LocalDate = LocalDate.now()) = Period.between(birth, on).years
    fun bmi(w: Double, hCm: Int) = w / ((hCm / 100.0) * (hCm / 100.0))
    fun bmr(w: Double, hCm: Int, age: Int, male: Boolean) = 10 * w + 6.25 * hCm - 5 * age + if (male) 5 else -161
    fun floorKcal(male: Boolean) = if (male) 1500 else 1200

    // ---------------- fase & jadwal ----------------

    class Session(val emoji: String, val name: String, val minutes: Int, val desc: String) {
        val rest get() = minutes == 0
    }

    class Phase(
        val name: String, val fromWeek: Int, val toWeek: Int,
        val steps: Int, val exMin: Int, val activeKcal: Int, val goal: String,
        val week: List<Session>, // Senin..Minggu
    )

    private const val STRENGTH_1 = "Squat ke kursi 2×10 · push-up dinding 2×10 · bridge 2×12 · plank lutut 2×20 dtk · " +
        "istirahat 60 dtk antar set. Gerak pelan, jangan sampai nyeri lutut."
    private const val STRENGTH_2 = "Squat ke kursi 3×12 · push-up meja 3×10 · row galon/botol 3×12 · bridge 3×15 · " +
        "plank 3×30 dtk · step-up anak tangga 2×10/kaki."
    private const val STRENGTH_3 = "Goblet squat (galon/dumbbell) 3×12 · push-up lutut 3×10 · row 3×12 · lunge berpegangan 3×8/kaki · " +
        "plank 3×40 dtk · farmer walk 3×30 m."

    val PHASES = listOf(
        Phase("Adaptasi", 1, 2, 6_000, 20, 250,
            "Membiasakan bergerak tiap hari dan merapikan pola makan. Belum perlu ngos-ngosan.",
            listOf(
                Session("🚶", "Jalan santai", 20, "Jalan dengan tempo nyaman, masih bisa ngobrol."),
                Session("🧘", "Peregangan + jalan", 20, "10 menit peregangan seluruh badan, lalu 10 menit jalan."),
                Session("🏓", "Pingpong santai", 30, "Main santai, istirahat kapan pun perlu. Minum air."),
                Session("🚶", "Jalan santai", 20, "Boleh dipecah 2×10 menit (pagi & sore)."),
                Session("💪", "Kekuatan ringan", 15, STRENGTH_1),
                Session("🚶", "Jalan agak jauh", 30, "Tempo nyaman, sambil cari rute yang enak."),
                Session("😌", "Istirahat", 0, "Tetap aktif ringan: beres-beres, jalan ke warung."),
            )),
        Phase("Membangun kebiasaan", 3, 6, 7_500, 30, 350,
            "Durasi naik, mulai latihan kekuatan 2× seminggu supaya otot tidak ikut hilang.",
            listOf(
                Session("🚶", "Jalan cepat", 30, "Tempo cepat: napas lebih berat tapi masih bisa bicara pendek."),
                Session("💪", "Latihan kekuatan", 25, STRENGTH_2),
                Session("🏓", "Pingpong", 45, "Pemanasan 5 menit dulu. Ini olahraga favorit — nikmati."),
                Session("🚶", "Jalan cepat", 30, "Bisa dipecah 3×10 menit setelah makan."),
                Session("💪", "Latihan kekuatan", 25, STRENGTH_2),
                Session("🏓", "Pingpong / jalan", 45, "Pingpong kalau ada lawan, kalau tidak jalan 45 menit."),
                Session("😌", "Istirahat aktif", 0, "Jalan santai bersama keluarga, peregangan."),
            )),
        Phase("Naik intensitas", 7, 12, 9_000, 40, 450,
            "Tubuh sudah lebih ringan: tambah durasi, mulai jalan interval (cepat–santai bergantian).",
            listOf(
                Session("🚶", "Jalan cepat", 40, "Tempo cepat stabil."),
                Session("💪", "Latihan kekuatan", 30, STRENGTH_2),
                Session("🏓", "Pingpong", 60, "Main lebih lama; jaga minum."),
                Session("⚡", "Jalan interval", 40, "Pemanasan 5 mnt, lalu 8× (2 mnt sangat cepat + 2 mnt santai), pendinginan 3 mnt."),
                Session("💪", "Latihan kekuatan", 30, STRENGTH_3),
                Session("🏓", "Pingpong", 60, "Atau jalan jauh 60 menit."),
                Session("🚶", "Jalan santai", 30, "Pemulihan."),
            )),
        Phase("Konsisten", 13, 99, 10_000, 45, 500,
            "Pertahankan ritme. Jogging ringan boleh dicoba kalau berat sudah < 105 kg dan lutut tidak nyeri.",
            listOf(
                Session("⚡", "Interval jalan/jogging", 45, "Jalan cepat; kalau lutut aman, selipkan 1 mnt jogging tiap 3 mnt."),
                Session("💪", "Latihan kekuatan", 35, STRENGTH_3),
                Session("🏓", "Pingpong", 60, "Main serius — ini kardio yang bagus."),
                Session("🚶", "Jalan cepat", 45, "Tempo cepat stabil."),
                Session("💪", "Latihan kekuatan", 35, STRENGTH_3),
                Session("🏓", "Pingpong", 75, "Main panjang, atau jalan jauh 60–75 menit."),
                Session("🚶", "Jalan santai", 30, "Pemulihan."),
            )),
    )

    fun phaseOf(week: Int) = PHASES.first { week in it.fromWeek..it.toWeek }

    // ---------------- rencana harian ----------------

    class Plan(
        val cfg: Cfg, val date: LocalDate, val day: Int, val week: Int, val phase: Phase, val today: Session,
        val curW: Double, val lineW: Double, val age: Int, val bmr: Int, val tdee: Int,
        val intake: Int, val protein: Int, val deficit: Int, val weeklyLoss: Double,
        val projEnd: Double, val floorHit: Boolean, val capHit: Boolean, val done: Boolean,
    ) {
        val steps get() = phase.steps
        val exMin get() = phase.exMin
        val activeKcal get() = phase.activeKcal
    }

    /** Berat garis target (lurus dari awal ke target) pada tanggal d. */
    fun lineW(c: Cfg, d: LocalDate): Double {
        val t = (ChronoUnit.DAYS.between(c.start, d).toDouble() / c.totalDays).coerceIn(0.0, 1.0)
        return c.w0 - (c.w0 - c.wT) * t
    }

    /** Berat terakhir yang tercatat sampai tanggal d (atau berat awal). */
    fun currentW(ctx: Context, c: Cfg, d: LocalDate = LocalDate.now()): Double {
        val to = d.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Db.get(ctx).latestWeight(to)?.second ?: c.w0
    }

    /** Hitung rencana untuk tanggal d dengan konfigurasi c (dipakai juga untuk pratinjau sebelum mulai). */
    fun plan(c: Cfg, curW: Double, d: LocalDate = LocalDate.now()): Plan {
        val day = (ChronoUnit.DAYS.between(c.start, d) + 1).toInt().coerceAtLeast(1)
        val week = (day - 1) / 7 + 1
        val phase = phaseOf(week)
        val today = phase.week[d.dayOfWeek.value - 1]
        val age = age(c.birth, d)
        val bmr = bmr(curW, c.heightCm, age, c.male)
        val tdee = bmr * 1.2
        val out = tdee + phase.activeKcal
        val remDays = max(7L, ChronoUnit.DAYS.between(d, c.end)).toDouble()
        val done = !d.isBefore(c.end)

        val toLose = curW - c.wT
        val need = if (toLose <= 0 || done) 0.0 else toLose * 7700 / remDays          // kkal/hari
        val cap = curW * 0.01 * 7700 / 7                                                // maks 1%/minggu
        var intake = out - min(need, cap)
        val capHit = need > cap
        val floor = floorKcal(c.male).toDouble()
        val floorHit = intake < floor && need > 0
        intake = max(intake, floor)
        if (toLose <= 0) intake = out                                                   // sudah sampai: jaga berat
        val intakeR = (intake / 50).roundToInt() * 50
        val deficit = (out - intakeR).roundToInt()

        // proyeksi: simulasi per minggu sampai akhir program dengan asupan yang sama
        var w = curW
        var dd = d
        while (dd.isBefore(c.end)) {
            val wk = (ChronoUnit.DAYS.between(c.start, dd) / 7 + 1).toInt()
            val o = bmr(w, c.heightCm, age, c.male) * 1.2 + phaseOf(wk).activeKcal
            val days = min(7L, ChronoUnit.DAYS.between(dd, c.end))
            w -= max(0.0, o - intakeR) * days / 7700
            if (w < c.wT && toLose > 0) { w = c.wT; break }
            dd = dd.plusDays(7)
        }

        val protein = ((1.5 * c.wT) / 5).roundToInt() * 5
        return Plan(c, d, day, week, phase, today, curW, lineW(c, d), age, bmr.roundToInt(), tdee.roundToInt(),
            intakeR, protein, deficit, deficit * 7 / 7700.0, w, floorHit, capHit, done)
    }

    /** Rencana hari ini kalau program aktif, selain itu null. */
    fun today(ctx: Context, d: LocalDate = LocalDate.now()): Plan? {
        val c = cfg(ctx)
        if (!c.active || d.isBefore(c.start)) return null
        return plan(c, currentW(ctx, c, d), d)
    }

    // ---------------- checklist makan harian ----------------

    val CHECKS = listOf(
        "🥚 Ada protein di tiap makan (telur/ikan/ayam/tahu/tempe)",
        "🍚 Nasi maks 1 centong per makan",
        "🥤 Tanpa minuman manis (teh manis, kopi susu, soda, jus kemasan)",
        "🍤 Gorengan maks 1 potong",
        "🥦 Sayur di minimal 2 kali makan",
        "💧 Air putih ≥ 2,5 liter",
        "🌙 Selesai makan malam sebelum 19.30",
    )

    fun checks(ctx: Context, d: LocalDate) = prefs(ctx).getInt("cek_$d", 0)
    fun setCheck(ctx: Context, d: LocalDate, i: Int, on: Boolean) {
        val v = checks(ctx, d)
        prefs(ctx).edit().putInt("cek_$d", if (on) v or (1 shl i) else v and (1 shl i).inv()).apply()
    }
    fun checkCount(ctx: Context, d: LocalDate) = Integer.bitCount(checks(ctx, d))

    // ---------------- pengingat ----------------

    private const val CH = "program"

    /** Dipanggil worker tiap 15 menit: pengingat rencana pagi & timbang mingguan. */
    fun remind(ctx: Context) {
        val c = cfg(ctx)
        if (!c.active || !c.remind) return
        val now = LocalTime.now(); val d = LocalDate.now()
        if (d.isBefore(c.start) || !d.isBefore(c.end)) return
        val p = prefs(ctx)
        val pl = plan(c, currentW(ctx, c, d), d)
        if (now.hour in 6..8 && p.getString("rem_pagi", "") != d.toString()) {
            p.edit().putString("rem_pagi", d.toString()).apply()
            val s = pl.today
            notify(ctx, 7001, "${s.emoji} Hari ini: ${s.name}" + (if (s.rest) "" else " ${s.minutes} menit"),
                "Target ${"%,d".format(pl.steps).replace(',', '.')} langkah · makan ±${pl.intake} kkal · protein ${pl.protein} g")
        }
        // timbang tiap minggu, di hari yang sama dengan hari mulai
        if (d.dayOfWeek == c.start.dayOfWeek && now.hour in 6..10 && p.getString("rem_timbang", "") != d.toString()) {
            val since = d.minusDays(5).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val recent = Db.get(ctx).latestWeight(Long.MAX_VALUE)?.first?.let { it >= since } == true
            if (!recent) {
                p.edit().putString("rem_timbang", d.toString()).apply()
                notify(ctx, 7002, "⚖ Waktunya timbang (minggu ${pl.week})",
                    "Timbang pagi setelah ke toilet, sebelum makan. Ketuk untuk mencatat.")
            }
        }
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Program turun berat", NotificationManager.IMPORTANCE_DEFAULT))
        val b = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, id, Intent(ctx, ProgramActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        try { NotificationManagerCompat.from(ctx).notify(id, b.build()) } catch (_: SecurityException) {}
    }
}
