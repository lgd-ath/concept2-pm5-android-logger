package com.concept2.strokelogger

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.concept2.strokelogger.data.exporter.GoogleDriveBridge
import com.concept2.strokelogger.data.model.WorkoutSession
import com.concept2.strokelogger.service.UsbForegroundService
import com.concept2.strokelogger.ui.components.ExportDialog
import com.concept2.strokelogger.ui.screens.LiveMonitorScreen
import com.concept2.strokelogger.ui.theme.CarbonDark
import com.concept2.strokelogger.ui.theme.Concept2StrokeLoggerTheme
import com.concept2.strokelogger.usb.LiveMetrics
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val usbServiceState = mutableStateOf<UsbForegroundService?>(null)
    private var isBound = false

    private val isConnectedState = mutableStateOf(false)
    private val isRecordingState = mutableStateOf(false)
    private var completedSession: WorkoutSession? = null

    private var pendingConnectCallback: ((UsbForegroundService) -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "UsbForegroundService connected to MainActivity")
            val binder = service as UsbForegroundService.LocalBinder
            val s = binder.service
            usbServiceState.value = s
            isBound = true
            isConnectedState.value = s.isConnected
            isRecordingState.value = s.activeSession != null

            pendingConnectCallback?.invoke(s)
            pendingConnectCallback = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "UsbForegroundService disconnected")
            usbServiceState.value = null
            isBound = false
            isConnectedState.value = false
        }
    }

    private fun ensureServiceBound(onBound: (UsbForegroundService) -> Unit) {
        val current = usbServiceState.value
        if (isBound && current != null) {
            onBound(current)
            return
        }
        pendingConnectCallback = onBound
        val serviceIntent = Intent(this, UsbForegroundService::class.java)
        try {
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed binding service", e)
            Toast.makeText(this, "Service initialization error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity onCreate on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        // Request notification permission on Android 13+ (S25+ runs Android 15/16)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            Concept2StrokeLoggerTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    color = CarbonDark
                ) {
                    val service = usbServiceState.value
                    val metrics = service?.liveMetrics?.collectAsState(initial = LiveMetrics())?.value
                        ?: LiveMetrics()

                    var showExportDialog by remember { mutableStateOf(false) }

                    LiveMonitorScreen(
                        isConnected = isConnectedState.value,
                        isRecording = isRecordingState.value,
                        metrics = metrics,
                        activeSession = service?.activeSession,
                        onConnectUsb = {
                            ensureServiceBound { srv ->
                                lifecycleScope.launch {
                                    try {
                                        val ok = srv.connectToDevice()
                                        isConnectedState.value = ok
                                        if (ok) {
                                            Toast.makeText(this@MainActivity, "PM5 Connected via USB!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(this@MainActivity, "PM5 not detected. Check USB-OTG cable.", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error connecting to PM5", e)
                                        Toast.makeText(this@MainActivity, "USB Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        onStartWorkout = {
                            ensureServiceBound { srv ->
                                try {
                                    val fgsIntent = Intent(this@MainActivity, UsbForegroundService::class.java).apply {
                                        action = UsbForegroundService.ACTION_START_RECORDING
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startForegroundService(fgsIntent)
                                    } else {
                                        startService(fgsIntent)
                                    }
                                } catch (e: Throwable) {
                                    Log.w(TAG, "startForegroundService exception: ${e.message}")
                                }
                                val session = srv.startRecording()
                                isRecordingState.value = true
                                Toast.makeText(this@MainActivity, "Workout started!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onFinishWorkout = {
                            completedSession = usbServiceState.value?.stopRecording()
                            isRecordingState.value = false
                            if (completedSession != null) {
                                // Automatically save CSV & JSON directly to Documents/ErgoSessions upon workout finish
                                GoogleDriveBridge.saveToLocalDocuments(this@MainActivity, completedSession!!)
                            }
                            showExportDialog = true
                        }
                    )

                    if (showExportDialog && completedSession != null) {
                        ExportDialog(
                            session = completedSession!!,
                            onShareCsv = {
                                GoogleDriveBridge.shareCsvForWebTool(this@MainActivity, completedSession!!)
                            },
                            onSaveToGoogleDrive = {
                                GoogleDriveBridge.shareToGoogleDrive(this@MainActivity, completedSession!!)
                            },
                            onSaveToDocuments = {
                                val success = GoogleDriveBridge.saveToLocalDocuments(this@MainActivity, completedSession!!)
                                if (success) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Saved to Documents/ErgoSessions",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            onDismiss = {
                                showExportDialog = false
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Triggered when Concept2 USB cable is attached while app is already open
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            ensureServiceBound { srv ->
                lifecycleScope.launch {
                    val ok = srv.connectToDevice()
                    isConnectedState.value = ok
                    if (ok) {
                        Toast.makeText(this@MainActivity, "PM5 Connected via USB-OTG", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding service: ${e.message}")
            }
            isBound = false
        }
    }
}
