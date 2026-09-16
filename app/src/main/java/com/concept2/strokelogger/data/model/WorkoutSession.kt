package com.concept2.strokelogger.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Represents an entire rowing session containing ordered stroke records.
 */
data class WorkoutSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "PM5 Session " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
    val startTime: Long = System.currentTimeMillis(),
    var endTime: Long = System.currentTimeMillis(),
    val strokes: MutableList<Stroke> = mutableListOf(),
    var totalMeters: Double = 0.0,
    var dragFactor: Int = 0
) {
    val strokeCount: Int
        get() = strokes.size

    val avgWatts: Int
        get() = if (strokes.isNotEmpty()) (strokes.map { it.watts }.average()).toInt() else 0

    val avgSpm: Int
        get() = if (strokes.isNotEmpty()) (strokes.map { it.spm }.average()).toInt() else 0

    val avgHeartRate: Int
        get() {
            val valid = strokes.filter { it.heartRate > 0 }
            return if (valid.isNotEmpty()) valid.map { it.heartRate }.average().toInt() else 0
        }

    val durationMs: Long
        get() = if (strokes.isNotEmpty()) {
            val last = strokes.last()
            val first = strokes.first()
            if (last.tsMs > 0 && first.tsMs >= 0) last.tsMs - first.tsMs else endTime - startTime
        } else endTime - startTime
}
