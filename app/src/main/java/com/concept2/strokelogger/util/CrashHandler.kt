package com.concept2.strokelogger.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import com.concept2.strokelogger.ui.CrashActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Global uncaught exception handler that intercepts fatal crashes,
 * writes the stack trace to persistent storage, and opens a diagnostic CrashActivity
 * displaying the exact stack trace directly on the user's phone screen.
 */
class CrashHandler private constructor(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    companion object {
        private const val TAG = "CrashHandler"
        const val EXTRA_CRASH_INFO = "extra_crash_info"

        fun install(context: Context) {
            val handler = CrashHandler(context.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            Log.i(TAG, "Global CrashHandler installed")
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val stackTrace = sw.toString()

            val errorReport = buildString {
                append("=== CONCEPT2 STROKE LOGGER CRASH REPORT ===\n")
                append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})\n")
                append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                append("Time: ${System.currentTimeMillis()}\n\n")
                append("Exception: ${throwable.javaClass.name}: ${throwable.message}\n\n")
                append("Stack Trace:\n")
                append(stackTrace)
            }

            Log.e(TAG, errorReport)

            // Save crash report to internal files
            try {
                val crashFile = File(context.filesDir, "latest_crash.txt")
                crashFile.writeText(errorReport)
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing crash log file", e)
            }

            // Launch diagnostic crash screen
            val crashIntent = Intent(context, CrashActivity::class.java).apply {
                putExtra(EXTRA_CRASH_INFO, errorReport)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(crashIntent)

            Thread.sleep(300)
            Process.killProcess(Process.myPid())
            exitProcess(10)
        } catch (e: Exception) {
            Log.e(TAG, "Error in CrashHandler", e)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
