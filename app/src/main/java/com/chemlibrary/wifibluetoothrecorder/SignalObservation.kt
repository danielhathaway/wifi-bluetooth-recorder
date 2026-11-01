package com.example.wifibluetoothrecorder

import org.json.JSONObject

/** Keep original timing and GPS evidence in the payload so CSV exports remain auditable. */
internal fun LocatedSignal.toObservation(): Observation {
    val sample = signal
    val measured = sample.measuredNanos?.takeIf { it > 0 && it <= sample.receivedElapsedNanos }
    val match = location
    val data = JSONObject(sample.data)
        .put("measurement_alignment_version", 1)
        .put("received_time_ms", sample.receivedTimeMillis)
        .put("received_elapsed_ns", sample.receivedElapsedNanos)
        .put("measurement_elapsed_ns", measured ?: JSONObject.NULL)
        .put("time_source", if (measured != null) sample.measurementSource else "callback")
        .put("location_match", match?.method ?: if (measured == null) "measurement_time_unavailable" else "no_matching_fix")
    if (match != null) {
        fun fixJson(fix: TimedLocation) = JSONObject()
            .put("elapsed_ns", fix.elapsedNanos).put("time_ms", fix.timeMillis)
            .put("latitude", fix.latitude).put("longitude", fix.longitude).put("accuracy_m", fix.accuracyMeters)
        data.put("location_before", fixJson(match.before)).put("location_after", fixJson(match.after))
    }
    return Observation(timeMillis, sample.type, sample.transport,
        match?.point?.latitude, match?.point?.longitude, data.toString())
}
