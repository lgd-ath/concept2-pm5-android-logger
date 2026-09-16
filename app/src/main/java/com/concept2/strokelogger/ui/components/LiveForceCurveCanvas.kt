package com.concept2.strokelogger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.concept2.strokelogger.ui.theme.AccentCyan
import com.concept2.strokelogger.ui.theme.AccentOrange
import com.concept2.strokelogger.ui.theme.CarbonBorder
import com.concept2.strokelogger.ui.theme.CarbonCard
import com.concept2.strokelogger.ui.theme.CurveFill
import com.concept2.strokelogger.ui.theme.TextMuted

/**
 * Real-time 60 FPS vector canvas rendering the discrete PM5 handle force curve
 * (Newtons vs Drive Duration) matching the Concept2 PM5 monitor and Laurent's coach workstation.
 */
@Composable
fun LiveForceCurveCanvas(
    forceMap: List<Int>,
    modifier: Modifier = Modifier
) {
    val peakForce = if (forceMap.isNotEmpty()) forceMap.maxOrNull() ?: 0 else 0

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(CarbonCard, RoundedCornerShape(12.dp))
            .border(1.dp, CarbonBorder, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val width = size.width
            val height = size.height

            // Baseline & grid lines
            drawLine(
                color = CarbonBorder,
                start = Offset(0f, height),
                end = Offset(width, height),
                strokeWidth = 2f
            )
            drawLine(
                color = CarbonBorder.copy(alpha = 0.5f),
                start = Offset(0f, height * 0.5f),
                end = Offset(width, height * 0.5f),
                strokeWidth = 1f
            )

            if (forceMap.size >= 2) {
                val maxVal = (peakForce * 1.15f).coerceAtLeast(300f)
                val stepX = width / (forceMap.size - 1)

                val linePath = Path()
                val fillPath = Path()

                fillPath.moveTo(0f, height)

                forceMap.forEachIndexed { index, force ->
                    val x = index * stepX
                    val normalizedY = (force / maxVal).coerceIn(0f, 1f)
                    val y = height - (normalizedY * height)

                    if (index == 0) {
                        linePath.moveTo(x, y)
                        fillPath.lineTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }

                fillPath.lineTo(width, height)
                fillPath.close()

                // Translucent gradient fill under curve
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(AccentCyan.copy(alpha = 0.35f), CurveFill.copy(alpha = 0.05f)),
                        startY = 0f,
                        endY = height
                    )
                )

                // High-visibility vector stroke line
                drawPath(
                    path = linePath,
                    color = AccentCyan,
                    style = Stroke(width = 5f, cap = StrokeCap.Round)
                )

                // Peak Force marker
                val peakIndex = forceMap.indexOf(peakForce)
                if (peakIndex >= 0 && peakForce > 0) {
                    val peakX = peakIndex * stepX
                    val peakY = height - ((peakForce / maxVal) * height)

                    drawCircle(
                        color = AccentOrange,
                        radius = 8f,
                        center = Offset(peakX, peakY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 4f,
                        center = Offset(peakX, peakY)
                    )
                }
            }
        }

        // Top labels
        Text(
            text = "FORCE CURVE (N)",
            color = TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopStart)
        )

        if (peakForce > 0) {
            Text(
                text = "Peak: $peakForce N",
                color = AccentOrange,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        } else if (forceMap.isEmpty()) {
            Text(
                text = "Waiting for first stroke…",
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
