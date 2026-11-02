package com.example.wifibluetoothrecorder

import android.app.ActivityManager
import android.content.Context

/** Delegate to Android so all processes/connections stop before private and app-specific data is cleared. */
internal object AppDataReset {
    fun request(context: Context): Boolean = context.getSystemService(ActivityManager::class.java)
        ?.clearApplicationUserData() ?: false
}
