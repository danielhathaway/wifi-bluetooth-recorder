package com.example.wifibluetoothrecorder

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DatabaseFilesTest {
    @get:Rule val directory=TemporaryFolder()
    @Test fun countsMainFileAndLiveWalShmAndRollbackJournals() {
        val db=File(directory.root,"test.db")
        db.writeBytes(ByteArray(100))
        File(db.path+"-wal").writeBytes(ByteArray(200))
        File(db.path+"-shm").writeBytes(ByteArray(30))
        File(db.path+"-journal").writeBytes(ByteArray(10))
        File(db.path+".csv").writeBytes(ByteArray(500))
        assertEquals(340L,DatabaseFiles.sizeBytes(db))
        File(db.path+"-wal").writeBytes(ByteArray(400))
        assertEquals(540L,DatabaseFiles.sizeBytes(db))
    }
    @Test fun missingFilesAreZeroAndSidecarsStillCountWithoutMainFile() {
        val db=File(directory.root,"test.db")
        assertEquals(0L,DatabaseFiles.sizeBytes(db))
        File(db.path+"-wal").writeBytes(ByteArray(50))
        assertEquals(50L,DatabaseFiles.sizeBytes(db))
    }
}
