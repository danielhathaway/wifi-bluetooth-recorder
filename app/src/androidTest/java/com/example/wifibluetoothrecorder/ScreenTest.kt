package com.example.wifibluetoothrecorder

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import android.view.View
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenTest {
    @Test fun mainScreenShowsSummaryHistoryAndCsvActions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<TextView>(R.id.elapsedValue).text.isNotBlank())
                assertTrue(activity.findViewById<TextView>(R.id.wifiValue).contentDescription.contains("Wi-Fi"))
                assertTrue(activity.findViewById<TextView>(R.id.bluetoothValue).contentDescription.contains("Bluetooth"))
                assertTrue(activity.findViewById<TextView>(R.id.distanceValue).contentDescription.isNotBlank())
                assertTrue(activity.findViewById<View>(R.id.historyButton).isClickable)
                assertTrue(activity.findViewById<TextView>(R.id.databaseSizeValue).contentDescription.toString().contains("Database size"))
                assertTrue(activity.findViewById<View>(R.id.manageStorageButton).isEnabled)
                assertTrue(activity.findViewById<Button>(R.id.exportButton).text.contains("CSV"))
                assertFalse(activity.findViewById<Button>(R.id.stopButton).isEnabled)
            }
        }
    }
    @Test fun historyAndMapScreensOpenWithoutRadioPermissions() {
        ActivityScenario.launch(HistoryActivity::class.java).use { scenario ->
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val id=RecordingStore.get(context).start()
        RecordingStore.get(context).finish(id)
        val intent=android.content.Intent(context,RecordingMapActivity::class.java).putExtra("recording_id",id)
        ActivityScenario.launch<RecordingMapActivity>(intent).use { scenario ->
            scenario.onActivity {
                assertFalse(it.isFinishing)
                val tabs=it.findViewById<TabLayout>(R.id.recordingTabs)
                assertEquals(4,tabs.tabCount)
                tabs.getTabAt(1)!!.select()
                assertEquals(View.VISIBLE,it.findViewById<View>(R.id.listPanel).visibility)
                assertEquals(View.GONE,it.findViewById<View>(R.id.mapPanel).visibility)
            }
            scenario.recreate()
            scenario.onActivity { assertEquals(1,it.findViewById<TabLayout>(R.id.recordingTabs).selectedTabPosition) }
        }
    }
    @Test fun launcherUsesPocketDriverLabelAndAdaptiveIcon() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("PocketDriver",context.applicationInfo.loadLabel(context.packageManager).toString())
        assertTrue(context.applicationInfo.loadIcon(context.packageManager) is android.graphics.drawable.AdaptiveIconDrawable)
    }
}
