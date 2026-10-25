package id.bram.cincinlangkah

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Step counter HP (TYPE_STEP_COUNTER) = total langkah sejak HP dinyalakan.
 * Dicatat tiap ±15 menit; selisih antar-sampel = langkah HP pada rentang itu.
 */
object PhoneSteps {

    fun available(ctx: Context): Boolean =
        (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
            .getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    suspend fun read(ctx: Context): Float? {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values[0])
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(l) }
            }
        }
    }

    /** Ambil satu sampel dan simpan. */
    suspend fun sample(ctx: Context, why: String): Float? {
        val v = read(ctx)
        if (v != null) {
            val now = System.currentTimeMillis()
            // Waktu HP dinyalakan, dibulatkan ke menit supaya stabil antar-sampel
            val boot = ((now - SystemClock.elapsedRealtime()) / 60_000L) * 60_000L
            Db.get(ctx).putPhone(now, v, boot)
            AppLog.d("📱 Step counter HP = ${v.toLong()} ($why)")
        } else {
            if (why != "latar") AppLog.d("📱 Step counter HP tidak terbaca ($why)")
        }
        return v
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<StepWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "phone-steps", ExistingPeriodicWorkPolicy.KEEP, req
        )
    }
}

class StepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        AppLog.init(applicationContext)
        val ctx = applicationContext
        PhoneSteps.sample(ctx, "latar")
        try { Program.remind(ctx) } catch (_: Exception) {}
        // Cek olahraga tiap 15 menit; kalau sedang berlangsung, ukur HR live dari cincin
        val ongoing = try { Exercise.detect(ctx) } catch (e: Exception) { AppLog.d("Deteksi olahraga error: ${e.message}"); false }
        if (ongoing) {
            runRing(ctx, 45, "⟳ Olahraga berlangsung — sinkron + HR live")
            try { Exercise.detect(ctx) } catch (_: Exception) {}
        }
        return Result.success()
    }
}
