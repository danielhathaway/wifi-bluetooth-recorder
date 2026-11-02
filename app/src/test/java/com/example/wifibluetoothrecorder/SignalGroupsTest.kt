package com.example.wifibluetoothrecorder

import org.junit.Assert.*
import org.junit.Test

class SignalGroupsTest {
    private fun reading(id: Long, address: String="00:11:22:33:44:55", name: String="Network", time: Long=id,
        type: String="wifi", detail: String="WPA2") = SignalReading(id,time,type,address,name,detail,1.0,2.0)

    @Test fun combinesRepeatedSightingsWithoutDiscardingCountsOrSecurityVariants() {
        val builder=SignalGroupBuilder()
        builder.add(reading(1)); builder.add(reading(2,detail="WPA3")); builder.add(reading(3))
        val group=builder.build().single()
        assertEquals(3L,group.count)
        assertEquals(listOf("WPA2","WPA3"),group.details)
        assertEquals(3L,group.lastSeen)
    }
    @Test fun identicalNamesOnDifferentAccessPointsStaySeparate() {
        val builder=SignalGroupBuilder()
        builder.add(reading(1)); builder.add(reading(2,address="00:11:22:33:44:66"))
        assertEquals(2,builder.build().size)
    }
    @Test fun namesUseMostRecentNonBlankObservationEvenWhenArrivalIsOutOfOrder() {
        val builder=SignalGroupBuilder()
        builder.add(reading(1,name="New",time=30))
        builder.add(reading(2,name="Old",time=10))
        builder.add(reading(3,name="",time=40))
        assertEquals("New",builder.build().single().name)
        assertEquals(40L,builder.build().single().lastSeen)
    }
    @Test fun normalizedDeviceAddressesCombineButRadioTypesDoNot() {
        val builder=SignalGroupBuilder()
        builder.add(reading(1,address="00:aa:bb:cc:dd:ee",type="bluetooth"))
        builder.add(reading(2,address="00-Aa-Bb-Cc-Dd-Ee",type="bluetooth"))
        builder.add(reading(3,address="00AABBCCDDEE",type="wifi"))
        assertEquals(listOf(2L,1L),builder.build().map { it.count })
    }
    @Test fun missingAddressesNeverCombineUnrelatedNamedDevices() {
        val builder=SignalGroupBuilder()
        builder.add(reading(1,address="")); builder.add(reading(2,address=""))
        assertEquals(2,builder.build().size)
    }
    @Test fun gpsGroupsOnlyCombineTheSameCoordinates() {
        val builder=SignalGroupBuilder()
        builder.add(reading(1,type="location").copy(latitude=0.0,longitude=0.0))
        builder.add(reading(2,type="location").copy(latitude=-0.0,longitude=0.0))
        builder.add(reading(3,type="location"))
        assertEquals(2,builder.build().size)
        assertEquals(2L,builder.build().first().count)
    }
    @Test fun onlyFiniteCompleteCoordinatesAreUseful() {
        assertTrue(Observation(1,"wifi",latitude=0.0,longitude=0.0).hasCoordinates())
        assertFalse(Observation(1,"wifi",latitude=0.0).hasCoordinates())
        assertFalse(Observation(1,"wifi",longitude=0.0).hasCoordinates())
        assertFalse(Observation(1,"wifi",latitude=Double.NaN,longitude=1.0).hasCoordinates())
        assertFalse(Observation(1,"wifi",latitude=1.0,longitude=181.0).hasCoordinates())
    }
}
