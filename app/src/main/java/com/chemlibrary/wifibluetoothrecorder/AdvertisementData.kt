package com.example.wifibluetoothrecorder

/** Lossless raw bytes are stored separately; this decoder also retains duplicate/unknown AD types. */
data class AdvertisingField(val type: Int?, val dataHex: String, val offset: Int,
    val declaredLength: Int, val truncated: Boolean)

object AdvertisementData {
    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 255
                append(digits[value ushr 4]); append(digits[value and 15])
            }
        }
    }
    fun fields(bytes: ByteArray): List<AdvertisingField> = buildList {
        var offset = 0
        while(offset < bytes.size) {
            val length = bytes[offset].toInt() and 255
            if(length == 0) break
            val end = minOf(offset + 1 + length, bytes.size)
            val type = if(offset + 1 < bytes.size) bytes[offset + 1].toInt() and 255 else null
            val data = if(offset + 2 <= end) bytes.copyOfRange(offset + 2,end) else byteArrayOf()
            val truncated = offset + 1 + length > bytes.size
            add(AdvertisingField(type,hex(data),offset,length,truncated))
            if(truncated) break
            offset = end
        }
    }
}
