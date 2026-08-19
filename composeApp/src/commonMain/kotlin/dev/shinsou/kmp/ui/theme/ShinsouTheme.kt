package dev.shinsou.kmp.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ShinsouLightColors = lightColorScheme(
    primary = Color(0xFF5569A9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF172452),
    secondary = Color(0xFF5C5F72),
    secondaryContainer = Color(0xFFE1E2F9),
    tertiary = Color(0xFF78536E),
    onBackground = Color(0xFF1A1B20),
    onSurface = Color(0xFF1A1B20),
    onSurfaceVariant = Color(0xFF45464F),
    background = Color(0xFFF9F9FC),
    surface = Color(0xFFF9F9FC),
    surfaceVariant = Color(0xFFE4E4EA),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C6CC),
    error = Color(0xFFBA1A1A),
)

private val ShinsouDarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF253A78),
    primaryContainer = Color(0xFF3D518F),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFC5C5DD),
    secondaryContainer = Color(0xFF454659),
    tertiary = Color(0xFFE7BAD8),
    onBackground = Color(0xFFE4E2E9),
    onSurface = Color(0xFFE4E2E9),
    onSurfaceVariant = Color(0xFFC6C5CE),
    background = Color(0xFF121316),
    surface = Color(0xFF121316),
    surfaceVariant = Color(0xFF46464D),
    outline = Color(0xFF909099),
    outlineVariant = Color(0xFF46464D),
    error = Color(0xFFFFB4AB),
)

private val ShinsouAmoledColors = ShinsouDarkColors.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainer = Color(0xFF0B0B0D),
    surfaceContainerLow = Color(0xFF070709),
    surfaceContainerHigh = Color(0xFF171719),
)

enum class ShinsouThemeMode {
    System,
    Light,
    Dark,
}

@Immutable
data class ShinsouMetrics(
    val compact: Boolean,
    val sidebarWidth: androidx.compose.ui.unit.Dp = if (compact) 208.dp else 228.dp,
    val contentMaxWidth: androidx.compose.ui.unit.Dp = 1180.dp,
    val cardRadius: androidx.compose.ui.unit.Dp = if (compact) 12.dp else 14.dp,
    val controlHeight: androidx.compose.ui.unit.Dp = if (compact) 34.dp else 40.dp,
)

val LocalShinsouMetrics = staticCompositionLocalOf { ShinsouMetrics(compact = false) }

@Composable
fun ShinsouTheme(
    mode: ShinsouThemeMode = ShinsouThemeMode.System,
    amoled: Boolean = false,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ShinsouThemeMode.System -> isSystemInDarkTheme()
        ShinsouThemeMode.Light -> false
        ShinsouThemeMode.Dark -> true
    }
    val targetScheme = when {
        dark && amoled -> ShinsouAmoledColors
        dark -> ShinsouDarkColors
        else -> ShinsouLightColors
    }
    val animatedScheme = targetScheme.animated()

    CompositionLocalProvider(LocalShinsouMetrics provides ShinsouMetrics(compact)) {
        MaterialTheme(
            colorScheme = animatedScheme,
            typography = MaterialTheme.typography.copy(
                displaySmall = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                ),
                headlineMedium = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                ),
                titleLarge = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 25.sp,
                ),
                titleMedium = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                ),
                bodyMedium = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                labelLarge = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
            ),
            content = content,
        )
    }
}

@Composable
private fun ColorScheme.animated(): ColorScheme = copy(
    primary = animateColorAsState(primary, label = "theme-primary").value,
    onPrimary = animateColorAsState(onPrimary, label = "theme-on-primary").value,
    primaryContainer = animateColorAsState(primaryContainer, label = "theme-primary-container").value,
    onPrimaryContainer = animateColorAsState(onPrimaryContainer, label = "theme-on-primary-container").value,
    secondary = animateColorAsState(secondary, label = "theme-secondary").value,
    onSecondary = animateColorAsState(onSecondary, label = "theme-on-secondary").value,
    secondaryContainer = animateColorAsState(secondaryContainer, label = "theme-secondary-container").value,
    onSecondaryContainer = animateColorAsState(onSecondaryContainer, label = "theme-on-secondary-container").value,
    tertiary = animateColorAsState(tertiary, label = "theme-tertiary").value,
    onTertiary = animateColorAsState(onTertiary, label = "theme-on-tertiary").value,
    tertiaryContainer = animateColorAsState(tertiaryContainer, label = "theme-tertiary-container").value,
    onTertiaryContainer = animateColorAsState(onTertiaryContainer, label = "theme-on-tertiary-container").value,
    background = animateColorAsState(background, label = "theme-background").value,
    onBackground = animateColorAsState(onBackground, label = "theme-on-background").value,
    surface = animateColorAsState(surface, label = "theme-surface").value,
    onSurface = animateColorAsState(onSurface, label = "theme-on-surface").value,
    surfaceVariant = animateColorAsState(surfaceVariant, label = "theme-surface-variant").value,
    onSurfaceVariant = animateColorAsState(onSurfaceVariant, label = "theme-on-surface-variant").value,
    surfaceContainer = animateColorAsState(surfaceContainer, label = "theme-surface-container").value,
    surfaceContainerLowest = animateColorAsState(surfaceContainerLowest, label = "theme-surface-container-lowest").value,
    surfaceContainerLow = animateColorAsState(surfaceContainerLow, label = "theme-surface-container-low").value,
    surfaceContainerHigh = animateColorAsState(surfaceContainerHigh, label = "theme-surface-container-high").value,
    surfaceContainerHighest = animateColorAsState(surfaceContainerHighest, label = "theme-surface-container-highest").value,
    outline = animateColorAsState(outline, label = "theme-outline").value,
    outlineVariant = animateColorAsState(outlineVariant, label = "theme-outline-variant").value,
    error = animateColorAsState(error, label = "theme-error").value,
    onError = animateColorAsState(onError, label = "theme-on-error").value,
    errorContainer = animateColorAsState(errorContainer, label = "theme-error-container").value,
    onErrorContainer = animateColorAsState(onErrorContainer, label = "theme-on-error-container").value,
)
