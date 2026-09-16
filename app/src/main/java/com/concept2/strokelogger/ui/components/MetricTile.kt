package com.concept2.strokelogger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.concept2.strokelogger.ui.theme.CarbonBorder
import com.concept2.strokelogger.ui.theme.CarbonCard
import com.concept2.strokelogger.ui.theme.TextMuted
import com.concept2.strokelogger.ui.theme.TextPrimary

/**
 * High-contrast athletic metric tile designed for glanceability during high-intensity erging.
 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String = "",
    accentColor: Color = TextPrimary,
    isLarge: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CarbonCard, RoundedCornerShape(12.dp))
            .border(1.dp, CarbonBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label.uppercase(),
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Start
        ) {
            Text(
                text = value,
                color = accentColor,
                fontSize = if (isLarge) 42.sp else 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = " $unit",
                    color = TextMuted,
                    fontSize = if (isLarge) 16.sp else 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = if (isLarge) 8.dp else 4.dp)
                )
            }
        }
    }
}
