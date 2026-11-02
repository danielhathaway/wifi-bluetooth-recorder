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
import android.net.wifi.ScanResult as WifiScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
        @Volatile var currentId: Long? = null
        @Volatile var error: String? = null
    }

    private val writer = Executors.newSingleThreadScheduledExecutor()
    private val signalBuffer = SignalLocationBuffer(MeasurementClock(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis()))
    private var acceptingWrites = true
    @Volatile private var active = false
    private var store: RecordingStore? = null
    private var recordingId: Long? = null // Accessed only on writer.
    private var distance = 0.0
    private var previousLocation: TimedLocation? = null
    @Volatile private var bleStarted = false
    private val seenWifi = WifiObservationCache()
    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    private fun write(block: (RecordingStore, Long) -> Unit) {
        synchronized(writer) {
            if (!active || !acceptingWrites) return
            writer.execute {
                val id = recordingId ?: return@execute
                try { block(store ?: return@execute, id) }
                catch (e: Exception) { fail("Unable to save recording: ${e.message}") }
            }
        }
    }
    private fun fail(message: String) {
        error = message
        mainHandler.post { stopSelf() }
    }

    private lateinit var wifiManager: WifiManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var scheduler: ScheduledExecutorService? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                    bleStarted = false
                    bleScanner = null
                }
                return
            }
            if (intent.action != BluetoothDevice.ACTION_FOUND) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            recordBluetooth(device, intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).let { if (it == Short.MIN_VALUE) null else it.toInt() })
        }
    }

    @SuppressLint("MissingPermission") // Checked at runtime; revocations are also caught below.
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
            try { recordBleAdvertisement(result) } catch (_: SecurityException) { }
        }

        override fun onBatchScanResults(results: MutableList<BleScanResult>) {
            results.forEach { try { recordBleAdvertisement(it) } catch (_: SecurityException) { } }
        }

        override fun onScanFailed(errorCode: Int) {
            // Retry on a later cycle if Android rejected the scan. Other recording continues.
            bleStarted = false
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!active) return
            val locations = result.locations.filter { it.hasAccuracy() }.map { location ->
                TimedLocation(location.elapsedRealtimeNanos, location.time, location.latitude,
                    location.longitude, location.accuracy.toDouble())
            }.sortedBy { it.elapsedNanos }
            write { database, id ->
                val rows = mutableListOf<Observation>()
                locations.forEach { location ->
                    if (!signalBuffer.addLocation(location)) return@forEach
                    val old = previousLocation
                    if (old == null || location.elapsedNanos > old.elapsedNanos) {
                        if (old != null) distance += RecordFormat.distance(
                            TrackPoint(old.latitude, old.longitude), TrackPoint(location.latitude, location.longitude))
                        previousLocation = location
                    }
                    rows += Observation(location.timeMillis, "location", latitude=location.latitude, longitude=location.longitude,
                        data=org.json.JSONObject().put("accuracy_m", location.accuracyMeters)
                            .put("elapsed_realtime_ns", location.elapsedNanos).toString())
                }
                if (rows.isNotEmpty()) database.append(id, rows, distance)
                flushSignals(database, id)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()

        createNotificationChannel()

        ContextCompat.registerReceiver(
            this,
            bluetoothReceiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND).apply { addAction(BluetoothAdapter.ACTION_STATE_CHANGED) },
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
        if (isRunning) return START_NOT_STICKY
        error = null
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
        active = true

        writer.execute {
            try {
                store = RecordingStore.get(this)
                recordingId = store!!.start()
                currentId = recordingId
                synchronized(writer) {
                    if (acceptingWrites) writer.scheduleWithFixedDelay({
                        try { recordingId?.let { id -> store?.let { flushSignals(it, id) } } }
                        catch (e: Exception) { fail("Unable to save recording: ${e.message}") }
                    }, 1, 1, TimeUnit.SECONDS)
                }
                mainHandler.post {
                    if (active) { startLocationUpdates(); startPeriodicScans() }
                }
            } catch (e: Exception) { fail("Cannot open encrypted database: ${e.message}") }
        }

        return START_NOT_STICKY
    }

    private fun startPeriodicScans() {
        if (scheduler != null) return

        scheduler = Executors.newSingleThreadScheduledExecutor().also { executor ->
            executor.scheduleWithFixedDelay(
                { performScanCycle() },
                0,
                INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun performScanCycle() {
        if (!active) return

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
                if (!adapter.isDiscovering) adapter.startDiscovery()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Keep one BLE scan active: repeated stop/start cycles trigger Android throttling.
        try {
            val adapter = bluetoothAdapter
            if (!bleStarted && adapter != null && hasBluetoothScanPermission() && adapter.isEnabled) {
                bleScanner = adapter.bluetoothLeScanner
                bleScanner?.let { it.startScan(bleScanCallback); bleStarted = true }
            }
        } catch (_: SecurityException) { }
    }

    private fun signal(type: String, transport: String = "", data: org.json.JSONObject,
        measuredNanos: Long?, source: String): PendingSignal = PendingSignal(
        type, transport, data.toString(), measuredNanos, SystemClock.elapsedRealtimeNanos(),
        System.currentTimeMillis(), source)

    private fun enqueueSignals(signals: List<PendingSignal>) {
        if (signals.isEmpty()) return
        write { database, id ->
            signals.forEach { signalBuffer.add(it) }
            flushSignals(database, id)
        }
    }

    private fun flushSignals(database: RecordingStore, id: Long, force: Boolean = false) {
        val rows = signalBuffer.drain(SystemClock.elapsedRealtimeNanos(), force).map { located ->
            located.toObservation()
        }
        val located = rows.filter { it.hasCoordinates() }
        if (located.isNotEmpty()) database.append(id, located)
    }

    @SuppressLint("MissingPermission")
    private fun recordWifiResults(results: List<WifiScanResult>) {
        // Android often broadcasts cached results as well as returning them during the polling cycle.
        // Persist a BSSID/timestamp pair only once per recording.
        val rows = synchronized(seenWifi) {
            results.filter { result ->
                seenWifi.accept(result.BSSID ?: "", result.timestamp)
            }.map { result ->
                @Suppress("DEPRECATION")
                signal("wifi", data=org.json.JSONObject().put("bssid", result.BSSID).put("ssid", result.SSID)
                    .put("scan_timestamp_us", result.timestamp)
                    .put("mode", "unknown").put("chan", RadioFields.frequencyToChannel(result.frequency))
                    .put("channel_width", result.channelWidth).put("signal", result.level).put("security", RadioFields.security(result.capabilities ?: "")),
                    measuredNanos=MeasurementClock.wifiNanos(result.timestamp), source="wifi_scan")
            }
        }
        enqueueSignals(rows)
    }

    @SuppressLint("MissingPermission")
    private fun recordBleAdvertisement(result: BleScanResult) {
        val record = result.scanRecord
        val manufacturers = org.json.JSONObject()
        record?.manufacturerSpecificData?.let { data ->
            for (i in 0 until data.size()) manufacturers.put(data.keyAt(i).toString(), data.valueAt(i).toHex())
        }
        val services = org.json.JSONObject()
        record?.serviceData?.forEach { (uuid, bytes) -> services.put(uuid.uuid.toString(), bytes.toHex()) }
        val data = org.json.JSONObject().put("name", record?.deviceName ?: safeName(result.device))
            .put("mac", result.device.address).put("rssi", result.rssi)
            .put("tx_power", if(result.txPower == Int.MIN_VALUE) org.json.JSONObject.NULL else result.txPower)
            .put("manufacturer_data", manufacturers)
            .put("service_uuids", org.json.JSONArray(record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList<String>()))
            .put("service_data", services)
        data.put("scan_timestamp_ns", result.timestampNanos)
        enqueueSignals(listOf(signal("bluetooth", "le", data, result.timestampNanos, "ble_scan")))
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String = try { device.name ?: "" } catch (_: SecurityException) { "" }

    @SuppressLint("MissingPermission")
    private fun recordBluetooth(device: BluetoothDevice, rssi: Int?) {
        if (!hasBluetoothConnectPermission()) return
        val data = org.json.JSONObject().put("name", safeName(device)).put("mac", device.address)
            .put("class", device.bluetoothClass?.deviceClass ?: org.json.JSONObject.NULL)
            .put("rssi", rssi ?: org.json.JSONObject.NULL)
        // ACTION_FOUND has no hardware measurement timestamp. Do not invent a measurement location.
        enqueueSignals(listOf(signal("bluetooth", "classic", data, null, "unavailable")))
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1_000L
        ).setMinUpdateIntervalMillis(500L).build()

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

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

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
            .setContentTitle("PocketDriver recording")
            .setContentText("Scanning Wi-Fi and Bluetooth approximately every 5 seconds")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    @SuppressLint("MissingPermission") // Radio shutdown is best-effort after permission revocation.
    override fun onDestroy() {
        active = false
        isRunning = false
        // Drain accepted writes before closing the session. No whole-recording final save.
        synchronized(writer) {
            acceptingWrites = false
            writer.execute {
                try { recordingId?.let { id -> store?.let { database ->
                    flushSignals(database, id, force=true)
                    database.finish(id)
                } } }
                catch (e: Exception) { error = "Unable to finish recording: ${e.message}" }
                finally { if (currentId == recordingId) currentId = null }
            }
            writer.shutdown()
        }
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
