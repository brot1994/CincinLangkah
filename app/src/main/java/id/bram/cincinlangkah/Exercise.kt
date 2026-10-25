package id.bram.cincinlangkah

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Deteksi olahraga otomatis dari langkah HP per menit (Health Connect / step counter),
 * diperkaya detak jantung dari cincin.
 *
 * Aturan:
 *  - menit aktif = ≥ 12 langkah
 *  - sesi = menit aktif berurutan, celah ≤ 3 menit masih dianggap satu sesi, total ≥ 5 menit
 *  - tebakan jenis: ritme ≥ 130/menit → lari; ritme 80–130 dan stabil → jalan; selain itu → pingpong
 *  - sesi direkam langsung; setelah selesai (3 menit tanpa aktivitas) muncul notifikasi untuk mengoreksi jenis
 */
object Exercise {
    const val PINGPONG = "pingpong"
    const val JALAN = "jalan"
    const val LARI = "lari"
    const val KEKUATAN = "kekuatan"   // dicatat manual dari halaman Program
    val TYPES = listOf(PINGPONG, JALAN, LARI)
    fun label(t: String) = when (t) { PINGPONG -> "Pingpong"; JALAN -> "Jalan"; LARI -> "Lari"; KEKUATAN -> "Latihan kekuatan"; else -> t }
    fun emoji(t: String) = when (t) { PINGPONG -> "🏓"; JALAN -> "🚶"; LARI -> "🏃"; KEKUATAN -> "💪"; else -> "•" }
    /** MET perkiraan untuk kalori */
    fun met(t: String) = when (t) { PINGPONG -> 4.0; JALAN -> 3.5; LARI -> 8.5; KEKUATAN -> 3.5; else -> 3.5 }

    private const val ACTIVE_MIN_STEPS = 12.0
    private const val MAX_GAP_MIN = 3
    private const val MIN_LEN_MIN = 5
    private const val CHANNEL = "olahraga"

