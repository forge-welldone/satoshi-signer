package com.remotesigner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.remotesigner.ui.icons.AppIcons
import com.remotesigner.ui.theme.LocalVaultColors
import com.remotesigner.ui.theme.LocalVaultShapes
import com.remotesigner.ui.theme.LocalVaultTypography

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    right: (@Composable () -> Unit)? = null,
) {
    val colors = LocalVaultColors.current
    val typography = LocalVaultTypography.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bg)
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            BackButton(onBack)
        }
        Text(
            text = title,
            style = typography.title.copy(color = colors.text),
            modifier = Modifier.weight(1f),
        )
        if (right != null) right()
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    val colors = LocalVaultColors.current
    val shapes = LocalVaultShapes.current
    val painter = rememberVectorPainter(image = AppIcons.ChevronLeft)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shapes.small)
            .border(1.dp, colors.line, shapes.small)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = painter,
            contentDescription = "Back",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(colors.text),
            modifier = Modifier.size(16.dp),
        )
    }
}
