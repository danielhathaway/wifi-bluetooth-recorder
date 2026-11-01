package com.example.wifibluetoothrecorder

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Keep controls clear of status/navigation bars when Android 15 enforces edge-to-edge. */
internal fun AppCompatActivity.fitSystemBars() {
    val content = findViewById<android.view.View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(content)
}
