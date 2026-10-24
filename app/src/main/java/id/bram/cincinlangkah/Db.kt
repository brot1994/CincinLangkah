package id.bram.cincinlangkah

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "data.db", null, 1) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE ring_sport(start INTEGER PRIMARY KEY, end_ INTEGER, steps INTEGER, dist INTEGER, kcal INTEGER)")
        db.execSQL("CREATE TABLE ring_all(ts INTEGER PRIMARY KEY, steps INTEGER, hr INTEGER, spo2 INTEGER)")
        db.execSQL("CREATE TABLE phone(ts INTEGER PRIMARY KEY, counter REAL, boot INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** @return jumlah record yang benar-benar baru */
    fun putSport(list: List<Ycbt.SportRec>): Int {
        var fresh = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in list) {
                val cv = ContentValues().apply {
                    put("start", r.startMs); put("end_", r.endMs); put("steps", r.steps)
                    put("dist", r.distM); put("kcal", r.kcal)
                }
                if (db.insertWithOnConflict("ring_sport", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) fresh++
                else db.update("ring_sport", cv, "start=?", arrayOf(r.startMs.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return fresh
    }

    fun putAll(list: List<Ycbt.AllRec>): Int {
        var fresh = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in list) {
                val cv = ContentValues().apply {
                    put("ts", r.tsMs); put("steps", r.steps); put("hr", r.hr); put("spo2", r.spo2)
                }
                if (db.insertWithOnConflict("ring_all", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) fresh++
                else db.update("ring_all", cv, "ts=?", arrayOf(r.tsMs.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return fresh
    }

    fun putPhone(ts: Long, counter: Float, boot: Long) {
        val cv = ContentValues().apply { put("ts", ts); put("counter", counter.toDouble()); put("boot", boot) }
        writableDatabase.insertWithOnConflict("phone", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun sport(from: Long, to: Long): List<Ycbt.SportRec> {
        val out = ArrayList<Ycbt.SportRec>()
        readableDatabase.rawQuery(
            "SELECT start,end_,steps,dist,kcal FROM ring_sport WHERE start>=? AND start<? ORDER BY start",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) out += Ycbt.SportRec(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getInt(4))
        }
        return out
    }

    fun all(from: Long, to: Long): List<Ycbt.AllRec> {
        val out = ArrayList<Ycbt.AllRec>()
        readableDatabase.rawQuery(
            "SELECT ts,steps,hr,spo2 FROM ring_all WHERE ts>=? AND ts<? ORDER BY ts",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) out += Ycbt.AllRec(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3))
        }
        return out
    }

    data class PhoneSample(val ts: Long, val counter: Float, val boot: Long)

    /** Sampel dalam rentang + satu sampel sebelum `from` sebagai titik awal. */
    fun phone(from: Long, to: Long): List<PhoneSample> {
        val out = ArrayList<PhoneSample>()
        readableDatabase.rawQuery(
            "SELECT ts,counter,boot FROM phone WHERE ts<? ORDER BY ts DESC LIMIT 1",
            arrayOf(from.toString())
        ).use { c -> if (c.moveToNext()) out += PhoneSample(c.getLong(0), c.getFloat(1), c.getLong(2)) }
        readableDatabase.rawQuery(
            "SELECT ts,counter,boot FROM phone WHERE ts>=? AND ts<? ORDER BY ts",
            arrayOf(from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) out += PhoneSample(c.getLong(0), c.getFloat(1), c.getLong(2)) }
        return out
    }

    fun firstPhoneTs(): Long? =
        readableDatabase.rawQuery("SELECT MIN(ts) FROM phone", null).use { c ->
            if (c.moveToNext() && !c.isNull(0)) c.getLong(0) else null
        }
}
