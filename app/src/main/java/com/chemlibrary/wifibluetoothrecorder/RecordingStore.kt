package com.example.wifibluetoothrecorder

import android.content.Context
import android.database.Cursor
import org.json.JSONObject
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.Writer

/** Serialized recording commits with separate read connections for streaming exports. */
class RecordingStore internal constructor(context: Context, databaseName: String = DatabaseFiles.NAME,
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
        if (db.version < 1) transaction {
            // Upgrade existing installations atomically, retaining empty sessions and valid (0,0) fixes.
            readRecordings(db).forEach { recording ->
                val table = RecordFormat.table(recording.id)
                db.execSQL("DELETE FROM $table WHERE latitude IS NULL OR longitude IS NULL OR latitude NOT BETWEEN -90 AND 90 OR longitude NOT BETWEEN -180 AND 180")
                db.execSQL("UPDATE recordings SET wifi=(SELECT COUNT(*) FROM $table WHERE type='wifi'),bluetooth=(SELECT COUNT(*) FROM $table WHERE type='bluetooth') WHERE id=?", arrayOf(recording.id))
                for (operation in listOf("INSERT", "UPDATE")) {
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS ${table}_located_${operation.lowercase()} BEFORE $operation ON $table WHEN NEW.latitude IS NULL OR NEW.longitude IS NULL OR NEW.latitude NOT BETWEEN -90 AND 90 OR NEW.longitude NOT BETWEEN -180 AND 180 BEGIN SELECT RAISE(ABORT,'Coordinates required'); END")
                }
            }
            db.version = 1
        }
    }
    private fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    @Synchronized fun start(now: Long = System.currentTimeMillis()): Long = transaction {
        db.execSQL("INSERT INTO recordings(started,last_saved) VALUES(?,?)", arrayOf(now, now))
        val id = db.rawQuery("SELECT last_insert_rowid()", null).use { it.moveToFirst(); it.getLong(0) }
        db.execSQL("CREATE TABLE ${RecordFormat.table(id)} (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, type TEXT NOT NULL, transport TEXT NOT NULL, latitude REAL NOT NULL CHECK(latitude BETWEEN -90 AND 90), longitude REAL NOT NULL CHECK(longitude BETWEEN -180 AND 180), data TEXT NOT NULL)")
        id
    }
    @Synchronized fun append(id: Long, rows: List<Observation>, distance: Double? = null) = transaction {
        val table = RecordFormat.table(id)
        val located = rows.filter { it.hasCoordinates() }
        located.forEach { row ->
            db.execSQL("INSERT INTO $table(time,type,transport,latitude,longitude,data) VALUES(?,?,?,?,?,?)",
                arrayOf(row.time, row.type, row.transport, row.latitude, row.longitude, row.data))
        }
        db.execSQL("UPDATE recordings SET bluetooth=bluetooth+?,wifi=wifi+?,distance=COALESCE(?,distance),last_saved=? WHERE id=?",
            arrayOf(located.count { it.type == "bluetooth" }, located.count { it.type == "wifi" }, distance, System.currentTimeMillis(), id))
    }
    @Synchronized fun finish(id: Long) {
        db.execSQL("UPDATE recordings SET ended=?,last_saved=? WHERE id=?", arrayOf(System.currentTimeMillis(), System.currentTimeMillis(), id))
    }
    @Synchronized fun recordings(): List<Recording> = readRecordings(db)
    private fun readRecordings(connection: SQLiteDatabase): List<Recording> = connection.rawQuery("SELECT * FROM recordings ORDER BY id DESC", null).use { c ->
        buildList { while(c.moveToNext()) add(Recording(c.getLong(0),c.getLong(1),if(c.isNull(2)) null else c.getLong(2),c.getLong(3),c.getLong(4),c.getDouble(5),c.getLong(6))) }
    }
    /** Capture an append-only prefix, then release the writer lock before scanning a large recording. */
    private fun <T> readPrefix(id: Long, block: (SQLiteDatabase, Long) -> T): T {
        val table = RecordFormat.table(id)
        val limit = synchronized(this) {
            db.rawQuery("SELECT COALESCE(MAX(id),0) FROM $table",null).use { it.moveToFirst(); it.getLong(0) }
        }
        val reader = SQLiteDatabase.openDatabase(file.absolutePath,password,null,SQLiteDatabase.OPEN_READONLY,null)
        try { return block(reader,limit) } finally { reader.close() }
    }
    fun points(id: Long): List<TrackPoint> = readPrefix(id) { reader,limit ->
        reader.rawQuery("SELECT DISTINCT latitude,longitude FROM ${RecordFormat.table(id)} WHERE id<=?",arrayOf(limit.toString())).use { c ->
            buildList { while(c.moveToNext()) add(TrackPoint(c.getDouble(0),c.getDouble(1))) }
        }
    }
    private fun reading(cursor: Cursor): SignalReading {
        val data = try { JSONObject(cursor.getString(6)) } catch (_: Exception) { JSONObject() }
        fun text(key: String) = if (data.isNull(key)) "" else data.optString(key, "")
        val type = cursor.getString(2)
        return SignalReading(cursor.getLong(0),cursor.getLong(1),type,
            text(if(type == "wifi") "bssid" else "mac"),text(if(type == "wifi") "ssid" else "name"),
            text(if(type == "wifi") "security" else if(type == "location") "accuracy_m" else ""),
            cursor.getDouble(4),cursor.getDouble(5))
    }
    fun groups(id: Long, type: String): List<SignalGroup> = readPrefix(id) { reader,limit ->
        require(type in listOf("wifi","bluetooth","location"))
        val builder = SignalGroupBuilder()
        reader.rawQuery("SELECT * FROM ${RecordFormat.table(id)} WHERE type=? AND id<=? ORDER BY id",arrayOf(type,limit.toString())).use { c ->
            while (c.moveToNext()) builder.add(reading(c))
        }
        builder.build()
    }
    fun groupPoints(id: Long, type: String, key: String): List<TrackPoint> = readPrefix(id) { reader,limit ->
        require(type in listOf("wifi","bluetooth","location"))
        val points = linkedSetOf<TrackPoint>()
        reader.rawQuery("SELECT * FROM ${RecordFormat.table(id)} WHERE type=? AND id<=? ORDER BY id",arrayOf(type,limit.toString())).use { c ->
            while(c.moveToNext()) {
                val r = reading(c)
                if (SignalGroups.key(r.type,r.address,r.id,r.latitude,r.longitude) == key) points += TrackPoint(r.latitude,r.longitude)
            }
        }
        points.toList()
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
