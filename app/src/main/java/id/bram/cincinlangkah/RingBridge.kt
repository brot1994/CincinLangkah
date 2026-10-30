package id.bram.cincinlangkah

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri

/**
 * Cincin Langkah → Lige Pantau: data cincin (tidur, detak, tanda vital, langkah) dibagikan lewat
 * ContentProvider yang hanya bisa dibaca aplikasi bertanda tangan sama.
 */
object RingBridge {
    const val AUTH = "id.bram.cincinlangkah.data"
    /** tabel → kolom waktu */
    val TABLES = linkedMapOf("sleep_seg" to "start", "heart" to "ts", "vital" to "ts", "ring_sport" to "start", "ring_all" to "ts")
    @Volatile private var lastAt = 0L

    fun hasRing(ctx: Context) = !BuildConfig.BAND_ONLY ||
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("has_ring", false)

    /** Lige Pantau: salin data cincin terbaru dari Cincin Langkah ke database sendiri. */
    fun import(ctx: Context, force: Boolean = false): Int {
        if (!BuildConfig.BAND_ONLY) return 0
        val now = System.currentTimeMillis()
        if (!force && now - lastAt < 5 * 60_000L) return 0
        lastAt = now
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val first = !p.getBoolean("has_ring", false)
        val from = now - (if (first) 45L else 3L) * 86_400_000L
        val db = Db.get(ctx).writableDatabase
        var n = 0
        try {
            for ((table, col) in TABLES) {
                ctx.contentResolver.query(Uri.parse("content://$AUTH/$table"), null, null, arrayOf(from.toString()), null)?.use { c ->
                    db.beginTransaction()
                    try {
                        while (c.moveToNext()) {
                            val cv = ContentValues()
                            for (i in 0 until c.columnCount) when (c.getType(i)) {
                                Cursor.FIELD_TYPE_INTEGER -> cv.put(c.getColumnName(i), c.getLong(i))
                                Cursor.FIELD_TYPE_FLOAT -> cv.put(c.getColumnName(i), c.getDouble(i))
                                Cursor.FIELD_TYPE_STRING -> cv.put(c.getColumnName(i), c.getString(i))
                                else -> {}
                            }
                            if (db.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1L) n++
                        }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
            }
            if (n > 0) {
                p.edit().putBoolean("has_ring", true).apply()
                AppLog.d("💍 Data cincin dari Cincin Langkah: $n baris" + if (first) " (45 hari terakhir)" else "")
            }
        } catch (e: SecurityException) {
            AppLog.d("💍 Belum diizinkan membaca Cincin Langkah — perbarui Cincin Langkah dulu, lalu pasang ulang Lige Pantau")
        } catch (e: Exception) {
            AppLog.d("💍 Ambil data cincin gagal: ${e.message}")
        }
        return n
    }
}

/** Dipasang hanya di Cincin Langkah (lihat manifest flavor "cincin"). */
class RingProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val table = uri.lastPathSegment ?: return null
        val col = RingBridge.TABLES[table] ?: return null
        val from = args?.firstOrNull()?.toLongOrNull() ?: 0L
        return Db.get(context!!).readableDatabase.query(table, null, "$col>=?", arrayOf(from.toString()), null, null, col)
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