    /** Langkah per menit untuk [from, to). */
    suspend fun minuteSteps(ctx: Context, from: Long, to: Long): DoubleArray {
        val n = ((to - from) / 60_000L).toInt()
        val out = DoubleArray(n)
        var filled = false
        if (HcSteps.available(ctx)) try {
            if (HcSteps.granted(ctx)) {
                val client = HealthConnectClient.getOrCreate(ctx)
                val perSrc = HashMap<String, DoubleArray>()
                val dur = HashMap<String, Long>(); val cnt = HashMap<String, Int>()
                var token: String? = null
                do {
                    val r = client.readRecords(ReadRecordsRequest(StepsRecord::class,
                        TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)), pageToken = token))
                    for (rec in r.records) {
                        val k = rec.metadata.dataOrigin.packageName
                        val arr = perSrc.getOrPut(k) { DoubleArray(n) }
                        val s = rec.startTime.toEpochMilli(); val e = maxOf(rec.endTime.toEpochMilli(), s + 1)
                        dur[k] = (dur[k] ?: 0L) + (e - s); cnt[k] = (cnt[k] ?: 0) + 1
                        spreadMin(arr, from, s, e, rec.count.toDouble())
                    }
                    token = r.pageToken
                } while (!token.isNullOrEmpty())
                // semua sumber yang rinci (HP, Fitbit, jam): per menit ambil yang terbesar,
                // jadi kalau HP tertinggal, gelang/jam tetap bisa memicu deteksi
                val fine = perSrc.keys.filter {
                    (perSrc[it]?.sum() ?: 0.0) > 0 && (dur[it] ?: 0L) / maxOf(1, cnt[it] ?: 1) <= 15 * 60_000L
                }
                for (k in fine) {
                    val a = perSrc[k]!!
                    for (i in 0 until n) if (a[i] > out[i]) out[i] = a[i]
                    filled = true
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
        if (!filled) {
            // cadangan: sampel step counter aplikasi ini
            val s = Db.get(ctx).phone(from, to)
            for (i in 1 until s.size) {
                val a = s[i - 1]; val b = s[i]
                if (b.counter < a.counter || b.ts - a.ts > 30 * 60_000L) continue
                spreadMin(out, from, a.ts, b.ts, (b.counter - a.counter).toDouble())
            }
        }
        return out
    }

    private fun spreadMin(out: DoubleArray, base: Long, s: Long, e: Long, v: Double) {
        val len = (e - s).toDouble()
        val i0 = maxOf(0, ((s - base) / 60_000L).toInt())
        val i1 = minOf(out.size - 1, ((e - 1 - base) / 60_000L).toInt())
        for (i in i0..i1) {
            val ms = base + i * 60_000L
            val ov = minOf(e, ms + 60_000L) - maxOf(s, ms)
            if (ov > 0) out[i] += v * ov / len
        }
    }

    fun guess(min: DoubleArray): String {
        val act = min.filter { it >= ACTIVE_MIN_STEPS }
        if (act.isEmpty()) return PINGPONG
        val mean = act.average()
        val cv = sqrt(act.sumOf { (it - mean) * (it - mean) } / act.size) / mean
        return when {
            mean >= 130 -> LARI
            mean >= 80 && cv < 0.35 -> JALAN
            else -> PINGPONG
        }
    }

    /**
     * Cari sesi di 8 jam terakhir dan simpan/perbarui. Mengembalikan true kalau ada sesi yang sedang berlangsung.
     */
    suspend fun detect(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        val to = (now / 60_000L) * 60_000L
        val from = to - 8 * 3_600_000L
        val m = minuteSteps(ctx, from, to)
        val db = Db.get(ctx)
        val existing = db.exercises(from, to + 1)
        // sesi yang sudah direkam jam tidak perlu dideteksi ulang
        val watchS = try { WatchData.sessions(ctx, from, to) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }

        // susun sesi
        data class Seg(var s: Int, var e: Int)
        val segs = ArrayList<Seg>()
        var cur: Seg? = null
        for (i in m.indices) {
            if (m[i] >= ACTIVE_MIN_STEPS) {
                val c = cur
                if (c != null && i - c.e <= MAX_GAP_MIN + 1) c.e = i
                else { cur = Seg(i, i); segs += cur!! }
            }
        }
        var ongoing = false
        for (seg in segs) {
            val active = (seg.s..seg.e).count { m[it] >= ACTIVE_MIN_STEPS }
            if (active < MIN_LEN_MIN) continue
            val start = from + seg.s * 60_000L
            val end = from + (seg.e + 1) * 60_000L
            if (watchS.any { it.start < end && it.end > start }) continue
            val slice = m.copyOfRange(seg.s, seg.e + 1)
            val steps = slice.sum().roundToInt()
            val auto = guess(slice)
            val isOngoing = to - end <= MAX_GAP_MIN * 60_000L
            if (isOngoing) ongoing = true
            // gabungkan dengan sesi tersimpan yang tumpang tindih
            val olds = existing.filter { it.start < end + MAX_GAP_MIN * 60_000L && it.end > start - MAX_GAP_MIN * 60_000L }
            val chosen = olds.firstOrNull { it.chosen }
            val st = minOf(start, olds.minOfOrNull { it.start } ?: start)
            val en = maxOf(end, olds.maxOfOrNull { it.end } ?: end)
            olds.filter { it.start != st }.forEach { db.deleteExercise(it.start) }
            val notified = olds.any { it.notified }
            val e = Db.Exercise(st, en, chosen?.type ?: auto, auto, steps, chosen != null, notified)
            db.putExercise(e)
            if (olds.isEmpty()) AppLog.d("${emoji(auto)} Olahraga terdeteksi mulai ${t(st)} (tebakan: ${label(auto)})")
            if (!isOngoing && !e.notified && !e.chosen) {
                notify(ctx, e)
                db.putExercise(e.copy(notified = true))
            }
        }
        return ongoing
    }

    private fun t(ms: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

    // ---------------- notifikasi ----------------
    fun notify(ctx: Context, e: Db.Exercise) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Olahraga terdeteksi", NotificationManager.IMPORTANCE_DEFAULT))
        val mins = ((e.end - e.start) / 60_000L).toInt()
        val id = (e.start / 60_000L).toInt()
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("${emoji(e.type)} ${label(e.type)} terekam · $mins menit")
            .setContentText("${t(e.start)}–${t(e.end)} · ${e.steps} langkah. Benar jenisnya?")
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        TYPES.forEachIndexed { i, ty ->
            val pi = PendingIntent.getBroadcast(ctx, id * 4 + i,
                Intent(ctx, ExerciseReceiver::class.java).putExtra("start", e.start).putExtra("type", ty).putExtra("nid", id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, label(ty), pi)
        }
        try { NotificationManagerCompat.from(ctx).notify(id, b.build()) } catch (_: SecurityException) {}
    }
}

/** Tombol Pingpong / Jalan / Lari di notifikasi. */
class ExerciseReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val start = i.getLongExtra("start", 0L)
        val type = i.getStringExtra("type") ?: return
        Db.get(ctx).setExerciseType(start, type)
        AppLog.init(ctx)
        AppLog.d("${Exercise.emoji(type)} Sesi ${SimpleDateFormat("HH:mm", Locale.US).format(Date(start))} ditandai: ${Exercise.label(type)}")
        NotificationManagerCompat.from(ctx).cancel(i.getIntExtra("nid", 0))
    }
}
