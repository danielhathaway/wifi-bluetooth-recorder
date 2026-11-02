package com.example.wifibluetoothrecorder

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.Build
import android.os.ParcelUuid
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,29,33,35])
class BluetoothObservationEncoderTest {
    private lateinit var device: BluetoothDevice
    private val heart=ParcelUuid.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val bytes=byteArrayOf(2,1,6, 2,10,(-4).toByte(), 5,9,84,101,115,116,
        5,(-1).toByte(),76,0,1,2, 3,3,13,24, 4,22,13,24,85, 3,20,18,24,
        2,126,90, 2,126,107, 0)
    @Before fun setup() {
        device=ShadowBluetoothDevice.newInstance("00:17:F2:12:34:56")
        shadowOf(device).apply {
            setName("Cached name"); setType(BluetoothDevice.DEVICE_TYPE_DUAL)
            setBondState(BluetoothDevice.BOND_BONDED); setUuids(arrayOf(heart))
            setBluetoothClass(bluetoothClass(0x240414))
        }
    }
    private fun bluetoothClass(value: Int): BluetoothClass = ReflectionHelpers.callConstructor(
        BluetoothClass::class.java,ClassParameter.from(Int::class.javaPrimitiveType!!,value))
    private fun record(): ScanRecord = ReflectionHelpers.callStaticMethod(ScanRecord::class.java,
        "parseFromBytes",ClassParameter.from(ByteArray::class.java,bytes))
    private fun result(tx: Int=127, record: ScanRecord?=record(), interval: Int=16) = ScanResult(
        device,0x41,BluetoothDevice.PHY_LE_CODED,BluetoothDevice.PHY_LE_2M,3,tx,-61,interval,record,123456789L)

