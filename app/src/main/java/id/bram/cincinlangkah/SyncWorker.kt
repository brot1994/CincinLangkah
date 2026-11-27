package id.bram.cincinlangkah

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Sinkron cincin otomatis tiap jam di latar. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        /** Satu sinkron dalam satu waktu (manual atau otomatis). */
        @Volatile var running = false
        /** satu runRing() sedang jalan */
        @Volatile var busy = false

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("ring-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        AppLog.init(ctx)
        PhoneSteps.sample(ctx, "latar")
        val ongoing = try { Exercise.detect(ctx) } catch (e: Exception) { false }
        runRing(ctx, if (ongoing) 45 else 0, "⟳ Sinkron otomatis")
        try { Exercise.detect(ctx) } catch (_: Exception) {}
        return Result.success()
    }
}

/**
 * Jalankan satu sinkron cincin dari latar (dipakai sinkron tiap jam dan
 * pengecekan olahraga tiap 15 menit). Aman dipanggil bersamaan: hanya satu yang jalan.
 */
suspend fun runRing(ctx: Context, liveHrSec: Int, why: String): Boolean {
    if (Build.VERSION.SDK_INT >= 31 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) return false
    // cegah dua sinkron jalan bersamaan (mis. sinkron tiap jam + cek olahraga di detik yang sama)
    synchronized(SyncWorker) { if (SyncWorker.running || SyncWorker.busy || Wave.running) return false; SyncWorker.busy = true }
    try { return runRingInner(ctx, liveHrSec, why) } finally { SyncWorker.busy = false }
}

private suspend fun runRingInner(ctx: Context, liveHrSec: Int, why: String): Boolean {
    val cfg = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    if (BuildConfig.BAND_ONLY) {
        // Lige Pantau: data cincin dari Cincin Langkah (kalau terpasang) + sinkron cincin langsung + gelang
        try { RingBridge.import(ctx, force = true) } catch (_: Exception) {}
        // Riwayat di cincin hanya dihapus kalau Cincin Langkah tidak terpasang,
        // supaya Cincin Langkah tetap kebagian data yang sama.
        cfg.edit().putBoolean("hapus_di_cincin", !RingBridge.cincinInstalled(ctx)).apply()
        if (cfg.getString("addr", null).isNullOrBlank()) {
            AppLog.d(why)
            val r = try { Band.sync(ctx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { AppLog.d("⌚ Gelang error: ${e.message}"); false }
            return r
        }
    }
    val addr = cfg.getString("addr", null)
    if (addr.isNullOrBlank()) return false

    AppLog.d(why)
    SyncWorker.running = true
    val main = Handler(Looper.getMainLooper())
    var client: RingClient? = null
    val ok = try { withTimeoutOrNull(150_000) {
        suspendCancellableCoroutine { cont ->
            main.post {
                client = RingClient(ctx, liveHrSec) { ok, _ -> if (cont.isActive) cont.resume(ok) }
                client!!.start(addr)
            }
            cont.invokeOnCancellation { main.post { client?.cancel() } }
        }
    } } finally { SyncWorker.running = false }
    if (ok == null) main.post { client?.cancel() }
    if (ok == true && BuildConfig.BAND_ONLY) cfg.edit().putBoolean("has_ring", true).apply()
    val bandOk = try { Band.sync(ctx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { AppLog.d("⌚ Gelang error: ${e.message}"); false }
    if (liveHrSec == 0) try { Band.autoBp(ctx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
    return ok == true || (BuildConfig.BAND_ONLY && bandOk)
}
