package com.example.wifibluetoothrecorder

import android.app.ActivityManager
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowPopupMenu

/** Android erasure is shadowed: these tests never clear a real app installation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AppDataResetTest {
    @Before fun setup() { RecordingService.isRunning=false; RecordingService.currentId=null; RecordingService.error=null }
    private fun showConfirmation(activity: MainActivity): AlertDialog {
        activity.findViewById<View>(R.id.manageStorageButton).performClick()
        val popup=ShadowPopupMenu.getLatestPopupMenu()
        popup.menu.performIdentifierAction(R.id.action_erase_data,0)
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }
    @Test fun cancelNeverRequestsErasureAndStorageSizeIsAccessible() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().visible()
        try {
            val activity=controller.get()
            val manager=shadowOf(activity.getSystemService(ActivityManager::class.java))
            val sentinel=activity.filesDir.resolve("keep-on-cancel.txt").apply { writeText("saved") }
            val dialog=showConfirmation(activity)
            assertTrue(dialog.isShowing)
            assertFalse(manager.isApplicationUserDataCleared)
            assertTrue(activity.findViewById<TextView>(R.id.databaseSizeValue).contentDescription.toString().contains("Database size"))
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(manager.isApplicationUserDataCleared)
            assertEquals("saved",sentinel.readText())
            assertFalse(activity.isFinishing)
        } finally { controller.stop().destroy() }
    }
    @Test fun explicitConfirmationDelegatesToAndroidAndClosesTheTask() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().visible()
        try {
            val activity=controller.get()
            val manager=shadowOf(activity.getSystemService(ActivityManager::class.java))
            showConfirmation(activity).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(manager.isApplicationUserDataCleared)
            assertTrue(activity.isFinishing)
            assertFalse(activity.findViewById<View>(R.id.manageStorageButton).isEnabled)
        } finally { controller.stop().destroy() }
    }
    @Test @Config(shadows=[RejectedReset::class]) fun rejectedSystemRequestKeepsTheAppUsableAndReportsFailure() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().visible()
        try {
            val activity=controller.get()
            showConfirmation(activity).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(activity.isFinishing)
            assertTrue(activity.findViewById<View>(R.id.manageStorageButton).isEnabled)
            assertEquals(activity.getString(R.string.erase_failed),activity.findViewById<TextView>(R.id.errorText).text.toString())
        } finally { controller.stop().destroy() }
    }
    @Implements(ActivityManager::class)
    class RejectedReset : ShadowActivityManager() {
        @Implementation protected fun clearApplicationUserData(): Boolean = false
    }
}
