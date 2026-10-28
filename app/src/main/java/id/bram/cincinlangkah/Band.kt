package id.bram.cincinlangkah

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import kotlin.coroutines.resume

/**
 * Gelang/jam berprotokol QC Wireless (QWatch Pro / QRing — mis. Lige H59).
 * Hanya baca: baterai, langkah per 15 menit, detak jantung per 5 menit, HRV & stres per 30 menit,
 * tidur dan SpO2 (kanal big-data v2). Tidak ada perintah hapus.
 */
@SuppressLint("MissingPermission")
object Band {
    private val SVC = ProbeActivity.QC_SVC
    private val WR = ProbeActivity.QC_WRITE
    private val NT = ProbeActivity.QC_NOTIFY
    private val SVC2 = ProbeActivity.QC2_SVC
    private val WR2 = ProbeActivity.QC2_WRITE
    private val NT2 = ProbeActivity.QC2_NOTIFY
    private val CCCD = ProbeActivity.CCCD
    @Volatile var running = false

    fun addr(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("band_addr", null)
        ?.takeIf { it.isNotBlank() }

    /** Sinkron satu kali (dipanggil setelah sinkron cincin). */
    suspend fun sync(ctx: Context): Boolean {
        val a = addr(ctx) ?: return false
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        if (running) return false
        running = true
        val main = Handler(Looper.getMainLooper())
        var s: Session? = null
        val ok = withTimeoutOrNull(70_000) {
            suspendCancellableCoroutine { cont ->
                main.post { s = Session(ctx, a) { ok -> if (cont.isActive) cont.resume(ok) }.also { it.start() } }
                cont.invokeOnCancellation { main.post { s?.close("dibatalkan") } }
            }
        }
        if (ok == null) main.post { s?.close("waktu habis") }
        running = false
        return ok == true
    }

    private fun bcd(v: Int) = ((v / 10) shl 4) or (v % 10)
    private fun unbcd(v: Int) = (v shr 4) * 10 + (v and 0x0f)
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }

