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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.concept2.strokelogger.data.exporter.GoogleDriveBridge
import com.concept2.strokelogger.data.model.WorkoutSession
import com.concept2.strokelogger.service.UsbForegroundService
import com.concept2.strokelogger.ui.components.ExportDialog
import com.concept2.strokelogger.ui.screens.LiveMonitorScreen
import com.concept2.strokelogger.ui.theme.Concept2StrokeLoggerTheme
import com.concept2.strokelogger.usb.LiveMetrics
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var usbService: UsbForegroundService? = null
    private var isBound = false

    private val isConnectedState = mutableStateOf(false)
    private val isRecordingState = mutableStateOf(false)
    private var completedSession: WorkoutSession? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as UsbForegroundService.LocalBinder
            usbService = binder.service
            isBound = true
            isConnectedState.value = binder.service.isConnected
            isRecordingState.value = binder.service.activeSession != null

            // Automatically attempt connection on bind
            lifecycleScope.launch {
                val ok = binder.service.connectToDevice()
                isConnectedState.value = ok
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            usbService = null
            isBound = false
            isConnectedState.value = false
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+ (S25+ runs Android 15)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start and bind to UsbForegroundService
        val serviceIntent = Intent(this, UsbForegroundService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            Concept2StrokeLoggerTheme {
                val metrics = usbService?.liveMetrics?.collectAsState(initial = LiveMetrics())?.value
                    ?: LiveMetrics()

                var showExportDialog by remember { mutableStateOf(false) }

                LiveMonitorScreen(
                    isConnected = isConnectedState.value,
                    isRecording = isRecordingState.value,
                    metrics = metrics,
                    activeSession = usbService?.activeSession,
                    onConnectUsb = {
                        lifecycleScope.launch {
                            val ok = usbService?.connectToDevice() ?: false
                            isConnectedState.value = ok
                            if (!ok) {
                                Toast.makeText(this@MainActivity, "PM5 not detected. Check USB-OTG cable.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onStartWorkout = {
                        val session = usbService?.startRecording()
                        if (session != null) {
                            isRecordingState.value = true
                            Toast.makeText(this@MainActivity, "Workout started!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onFinishWorkout = {
                        completedSession = usbService?.stopRecording()
                        isRecordingState.value = false
                        showExportDialog = true
                    }
                )

                if (showExportDialog && completedSession != null) {
                    ExportDialog(
                        session = completedSession!!,
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Triggered when Concept2 USB cable is attached while app is already open
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            lifecycleScope.launch {
                val ok = usbService?.connectToDevice() ?: false
                isConnectedState.value = ok
                if (ok) {
                    Toast.makeText(this@MainActivity, "PM5 Connected via USB-OTG", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
