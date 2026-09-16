package com.concept2.strokelogger

import com.concept2.strokelogger.data.exporter.CsvSessionExporter
import com.concept2.strokelogger.data.exporter.JsonSessionExporter
import com.concept2.strokelogger.data.model.Stroke
import com.concept2.strokelogger.data.model.WorkoutSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying exact format compatibility between exported files
 * and the Concept2 Stroke-by-Stroke Analyzer.
 */
class SessionExporterTest {

    private fun createSampleSession(): WorkoutSession {
        val session = WorkoutSession(
            title = "Test Erg Session"
        )
        val stroke1 = Stroke(
            n = 1,
            ts = 1726480800000L,
            tsMs = 0L,
            watts = 210,
            spm = 24,
            driveMs = 720,
            recovMs = 1780,
            drag = 125,
            forceMap = listOf(0, 14, 45, 110, 185, 210, 205, 160, 115, 60, 20, 0),
            heartRate = 145,
            distanceMeters = 10.5
        )
        val stroke2 = Stroke(
            n = 2,
            ts = 1726480802500L,
            tsMs = 2500L,
            watts = 215,
            spm = 24,
            driveMs = 710,
            recovMs = 1790,
            drag = 125,
            forceMap = listOf(0, 18, 50, 115, 190, 215, 210, 165, 120, 65, 25, 0),
            heartRate = 148,
            distanceMeters = 21.2
        )
        session.strokes.add(stroke1)
        session.strokes.add(stroke2)
        session.totalMeters = 21.2
        return session
    }

    @Test
    fun testJsonExportFormat() {
        val session = createSampleSession()
        val jsonStr = JsonSessionExporter.exportToJson(session)

        val jsonObj = JSONObject(jsonStr)
        assertEquals("ergomonitor-session", jsonObj.getString("format"))
        assertEquals(3, jsonObj.getInt("version"))
        assertTrue(jsonObj.has("exportedAt"))
        assertTrue(jsonObj.has("title"))

        val strokesArray = jsonObj.getJSONArray("strokes")
        assertEquals(2, strokesArray.length())

        val firstStroke = strokesArray.getJSONObject(0)
        assertEquals(1, firstStroke.getInt("n"))
        assertEquals(210, firstStroke.getInt("watts"))
        assertEquals(24, firstStroke.getInt("spm"))
        assertEquals(720, firstStroke.getInt("driveMs"))
        assertEquals(1780, firstStroke.getInt("recovMs"))
        assertEquals(125, firstStroke.getInt("drag"))

        val forceMap = firstStroke.getJSONArray("forceMap")
        assertEquals(12, forceMap.length())
        assertEquals(0, forceMap.getInt(0))
        assertEquals(210, forceMap.getInt(5))
    }

    @Test
    fun testCsvExportFormat() {
        val session = createSampleSession()
        val csvStr = CsvSessionExporter.exportToStandardCsv(session)

        val lines = csvStr.trim().split("\n")
        assertEquals(3, lines.size) // Header + 2 stroke rows

        val header = lines[0]
        assertTrue(header.startsWith("stroke,phase,block,time_ms,spm,spm_derived,watts"))
        assertTrue(header.endsWith("shape,forceMap"))

        val row1 = lines[1]
        assertTrue(row1.startsWith("1,1,1,0,24,0,210"))
        assertTrue(row1.contains("\"[0 14 45 110 185 210 205 160 115 60 20 0]\""))
    }
}
