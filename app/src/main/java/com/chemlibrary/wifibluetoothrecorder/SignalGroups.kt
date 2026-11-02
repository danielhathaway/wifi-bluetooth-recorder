package com.example.wifibluetoothrecorder

/** List grouping never deletes or consolidates stored observations. */
data class SignalReading(val id: Long, val time: Long, val type: String, val address: String,
    val name: String, val detail: String, val latitude: Double, val longitude: Double)

data class SignalGroup(val key: String, val type: String, val address: String, val name: String,
    val details: List<String>, val count: Long, val lastSeen: Long, val firstPoint: TrackPoint)

object SignalGroups {
    fun key(type: String, address: String, id: Long, latitude: Double, longitude: Double): String =
        if (type == "location") "gps:${if(latitude == 0.0) 0.0 else latitude},${if(longitude == 0.0) 0.0 else longitude}"
        else MacVendors.normalize(address)?.let { "mac:$it" } ?: "row:$id"
}

class SignalGroupBuilder {
    private data class Entry(val first: SignalReading, var name: String, var nameTime: Long,
        var lastSeen: Long, var count: Long = 0, val details: MutableSet<String> = sortedSetOf())
    private val entries = linkedMapOf<String, Entry>()

    fun add(reading: SignalReading) {
        val key = SignalGroups.key(reading.type, reading.address, reading.id, reading.latitude, reading.longitude)
        val entry = entries.getOrPut("${reading.type}:$key") {
            Entry(reading, reading.name, if(reading.name.isBlank()) Long.MIN_VALUE else reading.time, reading.time)
        }
        entry.count++
        entry.lastSeen = maxOf(entry.lastSeen, reading.time)
        if (reading.name.isNotBlank() && reading.time >= entry.nameTime) {
            entry.name = reading.name
            entry.nameTime = reading.time
        }
        if (reading.detail.isNotBlank()) entry.details += reading.detail
    }

    fun build(): List<SignalGroup> = entries.values.map { e ->
        val r = e.first
        SignalGroup(SignalGroups.key(r.type,r.address,r.id,r.latitude,r.longitude),r.type,
            MacVendors.display(r.address),e.name,e.details.toList(),e.count,e.lastSeen,TrackPoint(r.latitude,r.longitude))
    }.sortedWith(compareByDescending<SignalGroup> { it.count }.thenBy { it.name }.thenBy { it.key })
}
