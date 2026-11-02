package com.example.wifibluetoothrecorder

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class RecordingMapActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var map: MapView
    private var recordingId = 0L
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) worker.execute {
            try {
                val stream = contentResolver.openOutputStream(uri) ?: error("Cannot open destination")
                stream.bufferedWriter(Charsets.UTF_8).use { RecordingStore.get(this).export(it, recordingId) }
                runOnUiThread { Toast.makeText(this, R.string.export_success, Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, getString(R.string.export_error, e.message), Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordingId = savedInstanceState?.getLong("recording_id") ?: intent.getLongExtra("recording_id", 0)
        if (recordingId <= 0) { finish(); return }
        Configuration.getInstance().apply {
            userAgentValue = "PocketDriver/1.0 ($packageName)"
            osmdroidBasePath = java.io.File(cacheDir, "maps")
            osmdroidTileCache = java.io.File(cacheDir, "maps/tiles")
        }
        setContentView(R.layout.activity_recording_map)
        fitSystemBars()
        findViewById<View>(R.id.mapFrame).clipToOutline = true
        findViewById<MaterialToolbar>(R.id.mapToolbar).apply {
            title = getString(R.string.recording_title, recordingId)
            setNavigationOnClickListener { finish() }
            menu.add(R.string.map_privacy).apply {
                setIcon(R.drawable.ic_info)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setOnMenuItemClickListener {
                    MaterialAlertDialogBuilder(this@RecordingMapActivity).setTitle(R.string.about_title)
                        .setMessage(R.string.about_body).setPositiveButton(R.string.about_action, null).show()
                    true
                }
            }
        }
        findViewById<Button>(R.id.exportRecordingButton).setOnClickListener {
            export.launch("PocketDriver-recording-$recordingId.csv")
        }
        findViewById<View>(R.id.mapAttribution).setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) }
            catch (_: android.content.ActivityNotFoundException) { }
        }
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(2.0)
        }
        findViewById<FrameLayout>(R.id.mapHost).addView(map, FrameLayout.LayoutParams(-1, -1))
        findViewById<View>(R.id.zoomInButton).setOnClickListener { map.controller.zoomIn() }
        findViewById<View>(R.id.zoomOutButton).setOnClickListener { map.controller.zoomOut() }
        loadRecording()
    }

    private fun loadRecording() {
        worker.execute {
            try {
                val database = RecordingStore.get(this)
                val recording = database.recordings().first { it.id == recordingId }
                val points = database.points(recordingId).map { GeoPoint(it.latitude, it.longitude) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    findViewById<View>(R.id.mapProgress).visibility = View.GONE
                    findViewById<MaterialToolbar>(R.id.mapToolbar).subtitle =
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(recording.started))
                    findViewById<TextView>(R.id.mapWifiValue).apply {
                        text = UiFormat.number(recording.wifi)
                        contentDescription = getString(R.string.wifi_accessibility, text)
                    }
                    findViewById<TextView>(R.id.mapBluetoothValue).apply {
                        text = UiFormat.number(recording.bluetooth)
                        contentDescription = getString(R.string.bluetooth_accessibility, text)
                    }
                    findViewById<TextView>(R.id.mapDistanceValue).apply {
                        text = UiFormat.distance(this@RecordingMapActivity, recording.distance)
                        contentDescription = getString(R.string.distance_accessibility, text)
                    }
                    findViewById<TextView>(R.id.mapCountText).text = getString(R.string.map_points, UiFormat.number(points.size.toLong()))
                    findViewById<Button>(R.id.exportRecordingButton).isEnabled = true
                    if (points.isEmpty()) {
                        findViewById<View>(R.id.mapEmptyState).visibility = View.VISIBLE
                    } else showPoints(points)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    findViewById<View>(R.id.mapProgress).visibility = View.GONE
                    findViewById<View>(R.id.mapCountText).visibility = View.GONE
                    findViewById<View>(R.id.mapEmptyState).visibility = View.VISIBLE
                    findViewById<TextView>(R.id.mapEmptyTitle).setText(R.string.error_title)
                    findViewById<TextView>(R.id.mapEmptyBody).text = getString(R.string.map_error, e.message)
                }
            }
        }
    }

    private fun showPoints(points: List<GeoPoint>) {
        val markerColor = ContextCompat.getColor(this, R.color.app_primary)
        val outlineColor = ContextCompat.getColor(this, R.color.app_surface)
        val density = resources.displayMetrics.density
        map.overlays.add(object : Overlay() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = markerColor }
            private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = outlineColor }
            override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
                if (shadow) return
                val pixel = android.graphics.Point()
                points.forEach {
                    mapView.projection.toPixels(it, pixel)
                    canvas.drawCircle(pixel.x.toFloat(), pixel.y.toFloat(), 5f * density, outline)
                    canvas.drawCircle(pixel.x.toFloat(), pixel.y.toFloat(), 3.5f * density, paint)
                }
            }
        })
        map.post {
            if (isDestroyed) return@post
            if (points.size == 1) { map.controller.setZoom(17.0); map.controller.setCenter(points.first()) }
            else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, (48 * density).toInt(), 18.0, null)
            map.invalidate()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putLong("recording_id", recordingId); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); if (::map.isInitialized) map.onResume() }
    override fun onPause() { if (::map.isInitialized) map.onPause(); super.onPause() }
    override fun onDestroy() { if (::map.isInitialized) map.onDetach(); worker.shutdown(); super.onDestroy() }
}
