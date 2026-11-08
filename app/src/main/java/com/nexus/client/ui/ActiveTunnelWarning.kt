package com.nexus.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.client.tunnel.ConnectionPhase
import com.nexus.client.tunnel.TunnelSnapshot

// Light warm amber warning styling tokens
val ActiveTunnelWarningSurface = Color(0xFFFFF7E6)
val ActiveTunnelWarningBorder = Color(0xFFFFCA78)
val ActiveTunnelWarningContent = Color(0xFF9C6500)

/**
 * Evaluates whether an active tunnel has an informational warning to display.
 */
internal fun activeTunnelWarning(snapshot: TunnelSnapshot): String? =
    if (snapshot.tunnelActive && snapshot.phase != ConnectionPhase.ERROR) {
        snapshot.error?.takeUnless(String::isBlank)
    } else null

/**
 * Restyled light warning banner component for active tunnel notices.
 */
@Composable
fun ActiveTunnelWarningBanner(
    warning: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ActiveTunnelWarningSurface,
        border = BorderStroke(1.dp, ActiveTunnelWarningBorder),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Canvas(modifier = Modifier.size(16.dp)) {
                val stroke = 1.6.dp.toPx()
                val path = Path().apply {
                    moveTo(size.width * 0.5f, size.height * 0.1f)
                    lineTo(size.width * 0.92f, size.height * 0.9f)
                    lineTo(size.width * 0.08f, size.height * 0.9f)
                    close()
                }
                drawPath(path, ActiveTunnelWarningContent, style = Stroke(stroke, cap = StrokeCap.Round))
                drawLine(
                    ActiveTunnelWarningContent,
                    Offset(size.width * 0.5f, size.height * 0.38f),
                    Offset(size.width * 0.5f, size.height * 0.62f),
                    stroke,
                    cap = StrokeCap.Round
                )
                drawCircle(
                    ActiveTunnelWarningContent,
                    radius = 1.2.dp.toPx(),
                    center = Offset(size.width * 0.5f, size.height * 0.76f)
                )
            }
            Text(
                text = warning,
                color = ActiveTunnelWarningContent,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

