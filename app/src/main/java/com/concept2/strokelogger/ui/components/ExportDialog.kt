package com.concept2.strokelogger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.concept2.strokelogger.data.model.WorkoutSession
import com.concept2.strokelogger.ui.theme.AccentCyan
import com.concept2.strokelogger.ui.theme.AccentGreen
import com.concept2.strokelogger.ui.theme.CarbonBorder
import com.concept2.strokelogger.ui.theme.CarbonCard
import com.concept2.strokelogger.ui.theme.TextMuted
import com.concept2.strokelogger.ui.theme.TextPrimary

/**
 * Post-workout export modal offering one-tap Google Drive upload and local storage export.
 */
@Composable
fun ExportDialog(
    session: WorkoutSession,
    onShareCsv: () -> Unit,
    onSaveToGoogleDrive: () -> Unit,
    onSaveToDocuments: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CarbonCard, RoundedCornerShape(16.dp))
                .border(1.dp, CarbonBorder, RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "WORKOUT COMPLETE",
                color = AccentGreen,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Summary stats grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SummaryStat("Strokes", "${session.strokeCount}")
                SummaryStat("Avg Power", "${session.avgWatts} W")
                SummaryStat("Avg SPM", "${session.avgSpm}")
                SummaryStat("Distance", "${session.totalMeters.toInt()} m")
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Session auto-saved to Documents/ErgoSessions",
                color = AccentGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Primary CSV export for Stroke-by-Stroke Web Analyzer
            Button(
                onClick = onShareCsv,
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "📊  Export CSV (for Website Tool)",
                    color = CarbonCard,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Secondary Google Drive bundle upload
            OutlinedButton(
                onClick = onSaveToGoogleDrive,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Text(
                    text = "☁  Upload Bundle to Google Drive (CSV + JSON)",
                    color = TextPrimary,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onDismiss) {
                Text(text = "Close", color = TextMuted)
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = TextMuted, fontSize = 11.sp)
        Text(text = value, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
