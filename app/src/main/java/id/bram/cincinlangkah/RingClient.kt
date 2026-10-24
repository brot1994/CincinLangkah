package id.bram.cincinlangkah

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Sambung ke cincin, set jam, tarik riwayat langkah (SPORT + ALL), simpan ke DB, lalu putus.
 * Hanya MEMBACA — tidak pernah mengirim perintah hapus, jadi SmartHealth tetap dapat datanya.
 * Semua logika berjalan di main thread (callback BLE dipindah lewat Handler).
 */
@SuppressLint("MissingPermission")
class RingClient(private val ctx: Context, private val onFinished: (ok: Boolean, summary: String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val db = Db.get(ctx)
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private val assembler = FrameAssembler()
    private var finished = false
    private var connected = false

    // --- antrian tulis (BLE hanya boleh satu operasi tulis sekaligus) ---
    private sealed class Op {
        class Desc(val c: BluetoothGattCharacteristic, val value: ByteArray) : Op()
        class Write(val data: ByteArray, val note: String) : Op()
    }
    private val ops = ArrayDeque<Op>()
    private var busy = false

    // --- status transfer riwayat ---
    private val queue = ArrayDeque<Ycbt.History>()
    private var current: Ycbt.History? = null
    private var receiving = false
    private var buffer = ByteArray(0)
    private var expPackets = 0
    private var expBytes = 0L
    private var retried = false
    private var blocksThisType = 0
    private val seenBlocks = HashSet<String>()
    private var newSport = 0
    private var newAll = 0
    private var gotSport = 0
    private var gotAll = 0
    private var liveSteps: Int? = null

    private val watchdog = Runnable {
        AppLog.d("⏱ Tidak ada data ${current?.name} selama 10 dtk → lanjut")
        advance()
    }
    private val connectTimeout = Runnable {
        if (!connected) fail("Tidak bisa tersambung ke cincin (25 dtk). Pastikan cincin dekat, tidak sedang tersambung ke SmartHealth, dan Bluetooth menyala.")
    }

    fun start(address: String?) {
        val ad = adapter
        if (ad == null || !ad.isEnabled) { fail("Bluetooth mati."); return }
        val addr = address?.trim()?.uppercase()
        if (!addr.isNullOrEmpty() && BluetoothAdapter.checkBluetoothAddress(addr)) {
            AppLog.d("Menyambung langsung ke $addr …")
            connect(ad.getRemoteDevice(addr))
        } else {
            scan()
        }
    }

    fun cancel() { finish(false, "Dibatalkan") }

    // ---------------- scan ----------------
    private var scanCb: ScanCallback? = null

    private fun scan() {
        val sc = adapter?.bluetoothLeScanner ?: return fail("Scanner BLE tidak tersedia.")
        AppLog.d("Mencari cincin (nama berawalan R11 / R20)…")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (name.startsWith("R11") || name.startsWith("R20")) {
                    AppLog.d("Ketemu: $name  ${result.device.address}  rssi=${result.rssi}")
                    stopScan()
                    main.post { connect(result.device) }
                }
            }
            override fun onScanFailed(errorCode: Int) { main.post { fail("Scan gagal (kode $errorCode)") } }
        }
        scanCb = cb
        sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        main.postDelayed({
            if (scanCb != null) { stopScan(); fail("Cincin tidak ditemukan dalam 15 dtk. Tutup SmartHealth / putuskan di sana, lalu coba lagi.") }
        }, 15_000)
    }

    private fun stopScan() {
        val cb = scanCb ?: return
        scanCb = null
        try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }

    // ---------------- GATT ----------------
    private fun connect(dev: BluetoothDevice) {
        main.postDelayed(connectTimeout, 25_000)
        gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected = true
                    main.removeCallbacks(connectTimeout)
                    AppLog.d("Tersambung (status=$status). Minta MTU 512…")
                    if (!g.requestMtu(512)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    AppLog.d("Terputus (status=$status)")
                    if (!finished) finish(gotSport + gotAll > 0, summary())
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { AppLog.d("MTU = $mtu"); g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { onServices(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) AppLog.d("Tulis CCCD ${d.characteristic.uuid} gagal: $status")
                busy = false; pump()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) AppLog.d("Tulis gagal: $status")
                busy = false; pump()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            main.post { onData(c.uuid, copy) }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION") val copy = c.value?.copyOf() ?: return
                main.post { onData(c.uuid, copy) }
            }
        }
    }

    private fun onServices(g: BluetoothGatt) {
        val svc = g.getService(Ycbt.SERVICE)
        if (svc == null) {
            AppLog.d("Layanan YCBT tidak ada. Layanan yang ada: " + g.services.joinToString { it.uuid.toString() })
            return fail("Ini sepertinya bukan cincin YCBT/SmartHealth.")
        }
        val cmd = svc.getCharacteristic(Ycbt.CHAR_CMD) ?: return fail("Karakteristik perintah tidak ada.")
        cmdChar = cmd
        AppLog.d("Layanan YCBT ditemukan. Karakteristik: " +
            svc.characteristics.joinToString { "${it.uuid.toString().substring(4, 8)}(p=${it.properties})" })
        for (c in svc.characteristics) {
            val p = c.properties
            if (p and (BluetoothGattCharacteristic.PROPERTY_INDICATE or BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) continue
            g.setCharacteristicNotification(c, true)
            val v = if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            ops.addLast(Op.Desc(c, v))
        }
        // Urutan seperti aplikasi SmartHealth: set jam, info perangkat, lalu riwayat.
        send(Ycbt.setTime(), "set jam cincin = jam HP")
        send(Ycbt.frame(Ycbt.G_GET, 0x00, byteArrayOf(0x47, 0x43)), "info perangkat")
        send(Ycbt.frame(Ycbt.G_APPCTRL, 0x09, byteArrayOf(0x01, 0x00, 0x02)), "aktifkan kiriman langkah live")
        pump()
        main.postDelayed({
            queue.clear(); queue.addAll(listOf(Ycbt.History.SPORT, Ycbt.History.ALL))
            advance()
        }, 800)
    }

    private fun send(data: ByteArray, note: String) {
        ops.addLast(Op.Write(data, note))
        pump()
    }

    @Suppress("DEPRECATION")
    private fun pump() {
        if (busy) return
        val g = gatt ?: return
        val op = ops.removeFirstOrNull() ?: return
        busy = true
        val ok = when (op) {
            is Op.Desc -> {
                val d = op.c.getDescriptor(Ycbt.CCCD)
                if (d == null) false
                else if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, op.value) == BluetoothStatusCodes.SUCCESS
                else { d.value = op.value; g.writeDescriptor(d) }
            }
            is Op.Write -> {
                val c = cmdChar
                AppLog.d("TX ${Ycbt.hex(op.data)}   (${op.note})")
                if (c == null) false else {
                    val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, op.data, type) == BluetoothStatusCodes.SUCCESS
                    else { c.writeType = type; c.value = op.data; g.writeCharacteristic(c) }
                }
            }
        }
        if (!ok) {
            AppLog.d("Operasi BLE ditolak, lanjut ke berikutnya")
            busy = false
            main.postDelayed({ pump() }, 50)
        }
    }

    // ---------------- frame masuk ----------------
    private fun onData(src: UUID, data: ByteArray) {
        for (f in assembler.append(src, data)) handleFrame(f)
    }

    private fun handleFrame(f: ByteArray) {
        val type = f[0].toInt() and 0xFF
        val cmd = f[1].toInt() and 0xFF
        val payload = f.copyOfRange(4, f.size - 2)
        val big = payload.size > 48
        AppLog.d("RX %02x %02x  [%d] %s".format(type, cmd, payload.size, Ycbt.hex(payload, if (big) 48 else 4096)))

        when (type) {
            Ycbt.G_DEVCTRL -> {
                // Push dari cincin wajib dibalas 04 <key> {00}, kalau tidak akan diulang terus
                if (!Ycbt.isError(payload)) send(Ycbt.frame(Ycbt.G_DEVCTRL, cmd, byteArrayOf(0)), "ACK push")
            }
            Ycbt.G_GET -> if (cmd == 0x00 && payload.size >= 4 && !Ycbt.isError(payload)) {
                AppLog.d("Info perangkat (mentah): ${Ycbt.hex(payload)}")
            }
            Ycbt.G_REAL -> when (cmd) {
                0x00 -> if (payload.size >= 2) {
                    liveSteps = Ycbt.u16(payload, 0)
                    AppLog.d("👣 Langkah hari ini menurut cincin (live): $liveSteps")
                }
                0x15 -> if (payload.size >= 2) AppLog.d("🔋 Baterai cincin: ${Ycbt.u8(payload, 1)}%")
            }
            Ycbt.G_HEALTH -> onHealth(cmd, payload)
        }
    }

    // ---------------- transfer riwayat ----------------
    private fun advance() {
        main.removeCallbacks(watchdog)
        receiving = false
        buffer = ByteArray(0)
        retried = false
        blocksThisType = 0
        val next = queue.removeFirstOrNull()
        current = next
        if (next == null) {
            AppLog.d("Semua riwayat selesai. Menunggu 2 dtk lalu memutus…")
            main.postDelayed({ finish(true, summary()) }, 2000)
            return
        }
        query(next)
    }

    private fun query(h: Ycbt.History) {
        AppLog.d("▶ Minta riwayat ${h.label}")
        send(Ycbt.frame(Ycbt.G_HEALTH, h.query), "query ${h.name}")
        armWatchdog()
    }

    private fun armWatchdog() {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 10_000)
    }

    private fun onHealth(cmd: Int, payload: ByteArray) {
        val h = current ?: return
        if (Ycbt.isError(payload)) {
            AppLog.d("Cincin menolak ${h.name} (kode %02x)".format(payload[0].toInt() and 0xFF))
            advance(); return
        }
        when (cmd) {
            h.query -> {
                if (payload.size < 10) {
                    AppLog.d("Tidak ada data ${h.name} tersimpan di cincin")
                    advance(); return
                }
                val count = Ycbt.u16(payload, 0)
                expPackets = Ycbt.u16(payload, 2)
                expBytes = Ycbt.u32(payload, 6)
                AppLog.d("Header ${h.name}: $count record, $expPackets paket, $expBytes byte")
                buffer = ByteArray(0)
                receiving = true
                armWatchdog()
            }
            h.data -> {
                buffer += payload
                armWatchdog()
            }
            Ycbt.HEALTH_TERMINAL -> onTerminal(h, payload)
        }
    }

    private fun onTerminal(h: Ycbt.History, payload: ByteArray) {
        if (payload.size < 6) { advance(); return }
        val packets = Ycbt.u16(payload, 0)
        val bytes = Ycbt.u16(payload, 2)
        val crcExp = Ycbt.u16(payload, 4)
        val crcGot = Ycbt.crc16(buffer)
        val ok = crcGot == crcExp && bytes == (buffer.size and 0xFFFF)
        AppLog.d("Akhir blok ${h.name}: $packets paket, $bytes byte, crc %04x vs %04x → %s (buffer %d byte)"
            .format(crcExp, crcGot, if (ok) "COCOK" else "TIDAK COCOK", buffer.size))

        // ACK wajib dan dikirim sebelum parsing (00 = cocok, 04 = salah)
        send(Ycbt.frame(Ycbt.G_HEALTH, Ycbt.HEALTH_TERMINAL, byteArrayOf(if (ok) 0 else 4)), "ACK blok")

        if (!ok) {
            if (!retried) { retried = true; buffer = ByteArray(0); query(h) } else advance()
            return
        }

        val key = "${h.name}:${buffer.size}:$crcExp"
        val dup = !seenBlocks.add(key)
        if (!dup) save(h, buffer)
        else AppLog.d("Blok ${h.name} sama dengan sebelumnya (cincin mengulang) → lanjut")

        blocksThisType++
        buffer = ByteArray(0)
        // Beri jeda singkat: kalau cincin masih punya blok berikutnya, ia akan mengirim header baru
        main.removeCallbacks(watchdog)
        if (dup || blocksThisType >= 5) advance()
        else main.postDelayed(watchdog, 1500)
    }

    private fun save(h: Ycbt.History, buf: ByteArray) {
        when (h) {
            Ycbt.History.SPORT -> {
                val recs = Ycbt.parseSport(buf)
                gotSport += recs.size
                newSport += db.putSport(recs)
                val tot = recs.sumOf { it.steps }
                AppLog.d("✔ ${recs.size} record aktivitas (total $tot langkah). Contoh: " +
                    recs.take(3).joinToString { "${fmt(it.startMs)}–${fmt(it.endMs)}=${it.steps}" })
            }
            Ycbt.History.ALL -> {
                val recs = Ycbt.parseAll(buf)
                gotAll += recs.size
                newAll += db.putAll(recs)
                AppLog.d("✔ ${recs.size} record gabungan. Contoh: " +
                    recs.takeLast(3).joinToString { "${fmt(it.tsMs)} kum=${it.steps} hr=${it.hr}" })
            }
        }
    }

    private fun fmt(ms: Long) = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(ms))

    private fun summary(): String {
        val live = liveSteps?.let { " Langkah hari ini di cincin: $it." } ?: ""
        return "Aktivitas: $gotSport record ($newSport baru). Gabungan: $gotAll record ($newAll baru).$live"
    }

    // ---------------- selesai ----------------
    private fun fail(msg: String) {
        AppLog.d("✖ $msg")
        finish(false, msg)
    }

    private fun finish(ok: Boolean, summary: String) {
        if (finished) return
        finished = true
        stopScan()
        main.removeCallbacks(watchdog)
        main.removeCallbacks(connectTimeout)
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        AppLog.d("Selesai: $summary")
        onFinished(ok, summary)
    }
}
