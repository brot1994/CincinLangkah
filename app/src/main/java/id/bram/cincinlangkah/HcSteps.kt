package id.bram.cincinlangkah

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.LocalDate
import java.time.ZoneId

/**
 * Langkah HP dari Health Connect.
 * Di HP ini ada dua pencatat (Samsung Health dan pencatat bawaan Android) yang menghitung langkah
 * yang SAMA, jadi tidak boleh dijumlahkan: dipilih SATU sumber saja (Samsung Health kalau ada).
 */
object HcSteps {
    val PERMS = setOf(HealthPermission.getReadPermission(StepsRecord::class))
    private const val SAMSUNG = "com.sec.android.app.shealth"
    private val lastLogged = HashMap<LocalDate, String>()

    fun available(ctx: Context) =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(ctx: Context): Boolean =
        HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(PERMS)

    suspend fun hourly(ctx: Context, date: LocalDate): DoubleArray {
        val client = HealthConnectClient.getOrCreate(ctx)
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone)
        val from = start.toInstant(); val to = start.plusDays(1).toInstant()
        val hs = LongArray(25) { h -> start.plusHours(h.toLong()).toInstant().toEpochMilli() }

        val perSrc = HashMap<String, DoubleArray>()
        var token: String? = null
        do {
            val r = client.readRecords(ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(from, to), pageToken = token))
            for (rec in r.records) {
                val arr = perSrc.getOrPut(rec.metadata.dataOrigin.packageName) { DoubleArray(24) }
                val s = rec.startTime.toEpochMilli()
                val e = maxOf(rec.endTime.toEpochMilli(), s + 1)
                val len = (e - s).toDouble()
                for (h in 0 until 24) {
                    val ov = minOf(e, hs[h + 1]) - maxOf(s, hs[h])
                    if (ov > 0) arr[h] += rec.count * ov / len
                }
            }
            token = r.pageToken
        } while (!token.isNullOrEmpty())

        val pick = perSrc[SAMSUNG]?.takeIf { it.sum() > 0 }?.let { SAMSUNG }
            ?: perSrc.maxByOrNull { it.value.sum() }?.key
        val out = pick?.let { perSrc[it] } ?: DoubleArray(24)

        val msg = "Health Connect $date: dipakai ${pick ?: "-"} = ${out.sum().toLong()} | semua sumber: " +
            perSrc.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value.sum().toLong()}" }
        if (lastLogged[date] != msg && out.sum() > 0) { lastLogged[date] = msg; AppLog.d(msg) }
        return out
    }
}
