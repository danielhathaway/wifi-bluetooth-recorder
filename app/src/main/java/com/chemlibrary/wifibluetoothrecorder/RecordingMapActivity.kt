package com.example.wifibluetoothrecorder

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.CopyrightOverlay
import java.util.concurrent.Executors

class RecordingMapActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var map: MapView
    private lateinit var description: TextView
    private var recordingId = 0L
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if(uri != null) worker.execute {
            try {
                val stream = contentResolver.openOutputStream(uri) ?: error("Cannot open destination")
                stream.bufferedWriter(Charsets.UTF_8).use { RecordingStore.get(this).export(it,recordingId) }
                runOnUiThread { Toast.makeText(this,"Recording exported",Toast.LENGTH_LONG).show() }
            } catch(e: Exception) { runOnUiThread { Toast.makeText(this,"Export failed: ${e.message}",Toast.LENGTH_LONG).show() } }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordingId=savedInstanceState?.getLong("recording_id") ?: intent.getLongExtra("recording_id",0)
        if(recordingId <= 0) { finish(); return }
        Configuration.getInstance().apply {
            userAgentValue="PocketDriver/1.0 (${packageName})"
            osmdroidBasePath=java.io.File(cacheDir,"maps")
            osmdroidTileCache=java.io.File(cacheDir,"maps/tiles")
        }
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(16,16,16,16) }
        setContentView(layout)
        fitSystemBars()
        layout.addView(Button(this).apply { text="Back to recordings"; setOnClickListener { finish() } })
        description=TextView(this).apply { text="Loading recording $recordingId…"; textSize=18f }
        layout.addView(description)
        layout.addView(Button(this).apply { text="Export this recording as CSV"; setOnClickListener { export.launch("PocketDriver-recording-$recordingId.csv") } })
        layout.addView(TextView(this).apply { text="Map tiles require internet. Recorded points remain visible offline. Map requests go to OpenStreetMap." })
        map=MapView(this).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(2.0) }
        layout.addView(map,LinearLayout.LayoutParams(-1,0,1f))
        map.overlays.add(CopyrightOverlay(this))
        worker.execute {
            try {
                val database=RecordingStore.get(this)
                val recording=database.recordings().first { it.id==recordingId }
                val points=database.points(recordingId).map { GeoPoint(it.latitude,it.longitude) }
                runOnUiThread {
                    description.text="Recording $recordingId · ${recording.bluetooth} Bluetooth · ${recording.wifi} Wi-Fi\n${recording.distance.toInt()} m · ${points.size} mapped locations" + if(points.isEmpty()) "\nNo location fixes were available." else ""
                    if(points.isNotEmpty()) {
                        map.overlays.add(object : Overlay() {
                            private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(0,133,125) }
                            override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
                                if(shadow) return
                                val pixel=android.graphics.Point()
                                points.forEach { mapView.projection.toPixels(it,pixel); canvas.drawCircle(pixel.x.toFloat(),pixel.y.toFloat(),5f*resources.displayMetrics.density,paint) }
                            }
                        })
                        map.post {
                            if(points.size==1) { map.controller.setZoom(17.0); map.controller.setCenter(points.first()) }
                            else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points),false,48,18.0,null)
                            map.invalidate()
                        }
                    }
                }
            } catch(e: Exception) { runOnUiThread { description.text="Unable to load recording: ${e.message}" } }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putLong("recording_id",recordingId); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); if(::map.isInitialized) map.onResume() }
    override fun onPause() { if(::map.isInitialized) map.onPause(); super.onPause() }
    override fun onDestroy() { if(::map.isInitialized) map.onDetach(); worker.shutdown(); super.onDestroy() }
}
