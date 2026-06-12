package com.remotesigner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.remotesigner.ui.theme.LocalVaultColors
import java.util.Locale
import com.remotesigner.ui.theme.LocalVaultShapes
import com.remotesigner.ui.theme.LocalVaultTypography

enum class PillTone { Neutral, Good, Warn, Bad, Accent }

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.Neutral,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    val shapes = LocalVaultShapes.current

    val (bg, fg, border) = when (tone) {
        PillTone.Neutral -> Triple(colors.surface, colors.textDim, colors.line)
        PillTone.Good -> Triple(Color.Transparent, colors.good, colors.good)
        PillTone.Warn -> Triple(Color.Transparent, colors.warn, colors.warn)
        PillTone.Bad -> Triple(Color.Transparent, colors.bad, colors.bad)
        PillTone.Accent -> Triple(colors.accent, colors.accentInk, colors.accent)
    }

    Row(
        modifier = modifier
            .clip(shapes.pill)
            .border(1.dp, border, shapes.pill)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (leadingIcon != null) leadingIcon()
        Text(
            text = text.uppercase(Locale.ROOT),
            style = typography.eyebrow.copy(color = fg),
        )
    }
}
