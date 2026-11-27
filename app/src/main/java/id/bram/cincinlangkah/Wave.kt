package id.bram.cincinlangkah

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * EKSPERIMEN: coba minta gelombang PPG mentah (bentuk tiap denyut) dari cincin (YCBT) dan gelang (QC).
 * Semua paket yang masuk dicatat apa adanya ke berkas CSV supaya bisa dianalisis.
 * Tidak ada perintah hapus / ubah setelan permanen.
 *
 * Cincin (YCBT): AppControlReal 03 09 [on, tipe, 2] (tipe 4 = PPG), AppControlWave 03 0B [on, 0],
 *   AppStartMeasurement 03 2F [on, jenis] (0 detak, 1 tensi). Gelombang datang sebagai 06 04 (PPG) / 06 0F (multi-kanal).
 * Gelang (QC): a1 04 = nyalakan data sensor mentah; matikan dengan a1 05 lalu a1 02 (kalau hanya a1 02, LED tetap menyala). Paket a1 01 (SpO₂/merah), a1 02 (PPG), a1 03 (akselerometer).
 */
@SuppressLint("MissingPermission")
object Wave {
    class Pkt(val t: Long, val phase: Int, val b: ByteArray)
    class Series(val name: String, val pts: List<Pair<Long, Int>>)
    class Result(val dev: String, val pkts: List<Pkt>, val file: File?, val summary: String, val series: List<Series>, val csv: String)

    @Volatile var running = false

