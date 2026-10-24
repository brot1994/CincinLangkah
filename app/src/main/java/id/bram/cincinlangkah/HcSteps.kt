package id.bram.cincinlangkah

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/** Langkah dari Health Connect (biasanya diisi Samsung Health dari sensor HP). */
object HcSteps {
    val PERMS = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    fun available(ctx: Context) =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(ctx: Context): Boolean =
        HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(PERMS)

    /** Langkah per jam (gabungan semua sumber, sesuai prioritas Health Connect). */
    suspend fun hourly(ctx: Context, date: LocalDate): DoubleArray {
        val client = HealthConnectClient.getOrCreate(ctx)
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone)
        val out = DoubleArray(24)
        val res = client.aggregateGroupByDuration(
            AggregateGroupByDurationRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start.toInstant(), start.plusDays(1).toInstant()),
                timeRangeSlicer = Duration.ofHours(1)
            )
        )
        for (g in res) {
            val h = Duration.between(start.toInstant(), g.startTime).toHours().toInt()
            if (h in 0..23) out[h] += (g.result[StepsRecord.COUNT_TOTAL] ?: 0L).toDouble()
        }

        // Catat sumber-sumbernya ke log (berguna untuk tahu siapa yang menulis)
        val perSrc = HashMap<String, Long>()
        var token: String? = null
        do {
            val r = client.readRecords(
                ReadRecordsRequest(
                    StepsRecord::class,
                    TimeRangeFilter.between(start.toInstant(), start.plusDays(1).toInstant()),
                    pageToken = token
                )
            )
            for (rec in r.records) {
                val k = rec.metadata.dataOrigin.packageName
                perSrc[k] = (perSrc[k] ?: 0L) + rec.count
            }
            token = r.pageToken
        } while (!token.isNullOrEmpty())
        AppLog.d("Health Connect $date: total ${out.sum().toLong()} | per sumber: " +
            perSrc.entries.joinToString { "${it.key}=${it.value}" })
        return out
    }
}
