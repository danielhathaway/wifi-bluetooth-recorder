package com.example.wifibluetoothrecorder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import org.json.JSONArray
import org.json.JSONObject

/** Public scan data and cached device metadata are labelled separately; no GATT/SDP connection is made. */
internal object BluetoothObservationEncoder {
    @SuppressLint("MissingPermission") // Every permission-dependent getter is independently guarded.
    fun device(device: BluetoothDevice): JSONObject {
        val unavailable = JSONArray()
        fun read(field: String, getter: () -> Any?): Any = try { getter() ?: JSONObject.NULL }
        catch (_: SecurityException) { unavailable.put(field); JSONObject.NULL }
        catch (_: IllegalStateException) { unavailable.put(field); JSONObject.NULL }
        val data = JSONObject()
            .put("mac",read("mac") { device.address })
            .put("cached_name",read("cached_name") { device.name })
            .put("type",read("type") { device.type })
            .put("bond_state",read("bond_state") { device.bondState })
            .put("cached_uuids",read("cached_uuids") { device.uuids?.let { uuids -> JSONArray(uuids.map { it.uuid.toString() }) } })
            .put("bluetooth_class",read("bluetooth_class") { device.bluetoothClass?.let { bluetoothClass(it) } })
        data.put("alias",if(Build.VERSION.SDK_INT >= 30) read("alias") { device.alias } else JSONObject.NULL)
        data.put("address_type",if(Build.VERSION.SDK_INT >= 35) read("address_type") { device.addressType } else JSONObject.NULL)
        return data.put("unavailable_fields",unavailable)
    }

    fun bluetoothClass(value: BluetoothClass): JSONObject {
        val services = linkedMapOf(
            "limited_discoverability" to BluetoothClass.Service.LIMITED_DISCOVERABILITY,
            "positioning" to BluetoothClass.Service.POSITIONING,
            "networking" to BluetoothClass.Service.NETWORKING,
            "render" to BluetoothClass.Service.RENDER,
            "capture" to BluetoothClass.Service.CAPTURE,
            "object_transfer" to BluetoothClass.Service.OBJECT_TRANSFER,
            "audio" to BluetoothClass.Service.AUDIO,
            "telephony" to BluetoothClass.Service.TELEPHONY,
            "information" to BluetoothClass.Service.INFORMATION)
        if(Build.VERSION.SDK_INT >= 33) services["le_audio"] = BluetoothClass.Service.LE_AUDIO
        val flags = JSONObject()
        services.forEach { (name,bit) -> flags.put(name,value.hasService(bit)) }
        return JSONObject().put("device_class",value.deviceClass).put("major_device_class",value.majorDeviceClass)
            .put("raw",value.toString()).put("services",flags)
    }

    fun ble(result: ScanResult, callbackType: Int?, delivery: String, batchIndex: Int? = null, batchSize: Int? = null): JSONObject {
        val cached = device(result.device)
        val record = result.scanRecord
        val manufacturers = JSONObject()
        record?.manufacturerSpecificData?.let { values ->
            for(i in 0 until values.size()) manufacturers.put(values.keyAt(i).toString(),AdvertisementData.hex(values.valueAt(i)))
        }
        val services = JSONObject()
        record?.serviceData?.forEach { (uuid,bytes) -> services.put(uuid.uuid.toString(),AdvertisementData.hex(bytes)) }
        val raw = record?.bytes
        val structures = raw?.let { bytes -> JSONArray().apply {
            AdvertisementData.fields(bytes).forEach { field -> put(JSONObject()
                .put("type",field.type ?: JSONObject.NULL).put("data_hex",field.dataHex)
                .put("offset",field.offset).put("declared_length",field.declaredLength).put("truncated",field.truncated)) }
        } }
        val data = JSONObject().put("bluetooth_capture_version",2).put("captured_api_level",Build.VERSION.SDK_INT)
            .put("name",record?.deviceName ?: cachedName(cached))
            .put("mac",cached.opt("mac") ?: JSONObject.NULL).put("rssi",result.rssi)
            // ScanResult uses 127, whereas ScanRecord uses Integer.MIN_VALUE for absent TX power.
            .put("tx_power",if(result.txPower == ScanResult.TX_POWER_NOT_PRESENT) JSONObject.NULL else result.txPower)
            .put("tx_power_raw",result.txPower)
            .put("advertised_tx_power",record?.txPowerLevel?.takeUnless { it == Int.MIN_VALUE } ?: JSONObject.NULL)
            .put("advertise_flags",record?.advertiseFlags?.takeUnless { it == -1 } ?: JSONObject.NULL)
            .put("scan_record_name",record?.deviceName ?: JSONObject.NULL)
            .put("scan_record_present",record != null).put("raw_scan_record_hex",raw?.let { AdvertisementData.hex(it) } ?: JSONObject.NULL)
            .put("advertising_structures",structures ?: JSONObject.NULL)
            .put("manufacturer_data",manufacturers).put("service_data",services)
            .put("service_uuids",JSONArray(record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList<String>()))
            .put("service_solicitation_uuids",if(Build.VERSION.SDK_INT >= 29 && record != null)
                JSONArray(record.serviceSolicitationUuids?.map { it.uuid.toString() } ?: emptyList<String>()) else JSONObject.NULL)
            .put("scan_timestamp_ns",result.timestampNanos).put("callback_type",callbackType ?: JSONObject.NULL)
            .put("delivery_mode",delivery).put("batch_index",batchIndex ?: JSONObject.NULL)
            .put("batch_size",batchSize ?: JSONObject.NULL).put("is_legacy",result.isLegacy).put("is_connectable",result.isConnectable)
            .put("data_status",result.dataStatus).put("primary_phy",result.primaryPhy).put("secondary_phy",result.secondaryPhy)
            .put("advertising_sid",result.advertisingSid).put("periodic_advertising_interval",result.periodicAdvertisingInterval)
            .put("periodic_advertising_interval_ms",if(result.periodicAdvertisingInterval == ScanResult.PERIODIC_INTERVAL_NOT_PRESENT)
                JSONObject.NULL else result.periodicAdvertisingInterval * 1.25)
            .put("device_metadata",cached)
        if(Build.VERSION.SDK_INT >= 33 && record != null) {
            val fields = JSONObject()
            record.advertisingDataMap?.forEach { (type,bytes) -> fields.put(type.toString(),AdvertisementData.hex(bytes)) }
            data.put("android_advertising_data_map",fields)
        } else data.put("android_advertising_data_map",JSONObject.NULL)
        return data
    }

