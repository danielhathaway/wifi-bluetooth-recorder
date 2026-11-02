package com.example.wifibluetoothrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupMenu
import android.text.format.Formatter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val exports = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var elapsed: TextView
    private lateinit var wifi: TextView
    private lateinit var bluetooth: TextView
    private lateinit var distance: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private var lastRecording: Recording? = null
    private var resumed = false
    private var starting = false
    private var stopping = false
    private var issue: String? = null
    private var storageIssue: String? = null
    private var databaseBytes: Long? = null
    @Volatile private var erasing = false

    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed || erasing) return
            render()
            worker.execute {
                if(erasing) return@execute
                val size = DatabaseFiles.sizeBytes(getDatabasePath(DatabaseFiles.NAME))
                try {
                    val latest = RecordingStore.get(this@MainActivity).recordings().firstOrNull()
                    runOnUiThread {
                        if (!isDestroyed && !erasing) { lastRecording = latest; databaseBytes = size; storageIssue = null; render() }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        if (!isDestroyed && !erasing) { databaseBytes = size; storageIssue = getString(R.string.storage_error, e.message); render() }
                    }
                }
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (canRecord()) begin() else showIssue(getString(R.string.permissions_required))
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && !erasing) exports.execute {
            if(erasing) return@execute
            try {
                val output = contentResolver.openOutputStream(uri) ?: error("Cannot open destination")
                output.bufferedWriter(Charsets.UTF_8).use { RecordingStore.get(this).export(it) }
                runOnUiThread { Toast.makeText(this, R.string.export_success, Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, getString(R.string.export_error, e.message), Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fitSystemBars()
        UiFormat.centerContent(findViewById(R.id.mainContent))
        status = findViewById(R.id.statusText)
        elapsed = findViewById(R.id.elapsedValue)
        wifi = findViewById(R.id.wifiValue)
        bluetooth = findViewById(R.id.bluetoothValue)
        distance = findViewById(R.id.distanceValue)
        start = findViewById(R.id.startButton)
        stop = findViewById(R.id.stopButton)
        start.setOnClickListener { requestRecordingPermissions() }
        stop.setOnClickListener {
            stopping = true
            stopService(Intent(this, RecordingService::class.java))
            render()
        }
        findViewById<View>(R.id.historyButton).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<Button>(R.id.exportButton).setOnClickListener { export.launch("PocketDriver-database.csv") }
        findViewById<View>(R.id.manageStorageButton).setOnClickListener { anchor ->
            PopupMenu(this,anchor).apply {
                menu.add(0,R.id.action_erase_data,0,R.string.erase_app_data)
                setOnMenuItemClickListener { item ->
                    if(item.itemId == R.id.action_erase_data) { confirmErase(); true } else false
                }
                show()
            }
        }
        findViewById<View>(R.id.aboutButton).setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle(R.string.about_title).setMessage(R.string.about_body)
                .setPositiveButton(R.string.about_action, null).show()
        }
        render()
    }

    private fun confirmErase() {
        if(erasing) return
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.erase_title)
            .setMessage(R.string.erase_message).setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.erase_confirm) { _,_ -> eraseAppData() }.create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setTextColor(ContextCompat.getColor(this,R.color.app_error))
        }
        dialog.show()
    }

    private fun eraseAppData() {
        if(erasing) return
        erasing = true
        handler.removeCallbacks(refresh)
        render()
        // Android stops the whole app before clearing its data, including all open SQLite sidecars.
        // Do not delete files or keys piecemeal while service/read/export connections may be active.
        val accepted = try { AppDataReset.request(this) } catch (_: Exception) { false }
        if(accepted) finishAndRemoveTask() else {
            erasing = false
            showIssue(getString(R.string.erase_failed))
            if(resumed) handler.post(refresh)
        }
    }

    private fun requestRecordingPermissions() {
        if(erasing) return
        issue = null
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) needed += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) begin() else permissions.launch(missing.toTypedArray())
    }

    private fun canRecord(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < 31 || listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        })

    private fun begin() {
        if(erasing) return
        if (!canRecord()) { showIssue(getString(R.string.permissions_required)); return }
        if (!androidx.core.location.LocationManagerCompat.isLocationEnabled(getSystemService(android.location.LocationManager::class.java))) {
            showIssue(getString(R.string.location_required)); return
        }
        try {
            issue = null
            ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java))
            starting = true
            render()
        } catch (e: Exception) { showIssue(getString(R.string.start_error, e.message)) }
    }

    private fun showIssue(message: String) { issue = message; starting = false; render() }

    private fun render() {
        if (RecordingService.isRunning || RecordingService.error != null) starting = false
        if (!RecordingService.isRunning && RecordingService.currentId == null) stopping = false
        val running = RecordingService.isRunning && !stopping
        val saving = stopping || (!RecordingService.isRunning && RecordingService.currentId != null)
        val problem = issue ?: storageIssue ?: RecordingService.error
        start.isEnabled = !running && !saving && !starting && !erasing
        stop.isEnabled = running && !erasing
        listOf(R.id.historyButton,R.id.exportButton,R.id.manageStorageButton).forEach {
            findViewById<View>(it).isEnabled = !erasing
        }
        start.alpha = if (start.isEnabled) 1f else 0.5f
        start.visibility = if (running) View.GONE else View.VISIBLE
        stop.visibility = if (running) View.VISIBLE else View.GONE
        status.setText(when {
            erasing -> R.string.status_erasing
            saving -> R.string.status_saving
            running -> R.string.status_recording
            starting -> R.string.status_starting
            problem != null -> R.string.status_error
            else -> R.string.status_ready
        })
        findViewById<TextView>(R.id.errorText).apply {
            text = problem
            visibility = if (problem == null) View.GONE else View.VISIBLE
        }
        val r = lastRecording?.takeIf { !RecordingService.isRunning || it.id == RecordingService.currentId }
        findViewById<TextView>(R.id.sessionLabel).setText(when {
            running || starting || saving -> R.string.session_live
            r != null -> R.string.session_last
            else -> R.string.session_new
        })
        val seconds = r?.elapsed(System.currentTimeMillis()) ?: 0
        elapsed.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        wifi.text = UiFormat.number(r?.wifi ?: 0)
        bluetooth.text = UiFormat.number(r?.bluetooth ?: 0)
        distance.text = UiFormat.distance(this, r?.distance ?: 0.0)
        findViewById<TextView>(R.id.databaseSizeValue).apply {
            text = databaseBytes?.let { Formatter.formatShortFileSize(this@MainActivity,it) }
                ?: getString(R.string.database_size_loading)
            contentDescription = getString(R.string.database_size_accessibility,text)
        }
        elapsed.contentDescription = getString(R.string.elapsed_accessibility, elapsed.text)
        wifi.contentDescription = getString(R.string.wifi_accessibility, wifi.text)
        bluetooth.contentDescription = getString(R.string.bluetooth_accessibility, bluetooth.text)
        distance.contentDescription = getString(R.string.distance_accessibility, distance.text)
    }

    override fun onResume() { super.onResume(); resumed = true; handler.post(refresh) }
    override fun onPause() { resumed = false; handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { worker.shutdown(); exports.shutdown(); super.onDestroy() }
}
