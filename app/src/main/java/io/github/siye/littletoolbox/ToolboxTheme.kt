package io.github.siye.littletoolbox

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

internal val LocalPageGutter = staticCompositionLocalOf { 22.dp }

@Composable
internal fun ToolboxTheme(preferences: ToolboxPreferences, content: @Composable () -> Unit) {
    val dark = when (preferences.colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val (primaryLight, primaryDark, accentLight) = when (preferences.palette) {
        Palette.LAVENDER -> Triple(Color(0xFF655092), Color(0xFFD3BCFF), Color(0xFFEADFFF))
        Palette.SAGE -> Triple(Color(0xFF416650), Color(0xFFA6D1B2), Color(0xFFDBEEE2))
        Palette.MIST -> Triple(Color(0xFF395F85), Color(0xFFA1CAEF), Color(0xFFDCEAF8))
    }
    val colors = if (dark) darkColorScheme(
        primary = primaryDark,
        onPrimary = Color(0xFF221A2F),
        primaryContainer = Color(0xFF39314B),
        onPrimaryContainer = Color(0xFFF1E7FF),
        surface = Color(0xFF191623),
        onSurface = Color(0xFFF0EAF8),
        surfaceContainer = Color(0xFF24202F),
        surfaceContainerHigh = Color(0xFF2D2839),
        surfaceVariant = Color(0xFF333041),
        onSurfaceVariant = Color(0xFFCDC4D7),
        secondaryContainer = Color(0xFF3C3350),
        onSecondaryContainer = Color(0xFFF2E7FF),
        outline = Color(0xFF9B92A7),
    ) else lightColorScheme(
        primary = primaryLight,
        onPrimary = Color.White,
        primaryContainer = accentLight,
        onPrimaryContainer = Color(0xFF302546),
        surface = Color(0xFFFAF8FE),
        onSurface = Color(0xFF282332),
        surfaceContainer = Color(0xFFF1EDF8),
        surfaceContainerHigh = Color(0xFFEDE8F5),
        surfaceVariant = Color(0xFFEFEAF7),
        onSurfaceVariant = Color(0xFF5D556B),
        secondaryContainer = accentLight,
        onSecondaryContainer = Color(0xFF302546),
        outline = Color(0xFF817789),
    )
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * preferences.typeSize.scale),
        LocalPageGutter provides preferences.density.gutter.dp,
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
