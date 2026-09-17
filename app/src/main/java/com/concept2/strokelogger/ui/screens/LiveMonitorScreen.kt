package com.concept2.strokelogger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.concept2.strokelogger.data.model.WorkoutSession
import com.concept2.strokelogger.ui.components.LiveForceCurveCanvas
import com.concept2.strokelogger.ui.components.MetricTile
import com.concept2.strokelogger.ui.theme.AccentCyan
import com.concept2.strokelogger.ui.theme.AccentGreen
import com.concept2.strokelogger.ui.theme.AccentOrange
import com.concept2.strokelogger.ui.theme.AccentRed
import com.concept2.strokelogger.ui.theme.CarbonCard
import com.concept2.strokelogger.ui.theme.CarbonDark
import com.concept2.strokelogger.ui.theme.TextMuted
import com.concept2.strokelogger.ui.theme.TextPrimary
import com.concept2.strokelogger.usb.LiveMetrics
import java.util.Locale

/**
 * Main dashboard screen displaying real-time Watts, SPM, live force curve,
 * and workout recording controls.
 */
@Composable
fun LiveMonitorScreen(
    isConnected: Boolean,
    isRecording: Boolean,
    metrics: LiveMetrics,
    activeSession: WorkoutSession?,
    onConnectUsb: () -> Unit,
    onStartWorkout: () -> Unit,
    onFinishWorkout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val elapsedMinutes = metrics.elapsedSeconds / 60
    val elapsedSecs = metrics.elapsedSeconds % 60
    val formattedTime = String.format(Locale.US, "%02d:%02d", elapsedMinutes, elapsedSecs)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CarbonDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ── Top Connection Status Bar ──────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (isConnected) AccentGreen else AccentRed,
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isConnected) "PM5 CONNECTED (USB)" else "NO PM5 (CONNECT USB-OTG)",
                    color = if (isConnected) AccentGreen else TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }

            if (!isConnected) {
                OutlinedButton(
                    onClick = onConnectUsb,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("Connect", fontSize = 12.sp, color = AccentCyan)
                }
            } else if (isRecording) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "☀️ AWAKE",
                        color = AccentCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "● REC",
                        color = AccentRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Text(
                    text = "☀️ AWAKE",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (!isConnected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CarbonCard, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = "📱 ${android.os.Build.MANUFACTURER.uppercase()} ${android.os.Build.MODEL} • Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
                        color = AccentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "App initialized successfully. Connect your USB-OTG cable to the Concept2 PM5 monitor and tap Connect.",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // ── Primary Athletic Gauges (Watts & SPM) ─────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Mechanical Power in Watts (prominent, per coaching rule)
            MetricTile(
                label = "Power",
                value = if (metrics.watts > 0) "${metrics.watts}" else "--",
                unit = "W",
                accentColor = AccentCyan,
                isLarge = true,
                modifier = Modifier.weight(1.3f)
            )

            // Cadence (SPM)
            MetricTile(
                label = "Cadence",
                value = if (metrics.spm > 0) "${metrics.spm}" else "--",
                unit = "SPM",
                accentColor = AccentGreen,
                isLarge = true,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ── Live Force Curve Vector Canvas ────────────────────────────────────
        LiveForceCurveCanvas(
            forceMap = metrics.lastForceCurve,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        // ── Secondary Telemetry Grid ──────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricTile(
                    label = "Strokes",
                    value = "${metrics.strokeCount}",
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "Work Time",
                    value = formattedTime,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricTile(
                    label = "Distance",
                    value = "${metrics.distanceMeters.toInt()}",
                    unit = "m",
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "Heart Rate",
                    value = if (metrics.heartRate > 0) "${metrics.heartRate}" else "--",
                    unit = "bpm",
                    accentColor = if (metrics.heartRate > 0) AccentOrange else TextMuted,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "Drag",
                    value = if (metrics.dragFactor > 0) "${metrics.dragFactor}" else "--",
                    modifier = Modifier.weight(0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── Action Controls ───────────────────────────────────────────────────
        if (!isRecording) {
            Button(
                onClick = onStartWorkout,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    text = "START WORKOUT",
                    color = CarbonCard,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        } else {
            Button(
                onClick = onFinishWorkout,
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    text = "FINISH & EXPORT",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
