package com.concept2.strokelogger.data.exporter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.concept2.strokelogger.data.model.WorkoutSession
import java.io.File
import java.io.FileOutputStream

/**
 * Handles generating export session files (JSON and CSV) and triggering
 * direct upload to Google Drive via Android's native Storage Framework and Share Sheet.
 */
object GoogleDriveBridge {

    private const val TAG = "GoogleDriveBridge"
    private const val AUTHORITY = "com.concept2.strokelogger.fileprovider"

    /**
     * Dedicated share intent for the 19-column CSV file formatted for the
     * Concept2 Stroke-by-Stroke Web Analyzer. Uses explicit `text/csv` MIME type
     * so receiving applications (Google Drive, Gmail, Drive Sync) preserve the .csv extension.
     */
    fun shareCsvForWebTool(context: Context, session: WorkoutSession) {
        try {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val csvFilename = CsvSessionExporter.generateFilename(session)
            val csvFile = File(exportDir, csvFilename)
            FileOutputStream(csvFile).use { it.write(CsvSessionExporter.exportToStandardCsv(session).toByteArray()) }

            val csvUri: Uri = FileProvider.getUriForFile(context, AUTHORITY, csvFile)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, csvUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_SUBJECT, "Concept2 Stroke CSV: ${session.title}")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Concept2 PM5 Stroke Data (${session.strokeCount} strokes, ${session.avgWatts} W avg).\nReady to import into Stroke-by-Stroke Analyzer."
                )
            }

            val chooser = Intent.createChooser(shareIntent, "Export CSV (for Web Analyzer)").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share CSV for web tool", e)
        }
    }

    /**
     * Writes the session JSON and CSV files to the app's cache and fires
     * an Android Share Sheet intent. This allows the user to tap "Save to Drive"
     * to upload both files directly into any Google Drive folder.
     */
    fun shareToGoogleDrive(context: Context, session: WorkoutSession) {
        try {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }

            val jsonFilename = JsonSessionExporter.generateFilename(session)
            val jsonFile = File(exportDir, jsonFilename)
            FileOutputStream(jsonFile).use { it.write(JsonSessionExporter.exportToJson(session).toByteArray()) }

            val csvFilename = CsvSessionExporter.generateFilename(session)
            val csvFile = File(exportDir, csvFilename)
            FileOutputStream(csvFile).use { it.write(CsvSessionExporter.exportToStandardCsv(session).toByteArray()) }

            val jsonUri: Uri = FileProvider.getUriForFile(context, AUTHORITY, jsonFile)
            val csvUri: Uri = FileProvider.getUriForFile(context, AUTHORITY, csvFile)

            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(jsonUri, csvUri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_SUBJECT, "Concept2 PM5 Session: ${session.title}")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Session recorded on Samsung Galaxy S25+.\nStrokes: ${session.strokeCount}, Avg Power: ${session.avgWatts} W, Avg SPM: ${session.avgSpm}."
                )
            }

            val chooser = Intent.createChooser(shareIntent, "Save to Google Drive / Share Session").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share files to Google Drive", e)
        }
    }

    /**
     * Saves the session files directly into the phone's public Documents directory:
     * `Documents/ErgoSessions/` so they are accessible when connecting phone to computer via USB.
     */
    fun saveToLocalDocuments(context: Context, session: WorkoutSession): Boolean {
        return try {
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val ergoDir = File(docsDir, "ErgoSessions").apply { mkdirs() }

            val jsonFile = File(ergoDir, JsonSessionExporter.generateFilename(session))
            jsonFile.writeText(JsonSessionExporter.exportToJson(session))

            val csvFile = File(ergoDir, CsvSessionExporter.generateFilename(session))
            csvFile.writeText(CsvSessionExporter.exportToStandardCsv(session))

            Log.i(TAG, "Saved session to Documents/ErgoSessions: ${jsonFile.name}, ${csvFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving to Documents/ErgoSessions", e)
            false
        }
    }
}
