package com.example.wifibluetoothrecorder

import android.content.Context
import android.view.View
import java.text.NumberFormat
import kotlin.math.max
import kotlin.math.roundToLong

internal object UiFormat {
    fun number(value: Long): String = NumberFormat.getIntegerInstance().format(value)
    fun distance(context: Context, meters: Double): String = if (meters >= 1000) {
        val km = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(meters / 1000)
        context.getString(R.string.distance_kilometers, km)
    } else context.getString(R.string.distance_meters, number(meters.roundToLong()))

    fun duration(context: Context, seconds: Long): String = when {
        seconds >= 3600 -> context.getString(R.string.duration_hours, seconds / 3600, seconds / 60 % 60)
        seconds >= 60 -> context.getString(R.string.duration_minutes, seconds / 60, seconds % 60)
        else -> context.getString(R.string.duration_seconds, seconds)
    }

    /** Keep the same comfortable reading width on phones, tablets and landscape windows. */
    fun centerContent(view: View) {
        val density = view.resources.displayMetrics.density
        val minPadding = (24 * density).toInt()
        val maxWidth = (640 * density).toInt()
        view.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                val horizontal = max(minPadding, (right - left - maxWidth) / 2)
                v.setPadding(horizontal, v.paddingTop, horizontal, v.paddingBottom)
            }
        }
    }
}