    @Suppress("DEPRECATION")
    fun classic(device: BluetoothDevice, intent: Intent): JSONObject {
        val cached = device(device)
        val extras = JSONObject()
        val unsupported = JSONArray()
        // Preserve all scalar/array extras too, including fields added by newer Android versions.
        intent.extras?.let { bundle -> bundle.keySet().sorted().forEach { key ->
            extras.put(key,extra(bundle.get(key),unsupported,key))
        } }
        val discoveredClass = if(Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS,BluetoothClass::class.java)
        else intent.getParcelableExtra<BluetoothClass>(BluetoothDevice.EXTRA_CLASS)
        val classData = discoveredClass?.let { bluetoothClass(it) } ?: cached.optJSONObject("bluetooth_class")
        return JSONObject().put("bluetooth_capture_version",2).put("captured_api_level",Build.VERSION.SDK_INT)
            .put("name",intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: cachedName(cached))
            .put("mac",cached.opt("mac") ?: JSONObject.NULL)
            .put("rssi",if(intent.hasExtra(BluetoothDevice.EXTRA_RSSI)) intent.getShortExtra(BluetoothDevice.EXTRA_RSSI,0) else JSONObject.NULL)
            .put("class",classData?.opt("device_class") ?: JSONObject.NULL)
            .put("discovery_class",classData ?: JSONObject.NULL).put("device_metadata",cached)
            .put("broadcast_action",intent.action).put("broadcast_extras",extras).put("unsupported_extra_fields",unsupported)
    }

    private fun cachedName(cached: JSONObject): String = if(cached.isNull("cached_name")) "" else cached.optString("cached_name", "")

    private fun extra(value: Any?, unsupported: JSONArray, key: String): Any = when(value) {
        null -> JSONObject.NULL
        is String, is Boolean, is Number -> value
        is CharSequence -> value.toString()
        is BluetoothDevice -> device(value)
        is BluetoothClass -> bluetoothClass(value)
        is ParcelUuid -> value.uuid.toString()
        is ByteArray -> JSONObject().put("encoding","hex").put("value",AdvertisementData.hex(value))
        is Bundle -> JSONObject().apply { value.keySet().sorted().forEach { name ->
            @Suppress("DEPRECATION")
            put(name,extra(value.get(name),unsupported,"$key.$name"))
        } }
        is List<*> -> JSONArray().apply { value.forEachIndexed { i,item -> put(extra(item,unsupported,"$key[$i]")) } }
        else -> if(value.javaClass.isArray) JSONArray().apply {
            for(i in 0 until java.lang.reflect.Array.getLength(value)) put(extra(java.lang.reflect.Array.get(value,i),unsupported,"$key[$i]"))
        } else {
            unsupported.put(key)
            JSONObject().put("class",value.javaClass.name).put("description",value.toString()).put("capture_status","unsupported_type")
        }
    }
}
