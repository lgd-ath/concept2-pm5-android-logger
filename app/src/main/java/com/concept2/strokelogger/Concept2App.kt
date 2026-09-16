package com.concept2.strokelogger

import android.app.Application
import android.util.Log
import com.concept2.strokelogger.util.CrashHandler

/**
 * Custom application class to install global crash interception and logging.
 */
class Concept2App : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        Log.i("Concept2App", "Application initialized with CrashHandler")
    }
}
