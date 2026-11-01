package com.example.wifibluetoothrecorder

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class RecordModelsTest {
    @Test fun csvPreservesQuotesCommasUnicodeAndNewlines() {
        val out=StringWriter()
        RecordFormat.csvRow(out,listOf("a,b", "say \"hello\"", "line\nline", "é",null,0))
        assertEquals("\"a,b\",\"say \"\"hello\"\"\",\"line\nline\",\"é\",\"\",\"0\"\r\n",out.toString())
    }
    @Test fun tableNamesOnlyAcceptPositiveIds() {
        assertEquals("recording_42", RecordFormat.table(42))
        listOf(0L,-1L,Long.MIN_VALUE).forEach { id ->
            try { RecordFormat.table(id); fail("Invalid id accepted") } catch(_: IllegalArgumentException) { }
        }
    }
    @Test fun elapsedHandlesActiveCompletedAndClockChanges() {
        val r=Recording(1,10000,null,0,0,0.0,10000)
        assertEquals(5L,r.elapsed(15001))
        assertEquals(0L,r.elapsed(1000))
        assertEquals(2L,r.copy(ended=12000).elapsed(99999))
    }
    @Test fun distanceHandlesZeroKnownRouteAndDateLine() {
        assertEquals(0.0,RecordFormat.distance(TrackPoint(0.0,0.0),TrackPoint(0.0,0.0)),0.00001)
        assertEquals(111195.0,RecordFormat.distance(TrackPoint(0.0,0.0),TrackPoint(0.0,1.0)),1.0)
        assertEquals(22239.0,RecordFormat.distance(TrackPoint(0.0,179.9),TrackPoint(0.0,-179.9)),1.0)
    }
    @Test fun wifiCacheRejectsOnlyMatchingBssidAndTimestamp() {
        val cache=WifiObservationCache()
        assertTrue(cache.accept("a",10))
        assertFalse(cache.accept("a",10))
        assertTrue(cache.accept("a",9))
        assertFalse(cache.accept("a",9))
        assertTrue(cache.accept("a",11))
        assertTrue(cache.accept("b",10))
        assertTrue(WifiObservationCache().accept("a",10))
    }
    @Test fun channelMappingIncludesAllBandsAndSpecialChannels() {
        mapOf(2412 to 1,2484 to 14,5180 to 36,5935 to 2,5955 to 1,7115 to 233,100 to 0).forEach { (freq,channel) ->
            assertEquals(channel,RadioFields.frequencyToChannel(freq))
        }
    }
    @Test fun securityUnderstandsModernAndroidCapabilities() {
        mapOf("[WPA2-PSK]" to "WPA2","[RSN-SAE-CCMP]" to "WPA3","[OWE]" to "OWE","[WEP]" to "WEP","[ESS]" to "OPEN").forEach { (caps,security) ->
            assertEquals(security,RadioFields.security(caps))
        }
    }
}