    suspend fun record(ctx: Context, ring: Boolean, sec: Int, onUpdate: (String) -> Unit): Result? {
        val cfg = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val addr = (if (ring) cfg.getString("addr", null) else Band.addr(ctx))?.takeIf { it.isNotBlank() } ?: return null
        if (running || Band.running || SyncWorker.running || SyncWorker.busy) return null
        running = true
        try {
            val main = Handler(Looper.getMainLooper())
            var s: Session? = null
            val r = withTimeoutOrNull((sec + 40) * 1000L) {
                suspendCancellableCoroutine<Result?> { cont ->
                    main.post { s = Session(ctx, addr, ring, sec, onUpdate) { r -> if (cont.isActive) cont.resume(r) }.also { it.start() } }
                    cont.invokeOnCancellation { main.post { s?.end("dibatalkan") } }
                }
            }
            if (r == null) main.post { s?.end("waktu habis") }
            return r
        } finally { running = false }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun qc(vararg b: Int): ByteArray {
        val p = ByteArray(16)
        for (i in b.indices) p[i] = b[i].toByte()
        p[15] = (p.take(15).sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
        return p
    }

    private sealed class Op {
        class Desc(val c: BluetoothGattCharacteristic) : Op()
        class Write(val svc: UUID, val ch: UUID, val data: ByteArray, val note: String) : Op()
        class Mark(val phase: Int, val note: String) : Op()
    }

    private class Session(val ctx: Context, val addr: String, val ring: Boolean, val sec: Int,
                          val onUpdate: (String) -> Unit, val onDone: (Result?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var gatt: BluetoothGatt? = null
        val ops = ArrayDeque<Op>()
        var busy = false
        var ended = false
        var stopping = false
        var started = false
        var phase = 0
        val pkts = ArrayList<Pkt>()
        val asm = FrameAssembler()
        val t0 = System.currentTimeMillis()
        val dev = if (ring) "cincin" else "gelang"

        fun log(s: String) = AppLog.d("🧪 $s")

        fun start() {
            onUpdate("Menyambung ke $dev…")
            log("Uji gelombang $dev ($addr) $sec dtk")
            val d = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.getRemoteDevice(addr)
            gatt = if (Build.VERSION.SDK_INT >= 23) d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) else d.connectGatt(ctx, false, cb)
            main.postDelayed({ if (!started) end("tidak tersambung") }, 25_000)
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                main.post {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        log("tersambung, minta MTU")
                        if (!g.requestMtu(247)) g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) end("terputus (status $status)")
                }
            }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { main.post { log("MTU $mtu"); g.discoverServices() } }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { main.post { onServices(g) } }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { main.post { busy = false; pump() } }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                main.post { if (status != 0) log("tulis gagal $status"); busy = false; pump() }
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                val v = value.copyOf(); main.post { onRx(c.uuid, v) }
            }
            @Deprecated("API < 33")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) { @Suppress("DEPRECATION") val v = c.value?.copyOf() ?: return; main.post { onRx(c.uuid, v) } }
            }
        }

        fun onServices(g: BluetoothGatt) {
            val svcs = if (ring) listOf(Ycbt.SERVICE) else listOf(ProbeActivity.QC_SVC, ProbeActivity.QC2_SVC)
            val main0 = g.getService(svcs[0])
            if (main0 == null) { log("layanan tidak ditemukan: " + g.services.joinToString { it.uuid.toString() }); end("perangkat tidak dikenali"); return }
            started = true
            for (u in svcs) for (c in g.getService(u)?.characteristics ?: emptyList()) {
                if (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) continue
                g.setCharacteristicNotification(c, true); ops.addLast(Op.Desc(c))
            }
            if (ring) planRing() else planBand()
            pump()
        }

        fun w(data: ByteArray, note: String) {
            val (s, c) = if (ring) Ycbt.SERVICE to Ycbt.CHAR_CMD else ProbeActivity.QC_SVC to ProbeActivity.QC_WRITE
            ops.addLast(Op.Write(s, c, data, note))
        }
        fun y(cmd: Int, vararg p: Int, note: String) = w(Ycbt.frame(Ycbt.G_APPCTRL, cmd, ByteArray(p.size) { p[it].toByte() }), note)

        /** lakukan [body] setelah [ms] lalu langsung jalankan antrean */
        fun later(ms: Long, body: () -> Unit) = main.postDelayed({ if (!ended) { body(); pump() } }, ms)

        fun planRing() {
            val half = sec * 500L
            ops.addLast(Op.Mark(1, "Tahap 1: ukur detak + gelombang PPG"))
            y(0x09, 1, 4, 2, note = "kirim data PPG real-time ON")
            y(0x0B, 1, 0, note = "gelombang PPG ON")
            y(0x2F, 1, 0, note = "mulai ukur detak")
            later(half) {
                y(0x2F, 0, 0, note = "stop ukur detak"); y(0x0B, 0, 0, note = "gelombang OFF"); y(0x09, 0, 4, 2, note = "PPG real-time OFF")
                ops.addLast(Op.Mark(2, "Tahap 2: ukur tensi + gelombang"))
                y(0x09, 1, 4, 2, note = "kirim data PPG real-time ON")
                y(0x0B, 1, 0, note = "gelombang PPG ON")
                y(0x2F, 1, 1, note = "mulai ukur tensi")
            }
            later(half * 2) {
                y(0x2F, 0, 1, note = "stop ukur tensi"); y(0x0B, 0, 0, note = "gelombang OFF"); y(0x09, 0, 4, 2, note = "PPG real-time OFF")
            }
            later(half * 2 + 2500) { end("selesai") }
        }

        fun planBand() {
            val half = sec * 500L
            ops.addLast(Op.Mark(1, "Tahap 1: data sensor mentah"))
            w(qc(0xa1, 0x04), "sensor mentah ON")
            later(half) {
                ops.addLast(Op.Mark(2, "Tahap 2: sensor mentah + ukur detak"))
                w(qc(0x69, 1, 1), "mulai ukur detak")
            }
            for (t in 10 until sec / 2 step 10) later(half + t * 1000L) { w(qc(0x69, 1, 3), "perpanjang ukur") }
            later(half * 2) { w(qc(0x6a, 1, 0, 0), "stop ukur"); w(qc(0xa1, 0x05), "hentikan aliran mentah"); w(qc(0xa1, 0x02), "sensor mentah OFF") }
            later(half * 2 + 2000) { end("selesai") }
        }

        @Suppress("DEPRECATION")
        fun pump() {
            if (busy || (ended && !stopping)) return
            val g = gatt ?: return
            val op = ops.removeFirstOrNull() ?: return
            when (op) {
                is Op.Mark -> { phase = op.phase; log(op.note); onUpdate("${op.note}… diam, jangan bergerak"); pump(); return }
                is Op.Desc -> {
                    busy = true
                    val d = op.c.getDescriptor(ProbeActivity.CCCD)
                    val v = if (op.c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val ok = d != null && (if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == 0 else { d.value = v; g.writeDescriptor(d) })
                    if (!ok) { busy = false; main.postDelayed({ pump() }, 30) }
                }
                is Op.Write -> {
                    val c = g.getService(op.svc)?.getCharacteristic(op.ch)
                    if (c == null) { pump(); return }
                    busy = true
                    log("TX ${hex(op.data)} (${op.note})")
                    val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, op.data, type) == 0
                        else { c.writeType = type; c.value = op.data; g.writeCharacteristic(c) }
                    if (!ok) { busy = false; log("tulis ditolak"); main.postDelayed({ pump() }, 50) }
                }
            }
        }

        var lastUi = 0L
        fun onRx(src: UUID, v: ByteArray) {
            val now = System.currentTimeMillis()
            if (ring) {
                for (f in asm.append(src, v)) {
                    pkts += Pkt(now, phase, f)
                    val type = u8(f, 0); val cmd = u8(f, 1)
                    // push dari cincin wajib dibalas, kalau tidak diulang terus
                    if (type == Ycbt.G_DEVCTRL) { w(Ycbt.frame(Ycbt.G_DEVCTRL, cmd, byteArrayOf(0)), "ACK push"); pump() }
                }
            } else pkts += Pkt(now, phase, v)
            if (now - lastUi > 1000) {
                lastUi = now
                val el = (now - t0) / 1000
                onUpdate("Tahap $phase · ${pkts.size} paket diterima · ${el} dtk")
            }
        }

        fun end(why: String) {
            if (ended) return
            ended = true
            log("selesai ($why): ${pkts.size} paket")
            try {
                // matikan semua yang mungkin masih menyala (2× supaya pasti), satu per satu setelah tulisan sebelumnya selesai
                val g = gatt
                if (g != null && started) {
                    val (s, ch) = if (ring) Ycbt.SERVICE to Ycbt.CHAR_CMD else ProbeActivity.QC_SVC to ProbeActivity.QC_WRITE
                    val list = if (ring) listOf(
                        Ycbt.frame(Ycbt.G_APPCTRL, 0x2F, byteArrayOf(0, 0)), Ycbt.frame(Ycbt.G_APPCTRL, 0x2F, byteArrayOf(0, 1)),
                        Ycbt.frame(Ycbt.G_APPCTRL, 0x0B, byteArrayOf(0, 0)), Ycbt.frame(Ycbt.G_APPCTRL, 0x09, byteArrayOf(0, 4, 2)))
                    else listOf(qc(0x6a, 1, 0, 0), qc(0xa1, 0x05), qc(0xa1, 0x02), qc(0xa1, 0x05), qc(0xa1, 0x02))
                    ops.clear(); busy = false
                    for (d in list) ops.addLast(Op.Write(s, ch, d, "matikan"))
                    stopping = true
                    pump()
                }
            } catch (_: Exception) {}
            main.postDelayed({ try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {} }, 3000)
            onDone(analyze())
        }

        fun analyze(): Result {
            val sb = StringBuilder("t_ms,tahap,hex\n")
            for (p in pkts) sb.append(p.t - t0).append(',').append(p.phase).append(',').append(hex(p.b)).append('\n')
            val csv = sb.toString()
            val file = try {
                val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "gelombang").apply { mkdirs() }
                File(dir, "$dev-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(t0)) + ".csv").apply { writeText(csv) }
            } catch (_: Exception) { null }

            // ringkasan per jenis paket
            fun key(b: ByteArray) = if (ring) "%02x %02x".format(u8(b, 0), u8(b, 1)) else "%02x %02x".format(u8(b, 0), if (b.size > 1) u8(b, 1) else 0)
            val groups = pkts.groupBy { key(it.b) }
            val lines = ArrayList<String>()
            for ((k, l) in groups.entries.sortedByDescending { it.value.size }) {
                val span = (l.last().t - l.first().t) / 1000.0
                val rate = if (span > 0.5) "%.1f/dtk".format((l.size - 1) / span) else "-"
                val sz = l.map { it.b.size }.distinct().take(3).joinToString("/")
                lines += "$k: ${l.size} paket, $rate, ukuran $sz b"
                log("$k: ${l.size} paket, $rate, ukuran $sz b, contoh ${hex(l.first().b).take(80)}")
            }

            // coba urai jadi deret angka untuk grafik
            val series = ArrayList<Series>()
            if (ring) {
                // 06 04: PPG mentah — anggap deret int16 little-endian di payload (urutan ini masih tebakan)
                val ppg = pkts.filter { u8(it.b, 0) == 0x06 && u8(it.b, 1) == 0x04 }
                if (ppg.isNotEmpty()) {
                    val pts = ArrayList<Pair<Long, Int>>()
                    for (p in ppg) {
                        val pl = p.b.copyOfRange(4, p.b.size - 2)
                        var i = 0
                        while (i + 1 < pl.size) { pts += (p.t - t0) to ((u8(pl, i) or (u8(pl, i + 1) shl 8)).toShort().toInt()); i += 2 }
                    }
                    series += Series("06 04 PPG (int16, tebakan)", pts)
                }
                val mul = pkts.filter { u8(it.b, 0) == 0x06 && u8(it.b, 1) == 0x0f }
                if (mul.isNotEmpty()) {
                    val pts = ArrayList<Pair<Long, Int>>()
                    for (p in mul) {
                        val pl = p.b.copyOfRange(4, p.b.size - 2)
                        if (pl.isEmpty()) continue
                        val st = u8(pl, 0)
                        val step = when (st) { 0 -> 12; 1 -> 8; else -> 4 }
                        var i = 1
                        while (i + 3 < pl.size) {
                            val x = if (st == 0) u8(pl, i) or (u8(pl, i + 1) shl 8) or (u8(pl, i + 2) shl 16) or (u8(pl, i + 3) shl 24)
                                else (u8(pl, i) shl 24) or (u8(pl, i + 1) shl 16) or (u8(pl, i + 2) shl 8) or u8(pl, i + 3)
                            pts += (p.t - t0) to x; i += step
                        }
                    }
                    series += Series("06 0F gelombang multi-kanal (kanal 1)", pts)
                }
                val hr = pkts.filter { u8(it.b, 0) == 0x06 && u8(it.b, 1) == 0x01 && it.b.size > 6 }.map { (it.t - t0) to u8(it.b, 4) }
                if (hr.isNotEmpty()) series += Series("06 01 detak", hr)
            } else {
                val ppg = pkts.filter { it.b.size >= 10 && u8(it.b, 0) == 0xa1 && u8(it.b, 1) == 0x02 }
                    .map { (it.t - t0) to ((u8(it.b, 2) shl 8) or u8(it.b, 3)) }.filter { it.second != 0 }
                if (ppg.isNotEmpty()) series += Series("a1 02 PPG", ppg)
                val red = pkts.filter { it.b.size >= 10 && u8(it.b, 0) == 0xa1 && u8(it.b, 1) == 0x01 }
                    .map { (it.t - t0) to ((u8(it.b, 2) shl 8) or u8(it.b, 3)) }.filter { it.second != 0 }
                if (red.isNotEmpty()) series += Series("a1 01 SpO₂/merah", red)
            }
            for (s in series) if (s.pts.size > 1) {
                val span = (s.pts.last().first - s.pts.first().first) / 1000.0
                val r = if (span > 0) "%.1f sampel/dtk".format(s.pts.size / span) else "-"
                lines += "${s.name}: ${s.pts.size} sampel, $r"
                log("${s.name}: ${s.pts.size} sampel, $r, rentang ${s.pts.minOf { it.second }}–${s.pts.maxOf { it.second }}")
            }
            val verdict = when {
                pkts.isEmpty() -> "Tidak ada paket sama sekali."
                series.none { it.name.contains("PPG") || it.name.contains("gelombang") } -> "Tidak ada gelombang PPG yang terkirim (hanya paket lain)."
                else -> {
                    val best = series.filter { it.name.contains("PPG") || it.name.contains("gelombang") }.maxBy { it.pts.size }
                    val span = (best.pts.last().first - best.pts.first().first) / 1000.0
                    val hz = if (span > 0) best.pts.size / span else 0.0
                    if (hz >= 20) "✅ Ada gelombang PPG ±%.0f sampel/dtk — cukup rapat untuk dianalisis bentuknya.".format(hz)
                    else "⚠ Ada data PPG tapi hanya ±%.1f sampel/dtk — terlalu jarang untuk bentuk gelombang (perlu ≥25).".format(hz)
                }
            }
            log(verdict)
            val summary = verdict + "\n\n" + lines.joinToString("\n") + (file?.let { "\n\nBerkas: ${it.name}" } ?: "")
            return Result(dev, pkts, file, summary, series, csv)
        }
    }
}

