package com.remotesigner.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.remotesigner.ui.motion.rememberSsSpin
import com.remotesigner.ui.theme.LocalVaultColors

@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color? = null,
    strokeWidth: Dp = 2.dp,
) {
    val colors = LocalVaultColors.current
    val rotation by rememberSsSpin()
    val tint = color ?: colors.accent
    val stroke = strokeWidth
    // progressSemantics exposes an indeterminate ProgressBarRangeInfo so
    // TalkBack announces the busy state (parity with CircularProgressIndicator).
    Canvas(modifier = modifier.size(size).progressSemantics()) {
        rotate(degrees = rotation) {
            val pad = stroke.toPx()
            val rect = Size(this.size.width - pad * 2, this.size.height - pad * 2)
            val topLeft = Offset(pad, pad)
            drawArc(
                color = tint.copy(alpha = 0.2f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = rect,
                style = Stroke(width = stroke.toPx()),
            )
            drawArc(
                color = tint,
                startAngle = -90f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = rect,
                style = Stroke(width = stroke.toPx()),
            )
        }
    }
}
