package id.bram.cincinlangkah

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "data.db", null, 5) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE ring_sport(start INTEGER PRIMARY KEY, end_ INTEGER, steps INTEGER, dist INTEGER, kcal INTEGER, cleared INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE ring_all(ts INTEGER PRIMARY KEY, steps INTEGER, hr INTEGER, spo2 INTEGER)")
        db.execSQL("CREATE TABLE phone(ts INTEGER PRIMARY KEY, counter REAL, boot INTEGER)")
        createV2(db)
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS heart(ts INTEGER PRIMARY KEY, hr INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sleep_seg(start INTEGER PRIMARY KEY, dur INTEGER, stage INTEGER)")
        createV3(db)
    }

    private fun createV4(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS exercise(start INTEGER PRIMARY KEY, end_ INTEGER, type TEXT, auto_type TEXT, " +
            "steps INTEGER, chosen INTEGER DEFAULT 0, notified INTEGER DEFAULT 0)")
        createV5(db)
    }

    private fun createV5(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS weight(ts INTEGER PRIMARY KEY, kg REAL, src TEXT)")
    }

    /** Berat badan: (waktu, kg). */
    fun weights(from: Long, to: Long): List<Pair<Long, Double>> {
        val out = ArrayList<Pair<Long, Double>>()
        readableDatabase.rawQuery("SELECT ts,kg FROM weight WHERE ts>=? AND ts<? ORDER BY ts",
            arrayOf(from.toString(), to.toString())).use { c -> while (c.moveToNext()) out += c.getLong(0) to c.getDouble(1) }
        return out
    }

    fun latestWeight(before: Long): Pair<Long, Double>? =
        readableDatabase.rawQuery("SELECT ts,kg FROM weight WHERE ts<? ORDER BY ts DESC LIMIT 1", arrayOf(before.toString())).use { c ->
            if (c.moveToNext()) c.getLong(0) to c.getDouble(1) else null
        }

    fun putWeight(ts: Long, kg: Double, src: String) {
        val cv = ContentValues().apply { put("ts", ts); put("kg", kg); put("src", src) }
        writableDatabase.insertWithOnConflict("weight", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteWeight(ts: Long) { writableDatabase.delete("weight", "ts=?", arrayOf(ts.toString())) }

    data class Exercise(val start: Long, val end: Long, val type: String, val autoType: String,
                        val steps: Int, val chosen: Boolean, val notified: Boolean)

    fun exercises(from: Long, to: Long): List<Exercise> {
        val out = ArrayList<Exercise>()
        readableDatabase.rawQuery("SELECT start,end_,type,auto_type,steps,chosen,notified FROM exercise " +
            "WHERE end_>=? AND start<? ORDER BY start", arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) out += Exercise(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
                c.getInt(4), c.getInt(5) == 1, c.getInt(6) == 1)
        }
        return out
    }

    fun putExercise(e: Exercise) {
        val cv = ContentValues().apply {
            put("start", e.start); put("end_", e.end); put("type", e.type); put("auto_type", e.autoType)
            put("steps", e.steps); put("chosen", if (e.chosen) 1 else 0); put("notified", if (e.notified) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("exercise", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteExercise(start: Long) { writableDatabase.delete("exercise", "start=?", arrayOf(start.toString())) }

    fun setExerciseType(start: Long, type: String) {
        writableDatabase.execSQL("UPDATE exercise SET type=?, chosen=1 WHERE start=?", arrayOf<Any>(type, start))
    }

    private fun createV3(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS vital(ts INTEGER, kind TEXT, v1 INTEGER, v2 INTEGER, PRIMARY KEY(ts, kind))")
        createV4(db)
    }

    fun putVitals(list: List<Ycbt.Vital>): Int {
        var n = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (v in list) {
                val cv = ContentValues().apply { put("ts", v.tsMs); put("kind", v.kind); put("v1", v.v1); put("v2", v.v2) }
                if (db.insertWithOnConflict("vital", null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1L) n++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return n
    }

    fun vitals(kind: String, from: Long, to: Long): List<Ycbt.Vital> {
        val out = ArrayList<Ycbt.Vital>()
        readableDatabase.rawQuery("SELECT ts,v1,v2 FROM vital WHERE kind=? AND ts>=? AND ts<? ORDER BY ts",
            arrayOf(kind, from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) out += Ycbt.Vital(c.getLong(0), kind, c.getInt(1), c.getInt(2))
        }
        return out
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createV2(db)
            db.execSQL("ALTER TABLE ring_sport ADD COLUMN cleared INTEGER DEFAULT 0")
        }
        if (oldVersion < 3) createV3(db)
        if (oldVersion < 4) createV4(db)
        if (oldVersion < 5) createV5(db)
    }

    fun putHeart(list: List<Ycbt.HeartRec>): Int {
        var fresh = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in list) {
                val cv = ContentValues().apply { put("ts", r.tsMs); put("hr", r.hr) }
                if (db.insertWithOnConflict("heart", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) fresh++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return fresh
    }

    fun putSleep(list: List<Ycbt.SleepSeg>): Int {
        var fresh = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in list) {
                val cv = ContentValues().apply { put("start", r.startMs); put("dur", r.durSec); put("stage", r.stage) }
                if (db.insertWithOnConflict("sleep_seg", null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1L) fresh++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return fresh
    }

    fun heart(from: Long, to: Long): List<Ycbt.HeartRec> {
        val out = ArrayList<Ycbt.HeartRec>()
        readableDatabase.rawQuery("SELECT ts,hr FROM heart WHERE ts>=? AND ts<? ORDER BY ts",
            arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) out += Ycbt.HeartRec(c.getLong(0), c.getInt(1))
        }
        return out
    }

    fun sleep(from: Long, to: Long): List<Ycbt.SleepSeg> {
        val out = ArrayList<Ycbt.SleepSeg>()
        readableDatabase.rawQuery("SELECT start,dur,stage FROM sleep_seg WHERE start>=? AND start<? ORDER BY start",
            arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) out += Ycbt.SleepSeg(c.getLong(0), c.getInt(1), c.getInt(2))
        }
        return out
    }

    /** @return jumlah record yang benar-benar baru */
    fun putSport(list: List<Ycbt.SportRec>): Int {
        var fresh = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in list) {
                var steps = r.steps; var dist = r.distM; var kcal = r.kcal
                db.rawQuery("SELECT steps,dist,kcal,cleared FROM ring_sport WHERE start=?", arrayOf(r.startMs.toString())).use { c ->
                    if (c.moveToNext()) {
                        if (c.getInt(3) == 1) {
                            // Bucket ini sudah pernah dibaca lalu dihapus dari cincin saat masih berjalan:
                            // yang datang sekarang adalah sisanya → dijumlahkan.
                            steps += c.getInt(0); dist += c.getInt(1); kcal += c.getInt(2)
                        }
                    } else fresh++
                }
                val cv = ContentValues().apply {
                    put("start", r.startMs); put("end_", r.endMs); put("steps", steps)
                    put("dist", dist); put("kcal", kcal); put("cleared", 0)
                }
                db.insertWithOnConflict("ring_sport", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
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

    /** Tandai bucket yang baru saja dihapus dari cincin. */
    fun markSportCleared(starts: Collection<Long>) {
        val db = writableDatabase
        for (s in starts) db.execSQL("UPDATE ring_sport SET cleared=1 WHERE start=?", arrayOf(s))
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
