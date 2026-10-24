package id.bram.cincinlangkah

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Protokol Yucheng YCBT (aplikasi SmartHealth, cincin R11M).
 *
 * Frame: [TYPE][CMD][LEN lo][LEN hi][payload...][CRC lo][CRC hi]
 * LEN = total panjang frame (payload + 6). CRC-16/CCITT-FALSE atas semua byte sebelum CRC.
 *
 * Referensi: SDK com.yucheng.ycbtsdk (didekompilasi), PulseLoop (tasks/ycbt-protocol.md),
 * Gadgetbridge PR #6239 (driver R20/R11M).
 */
object Ycbt {
    val SERVICE: UUID = UUID.fromString("be940000-7333-be46-b7ae-689e71722bd5")
    val CHAR_CMD: UUID = UUID.fromString("be940001-7333-be46-b7ae-689e71722bd5")
    val CHAR_STREAM: UUID = UUID.fromString("be940003-7333-be46-b7ae-689e71722bd5")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val EPOCH_2000 = 946_684_800L

    // Grup
    const val G_SETTING = 0x01
    const val G_GET = 0x02
    const val G_APPCTRL = 0x03
    const val G_DEVCTRL = 0x04
    const val G_HEALTH = 0x05
    const val G_REAL = 0x06

    const val HEALTH_TERMINAL = 0x80

    /** Jenis riwayat yang kita minta: kunci query -> kunci data, panjang record. */
    enum class History(val query: Int, val data: Int, val delete: Int, val label: String) {
        SPORT(0x02, 0x11, 0x40, "aktivitas (langkah per 30 menit)"),
        SLEEP(0x04, 0x13, 0x41, "tidur"),
        HEART(0x06, 0x15, 0x42, "detak jantung"),
        ALL(0x09, 0x18, 0x44, "gabungan (penghitung langkah kumulatif)"),
    }

    /** 05 4x {02}: hapus riwayat jenis itu dari cincin (seperti yang dilakukan SmartHealth setelah sinkron). */
    fun deleteFrame(h: History) = frame(G_HEALTH, h.delete, byteArrayOf(2))

