package com.example.wifibluetoothrecorder

import java.io.Writer
import kotlin.math.*

data class Observation(val time: Long, val type: String, val transport: String = "",
    val latitude: Double? = null, val longitude: Double? = null, val data: String = "{}") {
    fun hasCoordinates(): Boolean = latitude != null && longitude != null &&
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}
data class Recording(val id: Long, val started: Long, val ended: Long?, val bluetooth: Long,
    val wifi: Long, val distance: Double, val lastSaved: Long) {
    fun elapsed(now: Long) = ((ended ?: now) - started).coerceAtLeast(0) / 1000
}
data class TrackPoint(val latitude: Double, val longitude: Double)

object RecordFormat {
    fun table(id: Long): String { require(id > 0); return "recording_$id" }
    fun csvRow(writer: Writer, values: List<Any?>) {
        writer.write(values.joinToString(",") { "\"" + (it?.toString() ?: "").replace("\"", "\"\"") + "\"" } + "\r\n")
    }
    fun distance(a: TrackPoint, b: TrackPoint): Double {
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
            cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        return 6371000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}

/** Fresh Android Wi-Fi timestamps are microseconds since boot, not wall clock time. */
class WifiObservationCache {
    private val observations = mutableSetOf<Pair<String, Long>>()
    @Synchronized fun accept(bssid: String, timestamp: Long): Boolean {
        return observations.add(bssid to timestamp)
    }
}

object RadioFields {
    fun frequencyToChannel(frequency: Int): Int = when {
        frequency == 2484 -> 14
        frequency in 2412..2472 -> (frequency - 2407) / 5
        frequency in 5000..5895 -> (frequency - 5000) / 5
        frequency == 5935 -> 2
        frequency in 5955..7115 -> (frequency - 5950) / 5
        else -> 0
    }
    fun security(capabilities: String): String {
        val caps = capabilities.uppercase(java.util.Locale.US)
        return when {
            "WPA3" in caps || "SAE" in caps -> "WPA3"
            "WPA2" in caps || "RSN" in caps -> "WPA2"
            "WPA" in caps -> "WPA"
            "WEP" in caps -> "WEP"
            "OWE" in caps -> "OWE"
            else -> "OPEN"
        }
    }
}