    private fun packet(vararg b: Int): ByteArray {
        val p = ByteArray(16)
        for (i in b.indices) p[i] = b[i].toByte()
        p[15] = (p.take(15).sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
        return p
    }

    private class Session(val ctx: Context, val addr: String, val onDone: (Boolean) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var gatt: BluetoothGatt? = null
        val queue = ArrayDeque<() -> Unit>()
        var busy = false
        var finished = false
        val db = Db.get(ctx)
        val zone: ZoneId = ZoneId.systemDefault()
        var nSteps = 0; var nHr = 0; var nVit = 0; var nSleep = 0
        var big = ByteArray(0)
        val dayMid = HashMap<Int, Long>()    // permintaan HR: stempel lokal → ms tengah malam

        fun log(s: String) = AppLog.d("⌚ $s")

        fun start() {
            log("Gelang: menyambung ke $addr …")
            val dev = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.getRemoteDevice(addr)
            gatt = if (Build.VERSION.SDK_INT >= 23) dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) else dev.connectGatt(ctx, false, cb)
        }

        fun close(why: String) {
            if (finished) return
            finished = true
            log("Gelang selesai ($why): langkah $nSteps slot, HR $nHr, HRV/stres $nVit, tidur $nSleep segmen")
            try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
            onDone(why == "ok")
        }

        fun enqueue(op: () -> Unit) { queue.addLast(op); if (!busy) next() }
        fun next() {
            if (finished) return
            val op = queue.removeFirstOrNull()
            if (op == null) { busy = false; return }
            busy = true; op()
        }
        fun done() = main.postDelayed({ next() }, 100)
        fun pause(ms: Long) = enqueue { main.postDelayed({ next() }, ms) }

        fun send(svc: java.util.UUID, chr: java.util.UUID, data: ByteArray) = enqueue {
            val c = gatt?.getService(svc)?.getCharacteristic(chr)
            if (c == null) { done(); return@enqueue }
            c.value = data
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (gatt?.writeCharacteristic(c) != true) done()
        }

        fun notifyOn(svc: java.util.UUID, chr: java.util.UUID) = enqueue {
            val c = gatt?.getService(svc)?.getCharacteristic(chr)
            if (c == null) { done(); return@enqueue }
            gatt?.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CCCD)
            if (d == null) done() else { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; if (gatt?.writeDescriptor(d) != true) done() }
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) main.postDelayed({ g.requestMtu(247) }, 300)
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) main.post { close(if (finished) "ok" else "terputus status=$status") }
            }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { main.postDelayed({ g.discoverServices() }, 200) }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (g.getService(SVC) == null) { main.post { log("Bukan gelang QC (layanan tidak ada)"); close("bukan QC") }; return }
                main.post { plan() }
            }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { main.post { done() } }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { main.post { done() } }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                val v = c.value?.copyOf() ?: return
                main.post { try { if (c.uuid == NT) onQc(v) else if (c.uuid == NT2) onBig(v) } catch (e: Exception) { log("parse error: ${e.message} · ${hex(v)}") } }
            }
        }

        fun plan() {
            notifyOn(SVC, NT); notifyOn(SVC2, NT2)
            val cal = Calendar.getInstance()
            send(SVC, WR, packet(0x01, bcd(cal.get(Calendar.YEAR) % 100), bcd(cal.get(Calendar.MONTH) + 1), bcd(cal.get(Calendar.DAY_OF_MONTH)),
                bcd(cal.get(Calendar.HOUR_OF_DAY)), bcd(cal.get(Calendar.MINUTE)), bcd(cal.get(Calendar.SECOND)), 1))
            pause(600)
            send(SVC, WR, packet(0x03)); pause(800)
            for (d in 0..2) { send(SVC, WR, packet(0x43, d, 0x0f, 0x00, 0x5f, 0x01)); pause(2500) }
            for (d in 0..1) {
                val day = LocalDate.now().minusDays(d.toLong())
                val midMs = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val mid = Calendar.getInstance().apply { timeInMillis = midMs }
                val ts = ((midMs + mid.timeZone.getOffset(midMs)) / 1000).toInt()   // waktu lokal sebagai detik "UTC"
                dayMid[ts] = midMs
                send(SVC, WR, packet(0x15, ts and 0xff, (ts shr 8) and 0xff, (ts shr 16) and 0xff, (ts shr 24) and 0xff)); pause(2500)
            }
            send(SVC, WR, packet(0x39)); pause(2000)
            send(SVC, WR, packet(0x37)); pause(2000)
            if (gatt?.getService(SVC2) != null) {
                send(SVC2, WR2, byteArrayOf(0xbc.toByte(), 0x27, 0x01, 0x00, 0xff.toByte(), 0x00, 0xff.toByte())); pause(3000)
                send(SVC2, WR2, byteArrayOf(0xbc.toByte(), 0x2a, 0x01, 0x00, 0xff.toByte(), 0x00, 0xff.toByte())); pause(3000)
            }
            enqueue { close("ok") }
        }

        // ---------- paket 16 byte ----------
        var hrMid = 0L; var hrInt = 5
        var vitInt = 30

        fun onQc(v: ByteArray) {
            if (v.size < 16) return
            when (u8(v, 0)) {
                0x03 -> {
                    ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putInt("band_batt", u8(v, 1)).apply()
                    log("🔋 Baterai gelang ${u8(v, 1)}%")
                }
                0x43 -> {
                    if (u8(v, 1) == 0xff || u8(v, 1) == 0xf0) return
                    val date = LocalDate.of(2000 + unbcd(u8(v, 1)), unbcd(u8(v, 2)), unbcd(u8(v, 3)))
                    val slot = u8(v, 4)
                    val ts = date.atStartOfDay(zone).toInstant().toEpochMilli() + slot * 15 * 60_000L
                    val steps = u8(v, 9) or (u8(v, 10) shl 8)
                    val kcal = u8(v, 7) or (u8(v, 8) shl 8)
                    val dist = u8(v, 11) or (u8(v, 12) shl 8)
                    db.putBandSteps(ts, steps, kcal, dist); nSteps++
                }
                0x15 -> {
                    val sub = u8(v, 1)
                    when {
                        sub == 0 -> { hrInt = u8(v, 3).takeIf { it in 1..60 } ?: 5 }
                        sub == 0xff -> {}
                        sub == 1 -> {
                            val ts = u8(v, 2) or (u8(v, 3) shl 8) or (u8(v, 4) shl 16) or (u8(v, 5) shl 24)
                            hrMid = dayMid[ts] ?: run {
                                // stempel tak dikenal: anggap waktu lokal dalam detik
                                val ms = ts * 1000L; ms - java.util.TimeZone.getDefault().getOffset(ms)
                            }
                            hrValues(v, 6, 0)
                        }
                        else -> hrValues(v, 2, 9 + (sub - 2) * 13)
                    }
                }
                0x39, 0x37 -> {
                    val sub = u8(v, 1)
                    if (sub == 0) { vitInt = u8(v, 3).takeIf { it in 1..120 } ?: 30; return }
                    val kind = if (u8(v, 0) == 0x39) "hrv" else "stress"
                    val first = if (sub == 1) 3 else 2
                    val base = if (sub == 1) 0 else 12 + (sub - 2) * 13
                    val mid = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
                    val list = ArrayList<Ycbt.Vital>()
                    for (i in first..14) {
                        val x = u8(v, i); if (x == 0) continue
                        list += Ycbt.Vital(mid + (base + i - first) * vitInt * 60_000L, kind, x)
                    }
                    if (list.isNotEmpty()) { nVit += db.putVitals(list) }
                }
            }
        }

        fun hrValues(v: ByteArray, from: Int, baseIdx: Int) {
            if (hrMid == 0L) return
            val now = System.currentTimeMillis()
            val out = ArrayList<Ycbt.HeartRec>()
            for (i in from..14) {
                val hr = u8(v, i); if (hr !in 30..220) continue
                val ts = hrMid + (baseIdx + i - from) * hrInt * 60_000L
                if (ts <= now + 60_000L) out += Ycbt.HeartRec(ts, hr)
            }
            if (out.isNotEmpty()) nHr += db.putBandHr(out)
        }

        // ---------- big data v2 (tidur, SpO2) ----------
        fun onBig(v: ByteArray) {
            big = if (big.isEmpty() && u8(v, 0) == 0xbc) v else big + v
            if (big.size < 6) return
            val len = u8(big, 2) or (u8(big, 3) shl 8)
            if (big.size < 6 + len) return
            val type = u8(big, 1)
            val payload = big.copyOfRange(6, 6 + len)
            big = ByteArray(0)
            log("big-data 0x%02x: %d byte · %s".format(type, len, hex(payload.copyOf(minOf(payload.size, 48)))))
            if (type == 0x27) parseSleep(payload)
        }

        fun parseSleep(p: ByteArray) {
            if (p.isEmpty()) return
            val days = u8(p, 0); var i = 1
            val segs = ArrayList<Ycbt.SleepSeg>()
            repeat(days) {
                if (i + 6 > p.size) return@repeat
                val ago = u8(p, i); val nBytes = u8(p, i + 1)
                val start = u8(p, i + 2) or (u8(p, i + 3) shl 8)
                val end = u8(p, i + 4) or (u8(p, i + 5) shl 8)
                val day = LocalDate.now().minusDays(ago.toLong())
                val startDay = if (start > end) day.minusDays(1) else day
                var t = startDay.atStartOfDay(zone).toInstant().toEpochMilli() + start * 60_000L
                var j = i + 6
                val stop = minOf(p.size, i + 2 + nBytes)
                while (j + 1 < stop) {
                    val type = u8(p, j); val min = u8(p, j + 1)
                    val stage = when (type) { 3 -> 1; 2 -> 2; 4 -> 3; else -> 4 }
                    if (min > 0) segs += Ycbt.SleepSeg(t, min * 60, stage)
                    t += min * 60_000L; j += 2
                }
                i += 2 + nBytes
            }
            if (segs.isNotEmpty()) nSleep += db.putBandSleep(segs)
        }
    }
}
