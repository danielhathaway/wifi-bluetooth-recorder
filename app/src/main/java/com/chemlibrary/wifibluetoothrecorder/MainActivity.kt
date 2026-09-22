package com.example.wifibluetoothrecorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var bleDevicesText: TextView
    private lateinit var bleScrollView: NestedScrollView
    private lateinit var fileText: TextView
    private lateinit var exportButton: Button

    private val bleReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action != RecordingService.ACTION_BLE_UPDATE) return
            val devices = intent.getStringExtra(RecordingService.EXTRA_BLE_DEVICES).orEmpty()
            bleDevicesText.text = if (devices.isBlank()) {
                "No BLE advertisements seen yet."
            } else {
                devices
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val locationGranted =
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            val bluetoothGranted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                result[Manifest.permission.BLUETOOTH_SCAN] == true

            if (locationGranted && bluetoothGranted) {
                startRecordingService()
            } else {
                statusText.text = "Required permissions were not granted."
            }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            if (uri != null) {
                try {
                    val srcFile = RecordingService.getOutputFile(this)
                    if (srcFile.exists() && srcFile.length() > 0) {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            srcFile.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                        Toast.makeText(this, "Recording exported successfully!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "No recording data found to export yet.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        bleDevicesText = findViewById(R.id.bleDevicesText)
        bleScrollView = findViewById(R.id.bleScrollView)
        fileText = findViewById(R.id.fileText)
        exportButton = findViewById(R.id.exportButton)

        // Ensure scrolling inside the BLE list container doesn't get intercepted by the outer ScrollView
        bleScrollView.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        updateFilePathDisplay()

        startButton.setOnClickListener { requestPermissionsAndStart() }
        stopButton.setOnClickListener { stopRecordingService() }
        exportButton.setOnClickListener {
            exportLauncher.launch("network-records.jsonl")
        }

        updateButtons(RecordingService.isRunning)
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter(RecordingService.ACTION_BLE_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bleReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(bleReceiver, filter)
        }
    }

    override fun onStop() {
        try {
            unregisterReceiver(bleReceiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateFilePathDisplay()
        updateButtons(RecordingService.isRunning)
    }

    private fun updateFilePathDisplay() {
        val file = RecordingService.getOutputFile(this)
        fileText.text = "Record file:\n${file.absolutePath}"
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startRecordingService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startRecordingService() {
        val intent = Intent(this, RecordingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        updateButtons(true)
        updateFilePathDisplay()
    }

    private fun stopRecordingService() {
        stopService(Intent(this, RecordingService::class.java))
        updateButtons(false)
        updateFilePathDisplay()
    }

    private fun updateButtons(running: Boolean) {
        statusText.text = if (running) "Recording..." else "Stopped"
        startButton.isEnabled = !running
        stopButton.isEnabled = running
    }
}
