package com.example.wifibluetoothrecorder

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class HistoryActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(list) })
        fitSystemBars()
        list.addView(Button(this).apply { text="Back"; setOnClickListener { finish() } })
        list.addView(TextView(this).apply { text="Previous recordings"; textSize=24f })
        worker.execute {
            try {
                val recordings = RecordingStore.get(this).recordings()
                runOnUiThread {
                    if(recordings.isEmpty()) list.addView(TextView(this).apply { text="No recordings yet." })
                    recordings.forEach { r ->
                        list.addView(Button(this).apply {
                            text="Recording ${r.id} · ${DateFormat.getDateTimeInstance().format(Date(r.started))}\n${if(r.ended == null) "In progress" else "Finished"} · ${r.bluetooth} Bluetooth · ${r.wifi} Wi-Fi\n${r.elapsed(System.currentTimeMillis())} seconds · ${r.distance.toInt()} m · View map & export"
                            setOnClickListener { startActivity(Intent(this@HistoryActivity, RecordingMapActivity::class.java).putExtra("recording_id",r.id)) }
                        })
                    }
                }
            } catch(e: Exception) { runOnUiThread { list.addView(TextView(this).apply { text="Cannot load recordings: ${e.message}" }) } }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
