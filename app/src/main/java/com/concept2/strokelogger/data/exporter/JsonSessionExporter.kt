package com.concept2.strokelogger.data.exporter

import com.concept2.strokelogger.data.model.WorkoutSession
import com.google.gson.GsonBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Serializes a WorkoutSession into the exact JSON session backup schema (`ergomonitor-session` v3)
 * recognized natively by the Laurent's Coach Concept2 Stroke-by-Stroke Analyzer.
 */
object JsonSessionExporter {

    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Converts a completed WorkoutSession into a formatted JSON string.
     */
    fun exportToJson(session: WorkoutSession): String {
        val root = mutableMapOf<String, Any>()
        root["format"] = "ergomonitor-session"
        root["version"] = 3
        root["exportedAt"] = isoDateFormat.format(Date())
        root["title"] = session.title
        root["phases"] = emptyList<Any>()

        val strokeList = session.strokes.map { s ->
            mapOf(
                "n" to s.n,
                "ts" to s.ts,
                "tsMs" to s.tsMs,
                "block" to s.block,
                "phase" to s.phase,
                "watts" to s.watts,
                "spm" to s.spm,
                "driveMs" to s.driveMs,
                "recovMs" to s.recovMs,
                "drag" to s.drag,
                "spmDerived" to s.spmDerived,
                "forceMap" to s.forceMap
            )
        }
        root["strokes"] = strokeList

        val gson = GsonBuilder().setPrettyPrinting().create()
        return gson.toJson(root)
    }

    /**
     * Generates a standardized export filename.
     * Example: `ergomonitor-session-2026-09-16T12-30-00Z.json`
     */
    fun generateFilename(session: WorkoutSession): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "ergomonitor-session-${sdf.format(Date(session.startTime))}.json"
    }
}
