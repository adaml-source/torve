package com.torve.android.ui.splash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.ui.theme.Snow

@Composable
fun TorveLogomark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    showWordmark: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val cx = this.size.width / 2
            val cy = this.size.height / 2
            val r = this.size.width * 0.4f

            // Iris ring
            drawCircle(
                color = Color(0xFFC4922A).copy(alpha = 0.3f),
                radius = r * 1.1f,
                center = Offset(cx, cy),
                style = Stroke(width = 1f),
            )

            // Diamond
            val path = Path().apply {
                moveTo(cx, cy - r)
                lineTo(cx + r, cy)
                lineTo(cx, cy + r)
                lineTo(cx - r, cy)
                close()
            }
            drawPath(
                path,
                Brush.linearGradient(
                    listOf(Color(0xFFECC44E), Color(0xFFB87A22)),
                    start = Offset(cx - r, cy - r),
                    end = Offset(cx + r, cy + r),
                ),
            )

            // Void + core
            drawCircle(color = Color(0xFF050507), radius = r * 0.26f, center = Offset(cx, cy))
            drawCircle(color = Color(0xFFECC44E), radius = r * 0.12f, center = Offset(cx, cy))
        }

        if (showWordmark) {
            Spacer(Modifier.width(10.dp))
            Text(
                "TORVE",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
                color = Snow,
            )
        }
    }
}
