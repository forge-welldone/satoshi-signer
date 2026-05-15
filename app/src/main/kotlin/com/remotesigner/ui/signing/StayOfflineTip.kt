package com.remotesigner.ui.signing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.remotesigner.ui.components.Eyebrow
import com.remotesigner.ui.theme.LocalVaultColors
import com.remotesigner.ui.theme.LocalVaultShapes
import com.remotesigner.ui.theme.LocalVaultTypography

@Composable
fun StayOfflineTip(modifier: Modifier = Modifier) {
    val colors = LocalVaultColors.current
    val shapes = LocalVaultShapes.current
    val typography = LocalVaultTypography.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.card)
            .border(1.dp, colors.line, shapes.card)
            .background(colors.surfaceSunken)
            .padding(14.dp),
    ) {
        Eyebrow(text = "Stay offline")
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Your private keys never leave the Trezor. Signing works with Wi-Fi and cellular off — only broadcasting needs the network.",
            style = typography.bodyDim.copy(color = colors.textDim),
        )
    }
}
