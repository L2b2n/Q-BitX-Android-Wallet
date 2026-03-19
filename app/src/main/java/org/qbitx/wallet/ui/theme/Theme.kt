package org.qbitx.wallet.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val QBXColorScheme = darkColorScheme(
    primary = QBXPurple,
    onPrimary = QBXOnSurface,
    secondary = QBXBlue,
    onSecondary = QBXOnSurface,
    tertiary = QBXCyan,
    background = QBXBackground,
    surface = QBXSurface,
    surfaceVariant = QBXCardDark,
    onSurface = QBXOnSurface,
    onSurfaceVariant = QBXOnSurfaceDim,
    error = QBXRed,
    onError = QBXOnSurface,
    outline = QBXDivider,
    outlineVariant = QBXDivider
)

private val QBXTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.25).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)

@Composable
fun QBitXWalletTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = QBXColorScheme,
        typography = QBXTypography,
        content = content
    )
}