    fun crc16(b: ByteArray, len: Int = b.size): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor ((b[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    fun frame(type: Int, cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = payload.size + 6
        val out = ByteArray(len)
        out[0] = type.toByte()
        out[1] = cmd.toByte()
        out[2] = (len and 0xFF).toByte()
        out[3] = ((len shr 8) and 0xFF).toByte()
        payload.copyInto(out, 4)
        val c = crc16(out, len - 2)
        out[len - 2] = (c and 0xFF).toByte()
        out[len - 1] = ((c shr 8) and 0xFF).toByte()
        return out
    }

    /** 01 00: set jam cincin = jam lokal HP. Hari: Senin = 0 ... Minggu = 6. */
    fun setTime(now: LocalDateTime = LocalDateTime.now()): ByteArray {
        val p = byteArrayOf(
            (now.year and 0xFF).toByte(), ((now.year shr 8) and 0xFF).toByte(),
            now.monthValue.toByte(), now.dayOfMonth.toByte(),
            now.hour.toByte(), now.minute.toByte(), now.second.toByte(),
            (now.dayOfWeek.value - 1).toByte()
        )
        return frame(G_SETTING, 0x00, p)
    }

    fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    fun u32(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong()) or (u8(b, o + 1).toLong() shl 8) or
            (u8(b, o + 2).toLong() shl 16) or (u8(b, o + 3).toLong() shl 24)

    /**
     * Cincin menyimpan jam dinding lokal seolah-olah UTC (tidak kenal zona waktu).
     * Detik-sejak-2000 dibaca sebagai waktu lokal, lalu dikonversi ke instan sebenarnya.
     */
    fun ringTimeToMillis(sec2000: Long): Long =
        LocalDateTime.ofEpochSecond(sec2000 + EPOCH_2000, 0, ZoneOffset.UTC)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    data class SportRec(val startMs: Long, val endMs: Long, val steps: Int, val distM: Int, val kcal: Int)
    data class AllRec(val tsMs: Long, val steps: Int, val hr: Int, val spo2: Int)
    data class HeartRec(val tsMs: Long, val hr: Int)
    /** stage: 1 nyenyak, 2 ringan, 3 REM, 4 terjaga, 5 tidur siang */
    data class SleepSeg(val startMs: Long, val durSec: Int, val stage: Int)

    fun parseHeart(buf: ByteArray): List<HeartRec> {
        val out = ArrayList<HeartRec>()
        var i = 0
        while (i + 6 <= buf.size) {
            val t = u32(buf, i); val hr = u8(buf, i + 5)
            if (t > 0 && hr in 25..230) out += HeartRec(ringTimeToMillis(t), hr)
            i += 6
        }
        return out
    }

    /** Sesi: header 20 byte (panjang sesi @2), lalu segmen 8 byte [tag][start u32][durasi u24]. */
    fun parseSleep(buf: ByteArray): List<SleepSeg> {
        val out = ArrayList<SleepSeg>()
        var p = 0
        while (p + 20 <= buf.size) {
            val len = u16(buf, p + 2)
            if (len < 20) break
            val end = minOf(p + len, buf.size)
            var q = p + 20
            while (q + 8 <= end) {
                val stage = u8(buf, q) and 0x0F
                val st = u32(buf, q + 1)
                val dur = u8(buf, q + 5) or (u8(buf, q + 6) shl 8) or (u8(buf, q + 7) shl 16)
                if (st > 0 && dur > 0 && stage in 1..5) out += SleepSeg(ringTimeToMillis(st), dur, stage)
                q += 8
            }
            p += len
        }
        return out
    }

    fun parseSport(buf: ByteArray): List<SportRec> {
        val out = ArrayList<SportRec>()
        var i = 0
        while (i + 14 <= buf.size) {
            val s = u32(buf, i)
            val e = u32(buf, i + 4)
            if (s > 0) {
                out += SportRec(
                    ringTimeToMillis(s), ringTimeToMillis(e),
                    u16(buf, i + 8), u16(buf, i + 10), u16(buf, i + 12)
                )
            }
            i += 14
        }
        return out
    }

    fun parseAll(buf: ByteArray): List<AllRec> {
        val out = ArrayList<AllRec>()
        var i = 0
        while (i + 20 <= buf.size) {
            val t = u32(buf, i)
            if (t > 0) {
                out += AllRec(ringTimeToMillis(t), u16(buf, i + 4), u8(buf, i + 6), u8(buf, i + 9))
            }
            i += 20
        }
        return out
    }

    fun hex(b: ByteArray, max: Int = 4096): String {
        val n = minOf(b.size, max)
        val sb = StringBuilder(n * 3)
        for (i in 0 until n) {
            if (i > 0) sb.append(' ')
            sb.append(String.format("%02x", b[i].toInt() and 0xFF))
        }
        if (b.size > max) sb.append(" …(+").append(b.size - max).append(")")
        return sb.toString()
    }

    /** Payload 1 byte FB..FF = kode error dari cincin. */
    fun isError(payload: ByteArray) = payload.size == 1 && (payload[0].toInt() and 0xFF) >= 0xFB
}

/**
 * Menyusun notifikasi BLE menjadi frame utuh berdasarkan field panjang,
 * bukan batas notifikasi (frame panjang terpecah, frame pendek bisa tergabung).
 */
class FrameAssembler {
    private val pending = HashMap<UUID, ByteArray>()

    fun reset() = pending.clear()

    fun append(src: UUID, data: ByteArray): List<ByteArray> {
        var buf = (pending[src] ?: ByteArray(0)) + data
        val frames = ArrayList<ByteArray>()
        while (buf.size >= 4) {
            val g = buf[0].toInt() and 0xFF
            val declared = (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
            val plausible = g in 0x01..0x0E || g == 0x13 || g == 0x7E
            if (!plausible || declared < 6 || declared > 2048) {
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            if (buf.size < declared) break
            frames += buf.copyOfRange(0, declared)
            buf = buf.copyOfRange(declared, buf.size)
        }
        pending[src] = buf
        return frames
    }
}
