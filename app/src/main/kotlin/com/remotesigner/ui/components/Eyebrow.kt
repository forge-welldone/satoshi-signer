package com.remotesigner.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.remotesigner.ui.theme.LocalVaultColors
import com.remotesigner.ui.theme.LocalVaultTypography
import java.util.Locale

@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    Text(
        text = text.uppercase(Locale.ROOT),
        style = typography.eyebrow.copy(color = color ?: colors.textMute),
        modifier = modifier,
    )
}
