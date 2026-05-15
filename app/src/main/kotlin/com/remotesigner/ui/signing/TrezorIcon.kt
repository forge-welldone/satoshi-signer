package com.remotesigner.ui.signing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.remotesigner.ui.motion.rememberSsPulse

/**
 * 32 dp Trezor body rendered as a stroked rect with the small screen window
 * and the round button. Sits inside the framed 64×64 panel on the signing
 * screen. Matches the SVG in `signing.jsx` (viewBox `0 0 20 30`).
 *
 * When [pulse] is true, an accent-tinted ring fades + scales outward in a
 * 1.4 s loop, driven by [rememberSsPulse].
 */
@Composable
fun TrezorIcon(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    color: Color = Color.White,
    pulse: Boolean = false,
    pulseColor: Color = color,
) {
    Box(modifier = modifier.size(size, size * 30f / 20f)) {
        if (pulse) {
            val progress by rememberSsPulse()
            Canvas(modifier = Modifier.fillMaxSize()) {
                val alpha = (1f - progress).coerceIn(0f, 1f) * 0.7f
                val scale = 1f + progress * 0.55f
                val w = this.size.width * scale
                val h = this.size.height * scale
                val left = (this.size.width - w) / 2f
                val top = (this.size.height - h) / 2f
                drawRoundRect(
                    color = pulseColor.copy(alpha = alpha),
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(10f, 10f),
                    style = Stroke(width = 2f),
                )
            }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val stroke = w * 0.07f
            val bodyLeft = w * 0.125f
            val bodyTop = h * 0.083f
            val bodyW = w * 0.75f
            val bodyH = h * 0.833f
            drawRoundRect(
                color = color,
                topLeft = Offset(bodyLeft, bodyTop),
                size = Size(bodyW, bodyH),
                cornerRadius = CornerRadius(w * 0.175f, w * 0.175f),
                style = Stroke(width = stroke),
            )
            val screenLeft = w * 0.25f
            val screenTop = h * 0.2f
            val screenW = w * 0.5f
            val screenH = h * 0.233f
            drawRoundRect(
                color = color,
                topLeft = Offset(screenLeft, screenTop),
                size = Size(screenW, screenH),
                cornerRadius = CornerRadius(w * 0.05f, w * 0.05f),
                style = Stroke(width = stroke),
            )
            val cx = w / 2f
            val cy = h * 0.7f
            val r = w * 0.1f
            val ring = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r))
            }
            drawPath(ring, color = color, style = Stroke(width = stroke))
        }
    }
}
