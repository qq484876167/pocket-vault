package com.qoder.pocketvault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Emerald = Color(0xFF0F6E56)
private val EmeraldLight = Color(0xFF3E9B7C)
private val EmeraldPale = Color(0xFFDCF2E9)
private val Ink = Color(0xFF141A19)
private val Sand = Color(0xFFF7F4EE)
private val Amber = Color(0xFFB4690E)
private val Danger = Color(0xFFB3261E)

private val LightScheme = lightColorScheme(
    primary = Emerald,
    onPrimary = Color.White,
    primaryContainer = EmeraldPale,
    onPrimaryContainer = Ink,
    secondary = EmeraldLight,
    surface = Sand,
    background = Sand,
    onSurface = Ink,
    onBackground = Ink,
    error = Danger,
)

private val DarkScheme = darkColorScheme(
    primary = EmeraldLight,
    onPrimary = Color(0xFF06231A),
    primaryContainer = Color(0xFF16402F),
    onPrimaryContainer = Color(0xFFD6F5E7),
    secondary = Color(0xFF8FD8BC),
    background = Color(0xFF0F1312),
    surface = Color(0xFF161B19),
    onSurface = Color(0xFFE3E7E4),
    error = Color(0xFFFFB4AB),
)

private val VaultTypography = Typography(
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 13.5.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

@Composable
fun PocketVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = VaultTypography,
        content = content,
    )
}

/** 类型标签的固定配色，保证图片/视频/音频在列表里一眼可分。 */
object KindColors {
    val image = Color(0xFF2E7D6B)
    val video = Color(0xFF8E4585)
    val audio = Color(0xFF2F6FB5)
    val document = Color(0xFFB4690E)
    val archive = Color(0xFF7A6B4F)
    val folder = Color(0xFF0F6E56)
    val other = Color(0xFF6B7069)
}
