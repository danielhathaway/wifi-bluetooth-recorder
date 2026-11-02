package com.example.wifibluetoothrecorder

import org.junit.Assert.*
import org.junit.Test

class AdvertisementDataTest {
    @Test fun preservesUnsignedBytesUnknownTypesAndDuplicateFields() {
        val bytes=byteArrayOf(2,0xFE.toByte(),0xFF.toByte(),2,0xFE.toByte(),0x80.toByte(),0)
        assertEquals("02feff02fe8000",AdvertisementData.hex(bytes))
        val fields=AdvertisementData.fields(bytes)
        assertEquals(listOf(254,254),fields.map { it.type })
        assertEquals(listOf("ff","80"),fields.map { it.dataHex })
        assertEquals(listOf(0,3),fields.map { it.offset })
        assertTrue(fields.none { it.truncated })
    }
    @Test fun malformedStructuresKeepTheirAvailableBytesAndDeclaredLength() {
        val fields=AdvertisementData.fields(byteArrayOf(5,0xFF.toByte(),1,2))
        assertEquals(1,fields.size)
        assertEquals(5,fields.single().declaredLength)
        assertEquals("0102",fields.single().dataHex)
        assertTrue(fields.single().truncated)
        assertNull(AdvertisementData.fields(byteArrayOf(5)).single().type)
    }
    @Test fun emptyAndTerminatedPayloadsDoNotInventFields() {
        assertTrue(AdvertisementData.fields(byteArrayOf()).isEmpty())
        assertTrue(AdvertisementData.fields(byteArrayOf(0,0,0)).isEmpty())
        assertEquals("000000",AdvertisementData.hex(byteArrayOf(0,0,0)))
    }
}
