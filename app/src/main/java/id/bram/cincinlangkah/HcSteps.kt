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
    /** yang diminta: baca langkah + baca di latar (untuk deteksi olahraga saat aplikasi tertutup) */
    val REQUEST = PERMS + WatchData.PERMS + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    private val lastLogged = HashMap<LocalDate, String>()
    /** Health Sync menulis data jam Huawei ke Health Connect atas namanya sendiri */
    const val WATCH_SRC = "nl.appyhapps.healthsync"
    /** Sumber wearable (bukan HP): jam Huawei via Health Sync, Fitbit (Air / Pixel Watch) */
    fun isWearable(pkg: String): Boolean =
        pkg == WATCH_SRC || pkg.contains("fitbit", ignoreCase = true)
    private val watchByDate = HashMap<LocalDate, DoubleArray>()

    /** Langkah jam per jam untuk tanggal itu (terisi setelah hourly() dipanggil). */
    fun watchFor(date: LocalDate): DoubleArray? = synchronized(watchByDate) { watchByDate[date] }

    fun available(ctx: Context) =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(ctx: Context): Boolean =
        HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(PERMS)

    suspend fun grantedBackground(ctx: Context): Boolean =
        HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
            .contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)

    suspend fun hourly(ctx: Context, date: LocalDate): DoubleArray {
        val client = HealthConnectClient.getOrCreate(ctx)
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone)
        val from = start.toInstant(); val to = start.plusDays(1).toInstant()
        val hs = LongArray(25) { h -> start.plusHours(h.toLong()).toInstant().toEpochMilli() }

        val perSrc = HashMap<String, DoubleArray>()
        val durSum = HashMap<String, Long>(); val durN = HashMap<String, Int>()
        var token: String? = null
        do {
            val r = client.readRecords(ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(from, to), pageToken = token))
            for (rec in r.records) {
                val src = rec.metadata.dataOrigin.packageName
                val arr = perSrc.getOrPut(src) { DoubleArray(24) }
                val s = rec.startTime.toEpochMilli()
                val e = maxOf(rec.endTime.toEpochMilli(), s + 1)
                durSum[src] = (durSum[src] ?: 0L) + (e - s); durN[src] = (durN[src] ?: 0) + 1
                val len = (e - s).toDouble()
                for (h in 0 until 24) {
                    val ov = minOf(e, hs[h + 1]) - maxOf(s, hs[h])
                    if (ov > 0) arr[h] += rec.count * ov / len
                }
            }
            token = r.pageToken
        } while (!token.isNullOrEmpty())

        // Pilih sumber yang catatannya rinci (rata-rata ≤ 1 jam); Samsung Health kadang menulis
        // satu catatan sepanjang hari sehingga tidak bisa dipakai untuk grafik per jam.
        fun avgMin(k: String) = (durSum[k] ?: 0L) / maxOf(1, durN[k] ?: 1) / 60_000L
        // langkah jam dipisah (kolom sendiri), bukan kandidat "langkah HP"
        synchronized(watchByDate) {
            // kalau dua wearable dipakai bersamaan: per jam ambil yang terbesar (tidak dijumlah)
            val ws = perSrc.filterKeys { isWearable(it) }.values
            if (ws.isNotEmpty()) watchByDate[date] = DoubleArray(24) { h -> ws.maxOf { it[h] } }
            else watchByDate.remove(date)
        }
        val phoneSrc = perSrc.filterKeys { !isWearable(it) }
        val fine = phoneSrc.filter { it.value.sum() > 0 && avgMin(it.key) <= 60 }
        val pick = (if (fine.isNotEmpty()) fine else phoneSrc.filter { it.value.sum() > 0 })
            .entries.sortedWith(compareBy<Map.Entry<String, DoubleArray>> { avgMin(it.key) }
                .thenByDescending { it.value.sum() })
            .firstOrNull()?.key
        val out = pick?.let { perSrc[it] } ?: DoubleArray(24)

        val msg = "Health Connect $date: dipakai ${pick ?: "-"} = ${out.sum().toLong()} | semua sumber: " +
            perSrc.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value.sum().toLong()} (±${avgMin(it.key)} mnt/catatan)" }
        if (lastLogged[date] != msg && out.sum() > 0) { lastLogged[date] = msg; AppLog.d(msg) }
        return out
    }
}
