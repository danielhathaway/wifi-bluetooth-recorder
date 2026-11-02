package com.example.wifibluetoothrecorder

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.StringWriter
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingStoreTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="test-${UUID.randomUUID()}.db"
    private lateinit var store: RecordingStore
    @Before fun setup() { store=RecordingStore(context,name,"test-secret") }
    @After fun cleanup() { store.close(); context.deleteDatabase(name) }
    @Test fun sessionsAreIsolatedAndBothBluetoothTransportsPreserveDistinctData() {
        val first=store.start()
        store.append(first,listOf(Observation(1,"bluetooth","classic",0.0,0.0,data="{\"class\":1032}"),
            Observation(2,"bluetooth","le",0.0,0.0,"{\"manufacturer_data\":{\"76\":\"ab\"}}"),
            Observation(3,"wifi",latitude=1.0,longitude=2.0)),12.5)
        store.finish(first)
        val second=store.start()
        store.append(second,listOf(Observation(4,"wifi",latitude=3.0,longitude=4.0)))
        val r=store.recordings().first { it.id==first }
        assertEquals(2L,r.bluetooth); assertEquals(1L,r.wifi); assertEquals(12.5,r.distance,0.001)
        assertNotNull(r.ended)
        assertEquals(2,store.points(first).size)
        assertEquals(1,store.points(second).size)
        val csv=StringWriter().also { store.export(it,first) }.toString()
        assertTrue(csv.contains("classic")); assertTrue(csv.contains("manufacturer_data"))
        assertFalse(csv.contains("\"4\",\"wifi\""))
        assertEquals(4,csv.split("\r\n").filter { it.isNotEmpty() }.size)
        assertEquals(5,StringWriter().also { store.export(it) }.toString().split("\r\n").filter { it.isNotEmpty() }.size)
    }
    @Test fun committedRowsSurviveReopenAndInterruptedSessionIsFinalized() {
        val id=store.start()
        store.append(id,listOf(Observation(10,"wifi",latitude=1.0,longitude=2.0)))
        store.close()
        store=RecordingStore(context,name,"test-secret")
        assertEquals(1L,store.recordings().single().wifi)
        assertNotNull(store.recordings().single().ended)
        assertTrue(StringWriter().also { store.export(it,id) }.toString().contains("wifi"))
    }
    @Test fun fileIsEncryptedAndWrongPasswordCannotReadIt() {
        val id=store.start(); store.append(id,listOf(Observation(1,"wifi",latitude=1.0,longitude=2.0,data="private-ssid")))
        val bytes=context.getDatabasePath(name).readBytes()
        assertFalse(String(bytes,Charsets.ISO_8859_1).contains("SQLite format 3"))
        assertFalse(String(bytes,Charsets.ISO_8859_1).contains("private-ssid"))
        try { RecordingStore(context,name,"wrong-key").close(); fail("Wrong password opened the database") }
        catch(_: android.database.sqlite.SQLiteException) { }
    }
    @Test fun emptyRecordingStillExportsMetadata() {
        val id=store.start(); store.finish(id)
        val rows=StringWriter().also { store.export(it,id) }.toString().split("\r\n").filter { it.isNotEmpty() }
        assertEquals(2,rows.size)
        assertTrue(rows[1].startsWith("\"$id\","))
    }
    @Test fun slowExportAllowsWritesAndKeepsItsOriginalSnapshot() {
        val id=store.start()
        store.append(id,listOf(Observation(1,"wifi",latitude=1.0,longitude=2.0)))
        var wrote=false
        val out=object : StringWriter() {
            override fun write(text: String) {
                if(!wrote) { wrote=true; store.append(id,listOf(Observation(2,"bluetooth","le",1.0,2.0))) }
                super.write(text)
            }
        }
        store.export(out,id)
        assertFalse(out.toString().contains("bluetooth\",\"le"))
        assertEquals(1L,store.recordings().single().bluetooth)
        assertEquals(2,out.toString().split("\r\n").filter { it.isNotEmpty() }.size)
    }
    @Test fun measurementTimeLocationAndSignalEvidenceSurviveCsvExport() {
        val buffer=SignalLocationBuffer(MeasurementClock(10_000_000_000L,100000L))
        buffer.addLocation(TimedLocation(10_000_000_000L,100000L,1.0,3.0,5.0))
        buffer.addLocation(TimedLocation(12_000_000_000L,102000L,3.0,3.0,6.0))
        buffer.add(PendingSignal("wifi","", "{\"bssid\":\"aa:bb:cc:dd:ee:ff\",\"signal\":-70}",
            11_000_000_000L,30_000_000_000L,120000L,"wifi_scan"))
        val row=buffer.drain(30_000_000_000L).single().toObservation()
        assertEquals(101000L,row.time)
        assertEquals(2.0,row.latitude!!,0.00001)
        val data=org.json.JSONObject(row.data)
        assertEquals(-70,data.getInt("signal"))
        assertEquals("interpolated",data.getString("location_match"))
        assertEquals("wifi_scan",data.getString("time_source"))
        assertEquals(120000L,data.getLong("received_time_ms"))
        assertEquals(11_000_000_000L,data.getLong("measurement_elapsed_ns"))
        assertEquals(5.0,data.getJSONObject("location_before").getDouble("accuracy_m"),0.0)
        val id=store.start()
        store.append(id,listOf(row))
        val csv=StringWriter().also { store.export(it,id) }.toString()
        assertTrue(csv.contains("\"101000\",\"wifi\""))
        assertTrue(csv.contains("\"2.0\",\"3.0\""))
        assertTrue(csv.contains("measurement_elapsed_ns"))
        assertTrue(csv.contains("location_after"))
    }
    @Test fun missingMeasurementTimestampIsExplicitlyUnlocated() {
        val buffer=SignalLocationBuffer(MeasurementClock(10_000_000_000L,100000L))
        buffer.addLocation(TimedLocation(30_000_000_000L,120000L,1.0,3.0,5.0))
        buffer.add(PendingSignal("bluetooth","classic","{}",null,30_000_000_000L,120000L,"unavailable"))
        val row=buffer.drain(30_000_000_000L).single().toObservation()
        assertNull(row.latitude); assertNull(row.longitude)
        val data=org.json.JSONObject(row.data)
        assertEquals("measurement_time_unavailable",data.getString("location_match"))
        assertEquals("callback",data.getString("time_source"))
        assertTrue(data.isNull("measurement_elapsed_ns"))
        val id=store.start()
        store.append(id,listOf(row))
        assertEquals(0L,store.recordings().single().bluetooth)
        assertTrue(store.groups(id,"bluetooth").isEmpty())
    }
    @Test fun locatedSightingsAreOnlyGroupedInListsAndMapsAreFilteredByAddress() {
        val id=store.start()
        val same="{\"bssid\":\"00:11:22:33:44:55\",\"ssid\":\"Cafe\",\"security\":\"WPA2\"}"
        val other="{\"bssid\":\"00:11:22:33:44:66\",\"ssid\":\"Cafe\",\"security\":\"OPEN\"}"
        store.append(id,listOf(Observation(1,"wifi",latitude=1.0,longitude=2.0,data=same),
            Observation(2,"wifi",latitude=3.0,longitude=4.0,data=same),
            Observation(3,"wifi",latitude=5.0,longitude=6.0,data=other),Observation(4,"wifi",data=same)))
        val groups=store.groups(id,"wifi")
        assertEquals(2,groups.size)
        assertEquals(2L,groups.first().count)
        assertEquals(listOf(TrackPoint(1.0,2.0),TrackPoint(3.0,4.0)),store.groupPoints(id,"wifi",groups.first().key))
        assertEquals(3L,store.recordings().single().wifi)
        assertEquals(4,StringWriter().also { store.export(it,id) }.toString().split("\r\n").filter { it.isNotEmpty() }.size)
    }
    @Test fun removesLegacyRowsWithoutCoordinatesAndRepairsCountsAtomically() {
        store.close()
        context.deleteDatabase(name)
        val raw=net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),"test-secret",null,null)
        raw.execSQL("CREATE TABLE recordings (id INTEGER PRIMARY KEY,started INTEGER,ended INTEGER,bluetooth INTEGER,wifi INTEGER,distance REAL,last_saved INTEGER)")
        raw.execSQL("INSERT INTO recordings VALUES(1,1,10,1,2,0,10),(2,1,10,1,0,0,10)")
        raw.execSQL("CREATE TABLE recording_1 (id INTEGER PRIMARY KEY,time INTEGER,type TEXT,transport TEXT,latitude REAL,longitude REAL,data TEXT)")
        raw.execSQL("INSERT INTO recording_1 VALUES(1,1,'wifi','',NULL,NULL,'{}'),(2,2,'wifi','',0,0,'{}'),(3,3,'bluetooth','le',1,NULL,'{}')")
        raw.execSQL("CREATE TABLE recording_2 (id INTEGER PRIMARY KEY,time INTEGER,type TEXT,transport TEXT,latitude REAL,longitude REAL,data TEXT)")
        raw.execSQL("INSERT INTO recording_2 VALUES(1,1,'bluetooth','le',NULL,NULL,'{}')")
        raw.close()
        store=RecordingStore(context,name,"test-secret")
        assertEquals(2,store.recordings().size)
        assertEquals(1L,store.recordings().first { it.id==1L }.wifi)
        assertEquals(0L,store.recordings().first { it.id==1L }.bluetooth)
        assertEquals(0L,store.recordings().first { it.id==2L }.bluetooth)
        assertTrue(store.points(2).isEmpty())
        assertEquals(listOf(TrackPoint(0.0,0.0)),store.points(1))
        val check=net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),"test-secret",null,null)
        try {
            assertEquals(1,check.version)
            try { check.execSQL("INSERT INTO recording_1 VALUES(4,4,'wifi','',NULL,2,'{}')"); fail("Unlocated insert accepted") }
            catch(_: android.database.sqlite.SQLiteConstraintException) { }
        } finally { check.close() }
    }
    @Test fun bundledVendorDirectoryLoadsWithoutNetworkAccess() {
        val vendors=VendorDirectory.get(context)
        assertNotNull(vendors.lookup("00:1A:11:00:00:01"))
        assertNull(vendors.lookup("02:1A:11:00:00:01"))
    }
    @Test fun keystoreWrappedPasswordCanBeUnwrappedAfterReload() {
        assertEquals(DatabaseKey.password(context),DatabaseKey.password(context))
        val wrapped=context.getSharedPreferences("database_key",0).getString("wrapped",null)
        assertNotNull(wrapped)
        assertNotEquals(DatabaseKey.password(context),wrapped)
    }
}
