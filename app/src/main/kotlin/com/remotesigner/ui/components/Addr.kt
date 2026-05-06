package com.remotesigner.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.remotesigner.ui.theme.LocalVaultColors
import com.remotesigner.ui.theme.LocalVaultTypography

@Composable
fun Addr(
    value: String,
    modifier: Modifier = Modifier,
    head: Int = 8,
    tail: Int = 8,
    color: Color? = null,
) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    val display = if (value.length > head + tail + 3) {
        "${value.take(head)}…${value.takeLast(tail)}"
    } else {
        value
    }
    Text(
        text = display,
        style = typography.mono.copy(color = color ?: colors.text),
        modifier = modifier,
    )
}