/** Grafik garis sederhana untuk deret gelombang (tiap deret dinormalkan sendiri). */
class WaveView(ctx: Context, val series: List<Wave.Series>, val pal: Pal) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f }
    private val cols = listOf(pal.heart, pal.ring, pal.band, pal.awake)
    override fun onMeasure(w: Int, h: Int) {
        val hh = (resources.displayMetrics.density * (110 * series.size.coerceAtLeast(1))).toInt()
        setMeasuredDimension(MeasureSpec.getSize(w), hh)
    }
    override fun onDraw(c: Canvas) {
        if (series.isEmpty()) return
        val bandH = height.toFloat() / series.size
        text.color = pal.muted
        series.forEachIndexed { k, s ->
            val top = k * bandH
            c.drawText(s.name, 8f, top + 30f, text)
            if (s.pts.size < 2) return@forEachIndexed
            // tampilkan 8 detik terakhir supaya bentuk denyut terlihat
            val tEnd = s.pts.last().first; val tStart = maxOf(s.pts.first().first, tEnd - 8000)
            val p = s.pts.filter { it.first >= tStart }
            if (p.size < 2) return@forEachIndexed
            val lo = p.minOf { it.second }.toFloat(); val hi = p.maxOf { it.second }.toFloat()
            val rng = (hi - lo).coerceAtLeast(1f)
            line.color = cols[k % cols.size]
            val y0 = top + 40f; val y1 = top + bandH - 8f
            // sumbu x = urutan sampel (beberapa sampel dalam satu paket punya waktu terima yang sama)
            var px = -1f; var py = 0f
            p.forEachIndexed { i, (_, v) ->
                val x = width * i.toFloat() / (p.size - 1)
                val y = y1 - (v - lo) / rng * (y1 - y0)
                if (px >= 0) c.drawLine(px, py, x, y, line)
                px = x; py = y
            }
        }
    }
}