    @Test fun capturesRawPayloadParsedFieldsRadioMetadataAndCallbackContext() {
        val data=BluetoothObservationEncoder.ble(result(),1,"single")
        assertEquals("Test",data.getString("name"))
        assertEquals(AdvertisementData.hex(bytes),data.getString("raw_scan_record_hex"))
        assertEquals(6,data.getInt("advertise_flags"))
        assertEquals(-4,data.getInt("advertised_tx_power"))
        assertEquals("0102",data.getJSONObject("manufacturer_data").getString("76"))
        assertEquals("55",data.getJSONObject("service_data").getString(heart.uuid.toString()))
        assertEquals(heart.uuid.toString(),data.getJSONArray("service_uuids").getString(0))
        assertEquals(2,data.getInt("data_status"))
        assertEquals(BluetoothDevice.PHY_LE_CODED,data.getInt("primary_phy"))
        assertEquals(BluetoothDevice.PHY_LE_2M,data.getInt("secondary_phy"))
        assertTrue(data.getBoolean("is_connectable")); assertFalse(data.getBoolean("is_legacy"))
        assertEquals(3,data.getInt("advertising_sid"))
        assertEquals(16,data.getInt("periodic_advertising_interval"))
        assertEquals(20.0,data.getDouble("periodic_advertising_interval_ms"),0.0)
        assertEquals(1,data.getInt("callback_type")); assertEquals("single",data.getString("delivery_mode"))
        assertEquals(123456789L,data.getLong("scan_timestamp_ns"))
        val structures=data.getJSONArray("advertising_structures")
        assertEquals(2,(0 until structures.length()).count { structures.getJSONObject(it).getInt("type")==126 })
        val cached=data.getJSONObject("device_metadata")
        assertEquals("Cached name",cached.getString("cached_name"))
        assertEquals(BluetoothDevice.DEVICE_TYPE_DUAL,cached.getInt("type"))
        assertEquals(BluetoothDevice.BOND_BONDED,cached.getInt("bond_state"))
        assertEquals(heart.uuid.toString(),cached.getJSONArray("cached_uuids").getString(0))
        assertEquals(0x414,cached.getJSONObject("bluetooth_class").getInt("device_class"))
        assertTrue(cached.getJSONObject("bluetooth_class").getJSONObject("services").getBoolean("audio"))
        if(Build.VERSION.SDK_INT >= 29) assertEquals("00001812-0000-1000-8000-00805f9b34fb",
            data.getJSONArray("service_solicitation_uuids").getString(0))
        else assertTrue(data.isNull("service_solicitation_uuids"))
        if(Build.VERSION.SDK_INT >= 33) assertEquals("6b",data.getJSONObject("android_advertising_data_map").getString("126"))
        else assertTrue(data.isNull("android_advertising_data_map"))
        if(Build.VERSION.SDK_INT < 35) assertTrue(cached.isNull("address_type"))
        assertEquals(0,shadowOf(device).fetchUuidsWithSdpCount)
        assertTrue(shadowOf(device).bluetoothGatts.isEmpty())
    }
    @Test fun distinguishesAbsentScanTxPowerFromAdvertisedTxPower() {
        val absent=BluetoothObservationEncoder.ble(result(),1,"single")
        assertTrue(absent.isNull("tx_power")); assertEquals(127,absent.getInt("tx_power_raw"))
        assertEquals(-4,absent.getInt("advertised_tx_power"))
        val available=BluetoothObservationEncoder.ble(result(-10),1,"single")
        assertEquals(-10,available.getInt("tx_power"))
    }
    @Test fun absentScanRecordAndBatchCallbackAreExplicitlyRepresented() {
        val data=BluetoothObservationEncoder.ble(result(record=null,interval=0),null,"batch",2,5)
        assertTrue(data.isNull("raw_scan_record_hex")); assertFalse(data.getBoolean("scan_record_present"))
        assertTrue(data.isNull("callback_type")); assertEquals("batch",data.getString("delivery_mode"))
        assertEquals(2,data.getInt("batch_index")); assertEquals(5,data.getInt("batch_size"))
        assertTrue(data.isNull("advertised_tx_power")); assertTrue(data.isNull("periodic_advertising_interval_ms"))
        assertEquals("Cached name",data.getString("name"))
    }
    @Test fun classicUsesDiscoveryExtrasAndPreservesCachedMetadataSeparately() {
        val found=Intent(BluetoothDevice.ACTION_FOUND).putExtra(BluetoothDevice.EXTRA_DEVICE,device)
            .putExtra(BluetoothDevice.EXTRA_NAME,"Discovered name")
            .putExtra(BluetoothDevice.EXTRA_CLASS,bluetoothClass(0x240404))
            .putExtra(BluetoothDevice.EXTRA_RSSI,(-52).toShort())
            .putExtra(BluetoothDevice.EXTRA_UUID,arrayOf(heart))
            .putExtra("future.extra",true).putExtra("raw.extra",byteArrayOf(0,(-1).toByte()))
        if(Build.VERSION.SDK_INT >= 33) found.putExtra(BluetoothDevice.EXTRA_IS_COORDINATED_SET_MEMBER,true)
        val data=BluetoothObservationEncoder.classic(device,found)
        assertEquals("Discovered name",data.getString("name"))
        assertEquals(-52,data.getInt("rssi")); assertEquals(0x404,data.getInt("class"))
        assertEquals("Cached name",data.getJSONObject("device_metadata").getString("cached_name"))
        val extras=data.getJSONObject("broadcast_extras")
        assertTrue(extras.getBoolean("future.extra"))
        assertEquals("00ff",extras.getJSONObject("raw.extra").getString("value"))
        assertEquals(heart.uuid.toString(),extras.getJSONArray(BluetoothDevice.EXTRA_UUID).getString(0))
        assertEquals(0,data.getJSONArray("unsupported_extra_fields").length())
    }
    @Test @Config(sdk=[35]) fun permissionRevocationDoesNotDiscardTheAdvertisement() {
        shadowOf(device).setShouldThrowSecurityExceptions(true)
        val data=BluetoothObservationEncoder.ble(result(),1,"single")
        assertEquals(AdvertisementData.hex(bytes),data.getString("raw_scan_record_hex"))
        assertEquals("Test",data.getString("name"))
        assertTrue(data.getJSONObject("device_metadata").getJSONArray("unavailable_fields").length()>0)
    }
}
