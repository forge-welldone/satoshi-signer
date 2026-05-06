package com.remotesigner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.remotesigner.ui.theme.LocalVaultColors
import com.remotesigner.ui.theme.LocalVaultShapes

@Composable
fun VaultCard(
    modifier: Modifier = Modifier,
    pad: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    val colors = LocalVaultColors.current
    val shapes = LocalVaultShapes.current
    Box(
        modifier = modifier
            .clip(shapes.card)
            .border(1.dp, colors.line, shapes.card)
            .background(colors.surface)
            .padding(pad),
    ) {
        content()
    }
}
