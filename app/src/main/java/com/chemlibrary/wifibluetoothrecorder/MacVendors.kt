package com.example.wifibluetoothrecorder

import java.io.Reader
import java.util.Locale

/** Offline longest-prefix matching for IEEE MA-L, MA-M, MA-S and IAB assignments. */
class MacVendors private constructor(private val assignments: Map<String, String>) {
    fun lookup(address: String): String? {
        val mac = normalize(address) ?: return null
        if (mac.take(2).toInt(16) and 3 != 0 || mac.all { it == '0' }) return null
        return listOf(9, 7, 6).firstNotNullOfOrNull { assignments[mac.take(it)] }
    }

    companion object {
        fun normalize(address: String): String? {
            val compact = address.trim().replace(":", "").replace("-", "").replace(".", "").uppercase(Locale.US)
            return compact.takeIf { it.length == 12 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
        }
        fun isLocal(address: String): Boolean = normalize(address)?.take(2)?.toInt(16)?.let { it and 2 != 0 } ?: false
        fun display(address: String): String = normalize(address)?.chunked(2)?.joinToString(":") ?: address
        fun read(reader: Reader): MacVendors = reader.buffered().useLines { lines ->
            val assignments = mutableMapOf<String, String>()
            lines.forEach { line ->
                val fields = line.split('\t', limit=2)
                if (fields.size == 2 && fields[0].length in listOf(6,7,9) && fields[1].isNotBlank()) {
                    assignments[fields[0].uppercase(Locale.US)] = fields[1]
                }
            }
            MacVendors(assignments)
        }
    }
}
