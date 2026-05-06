package com.remotesigner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private fun materialFromVault(v: VaultColors) = if (v.isDark) {
    darkColorScheme(
        primary = v.accent,
        onPrimary = v.accentInk,
        secondary = v.text,
        onSecondary = v.bg,
        background = v.bg,
        onBackground = v.text,
        surface = v.surface,
        onSurface = v.text,
        surfaceVariant = v.surfaceElev,
        onSurfaceVariant = v.textDim,
        outline = v.line,
        outlineVariant = v.lineStrong,
        error = v.bad,
        onError = v.bg,
    )
} else {
    lightColorScheme(
        primary = v.accent,
        onPrimary = v.accentInk,
        secondary = v.text,
        onSecondary = v.bg,
        background = v.bg,
        onBackground = v.text,
        surface = v.surface,
        onSurface = v.text,
        surfaceVariant = v.surfaceElev,
        onSurfaceVariant = v.textDim,
        outline = v.line,
        outlineVariant = v.lineStrong,
        error = v.bad,
        onError = v.bg,
    )
}

@Composable
fun SatoshiSignerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val vault = if (darkTheme) DarkVaultColors else LightVaultColors
    val typography = DefaultVaultTypography
    CompositionLocalProvider(
        LocalVaultColors provides vault,
        LocalVaultShapes provides DefaultVaultShapes,
        LocalVaultTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = materialFromVault(vault),
            typography = vaultMaterialTypography(typography),
            content = content,
        )
    }
}
