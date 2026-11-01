package com.example.wifibluetoothrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private var lastRecording: Recording? = null
    private var resumed = false
    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed) return
            render()
            worker.execute {
                try {
                    val latest = RecordingStore.get(this@MainActivity).recordings().firstOrNull()
                    runOnUiThread { lastRecording = latest; render() }
                } catch (e: Exception) { runOnUiThread { status.text = "Storage unavailable: ${e.message}" } }
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (canRecord()) begin() else status.text = "Precise location and Bluetooth permissions are required to record."
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) worker.execute {
            try {
                val output = contentResolver.openOutputStream(uri) ?: error("Cannot open destination")
                output.bufferedWriter(Charsets.UTF_8).use { RecordingStore.get(this).export(it) }
                runOnUiThread { android.widget.Toast.makeText(this, "Database exported", android.widget.Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { runOnUiThread { android.widget.Toast.makeText(this, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show() } }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fitSystemBars()
        status = findViewById(R.id.statusText)
        stats = findViewById(R.id.statsText)
        start = findViewById(R.id.startButton)
        stop = findViewById(R.id.stopButton)
        start.setOnClickListener {
            val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if(Build.VERSION.SDK_INT >= 31) needed += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            if(Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
            val missing = needed.filter { ContextCompat.checkSelfPermission(this,it) != PackageManager.PERMISSION_GRANTED }
            if(missing.isEmpty()) begin() else permissions.launch(missing.toTypedArray())
        }
        stop.setOnClickListener { stopService(Intent(this, RecordingService::class.java)); render() }
        findViewById<Button>(R.id.exportButton).setOnClickListener { export.launch("PocketDriver-database.csv") }
        findViewById<Button>(R.id.historyButton).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
    }
    private fun canRecord(): Boolean = ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < 31 || listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(this,it) == PackageManager.PERMISSION_GRANTED })
    private fun begin() {
        if (!canRecord()) return
        if (!androidx.core.location.LocationManagerCompat.isLocationEnabled(getSystemService(android.location.LocationManager::class.java))) {
            status.text = "Enable device location services before recording."; return
        }
        try { ContextCompat.startForegroundService(this,Intent(this,RecordingService::class.java)); start.isEnabled=false }
        catch(e: Exception) { status.text="Unable to start: ${e.message}" }
    }
    private fun render() {
        val running = RecordingService.isRunning
        val saving = !running && RecordingService.currentId != null
        start.isEnabled = !running && !saving
        stop.isEnabled = running
        status.text = RecordingService.error ?: if(running) "Recording" else if(saving) "Finishing recording…" else "Stopped"
        val r = lastRecording
        stats.text = if(r == null) "Elapsed: 00:00:00\nBluetooth records: 0\nWi-Fi networks scanned: 0\nDistance: 0 m" else {
            val seconds = r.elapsed(System.currentTimeMillis())
            String.format(Locale.getDefault(), "Elapsed: %02d:%02d:%02d\nBluetooth records: %d\nWi-Fi networks scanned: %d\nDistance: %.0f m",
                seconds/3600, seconds/60%60, seconds%60, r.bluetooth,r.wifi,r.distance)
        }
    }
    override fun onResume() { super.onResume(); resumed=true; handler.post(refresh) }
    override fun onPause() { resumed=false; handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
