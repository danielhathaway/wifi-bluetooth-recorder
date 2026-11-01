package com.example.wifibluetoothrecorder

import java.util.TreeMap
import kotlin.math.abs

/** All matching uses Android elapsed realtime, so wall-clock adjustments cannot change a match. */
data class TimedLocation(
    val elapsedNanos: Long,
    val timeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double
)

data class LocationMatch(
    val point: TrackPoint,
    val method: String,
    val before: TimedLocation,
    val after: TimedLocation
)

class SignalLocationMatcher {
    private val fixes = TreeMap<Long, TimedLocation>()

    fun add(fix: TimedLocation): Boolean {
        if (fix.elapsedNanos <= 0 || !fix.accuracyMeters.isFinite() ||
            fix.accuracyMeters !in 0.0..50.0 || !fix.latitude.isFinite() ||
            !fix.longitude.isFinite() || fix.latitude !in -90.0..90.0 ||
            fix.longitude !in -180.0..180.0 || fixes.containsKey(fix.elapsedNanos)) return false
        fixes[fix.elapsedNanos] = fix
        return true
    }

    fun match(measuredNanos: Long): LocationMatch? {
        if (measuredNanos <= 0) return null
        val before = fixes.floorEntry(measuredNanos)?.value
        val after = fixes.ceilingEntry(measuredNanos)?.value
        if (before != null && before.elapsedNanos == measuredNanos) {
            return LocationMatch(TrackPoint(before.latitude, before.longitude), "exact", before, before)
        }
        // Interpolate only across short, bounded gaps. Never extrapolate a route through a GPS outage.
        if (before != null && after != null &&
            measuredNanos - before.elapsedNanos <= INTERPOLATION_SIDE_NANOS &&
            after.elapsedNanos - measuredNanos <= INTERPOLATION_SIDE_NANOS) {
            val fraction = (measuredNanos - before.elapsedNanos).toDouble() /
                (after.elapsedNanos - before.elapsedNanos)
            val longitudeDelta = ((after.longitude - before.longitude + 540.0) % 360.0) - 180.0
            val longitude = ((before.longitude + fraction * longitudeDelta + 540.0) % 360.0) - 180.0
            return LocationMatch(TrackPoint(
                before.latitude + fraction * (after.latitude - before.latitude), longitude
            ), "interpolated", before, after)
        }
        val nearest = listOfNotNull(before, after).minByOrNull { abs(it.elapsedNanos - measuredNanos) }
            ?.takeIf { abs(it.elapsedNanos - measuredNanos) <= NEAREST_NANOS } ?: return null
        return LocationMatch(TrackPoint(nearest.latitude, nearest.longitude), "nearest", nearest, nearest)
    }

    companion object {
        const val INTERPOLATION_SIDE_NANOS = 5_000_000_000L
        const val NEAREST_NANOS = 1_000_000_000L
    }
}

/** A fixed wall/elapsed clock anchor gives measurement UTC times a consistent session timebase. */
class MeasurementClock(private val anchorElapsedNanos: Long, private val anchorTimeMillis: Long) {
    fun timeMillis(measuredNanos: Long): Long =
        anchorTimeMillis + Math.floorDiv(measuredNanos - anchorElapsedNanos, 1_000_000L)

    companion object {
        fun wifiNanos(timestampMicros: Long): Long? =
            timestampMicros.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)
    }
}

data class PendingSignal(
    val type: String,
    val transport: String,
    val data: String,
    val measuredNanos: Long?,
    val receivedElapsedNanos: Long,
    val receivedTimeMillis: Long,
    val measurementSource: String
)

data class LocatedSignal(val signal: PendingSignal, val timeMillis: Long, val location: LocationMatch?)

/** Confined to the service writer. Only a short window of observations waits in memory. */
class SignalLocationBuffer(private val clock: MeasurementClock) {
    private val matcher = SignalLocationMatcher()
    private val pending = ArrayDeque<PendingSignal>()

    fun addLocation(location: TimedLocation): Boolean = matcher.add(location)
    fun add(signal: PendingSignal) { pending.addLast(signal) }

    fun drain(nowElapsedNanos: Long, force: Boolean = false): List<LocatedSignal> {
        val ready = mutableListOf<LocatedSignal>()
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val signal = iterator.next()
            val measured = signal.measuredNanos?.takeIf { it > 0 && it <= signal.receivedElapsedNanos }
            val location = measured?.let { matcher.match(it) }
            // Exact/interpolated matches are final; otherwise allow the next GPS callback to arrive.
            if (force || pending.size >= MAX_PENDING || measured == null ||
                location?.method in listOf("exact", "interpolated") ||
                nowElapsedNanos - signal.receivedElapsedNanos >= WAIT_NANOS) {
                ready += LocatedSignal(signal,
                    measured?.let { clock.timeMillis(it) } ?: signal.receivedTimeMillis, location)
                iterator.remove()
            }
        }
        return ready
    }

    companion object {
        const val WAIT_NANOS = 6_000_000_000L
        const val MAX_PENDING = 1000
    }
}
