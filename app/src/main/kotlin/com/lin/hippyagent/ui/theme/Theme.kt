package com.lin.hippyagent.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val WarmOrange = Color(0xFFE8763B)
private val WarmOrangeLight = Color(0xFFF5A66E)
private val WarmOrangeDark = Color(0xFFC45A20)

private val LightColorScheme = lightColorScheme(
    primary = WarmOrange,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFEDE3),
    secondary = Color(0xFF6B5B50),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF5EDE7),
    tertiary = Color(0xFF7D6656),
    background = Color(0xFFFAF8F6),
    onBackground = Color(0xFF1C1B1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1A),
    surfaceVariant = Color(0xFFF3EDE7),
    onSurfaceVariant = Color(0xFF7A6E64),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFFC4B8AD),
    outlineVariant = Color(0xFFE0D6CC),
)

private val DarkColorScheme = darkColorScheme(
    primary = WarmOrangeLight,
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFFC45A20),
    secondary = Color(0xFFD7C3B4),
    onSecondary = Color(0xFF3B2E24),
    secondaryContainer = Color(0xFF524339),
    tertiary = Color(0xFFBBA695),
    background = Color(0xFF1C1B1A),
    onBackground = Color(0xFFE6E1DC),
    surface = Color(0xFF242220),
    onSurface = Color(0xFFE6E1DC),
    surfaceVariant = Color(0xFF3A3330),
    onSurfaceVariant = Color(0xFFC4B8AD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFF5A524A),
    outlineVariant = Color(0xFF3A3330),
)

private val NotionTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    titleLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp),
)

@Composable
fun HippyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current

    val appFontScale = context.getSharedPreferences("ui_settings", android.content.Context.MODE_PRIVATE).let { prefs ->
        if (prefs.contains("app_font_scale")) {
            prefs.getFloat("app_font_scale", 1.0f)
        } else {
            context.resources.configuration.fontScale.coerceIn(0.5f, 2.0f)
        }
    }

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = scaledTypography(NotionTypography, appFontScale),
        content = content
    )
}

private fun scaledTypography(base: Typography, scale: Float): Typography {
    fun scaled(value: Float): TextUnit = (value * scale).sp
    return base.copy(
        headlineLarge = base.headlineLarge.copy(fontSize = scaled(32f), lineHeight = scaled(40f)),
        headlineMedium = base.headlineMedium.copy(fontSize = scaled(28f), lineHeight = scaled(36f)),
        headlineSmall = base.headlineSmall.copy(fontSize = scaled(24f), lineHeight = scaled(32f)),
        titleLarge = base.titleLarge.copy(fontSize = scaled(22f), lineHeight = scaled(28f)),
        titleMedium = base.titleMedium.copy(fontSize = scaled(16f), lineHeight = scaled(24f)),
        titleSmall = base.titleSmall.copy(fontSize = scaled(14f), lineHeight = scaled(20f)),
        bodyLarge = base.bodyLarge.copy(fontSize = scaled(16f), lineHeight = scaled(24f)),
        bodyMedium = base.bodyMedium.copy(fontSize = scaled(14f), lineHeight = scaled(20f)),
        bodySmall = base.bodySmall.copy(fontSize = scaled(12f), lineHeight = scaled(16f)),
        labelLarge = base.labelLarge.copy(fontSize = scaled(14f), lineHeight = scaled(20f)),
        labelMedium = base.labelMedium.copy(fontSize = scaled(12f), lineHeight = scaled(16f)),
        labelSmall = base.labelSmall.copy(fontSize = scaled(11f), lineHeight = scaled(16f))
    )
}

