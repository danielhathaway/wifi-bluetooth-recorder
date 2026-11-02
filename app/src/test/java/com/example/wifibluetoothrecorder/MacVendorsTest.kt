package com.example.wifibluetoothrecorder

import org.junit.Assert.*
import org.junit.Test

class MacVendorsTest {
    private val vendors=MacVendors.read("001122\tLarge company\n0011223\tMedium company\n001122345\tSmall company\n".reader())
    @Test fun findsMostSpecificManufacturerAssignment() {
        assertEquals("Small company",vendors.lookup("00:11:22:34:56:78"))
        assertEquals("Medium company",vendors.lookup("00:11:22:30:00:00"))
        assertEquals("Large company",vendors.lookup("00:11:22:ff:ff:ff"))
    }
    @Test fun acceptsStandardMacFormattingAndCase() {
        assertEquals("Small company",vendors.lookup("0011.2234.5678"))
        assertEquals("Large company",vendors.lookup("00-11-22-AA-BB-CC"))
    }
    @Test fun unknownLocalMulticastAndInvalidAddressesAreNotGuessed() {
        listOf("02:11:22:33:44:55","01:11:22:33:44:55","FF:FF:FF:FF:FF:FF","00:00:00:00:00:00","garbage","00:11:22").forEach {
            assertNull(vendors.lookup(it))
        }
        assertTrue(MacVendors.isLocal("02:11:22:33:44:55"))
        assertNull(vendors.lookup("00:33:44:55:66:77"))
    }
}
