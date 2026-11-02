package com.example.wifibluetoothrecorder

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class HistoryActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var loadVersion = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        fitSystemBars()
        UiFormat.centerContent(findViewById(R.id.historyContent))
        findViewById<MaterialToolbar>(R.id.historyToolbar).setNavigationOnClickListener { finish() }
    }

    override fun onResume() { super.onResume(); loadRecordings() }

    private fun loadRecordings() {
        val version = ++loadVersion
        findViewById<View>(R.id.historyProgress).visibility = View.VISIBLE
        worker.execute {
            try {
                val recordings = RecordingStore.get(this).recordings()
                runOnUiThread {
                    if (isDestroyed || version != loadVersion) return@runOnUiThread
                    findViewById<View>(R.id.historyProgress).visibility = View.GONE
                    findViewById<TextView>(R.id.historySubtitle).text = if (recordings.size == 1) {
                        getString(R.string.history_count_one)
                    } else getString(R.string.history_count, recordings.size)
                    val list = findViewById<LinearLayout>(R.id.recordingList)
                    list.visibility = View.VISIBLE
                    list.removeAllViews()
                    findViewById<View>(R.id.emptyState).visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
                    findViewById<TextView>(R.id.emptyTitle).setText(R.string.history_empty_title)
                    findViewById<TextView>(R.id.emptyBody).setText(R.string.history_empty_body)
                    findViewById<Button>(R.id.emptyAction).apply {
                        setText(R.string.back_to_recorder)
                        setOnClickListener { finish() }
                    }
                    recordings.forEach { r ->
                        val card = layoutInflater.inflate(R.layout.item_recording, list, false)
                        card.findViewById<TextView>(R.id.sessionTitle).text = getString(R.string.recording_title, r.id)
                        card.findViewById<TextView>(R.id.sessionDate).text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.started))
                        card.findViewById<TextView>(R.id.sessionStatus).setText(if (r.ended == null) R.string.recording_live else R.string.recording_saved)
                        card.findViewById<TextView>(R.id.signalCounts).text = getString(R.string.recording_count_summary, UiFormat.number(r.wifi), UiFormat.number(r.bluetooth))
                        card.findViewById<TextView>(R.id.tripSummary).text = getString(R.string.recording_trip_summary,
                            UiFormat.duration(this, r.elapsed(System.currentTimeMillis())), UiFormat.distance(this, r.distance))
                        card.setOnClickListener {
                            startActivity(Intent(this, RecordingMapActivity::class.java).putExtra("recording_id", r.id))
                        }
                        list.addView(card)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed || version != loadVersion) return@runOnUiThread
                    findViewById<View>(R.id.historyProgress).visibility = View.GONE
                    findViewById<View>(R.id.recordingList).visibility = View.GONE
                    findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
                    findViewById<TextView>(R.id.emptyTitle).setText(R.string.error_title)
                    findViewById<TextView>(R.id.emptyBody).text = getString(R.string.history_error, e.message)
                    findViewById<Button>(R.id.emptyAction).apply {
                        setText(R.string.retry)
                        setOnClickListener { findViewById<View>(R.id.recordingList).visibility = View.VISIBLE; loadRecordings() }
                    }
                }
            }
        }
    }
    override fun onDestroy() { loadVersion++; worker.shutdown(); super.onDestroy() }
}
