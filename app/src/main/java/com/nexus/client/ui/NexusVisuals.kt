package com.nexus.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.client.tunnel.ConnectionPhase

/**
 * Multi-stop warm stone ambient backdrop gradient matching the neXus Ray Mini App prototype.
 * Gradient: #D5C9B8 -> #C6B8A5 -> #B7A894 with soft ivory top-left highlight and emerald right highlight.
 */
@Composable
fun NexusBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // 1. Warm Stone vertical linear gradient
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color(0xFFD5C9B8),
                0.52f to Color(0xFFC6B8A5),
                1.0f to Color(0xFFB7A894),
                startY = 0f,
                endY = h
            )
        )

        // 2. Soft ivory ambient radial highlight (top-left)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xA6FFFCF5), Color.Transparent),
                center = Offset(w * 0.18f, h * 0.12f),
                radius = w * 0.75f
            )
        )

        // 3. Subtle emerald ambient radial highlight (mid-right)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x242A9D8F), Color.Transparent),
                center = Offset(w * 0.85f, h * 0.38f),
                radius = w * 0.70f
            )
        )
    }
}

/**
 * Circular dark charcoal badge containing the signature outlined E symbol.
 */
@Composable
fun ExBadge(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    badgeColor: Color = Color(0xFF17181A),
    textColor: Color = Color(0xFFF7F5F0),
) {
    Box(
        modifier = modifier
            .size(size)
            .background(badgeColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        ExWgMark(
            modifier = Modifier.size(size * 0.56f),
            color = textColor,
        )
    }
}

/**
 * Compact outlined E symbol used in the launcher icon and header badge.
 */
@Composable
fun ExWgMark(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFF4F5F5),
) {
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * .84f, size.height * .14f)
            lineTo(size.width * .36f, size.height * .14f)
            cubicTo(size.width * .18f, size.height * .14f, size.width * .13f, size.height * .24f, size.width * .13f, size.height * .36f)
            cubicTo(size.width * .13f, size.height * .47f, size.width * .2f, size.height * .51f, size.width * .36f, size.height * .51f)
            lineTo(size.width * .73f, size.height * .51f)
            moveTo(size.width * .73f, size.height * .51f)
            lineTo(size.width * .36f, size.height * .51f)
            cubicTo(size.width * .2f, size.height * .51f, size.width * .13f, size.height * .57f, size.width * .13f, size.height * .69f)
            cubicTo(size.width * .13f, size.height * .82f, size.width * .2f, size.height * .88f, size.width * .36f, size.height * .88f)
            lineTo(size.width * .84f, size.height * .88f)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/**
 * Top header wordmark featuring the dark circular ExBadge and "Ex WG" title in dark charcoal.
 */
