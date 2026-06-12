package com.remotesigner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

// Bridge for the not-yet-migrated Material3 screens: every slot those screens
// read must resolve to a Vault color, otherwise M3 baseline (purple-tinted)
// defaults leak through. Single mapping over the base scheme so dark and light
// can't drift apart.
//
// Slot notes:
// - `secondary` colors the signed/broadcast inbox chips -> good (success).
// - `tertiary` colors the in-progress "signing" chip -> warn.
// - `outline` borders OutlinedTextField/OutlinedButton and is read as text by
//   the "deleted" chip, so it must be opaque -> textMute (v.line at 8% alpha
//   is invisible as a border and unreadable as text).
// - `errorContainer` backs PSBT-warning and broadcast-failure cards -> bad
//   tinted like the Pill tones, with full-strength bad for the text on it.
// - `surfaceContainer*` back filled Cards and AlertDialogs in material3 1.3.
private fun materialFromVault(v: VaultColors) =
    (if (v.isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = v.accent,
        onPrimary = v.accentInk,
        secondary = v.good,
        onSecondary = v.bg,
        tertiary = v.warn,
        onTertiary = v.bg,
        background = v.bg,
        onBackground = v.text,
        surface = v.surface,
        onSurface = v.text,
        surfaceVariant = v.surfaceElev,
        onSurfaceVariant = v.textDim,
        surfaceContainerLowest = v.surfaceSunken,
        surfaceContainerLow = v.surface,
        surfaceContainer = v.surface,
        surfaceContainerHigh = v.surfaceElev,
        surfaceContainerHighest = v.surfaceElev,
        outline = v.textMute,
        outlineVariant = v.line,
        error = v.bad,
        onError = v.bg,
        errorContainer = v.bad.copy(alpha = 0.12f),
        onErrorContainer = v.bad,
    )

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
