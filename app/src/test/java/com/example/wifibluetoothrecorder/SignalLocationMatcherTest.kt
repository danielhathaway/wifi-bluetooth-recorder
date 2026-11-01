package com.example.wifibluetoothrecorder

import org.junit.Assert.*
import org.junit.Test

class SignalLocationMatcherTest {
    private fun seconds(value: Long) = value * 1_000_000_000L
    private fun fix(second: Long, latitude: Double = second.toDouble(), longitude: Double = 0.0,
        accuracy: Double = 5.0) = TimedLocation(seconds(second), second * 1000, latitude, longitude, accuracy)
    private fun signal(measured: Long?, received: Long = 30, type: String = "wifi") = PendingSignal(
        type, if(type == "wifi") "" else "le", "{}", measured?.let { seconds(it) },
        seconds(received), received * 1000, if(type == "wifi") "wifi_scan" else "ble_scan")
    private fun buffer() = SignalLocationBuffer(MeasurementClock(seconds(1), 1000))

    @Test fun delayedSignalUsesHistoricalFixInsteadOfLatestPosition() {
        val matcher=SignalLocationMatcher()
        matcher.add(fix(10,1.0)); matcher.add(fix(30,2.0))
        val match=matcher.match(seconds(10))!!
        assertEquals(1.0,match.point.latitude,0.0)
        assertEquals("exact",match.method)
    }
    @Test fun interpolatesAtMeasurementTimeWithOutOfOrderGpsFixes() {
        val matcher=SignalLocationMatcher()
        matcher.add(fix(12,12.0)); matcher.add(fix(10,10.0))
        val match=matcher.match(seconds(11))!!
        assertEquals(11.0,match.point.latitude,0.00001)
        assertEquals("interpolated",match.method)
        assertEquals(seconds(10),match.before.elapsedNanos)
        assertEquals(seconds(12),match.after.elapsedNanos)
    }
    @Test fun interpolationCrossesDateLineAlongShortestPath() {
        val matcher=SignalLocationMatcher()
        matcher.add(fix(10,0.0,179.9)); matcher.add(fix(12,0.0,-179.9))
        assertEquals(180.0,kotlin.math.abs(matcher.match(seconds(11))!!.point.longitude),0.00001)
    }
    @Test fun refusesStaleFixesAndInterpolationAcrossGpsOutages() {
        val matcher=SignalLocationMatcher()
        matcher.add(fix(10)); matcher.add(fix(30))
        assertNull(matcher.match(seconds(20)))
        assertNull(matcher.match(seconds(1)))
        assertNull(matcher.match(seconds(40)))
        assertNull(matcher.match(0))
    }
    @Test fun nearestFixMustBeWithinOneSecondAndIsLabelled() {
        val matcher=SignalLocationMatcher(); matcher.add(fix(10))
        assertEquals("nearest",matcher.match(seconds(11))!!.method)
        assertNull(matcher.match(seconds(11)+1))
    }
    @Test fun rejectsBadGpsAndKeepsFirstFixForDuplicateTimestamp() {
        val matcher=SignalLocationMatcher()
        assertFalse(matcher.add(fix(10,accuracy=51.0)))
        assertFalse(matcher.add(fix(10,latitude=Double.NaN)))
        assertFalse(matcher.add(fix(10,longitude=181.0)))
        assertFalse(matcher.add(fix(10,accuracy=Double.NaN)))
        assertTrue(matcher.add(fix(10,latitude=0.0,longitude=0.0)))
        assertFalse(matcher.add(fix(10,latitude=1.0)))
        assertEquals(0.0,matcher.match(seconds(10))!!.point.latitude,0.0)
    }
    @Test fun delayedGpsCallbackCanCompletePendingSignal() {
        val buffer=buffer()
        buffer.addLocation(fix(10,10.0))
        buffer.add(signal(11,12))
        assertTrue(buffer.drain(seconds(12)).isEmpty())
        buffer.addLocation(fix(12,12.0))
        val sample=buffer.drain(seconds(13)).single()
        assertEquals(11000L,sample.timeMillis)
        assertEquals(11.0,sample.location!!.point.latitude,0.00001)
        assertEquals("interpolated",sample.location.method)
    }
    @Test fun outOfOrderWifiAndBleSignalsRetainTheirOwnMeasurementTimesAndPositions() {
        val buffer=buffer()
        buffer.addLocation(fix(10,1.0)); buffer.addLocation(fix(20,2.0)); buffer.addLocation(fix(30,3.0))
        buffer.add(signal(20)); buffer.add(signal(10,type="bluetooth"))
        val samples=buffer.drain(seconds(30))
        assertEquals(listOf(20000L,10000L),samples.map { it.timeMillis })
        assertEquals(listOf(2.0,1.0),samples.map { it.location!!.point.latitude })
    }
    @Test fun unmatchedSignalsAreSavedWithoutInventingCoordinatesAfterDeadline() {
        val buffer=buffer(); buffer.add(signal(10))
        assertTrue(buffer.drain(seconds(35)).isEmpty())
        val sample=buffer.drain(seconds(36)).single()
        assertNull(sample.location)
        assertEquals(10000L,sample.timeMillis)
        assertTrue(buffer.drain(seconds(40)).isEmpty())
    }
    @Test fun stoppingFlushesPendingRecordsWithOnlyCredibleNearestFixes() {
        val buffer=buffer(); buffer.addLocation(fix(10,1.0))
        buffer.add(signal(11,12)); buffer.add(signal(20,30))
        val samples=buffer.drain(seconds(30),force=true)
        assertEquals("nearest",samples[0].location!!.method)
        assertNull(samples[1].location)
    }
    @Test fun unknownOrInvalidMeasurementTimeNeverUsesReceiptLocation() {
        val buffer=buffer(); buffer.addLocation(fix(30,1.0))
        buffer.add(signal(null)); buffer.add(signal(31)); buffer.add(signal(0))
        val samples=buffer.drain(seconds(30))
        assertEquals(3,samples.size)
        samples.forEach { assertNull(it.location); assertEquals(30000L,it.timeMillis) }
    }
    @Test fun pendingQueueIsBoundedWithoutDroppingSignals() {
        val buffer=buffer()
        repeat(SignalLocationBuffer.MAX_PENDING+10) { buffer.add(signal(10)) }
        val early=buffer.drain(seconds(30))
        val remaining=buffer.drain(seconds(30),force=true)
        assertTrue(early.isNotEmpty())
        assertTrue(remaining.size < SignalLocationBuffer.MAX_PENDING)
        assertEquals(SignalLocationBuffer.MAX_PENDING+10,early.size+remaining.size)
    }
    @Test fun wallClockChangesDoNotAffectMatchingOrMeasurementTimeline() {
        val buffer=buffer(); buffer.addLocation(fix(10,1.0))
        buffer.add(signal(10).copy(receivedTimeMillis=999999999))
        val sample=buffer.drain(seconds(30)).single()
        assertEquals(10000L,sample.timeMillis)
        assertEquals(999999999L,sample.signal.receivedTimeMillis)
        assertEquals(1.0,sample.location!!.point.latitude,0.0)
    }
    @Test fun convertsWifiMicrosecondsAndRejectsInvalidOrOverflowingTimestamps() {
        assertEquals(1234000L,MeasurementClock.wifiNanos(1234))
        assertNull(MeasurementClock.wifiNanos(0))
        assertNull(MeasurementClock.wifiNanos(-1))
        assertNull(MeasurementClock.wifiNanos(Long.MAX_VALUE))
        assertEquals(999L,MeasurementClock(2000000L,1000L).timeMillis(1999999L))
    }
}
