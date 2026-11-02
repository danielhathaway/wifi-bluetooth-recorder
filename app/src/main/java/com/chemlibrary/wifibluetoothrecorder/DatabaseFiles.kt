package com.example.wifibluetoothrecorder

import java.io.File

/** The on-disk database footprint includes live WAL and SQLite sidecars, not exported CSVs/map tiles. */
internal object DatabaseFiles {
    const val NAME = "pocketdriver.db"
    fun sizeBytes(database: File): Long = listOf("", "-wal", "-shm", "-journal").sumOf { suffix ->
        File(database.path + suffix).takeIf { it.isFile }?.length() ?: 0L
    }
}
