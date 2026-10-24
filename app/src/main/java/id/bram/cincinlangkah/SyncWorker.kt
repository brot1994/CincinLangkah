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

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("ring-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        AppLog.init(ctx)
        PhoneSteps.sample(ctx, "latar")
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()
        if (running) return Result.success()
        val addr = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("addr", null)
        if (addr.isNullOrBlank()) return Result.success()

        AppLog.d("⟳ Sinkron otomatis")
        running = true
        val main = Handler(Looper.getMainLooper())
        var client: RingClient? = null
        val ok = withTimeoutOrNull(120_000) {
            suspendCancellableCoroutine { cont ->
                main.post {
                    client = RingClient(ctx) { ok, _ -> if (cont.isActive) cont.resume(ok) }
                    client!!.start(addr)
                }
                cont.invokeOnCancellation { main.post { client?.cancel() } }
            }
        }
        if (ok == null) main.post { client?.cancel() }
        running = false
        return Result.success()
    }
}
