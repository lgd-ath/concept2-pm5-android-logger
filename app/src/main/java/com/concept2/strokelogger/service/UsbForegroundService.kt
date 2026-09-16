package com.concept2.strokelogger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.concept2.strokelogger.MainActivity
import com.concept2.strokelogger.R
import com.concept2.strokelogger.data.model.WorkoutSession
import com.concept2.strokelogger.usb.LiveMetrics
import com.concept2.strokelogger.usb.StrokeStateMachine
import com.concept2.strokelogger.usb.UsbTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground Service guaranteeing uninterrupted USB telemetry and force curve polling
 * from the Concept2 PM5 even when the screen is locked, Samsung battery optimization is active,
 * or ErgData / media apps are running in the foreground.
 */
class UsbForegroundService : Service() {

    companion object {
        private const val TAG = "UsbForegroundService"
        private const val CHANNEL_ID = "c2_pm5_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_RECORDING = "com.concept2.strokelogger.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.concept2.strokelogger.STOP_RECORDING"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var transport: UsbTransport
    private lateinit var stateMachine: StrokeStateMachine
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentSession: WorkoutSession? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: UsbForegroundService
            get() = this@UsbForegroundService
    }

    val liveMetrics: StateFlow<LiveMetrics>
        get() = stateMachine.liveMetrics

    val isConnected: Boolean
        get() = transport.isConnected

    val activeSession: WorkoutSession?
        get() = currentSession

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "UsbForegroundService created")
        createNotificationChannel()

        transport = UsbTransport(this)
        stateMachine = StrokeStateMachine(transport, serviceScope)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Concept2StrokeLogger::RecordingWakeLock")

        // Periodically update the notification with live stats during rowing
        serviceScope.launch {
            stateMachine.liveMetrics.collect { metrics ->
                updateNotification(metrics)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(null)
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Attempts connection to an attached Concept2 PM5 monitor.
     */
    suspend fun connectToDevice(): Boolean {
        val device = transport.findDevice() ?: return false
        transport.requestPermission(device)
        return transport.connect(device)
    }

    /**
     * Begins recording a new workout session.
     */
    fun startRecording(): WorkoutSession {
        wakeLock?.acquire(3 * 60 * 60 * 1000L) // 3-hour safety timeout

        val session = WorkoutSession()
        currentSession = session
        stateMachine.start(session)
        Log.i(TAG, "Started recording workout session: ${session.id}")
        return session
    }

    /**
     * Stops the active workout session.
     */
    fun stopRecording(): WorkoutSession? {
        stateMachine.stop()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        val session = currentSession
        session?.endTime = System.currentTimeMillis()
        Log.i(TAG, "Stopped recording workout session: ${session?.id}, strokes: ${session?.strokeCount}")
        return session
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        transport.disconnect()
        serviceScope.cancel()
        Log.i(TAG, "UsbForegroundService destroyed")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(metrics: LiveMetrics?): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (metrics != null && metrics.strokeCount > 0) {
            "Power: ${metrics.watts} W  |  SPM: ${metrics.spm}  |  Strokes: ${metrics.strokeCount}"
        } else {
            if (transport.isConnected) "PM5 Connected • Ready to record" else "PM5 Disconnected"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PM5 Stroke-by-Stroke Logger")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(metrics: LiveMetrics) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(metrics))
    }
}
