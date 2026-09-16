package com.concept2.strokelogger.data.exporter

import com.concept2.strokelogger.data.model.WorkoutSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Serializes a WorkoutSession into CSV formats natively ingested by the
 * Concept2 Stroke-by-Stroke Analyzer (`stroke-by-stroke-analyzer`).
 */
object CsvSessionExporter {

    /**
     * Exports full 19-column CSV matching the analyzer's primary stroke table export format (`ergo-strokes.csv`).
     */
    fun exportToStandardCsv(session: WorkoutSession): String {
        val sb = StringBuilder()
        val headers = listOf(
            "stroke", "phase", "block", "time_ms", "spm", "spm_derived",
            "watts", "watts_derived", "drive_ms", "recov_ms", "peak_force",
            "peak_pct", "avg_force", "impulse", "rfd", "catch_pct", "finish_pct",
            "shape", "forceMap"
        )
        sb.append(headers.joinToString(",")).append("\n")

        for (s in session.strokes) {
            val forceMapStr = "\"[" + s.forceMap.joinToString(" ") + "]\""
            val row = listOf(
                s.n.toString(),
                s.phase.toString(),
                s.block.toString(),
                s.tsMs.toString(),
                s.spm.toString(),
                if (s.spmDerived) "1" else "0",
                s.watts.toString(),
                if (s.watts == 0) "1" else "0",
                s.driveMs.toString(),
                s.recovMs.toString(),
                s.peakForce.toString(),
                s.peakPct.toString(),
                s.avgForce.toString(),
                s.impulse.toString(),
                s.rfd.toString(),
                s.catchPct.toString(),
                s.finishPct.toString(),
                s.shape,
                forceMapStr
            )
            sb.append(row.joinToString(",")).append("\n")
        }

        return sb.toString()
    }

    /**
     * Exports simplified 6-column format matching `sample_session.csv`.
     */
    fun exportToSimpleCsv(session: WorkoutSession): String {
        val sb = StringBuilder()
        sb.append("stroke.strokeNumber,stroke.driveMs,stroke.watts,stroke.strokesPerMin,driveStartMs,stroke.forceMap\n")

        for (s in session.strokes) {
            val forceMapStr = "\"[" + s.forceMap.joinToString(" ") + "]\""
            sb.append("${s.n},${s.driveMs},${s.watts},${s.spm},${s.tsMs},$forceMapStr\n")
        }

        return sb.toString()
    }

    /**
     * Generates standard filename for the exported CSV file.
     * Example: `ergo-strokes-2026-09-16T12-30-00Z.csv`
     */
    fun generateFilename(session: WorkoutSession): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "ergo-strokes-${sdf.format(Date(session.startTime))}.csv"
    }
}
