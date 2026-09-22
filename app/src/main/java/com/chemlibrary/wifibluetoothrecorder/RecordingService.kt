package com.example.wifibluetoothrecorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult as BleScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.wifi.ScanResult as WifiScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class RecordingService : Service() {

    companion object {
        @Volatile
        var isRunning: Boolean = false

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
        private const val INTERVAL_SECONDS = 5L
        const val ACTION_BLE_UPDATE = "com.example.wifibluetoothrecorder.BLE_UPDATE"
        const val EXTRA_BLE_DEVICES = "ble_devices"

        fun getOutputFile(context: Context): File {
            val candidates = mutableListOf<File>()

            // 1. Documents directory in shared storage (/storage/emulated/0/Documents/WifiBluetoothRecorder)
            try {
                val docsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "WifiBluetoothRecorder"
                )
                candidates += docsDir
            } catch (_: Exception) {
            }

            // 2. Download directory in shared storage (/storage/emulated/0/Download/WifiBluetoothRecorder)
            try {
                val downloadDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "WifiBluetoothRecorder"
                )
                candidates += downloadDir
            } catch (_: Exception) {
            }

            // 3. Direct folder in root external storage (/storage/emulated/0/WifiBluetoothRecorder)
            try {
                @Suppress("DEPRECATION")
                val rootDir = File(Environment.getExternalStorageDirectory(), "WifiBluetoothRecorder")
                candidates += rootDir
            } catch (_: Exception) {
            }

            for (dir in candidates) {
                try {
                    if (!dir.exists()) {
                        dir.mkdirs()
                    }
                    if (dir.exists() && dir.canWrite()) {
                        val file = File(dir, "network-records.jsonl")
                        if (!file.exists()) {
                            file.createNewFile()
                        }
                        if (file.canWrite()) {
                            return file
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // Fallback: app-specific external files directory
            val fallbackDir = context.getExternalFilesDir(null) ?: context.filesDir
            val fallbackFile = File(fallbackDir, "network-records.jsonl")
            fallbackFile.parentFile?.mkdirs()
            return fallbackFile
        }
    }

    private val latestBle = linkedMapOf<String, BleDeviceState>()
    private data class BleDeviceState(
        val name: String,
        val address: String,
        var rssi: Int,
        var lastSeen: Long
    )

    private lateinit var wifiManager: WifiManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var latestLocation: Location? = null
    private var scheduler: ScheduledExecutorService? = null
    private lateinit var outputFile: File

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_FOUND) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            recordBluetooth(device)
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                try {
                    if (hasLocationPermission()) {
                        @Suppress("DEPRECATION")
                        val results = wifiManager.scanResults ?: emptyList()
                        recordWifiResults(results)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: BleScanResult) {
            recordBleAdvertisement(result)
        }

        override fun onBatchScanResults(results: MutableList<BleScanResult>) {
            results.forEach { recordBleAdvertisement(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            // Android may throttle or reject BLE scans. Other recording continues.
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            latestLocation = result.lastLocation
        }
    }

    override fun onCreate() {
        super.onCreate()

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        createNotificationChannel()
        outputFile = getOutputFile(this)

        ContextCompat.registerReceiver(
            this,
            bluetoothReceiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND),
            ContextCompat.RECEIVER_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            wifiReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true

        startLocationUpdates()
        startPeriodicScans()

        return START_NOT_STICKY
    }

    private fun startPeriodicScans() {
        if (scheduler != null) return

        scheduler = Executors.newSingleThreadScheduledExecutor().also { executor ->
            executor.scheduleAtFixedRate(
                { performScanCycle() },
                0,
                INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun performScanCycle() {
        if (!isRunning) return

        // Wi-Fi scans can be throttled by Android. The latest available results
        // are recorded every cycle, and a new scan is requested where possible.
        try {
            if (hasLocationPermission()) {
                @Suppress("DEPRECATION")
                wifiManager.startScan()
                @Suppress("DEPRECATION")
                val results = wifiManager.scanResults ?: emptyList()
                recordWifiResults(results)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Classic Bluetooth discovery is comparatively expensive and may be
        // throttled by the OS/device. We request it for each cycle when possible.
        try {
            val adapter = bluetoothAdapter
            if (adapter != null && hasBluetoothScanPermission() && adapter.isEnabled) {
                if (adapter.isDiscovering) adapter.cancelDiscovery()
                adapter.startDiscovery()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Scan for BLE advertisements for most of each five-second cycle.
        try {
            val adapter = bluetoothAdapter
            if (adapter != null && hasBluetoothScanPermission() && adapter.isEnabled) {
                bleScanner = adapter.bluetoothLeScanner
                bleScanner?.startScan(bleScanCallback)
                scheduler?.schedule({
                    try {
                        bleScanner?.stopScan(bleScanCallback)
                    } catch (_: SecurityException) {
                    }
                }, 4, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordWifiResults(results: List<WifiScanResult>) {
        val location = latestLocation

        for (result in results) {
            val security = wifiSecurity(result)
            val channel = frequencyToChannel(result.frequency)
            val mode = "unknown"
            @Suppress("DEPRECATION")
            val rate = result.channelWidth.toString()

            appendJson(
                """
                {"type":"wifi","location":"${locationString(location)}","time":"${Instant.now()}","data":{"bssid":"${jsonEscape(result.BSSID ?: "")}","ssid":"${jsonEscape(result.SSID ?: "")}","mode":"$mode","chan":$channel,"rate":"${jsonEscape(rate)}","signal":${result.level},"security":"${jsonEscape(security)}"}}
                """.trimIndent()
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordBleAdvertisement(result: BleScanResult) {
        val location = latestLocation
        val record = result.scanRecord
        val name = record?.deviceName ?: try { result.device?.name ?: "" } catch (_: SecurityException) { "" }
        val address = try { result.device?.address ?: "" } catch (_: SecurityException) { "" }
        val txPower = if (result.txPower == Int.MIN_VALUE) null else result.txPower

        val manufacturers = mutableListOf<String>()
        record?.manufacturerSpecificData?.let { data ->
            for (i in 0 until data.size()) {
                manufacturers += "\"${data.keyAt(i)}\":\"${data.valueAt(i).toHex()}\""
            }
        }

        val serviceUuids = record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()

        val serviceData = mutableListOf<String>()
        record?.serviceData?.let { data ->
            for ((uuid, bytes) in data) {
                serviceData += "\"${uuid.uuid}\":\"${bytes.toHex()}\""
            }
        }

        val json = """{"type":"bluetooth_le","location":"${locationString(location)}","time":"${Instant.now()}","data":{"name":"${jsonEscape(name)}","mac":"${jsonEscape(address)}","rssi":${result.rssi},"tx_power":${txPower ?: "null"},"manufacturer_data":{${manufacturers.joinToString(",")}},"service_uuids":[${serviceUuids.joinToString(",") { "\"${jsonEscape(it)}\"" }}],"service_data":{${serviceData.joinToString(",")}}}}"""
        appendJson(json)
        updateBleUi(name, address, result.rssi)
    }

    private fun updateBleUi(name: String, address: String, rssi: Int) {
        val key = if (address.isBlank()) name else address
        if (key.isBlank()) return

        synchronized(latestBle) {
            latestBle[key] = BleDeviceState(
                name = name.ifBlank { "(unnamed BLE device)" },
                address = address.ifBlank { "address unavailable" },
                rssi = rssi,
                lastSeen = System.currentTimeMillis()
            )

            // Keep the live list bounded.
            while (latestBle.size > 100) {
                latestBle.remove(latestBle.keys.first())
            }

            val text = latestBle.values
                .sortedByDescending { it.rssi }
                .joinToString("\n") {
                    "${it.name}  |  ${it.address}  |  ${it.rssi} dBm"
                }

            sendBroadcast(Intent(ACTION_BLE_UPDATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_BLE_DEVICES, text)
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordBluetooth(device: BluetoothDevice) {
        if (!hasBluetoothConnectPermission()) return
        val location = latestLocation

        val name = try { device.name ?: "" } catch (_: SecurityException) { "" }
        val address = try { device.address ?: "" } catch (_: SecurityException) { "" }
        val clazz = try { device.bluetoothClass?.deviceClass?.toString() ?: "" } catch (_: SecurityException) { "" }

        appendJson(
            """
            {"type":"bluetooth","location":"${locationString(location)}","time":"${Instant.now()}","data":{"name":"${jsonEscape(name)}","mac":"${jsonEscape(address)}","class":"${jsonEscape(clazz)}"}}
            """.trimIndent()
        )
    }

    private fun appendJson(line: String) {
        synchronized(outputFile) {
            try {
                outputFile.parentFile?.mkdirs()
                outputFile.appendText(line + "\n", Charsets.UTF_8)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return

        try {
            fusedLocation.lastLocation.addOnSuccessListener { location ->
                if (location != null && latestLocation == null) {
                    latestLocation = location
                }
            }
        } catch (_: SecurityException) {
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5_000L
        ).setMinUpdateIntervalMillis(2_000L).build()

        try {
            fusedLocation.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
        }
    }

    private fun stopLocationUpdates() {
        fusedLocation.removeLocationUpdates(locationCallback)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothScanPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    private fun wifiSecurity(result: WifiScanResult): String {
        val caps = result.capabilities?.uppercase(Locale.US) ?: ""
        return when {
            "WPA3" in caps -> "WPA3"
            "WPA2" in caps -> "WPA2"
            "WPA" in caps -> "WPA"
            "WEP" in caps -> "WEP"
            else -> "OPEN"
        }
    }

    private fun frequencyToChannel(frequency: Int): Int {
        return when {
            frequency in 2412..2484 -> if (frequency == 2484) 14 else (frequency - 2407) / 5
            frequency in 5000..5895 -> (frequency - 5000) / 5
            frequency in 5955..7115 -> (frequency - 5950) / 5
            else -> 0
        }
    }

    private fun locationString(location: Location?): String =
        if (location != null) {
            String.format(Locale.US, "%.7f,%.7f", location.latitude, location.longitude)
        } else {
            ""
        }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Network recording",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Network recording active")
            .setContentText("Scanning Wi-Fi and Bluetooth approximately every 5 seconds")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        scheduler?.shutdownNow()
        scheduler = null

        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (_: Exception) {
        }

        try {
            bleScanner?.stopScan(bleScanCallback)
        } catch (_: Exception) {
        }

        stopLocationUpdates()

        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(wifiReceiver)
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
