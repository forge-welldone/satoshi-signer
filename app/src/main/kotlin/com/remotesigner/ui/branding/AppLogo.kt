package com.remotesigner.ui.branding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val LogoStart = Color(0xFFD97706)
private val LogoEnd = Color(0xFFE11D48)

/**
 * Faithful port of the prototype `Logo()` (`docs/icon.svg`) — gradient
 * rounded square, bracketed inner chip with side ticks, white check.
 *
 * The whole thing is drawn into a `viewportSize=108` coordinate space and
 * scaled to the requested `size`.
 */
@Composable
fun AppLogo(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.width / 108f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)

        val gradient = Brush.linearGradient(
            colors = listOf(LogoStart, LogoEnd),
            start = p(0f, 0f),
            end = p(108f, 108f),
        )

        // Background rounded square (radius 24)
        val bg = Path().apply {
            val r = 24f * s
            val w = 108f * s
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 0f,
                    top = 0f,
                    right = w,
                    bottom = w,
                    radiusX = r,
                    radiusY = r,
                ),
            )
        }
        drawPath(bg, gradient)

        val white = Color.White

        // Bracketed inner chip — superellipse-ish rounded square 32→76
        val chip = Path().apply {
            moveTo(40f * s, 32f * s)
            lineTo(68f * s, 32f * s)
            quadraticTo(76f * s, 32f * s, 76f * s, 40f * s)
            lineTo(76f * s, 68f * s)
            quadraticTo(76f * s, 76f * s, 68f * s, 76f * s)
            lineTo(40f * s, 76f * s)
            quadraticTo(32f * s, 76f * s, 32f * s, 68f * s)
            lineTo(32f * s, 40f * s)
            quadraticTo(32f * s, 32f * s, 40f * s, 32f * s)
            close()
        }
        drawPath(
            chip,
            color = white,
            style = Stroke(width = 2.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Side ticks (4 sides × 3 ticks)
        val tickStroke = Stroke(width = 2f * s, cap = StrokeCap.Round)
        listOf(44, 54, 64).forEach { y ->
            drawLine(white, p(32f, y.toFloat()), p(22f, y.toFloat()), strokeWidth = tickStroke.width, cap = tickStroke.cap)
            drawLine(white, p(76f, y.toFloat()), p(86f, y.toFloat()), strokeWidth = tickStroke.width, cap = tickStroke.cap)
        }
        listOf(44, 54, 64).forEach { x ->
            drawLine(white, p(x.toFloat(), 32f), p(x.toFloat(), 22f), strokeWidth = tickStroke.width, cap = tickStroke.cap)
            drawLine(white, p(x.toFloat(), 76f), p(x.toFloat(), 86f), strokeWidth = tickStroke.width, cap = tickStroke.cap)
        }

        // Check (43,54)→(50,61)→(67,44)
        val check = Path().apply {
            moveTo(43f * s, 54f * s)
            lineTo(50f * s, 61f * s)
            lineTo(67f * s, 44f * s)
        }
        drawPath(
            check,
            color = white,
            style = Stroke(width = 3f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
