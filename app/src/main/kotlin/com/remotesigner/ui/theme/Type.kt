package com.remotesigner.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.remotesigner.R

// Geist + Geist Mono are bundled at Regular and Medium (the two weights the
// Vault prototype uses — display headings target weight 500). TTFs taken
// from the open-source Vercel Geist font under SIL OFL 1.1.
val GeistFamily = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
)

val GeistMonoFamily = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
)

@Immutable
data class VaultTypography(
    val display: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodyDim: TextStyle,
    val caption: TextStyle,
    val mono: TextStyle,
    val monoSmall: TextStyle,
    val eyebrow: TextStyle,
)

// Every style sets an explicit lineHeight: replacing the M3 defaults (which
// all carry one) with lineHeight-less styles would silently tighten multi-line
// text everywhere to font-metric spacing.
fun buildVaultTypography(): VaultTypography = VaultTypography(
    display = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.03).em,
    ),
    title = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.02).em,
    ),
    body = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.01).em,
    ),
    bodyDim = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.01).em,
    ),
    caption = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
    mono = TextStyle(
        fontFamily = GeistMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    monoSmall = TextStyle(
        fontFamily = GeistMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
    eyebrow = TextStyle(
        fontFamily = GeistMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.14.em,
    ),
)

val DefaultVaultTypography = buildVaultTypography()

val LocalVaultTypography = staticCompositionLocalOf { DefaultVaultTypography }

// Copies that change fontSize must also re-set lineHeight, or they would
// inherit the base style's (now larger/smaller) line height verbatim.
fun vaultMaterialTypography(t: VaultTypography): Typography = Typography(
    displayLarge = t.display,
    displayMedium = t.display.copy(fontSize = 32.sp, lineHeight = 36.sp),
    displaySmall = t.display.copy(fontSize = 24.sp, lineHeight = 28.sp),
    headlineLarge = t.title.copy(fontSize = 22.sp, lineHeight = 28.sp),
    headlineMedium = t.title.copy(fontSize = 19.sp, lineHeight = 26.sp),
    headlineSmall = t.title,
    titleLarge = t.title,
    titleMedium = t.title.copy(fontSize = 15.sp, lineHeight = 22.sp),
    titleSmall = t.bodyDim.copy(fontWeight = FontWeight.Medium),
    bodyLarge = t.body.copy(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = t.body,
    bodySmall = t.bodyDim,
    labelLarge = t.body.copy(fontWeight = FontWeight.Medium),
    labelMedium = t.bodyDim.copy(fontWeight = FontWeight.Medium),
    labelSmall = t.caption,
)
