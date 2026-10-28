package id.bram.cincinlangkah

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * "Kenali perangkat baru": pindai perangkat Bluetooth di sekitar, sambungkan, catat semua layanan GATT,
 * lalu — kalau perangkatnya memakai protokol QC Wireless (QRing / QWatch Pro, mis. Lige, Colmi) —
 * kirim perintah baca-saja (baterai, langkah hari ini, detak jantung, tidur) dan catat jawabannya mentah.
 * Tidak ada perintah yang mengubah/menghapus data di perangkat.
 */
@SuppressLint("MissingPermission")
class ProbeActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, ProbeActivity::class.java))
        // QC Wireless (Colmi/QRing): UART 16 byte + kanal "big data" v2
        val QC_SVC: UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
        val QC_WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val QC_NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val QC2_SVC: UUID = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
        val QC2_WRITE: UUID = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
        val QC2_NOTIFY: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val night by lazy { (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES }
    private val pal by lazy { Pal(night) }
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).roundToInt()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var logView: TextView
    private lateinit var scanBtn: Button
    private val found = LinkedHashMap<String, ScanResult>()
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private val adapter by lazy { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = pal.bg
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14), px(8), px(14), px(28)); setBackgroundColor(pal.bg) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "‹"; textSize = 28f; setTextColor(pal.ring); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(px(36), px(44)); setOnClickListener { finish() }
        })
        head.addView(TextView(this).apply { text = "🔍 Kenali perangkat baru"; textSize = 19f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD })
        root.addView(head)
        root.addView(note("1) Tutup paksa aplikasi QWatch Pro dulu (Setelan → Aplikasi → QWatch Pro → Paksa berhenti) supaya gelang tidak sedang tersambung ke sana.\n" +
            "2) Dekatkan gelang ke HP, tekan Pindai, lalu ketuk nama gelangnya.\n" +
            "3) Tunggu sampai muncul \"Selesai\", lalu kembali ke dashboard → Pengaturan & log → Bagikan log.\n" +
            "Hanya perintah baca: tidak ada data di gelang yang diubah atau dihapus."))
        scanBtn = Button(this).apply {
            text = "Pindai"; isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = px(14).toFloat(); setColor(pal.ring) }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, px(50)).apply { topMargin = px(10); bottomMargin = px(8) }
            setOnClickListener { if (scanning) stopScan() else startScan() }
        }
        root.addView(scanBtn)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        logView = TextView(this).apply { textSize = 10.5f; typeface = Typeface.MONOSPACE; setTextColor(pal.muted); setTextIsSelectable(true); setPadding(0, px(10), 0, 0) }
        root.addView(logView)
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(pal.bg) })
    }

    override fun onDestroy() { stopScan(); gatt?.close(); gatt = null; super.onDestroy() }

    private fun note(t: String) = TextView(this).apply { text = t; textSize = 12.5f; setTextColor(pal.muted); setPadding(0, px(6), 0, 0) }

    private fun log(s: String) {
        AppLog.d("🔍 $s")
        main.post { logView.text = (logView.text.toString() + "\n" + s).trim().lines().takeLast(200).joinToString("\n") }
    }

    private fun hex(b: ByteArray?) = b?.joinToString(" ") { "%02x".format(it) } ?: "-"

    // ---------------- pindai ----------------

    private val ringAddr by lazy { getSharedPreferences("cfg", MODE_PRIVATE).getString("addr", "")?.uppercase() ?: "" }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val a = r.device.address
            val isNew = a !in found
            found[a] = r
            if (isNew) main.post { renderList() }
        }
        override fun onScanFailed(errorCode: Int) { log("Pindai gagal: kode $errorCode"); main.post { stopScan() } }
    }

    private fun startScan() {
        if (adapter == null || !adapter.isEnabled) { log("Bluetooth mati"); return }
        found.clear(); list.removeAllViews()
        scanning = true; scanBtn.text = "Berhenti memindai"
        adapter.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        main.postDelayed({ if (scanning) stopScan() }, 20_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false; scanBtn.text = "Pindai ulang"
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private fun renderList() {
        list.removeAllViews()
        val items = found.values.sortedWith(compareBy<ScanResult> { it.scanRecord?.deviceName.isNullOrBlank() }.thenByDescending { it.rssi })
        for (r in items) {
            val name = r.scanRecord?.deviceName ?: r.device.name ?: "(tanpa nama)"
            val isRing = r.device.address.uppercase() == ringAddr
            list.addView(TextView(this).apply {
                text = "${if (isRing) "💍 " else ""}$name\n${r.device.address} · ${r.rssi} dBm"
                textSize = 14f; setTextColor(if (isRing) pal.muted else pal.fg); setPadding(px(12), px(8), px(12), px(8))
                background = GradientDrawable().apply { cornerRadius = px(12).toFloat(); setColor(pal.card) }
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(6) }
                setOnClickListener { stopScan(); connect(r) }
            })
        }
    }

    // ---------------- sambung & catat ----------------

    private val queue = ArrayDeque<() -> Unit>()
    private var busy = false
    private fun enqueue(op: () -> Unit) { queue.addLast(op); if (!busy) next() }
    private fun next() {
        val op = queue.removeFirstOrNull()
        if (op == null) { busy = false; return }
        busy = true; op()
    }
    private fun done() = main.postDelayed({ next() }, 120)

    private fun connect(r: ScanResult) {
        gatt?.close()
        val rec = r.scanRecord
        log("════════ ${rec?.deviceName ?: r.device.name} ${r.device.address} rssi=${r.rssi}")
        log("Iklan (raw): ${hex(rec?.bytes?.let { b -> b.copyOf(minOf(b.size, 62)) })}")
        rec?.serviceUuids?.let { log("UUID diiklankan: ${it.joinToString()}") }
        rec?.manufacturerSpecificData?.let { m -> for (i in 0 until m.size()) log("Data pabrikan 0x%04x: %s".format(m.keyAt(i), hex(m.valueAt(i)))) }
        log("Menyambung…")
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("band_addr", r.device.address).apply()
        gatt = if (Build.VERSION.SDK_INT >= 23) r.device.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE) else r.device.connectGatt(this, false, cb)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Tersambung (status=$status). Minta MTU 247…")
                main.postDelayed({ g.requestMtu(247) }, 300)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Terputus (status=$status)")
                queue.clear(); busy = false
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { log("MTU = $mtu"); main.postDelayed({ g.discoverServices() }, 200) }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("Layanan ditemukan (status=$status):")
            for (s in g.services) {
                log("◆ ${s.uuid}")
                for (c in s.characteristics) {
                    val p = c.properties
                    val flags = listOfNotNull(
                        "R".takeIf { p and BluetoothGattCharacteristic.PROPERTY_READ != 0 },
                        "W".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 },
                        "w".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 },
                        "N".takeIf { p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 },
                        "I".takeIf { p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 },
                    ).joinToString("")
                    log("   └ ${c.uuid} [$flags]")
                }
            }
            // baca karakteristik yang bisa dibaca (nama, versi, baterai standar, dll.)
            for (s in g.services) for (c in s.characteristics)
                if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) enqueue { if (!g.readCharacteristic(c)) done() }
            // nyalakan semua notifikasi supaya jawaban apa pun tercatat
            for (s in g.services) for (c in s.characteristics)
                if (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) enqueue {
                    g.setCharacteristicNotification(c, true)
                    val d = c.getDescriptor(CCCD)
                    if (d == null) done() else {
                        d.value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        if (!g.writeDescriptor(d)) done()
                    }
                }
            val qc = g.getService(QC_SVC)
            if (qc != null) {
                log("✔ Protokol QC Wireless (QRing/QWatch) terdeteksi — kirim perintah baca…")
                qcProbe(g)
            } else {
                log("✖ Bukan layanan QC Wireless yang dikenal. Kirim log ini supaya formatnya bisa dipelajari.")
                enqueue { log("Selesai."); done() }
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val v = c.value
            val txt = v?.let { b -> String(b).filter { it.code in 32..126 } }?.takeIf { it.length >= 2 }
            log("READ ${c.uuid.toString().take(8)} = ${hex(v)}${txt?.let { "  \"$it\"" } ?: ""}")
            done()
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { done() }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { if (status != 0) log("Tulis gagal status=$status"); done() }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            log("RX ${c.uuid.toString().take(8)} [${v.size}] ${hex(v)}")
            if (c.uuid == QC_NOTIFY) decodeQc(v)
        }
    }

    // ---------------- protokol QC Wireless (hanya baca) ----------------

    private fun qcPacket(vararg b: Int): ByteArray {
        val p = ByteArray(16)
        for (i in b.indices) p[i] = b[i].toByte()
        p[15] = (p.take(15).sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
        return p
    }

    private fun send(g: BluetoothGatt, svc: UUID, chr: UUID, data: ByteArray, label: String) = enqueue {
        val c = g.getService(svc)?.getCharacteristic(chr)
        if (c == null) { log("(tidak ada karakteristik untuk $label)"); done(); return@enqueue }
        log("TX $label: ${hex(data)}")
        c.value = data
        c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (!g.writeCharacteristic(c)) { log("Tulis ditolak ($label)"); done() }
        else if (c.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) done()
    }

    private fun wait(ms: Long) = enqueue { main.postDelayed({ next() }, ms) }

    private fun bcd(v: Int) = ((v / 10) shl 4) or (v % 10)

    private fun qcProbe(g: BluetoothGatt) {
        val cal = Calendar.getInstance()
        // set jam (sama seperti aplikasi resmi saat menyambung; tidak menghapus data)
        send(g, QC_SVC, QC_WRITE, qcPacket(0x01, bcd(cal.get(Calendar.YEAR) % 100), bcd(cal.get(Calendar.MONTH) + 1),
            bcd(cal.get(Calendar.DAY_OF_MONTH)), bcd(cal.get(Calendar.HOUR_OF_DAY)), bcd(cal.get(Calendar.MINUTE)), bcd(cal.get(Calendar.SECOND)), 1), "set jam")
        wait(800)
        send(g, QC_SVC, QC_WRITE, qcPacket(0x03), "baterai")
        wait(1200)
        // langkah per 15 menit: hari ini & kemarin
        send(g, QC_SVC, QC_WRITE, qcPacket(0x43, 0, 0x0f, 0x00, 0x5f, 0x01), "langkah hari ini")
        wait(4000)
        send(g, QC_SVC, QC_WRITE, qcPacket(0x43, 1, 0x0f, 0x00, 0x5f, 0x01), "langkah kemarin")
        wait(4000)
        // detak jantung hari ini (stempel waktu = tengah malam hari ini, UTC detik)
        val mid = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val ts = ((mid.timeInMillis + mid.timeZone.getOffset(mid.timeInMillis)) / 1000).toInt()
        send(g, QC_SVC, QC_WRITE, qcPacket(0x15, ts and 0xff, (ts shr 8) and 0xff, (ts shr 16) and 0xff, (ts shr 24) and 0xff), "detak jantung hari ini")
        wait(4000)
        send(g, QC_SVC, QC_WRITE, qcPacket(0x39), "HRV")
        wait(3000)
        send(g, QC_SVC, QC_WRITE, qcPacket(0x37), "stres")
        wait(3000)
        // kanal big-data v2: tidur & SpO2 (format pendek, tidak 16 byte)
        if (g.getService(QC2_SVC) != null) {
            send(g, QC2_SVC, QC2_WRITE, byteArrayOf(0xbc.toByte(), 0x27, 0x01, 0x00, 0xff.toByte(), 0x00, 0xff.toByte()), "tidur (v2)")
            wait(4000)
            send(g, QC2_SVC, QC2_WRITE, byteArrayOf(0xbc.toByte(), 0x2a, 0x01, 0x00, 0xff.toByte(), 0x00, 0xff.toByte()), "SpO2 (v2)")
            wait(4000)
        } else log("(tidak ada kanal big-data v2)")
        enqueue { log("Selesai. Kembali ke dashboard → Pengaturan & log → Bagikan log, kirim ke Claude."); gatt?.disconnect(); done() }
    }

    private val tf = SimpleDateFormat("dd/MM HH:mm", Locale.US)
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun unbcd(v: Int) = (v shr 4) * 10 + (v and 0x0f)

    private fun decodeQc(v: ByteArray) {
        if (v.size < 16) return
        when (u8(v, 0)) {
            0x03 -> log("   🔋 baterai ${u8(v, 1)}%${if (u8(v, 2) == 1) " (mengisi)" else ""}")
            0x43 -> {
                if (u8(v, 1) == 0xff) { log("   👣 tidak ada data langkah"); return }
                if (u8(v, 1) == 0xf0) { log("   👣 header langkah (format kalori baru=${u8(v, 3) == 1})"); return }
                val slot = u8(v, 4)
                val steps = u8(v, 9) or (u8(v, 10) shl 8)
                val kcal = u8(v, 7) or (u8(v, 8) shl 8)
                val dist = u8(v, 11) or (u8(v, 12) shl 8)
                log("   👣 20%02d-%02d-%02d %02d:%02d  langkah=$steps  kal=$kcal  jarak=${dist}m  (${u8(v, 5) + 1}/${u8(v, 6)})".format(
                    unbcd(u8(v, 1)), unbcd(u8(v, 2)), unbcd(u8(v, 3)), slot / 4, (slot % 4) * 15))
            }
        }
    }
}
