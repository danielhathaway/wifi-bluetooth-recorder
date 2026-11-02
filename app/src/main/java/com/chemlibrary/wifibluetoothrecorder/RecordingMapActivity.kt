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
import com.google.android.material.tabs.TabLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private var selectedTab = 0
    private var listVersion = 0
    private var resumed = false
    private var groupType: String? = null
    private var groupKey: String? = null
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
        groupType = intent.getStringExtra("group_type")
        groupKey = intent.getStringExtra("group_key")
        selectedTab = savedInstanceState?.getInt("tab_position", 0) ?: 0
        Configuration.getInstance().apply {
            userAgentValue = "PocketDriver/1.0 ($packageName)"
            osmdroidBasePath = java.io.File(cacheDir, "maps")
            osmdroidTileCache = java.io.File(cacheDir, "maps/tiles")
        }
        setContentView(R.layout.activity_recording_map)
        fitSystemBars()
        findViewById<View>(R.id.mapFrame).clipToOutline = true
        findViewById<MaterialToolbar>(R.id.mapToolbar).apply {
            title = intent.getStringExtra("group_title") ?: getString(R.string.recording_title, recordingId)
            menu.add(R.string.export_recording).apply {
                setIcon(R.drawable.ic_export)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setOnMenuItemClickListener { export.launch("PocketDriver-recording-$recordingId.csv"); true }
            }
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
        configureTabs()
        loadRecording()
    }

    private fun configureTabs() {
        findViewById<RecyclerView>(R.id.recordsList).apply {
            layoutManager = LinearLayoutManager(this@RecordingMapActivity)
            itemAnimator = null
        }
        val tabs = findViewById<TabLayout>(R.id.recordingTabs)
        if (groupType != null && groupKey != null) {
            tabs.visibility = View.GONE
            findViewById<View>(R.id.mapSummary).visibility = View.GONE
            selectedTab = 0
            return
        }
        listOf(R.string.tab_map,R.string.tab_wifi,R.string.tab_bluetooth,R.string.tab_gps).forEach {
            tabs.addTab(tabs.newTab().setText(it))
        }
        selectedTab = selectedTab.coerceIn(0,3)
        tabs.selectTab(tabs.getTabAt(selectedTab))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { showTab(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) { }
            override fun onTabReselected(tab: TabLayout.Tab) { if(tab.position != 0) showTab(tab.position) }
        })
        showTab(selectedTab)
    }

    private fun showTab(position: Int) {
        selectedTab = position
        findViewById<View>(R.id.mapPanel).visibility = if(position == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.listPanel).visibility = if(position == 0) View.GONE else View.VISIBLE
        listVersion++
        if(position == 0) { if(resumed) map.onResume(); return }
        map.onPause()
        val type = listOf("", "wifi", "bluetooth", "location")[position]
        val version = listVersion
        findViewById<View>(R.id.groupsProgress).visibility = View.VISIBLE
        findViewById<View>(R.id.groupsEmpty).visibility = View.GONE
        findViewById<View>(R.id.recordsList).visibility = View.GONE
        findViewById<TextView>(R.id.groupsSummary).setText(R.string.records_loading)
        findViewById<View>(R.id.vendorHint).visibility = if(type == "bluetooth") View.VISIBLE else View.GONE
        worker.execute {
            try {
                val groups = RecordingStore.get(this).groups(recordingId,type)
                val vendors = if(type == "bluetooth") VendorDirectory.get(this) else null
                runOnUiThread {
                    if(isDestroyed || version != listVersion) return@runOnUiThread
                    findViewById<View>(R.id.groupsProgress).visibility = View.GONE
                    findViewById<TextView>(R.id.groupsSummary).text = getString(R.string.groups_summary,
                        UiFormat.number(groups.size.toLong()),UiFormat.number(groups.sumOf { it.count }))
                    findViewById<RecyclerView>(R.id.recordsList).apply {
                        adapter = SignalGroupAdapter(groups,vendors) { group,name,detail ->
                            startActivity(Intent(this@RecordingMapActivity,RecordingMapActivity::class.java)
                                .putExtra("recording_id",recordingId).putExtra("group_type",group.type)
                                .putExtra("group_key",group.key).putExtra("group_title",name).putExtra("group_detail",detail))
                        }
                        visibility = if(groups.isEmpty()) View.GONE else View.VISIBLE
                    }
                    findViewById<TextView>(R.id.groupsEmpty).apply {
                        setText(R.string.no_groups)
                        visibility = if(groups.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            } catch(e: Exception) {
                runOnUiThread {
                    if(isDestroyed || version != listVersion) return@runOnUiThread
                    findViewById<View>(R.id.groupsProgress).visibility = View.GONE
                    findViewById<TextView>(R.id.groupsSummary).setText(R.string.error_title)
                    findViewById<TextView>(R.id.groupsEmpty).apply {
                        text = getString(R.string.groups_error,e.message)
                        visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun loadRecording() {
        worker.execute {
            try {
                val database = RecordingStore.get(this)
                val recording = database.recordings().first { it.id == recordingId }
                val selectedType = groupType
                val selectedKey = groupKey
                val points = (if(selectedType != null && selectedKey != null) database.groupPoints(recordingId,selectedType,selectedKey)
                    else database.points(recordingId)).map { GeoPoint(it.latitude,it.longitude) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    findViewById<View>(R.id.mapProgress).visibility = View.GONE
                    findViewById<MaterialToolbar>(R.id.mapToolbar).subtitle =
                        intent.getStringExtra("group_detail") ?: DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(recording.started))
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

    override fun onSaveInstanceState(outState: Bundle) { outState.putLong("recording_id", recordingId); outState.putInt("tab_position",selectedTab); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); resumed = true; if (::map.isInitialized && selectedTab == 0) map.onResume() }
    override fun onPause() { resumed = false; if (::map.isInitialized) map.onPause(); super.onPause() }
    override fun onDestroy() { listVersion++; if (::map.isInitialized) map.onDetach(); worker.shutdown(); super.onDestroy() }
}