@Composable
fun ExWgWordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 28.sp,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ExBadge(size = (fontSize.value * 1.15f).dp)
        Column {
            Text(
                text = "Ex WG",
                style = TextStyle(
                    fontSize = fontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF17181A),
                    letterSpacing = (-0.5).sp,
                ),
                maxLines = 1,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontSize = (fontSize.value * 0.48f).sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF6E6B65),
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Phase-dependent frosted halo ring around the hero ConnectionButton:
 * - CONNECTED: emerald-teal glow (#2FA899) with specular white border
 * - DISCONNECTED: subtle dark charcoal glow (#17181A)
 * - CONNECTING / WAITING: warm amber glow (#E5A93C / #FFCA78)
 */
@Composable
fun ConnectionHalo(
    modifier: Modifier = Modifier,
    phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
) {
    val (glowColors, ringColor) = when (phase) {
        ConnectionPhase.CONNECTED -> Pair(
            listOf(Color(0x382FA899), Color(0x73FFFFFF), Color.Transparent),
            Color(0xEBFFFFFF)
        )
        ConnectionPhase.CONNECTING, ConnectionPhase.WAITING -> Pair(
            listOf(Color(0x4AE5A93C), Color(0x60FFFFFF), Color.Transparent),
            Color(0xFFFFCA78).copy(alpha = 0.75f)
        )
        ConnectionPhase.ERROR -> Pair(
            listOf(Color(0x38B83B2A), Color(0x55FFFFFF), Color.Transparent),
            Color(0xFFB83B2A).copy(alpha = 0.6f)
        )
        ConnectionPhase.DISCONNECTING -> Pair(
            listOf(Color(0x266E6B65), Color(0x55FFFFFF), Color.Transparent),
            Color(0x99FFFFFF)
        )
        ConnectionPhase.DISCONNECTED -> Pair(
            listOf(Color(0x1A17181A), Color(0x55FFFFFF), Color.Transparent),
            Color(0xC0FFFFFF)
        )
    }

    Canvas(modifier) {
        val radius = size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = glowColors,
                radius = radius
            ),
            radius = radius
        )
        drawCircle(
            color = ringColor,
            radius = radius * 0.90f,
            style = Stroke(width = 1.5.dp.toPx())
        )
    }
}

/**
 * Backward-compatible overload for boolean active state.
 */
@Composable
fun ConnectionHalo(
    modifier: Modifier = Modifier,
    active: Boolean,
) {
    ConnectionHalo(
        modifier = modifier,
        phase = if (active) ConnectionPhase.CONNECTED else ConnectionPhase.DISCONNECTED
    )
}

/**
 * Power glyph for hero button.
 */
@Composable
fun ExWgPower(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        drawArc(
            color = color,
            startAngle = -43f,
            sweepAngle = 266f,
            useCenter = false,
            topLeft = Offset(size.width * 0.14f, size.height * 0.14f),
            size = Size(size.width * 0.72f, size.height * 0.72f),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.5f, size.height * 0.05f),
            end = Offset(size.width * 0.5f, size.height * 0.48f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Stacked diamonds profile glyph.
 */
@Composable
fun ExWgProfileGlyph(modifier: Modifier = Modifier, color: Color = Color(0xFF17181A)) {
    Canvas(modifier) {
        val stroke = 1.35.dp.toPx()
        repeat(3) { index ->
            val top = size.height * (0.16f + index * 0.19f)
            val diamond = Path().apply {
                moveTo(size.width * 0.5f, top)
                lineTo(size.width * 0.84f, top + size.height * 0.16f)
                lineTo(size.width * 0.5f, top + size.height * 0.32f)
                lineTo(size.width * 0.16f, top + size.height * 0.16f)
                close()
            }
            drawPath(diamond, color, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/**
 * Clean canvas shield icon rendered with dark charcoal strokes #17181A and emerald accents #197B6E.
 */
@Composable
fun IconShield(
    modifier: Modifier = Modifier,
    strokeColor: Color = Color(0xFF17181A),
    accentColor: Color = Color(0xFF197B6E),
    active: Boolean = false,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()

        val shield = Path().apply {
            moveTo(w * 0.5f, h * 0.08f)
            cubicTo(w * 0.35f, h * 0.18f, w * 0.2f, h * 0.2f, w * 0.12f, h * 0.22f)
            cubicTo(w * 0.13f, h * 0.57f, w * 0.24f, h * 0.8f, w * 0.5f, h * 0.94f)
            cubicTo(w * 0.76f, h * 0.8f, w * 0.87f, h * 0.57f, w * 0.88f, h * 0.22f)
            cubicTo(w * 0.8f, h * 0.2f, w * 0.65f, h * 0.18f, w * 0.5f, h * 0.08f)
            close()
        }
        drawPath(shield, strokeColor, style = Stroke(stroke, cap = StrokeCap.Round))

        if (active) {
            val check = Path().apply {
                moveTo(w * 0.32f, h * 0.50f)
                lineTo(w * 0.45f, h * 0.63f)
                lineTo(w * 0.68f, h * 0.38f)
            }
            drawPath(check, accentColor, style = Stroke(stroke * 1.2f, cap = StrokeCap.Round))
        } else {
            drawCircle(accentColor, radius = 2.2.dp.toPx(), center = Offset(w * 0.5f, h * 0.52f))
        }
    }
}

/**
 * Clean canvas routing icon with dark charcoal strokes #17181A and emerald accents #197B6E.
 */
@Composable
fun IconRouting(
    modifier: Modifier = Modifier,
    strokeColor: Color = Color(0xFF17181A),
    accentColor: Color = Color(0xFF197B6E),
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()

        listOf(0.28f, 0.50f, 0.72f).forEachIndexed { i, y ->
            val nodeColor = if (i == 1) accentColor else strokeColor
            val railColor = if (i == 1) accentColor else strokeColor
            drawCircle(nodeColor, radius = 2.dp.toPx(), center = Offset(w * 0.22f, h * y))
            drawLine(
                railColor,
                start = Offset(w * 0.38f, h * y),
                end = Offset(w * 0.82f, h * y),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Clean canvas diagnostics pulse icon with dark charcoal strokes #17181A and emerald accents #197B6E.
 */
@Composable
fun IconDiagnostics(
    modifier: Modifier = Modifier,
    strokeColor: Color = Color(0xFF17181A),
    accentColor: Color = Color(0xFF197B6E),
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()

        val pulse = Path().apply {
            moveTo(w * 0.08f, h * 0.54f)
            lineTo(w * 0.28f, h * 0.54f)
            lineTo(w * 0.39f, h * 0.24f)
            lineTo(w * 0.54f, h * 0.78f)
            lineTo(w * 0.66f, h * 0.44f)
            lineTo(w * 0.92f, h * 0.44f)
        }
        drawPath(pulse, strokeColor, style = Stroke(stroke, cap = StrokeCap.Round))
        drawCircle(accentColor, radius = 2.5.dp.toPx(), center = Offset(w * 0.39f, h * 0.24f))
    }
}

/**
 * Shield drawing for backward compatibility.
 */
@Composable
fun NexusShield(modifier: Modifier = Modifier, connected: Boolean = false) {
    IconShield(
        modifier = modifier,
        strokeColor = Color(0xFF17181A),
        accentColor = Color(0xFF197B6E),
        active = connected
    )
}
