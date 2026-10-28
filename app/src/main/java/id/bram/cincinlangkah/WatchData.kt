package id.bram.cincinlangkah

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Data jam (Huawei Watch lewat Health Sync) dan sumber lain di Health Connect:
 * detak jantung, tidur, SpO2, sesi olahraga.
 */
object WatchData {
    val PERMS = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    class Session(val start: Long, val end: Long, val type: String?, val label: String, val src: String)

    class Day(
        val heart: List<Ycbt.HeartRec>,
        val sleep: List<Ycbt.SleepSeg>,
        val spo2: List<Ycbt.Vital>,
        val sessions: List<Session>,
    )

    private val lastLog = HashMap<LocalDate, String>()

    suspend fun granted(ctx: Context): Boolean = HcSteps.available(ctx) &&
        HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(PERMS)

    private suspend fun <T : Record> readAll(c: HealthConnectClient, k: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val r = c.readRecords(ReadRecordsRequest(k, TimeRangeFilter.between(from, to), pageToken = token))
            out += r.records
            token = r.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    private fun mapType(t: Int): String? = when (t) {
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> Exercise.PINGPONG
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> Exercise.JALAN
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> Exercise.LARI
        else -> null
    }

    /** Sesi olahraga dari Health Connect dalam rentang waktu (dipakai juga oleh detektor). */
    suspend fun sessions(ctx: Context, from: Long, to: Long): List<Session> {
        if (!granted(ctx)) return emptyList()
        val c = HealthConnectClient.getOrCreate(ctx)
        return readAll(c, ExerciseSessionRecord::class, Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)).map {
            val ty = mapType(it.exerciseType)
            Session(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), ty,
                it.title?.takeIf { s -> s.isNotBlank() } ?: ty?.let { t -> Exercise.label(t) } ?: "Olahraga",
                it.metadata.dataOrigin.packageName)
        }
    }

    suspend fun heart(ctx: Context, from: Long, to: Long): List<Ycbt.HeartRec> {
        if (!granted(ctx)) return emptyList()
        val c = HealthConnectClient.getOrCreate(ctx)
        return readAll(c, HeartRateRecord::class, Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))
            .flatMap { r -> r.samples.map { Ycbt.HeartRec(it.time.toEpochMilli(), it.beatsPerMinute.toInt()) } }
            .filter { it.hr in 25..230 && it.tsMs in from until to }.sortedBy { it.tsMs }
    }

    /** Tidur "malam sebelum" tanggal ini: 18:00 hari sebelumnya s/d 14:00. */
    /** Tidur dari jam (Health Connect); kalau tidak ada, dari gelang QC. */
    suspend fun sleep(ctx: Context, date: LocalDate): List<Ycbt.SleepSeg> {
        val hc = try { sleepHc(ctx, date) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
        if (hc.isNotEmpty()) return hc
        val zone = ZoneId.systemDefault()
        return Db.get(ctx).bandSleep(date.minusDays(1).atStartOfDay(zone).plusHours(18).toInstant().toEpochMilli(),
            date.atStartOfDay(zone).plusHours(14).toInstant().toEpochMilli())
    }

    private suspend fun sleepHc(ctx: Context, date: LocalDate): List<Ycbt.SleepSeg> {
        if (!granted(ctx)) return emptyList()
        val c = HealthConnectClient.getOrCreate(ctx)
        val zone = ZoneId.systemDefault()
        val sFrom = date.minusDays(1).atStartOfDay(zone).plusHours(18).toInstant()
        val sTo = date.atStartOfDay(zone).plusHours(14).toInstant()
        val sleep = ArrayList<Ycbt.SleepSeg>()
        for (s in readAll(c, SleepSessionRecord::class, sFrom, sTo)) {
            if (s.stages.isEmpty()) {
                sleep += Ycbt.SleepSeg(s.startTime.toEpochMilli(),
                    ((s.endTime.toEpochMilli() - s.startTime.toEpochMilli()) / 1000).toInt(), 2)
            } else for (st in s.stages) {
                val stage = when (st.stage) {
                    SleepSessionRecord.STAGE_TYPE_DEEP -> 1
                    SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING -> 2
                    SleepSessionRecord.STAGE_TYPE_REM -> 3
                    else -> 4
                }
                sleep += Ycbt.SleepSeg(st.startTime.toEpochMilli(),
                    ((st.endTime.toEpochMilli() - st.startTime.toEpochMilli()) / 1000).toInt(), stage)
            }
        }
        sleep.sortBy { it.startMs }
        return sleep
    }

    suspend fun spo2(ctx: Context, from: Long, to: Long): List<Ycbt.Vital> {
        if (!granted(ctx)) return emptyList()
        val c = HealthConnectClient.getOrCreate(ctx)
        return readAll(c, OxygenSaturationRecord::class, Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))
            .map { Ycbt.Vital(it.time.toEpochMilli(), "spo2", it.percentage.value.toInt()) }
            .filter { it.v1 in 70..100 }.sortedBy { it.tsMs }
    }

    suspend fun load(ctx: Context, date: LocalDate): Day? {
        if (!granted(ctx)) return null
        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val heart = heart(ctx, from, to)
        val sleep = sleep(ctx, date)
        val spo2 = spo2(ctx, from, to)
        val sess = sessions(ctx, from, to)

        val msg = "⌚ Health Connect $date: HR ${heart.size} titik, tidur ${sleep.sumOf { it.durSec } / 60} mnt, " +
            "SpO2 ${spo2.size}, olahraga ${sess.size}" +
            if (sess.isNotEmpty()) " (" + sess.joinToString { "${it.label}/${it.src.substringAfterLast('.')}" } + ")" else ""
        if (lastLog[date] != msg) { lastLog[date] = msg; AppLog.d(msg) }
        return Day(heart, sleep, spo2, sess)
    }
}
