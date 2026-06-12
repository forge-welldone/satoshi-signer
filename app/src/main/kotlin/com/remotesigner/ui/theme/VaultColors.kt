package com.remotesigner.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class VaultColors(
    val bg: Color,
    val surface: Color,
    val surfaceElev: Color,
    val surfaceSunken: Color,
    val text: Color,
    val textDim: Color,
    val textMute: Color,
    val line: Color,
    val lineStrong: Color,
    val accent: Color,
    val accentInk: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val isDark: Boolean,
)

val DarkVaultColors = VaultColors(
    bg = Color(0xFF0A0A0A),
    surface = Color(0xFF131313),
    surfaceElev = Color(0xFF1A1A1A),
    surfaceSunken = Color(0xFF050505),
    text = Color(0xFFF5F5F5),
    textDim = Color(0xFF9A9A9A),
    textMute = Color(0xFF5A5A5A),
    line = Color(0x14FFFFFF),
    lineStrong = Color(0x2EFFFFFF),
    accent = Color(0xFFF7931A),
    accentInk = Color(0xFF0A0A0A),
    good = Color(0xFF6FE39A),
    warn = Color(0xFFF5B84C),
    bad = Color(0xFFFF6B6B),
    isDark = true,
)

val LightVaultColors = VaultColors(
    bg = Color(0xFFFAFAF9),
    surface = Color(0xFFFFFFFF),
    surfaceElev = Color(0xFFF5F5F4),
    surfaceSunken = Color(0xFFEFEFED),
    text = Color(0xFF0A0A0A),
    textDim = Color(0xFF57534E),
    textMute = Color(0xFFA8A29E),
    line = Color(0x14000000),
    lineStrong = Color(0x2E000000),
    accent = Color(0xFFF7931A),
    accentInk = Color(0xFF0A0A0A),
    good = Color(0xFF2E9F6A),
    warn = Color(0xFFC58A20),
    bad = Color(0xFFD64545),
    isDark = false,
)

val LocalVaultColors = staticCompositionLocalOf<VaultColors> {
    error("VaultColors not provided. Wrap UI in SatoshiSignerTheme.")
}
