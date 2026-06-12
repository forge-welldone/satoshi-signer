package com.remotesigner.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class VaultShapes(
    val radius: Dp,
    val radiusCard: Dp,
    val radiusPill: Dp,
) {
    val small = RoundedCornerShape(radius)
    val card = RoundedCornerShape(radiusCard)
    val pill = RoundedCornerShape(radiusPill)
}

val DefaultVaultShapes = VaultShapes(
    radius = 10.dp,
    radiusCard = 16.dp,
    radiusPill = 999.dp,
)

val LocalVaultShapes = staticCompositionLocalOf { DefaultVaultShapes }
