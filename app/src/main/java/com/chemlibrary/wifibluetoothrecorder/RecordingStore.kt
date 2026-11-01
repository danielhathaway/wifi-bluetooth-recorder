package com.example.wifibluetoothrecorder

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.Writer

/** Serialized recording commits with separate read connections for streaming exports. */
class RecordingStore internal constructor(context: Context, databaseName: String = "pocketdriver.db",
    private val password: String = DatabaseKey.password(context)) {
    private val file = context.getDatabasePath(databaseName)
    private val db: SQLiteDatabase
    init {
        System.loadLibrary("sqlcipher")
        file.parentFile?.mkdirs()
        db = SQLiteDatabase.openOrCreateDatabase(file, password, null, null)
        db.enableWriteAheadLogging()
        db.execSQL("PRAGMA synchronous=FULL")
        db.execSQL("CREATE TABLE IF NOT EXISTS recordings (id INTEGER PRIMARY KEY AUTOINCREMENT, started INTEGER NOT NULL, ended INTEGER, bluetooth INTEGER NOT NULL DEFAULT 0, wifi INTEGER NOT NULL DEFAULT 0, distance REAL NOT NULL DEFAULT 0, last_saved INTEGER NOT NULL)")
        // A process death leaves committed observations intact and closes the interrupted session at its last commit.
        db.execSQL("UPDATE recordings SET ended=last_saved WHERE ended IS NULL")
    }
    private fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    @Synchronized fun start(now: Long = System.currentTimeMillis()): Long = transaction {
        db.execSQL("INSERT INTO recordings(started,last_saved) VALUES(?,?)", arrayOf(now, now))
        val id = db.rawQuery("SELECT last_insert_rowid()", null).use { it.moveToFirst(); it.getLong(0) }
        db.execSQL("CREATE TABLE ${RecordFormat.table(id)} (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, type TEXT NOT NULL, transport TEXT NOT NULL, latitude REAL, longitude REAL, data TEXT NOT NULL)")
        id
    }
    @Synchronized fun append(id: Long, rows: List<Observation>, distance: Double? = null) = transaction {
        val table = RecordFormat.table(id)
        rows.forEach { row ->
            db.execSQL("INSERT INTO $table(time,type,transport,latitude,longitude,data) VALUES(?,?,?,?,?,?)",
                arrayOf(row.time, row.type, row.transport, row.latitude, row.longitude, row.data))
        }
        db.execSQL("UPDATE recordings SET bluetooth=bluetooth+?,wifi=wifi+?,distance=COALESCE(?,distance),last_saved=? WHERE id=?",
            arrayOf(rows.count { it.type == "bluetooth" }, rows.count { it.type == "wifi" }, distance, System.currentTimeMillis(), id))
    }
    @Synchronized fun finish(id: Long) {
        db.execSQL("UPDATE recordings SET ended=?,last_saved=? WHERE id=?", arrayOf(System.currentTimeMillis(), System.currentTimeMillis(), id))
    }
    @Synchronized fun recordings(): List<Recording> = readRecordings(db)
    private fun readRecordings(connection: SQLiteDatabase): List<Recording> = connection.rawQuery("SELECT * FROM recordings ORDER BY id DESC", null).use { c ->
        buildList { while(c.moveToNext()) add(Recording(c.getLong(0),c.getLong(1),if(c.isNull(2)) null else c.getLong(2),c.getLong(3),c.getLong(4),c.getDouble(5),c.getLong(6))) }
    }
    @Synchronized fun points(id: Long): List<TrackPoint> = db.rawQuery(
        "SELECT DISTINCT latitude,longitude FROM ${RecordFormat.table(id)} WHERE latitude IS NOT NULL AND longitude IS NOT NULL", null).use { c ->
        buildList { while(c.moveToNext()) add(TrackPoint(c.getDouble(0),c.getDouble(1))) }
    }
    /** Streams rows, including session metadata and location fixes, without a whole-file buffer. */
    fun export(writer: Writer, id: Long? = null) {
        // Observation tables are append-only. Capture metadata and row limits under the writer lock,
        // then stream that immutable prefix on a read connection without blocking later commits.
        val sessions = synchronized(this) {
            readRecordings(db).filter { id == null || it.id == id }.reversed().map { recording ->
                val limit = db.rawQuery("SELECT COALESCE(MAX(id),0) FROM ${RecordFormat.table(recording.id)}", null).use { it.moveToFirst(); it.getLong(0) }
                recording to limit
            }
        }
        if (id != null) require(sessions.isNotEmpty()) { "Recording does not exist" }
        val snapshot = SQLiteDatabase.openDatabase(file.absolutePath, password, null, SQLiteDatabase.OPEN_READONLY, null)
        try {
            RecordFormat.csvRow(writer, listOf("recording_id","started_ms","ended_ms","distance_m","record_id","time_ms","type","transport","latitude","longitude","data_json"))
            sessions.forEach { (recording, limit) ->
                snapshot.rawQuery("SELECT * FROM ${RecordFormat.table(recording.id)} WHERE id<=? ORDER BY id", arrayOf(limit.toString())).use { c ->
                    val metadata = listOf(recording.id, recording.started, recording.ended, recording.distance)
                    if (!c.moveToFirst()) RecordFormat.csvRow(writer, metadata + List(7) { null })
                    else do {
                        RecordFormat.csvRow(writer, metadata + (0..6).map { if(c.isNull(it)) null else c.getString(it) })
                    } while(c.moveToNext())
                }
            }
            writer.flush()
        } finally { snapshot.close() }
    }
    @Synchronized internal fun close() { db.close() }
    companion object {
        @Volatile private var instance: RecordingStore? = null
        fun get(context: Context): RecordingStore = instance ?: synchronized(this) {
            instance ?: RecordingStore(context.applicationContext).also { instance = it }
        }
    }
}
