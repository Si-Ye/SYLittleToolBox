package io.github.siye.littletoolbox

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

internal val LocalPageGutter = staticCompositionLocalOf { 22.dp }

@Composable
internal fun ToolboxTheme(preferences: ToolboxPreferences, content: @Composable () -> Unit) {
    val dark = when (preferences.colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val window = LocalActivity.current?.window
    val view = LocalView.current
    if (window != null && !view.isInEditMode) {
        SideEffect {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * preferences.typeSize.scale),
        LocalPageGutter provides preferences.density.gutter.dp,
    ) {
        MaterialTheme(colorScheme = preferences.palette.colorScheme(dark), content = content)
    }
}

internal fun Palette.colorScheme(dark: Boolean): ColorScheme {
    val palette = when (this) {
        Palette.GRAPHITE -> graphite
        Palette.LAVENDER -> lavender
        Palette.IRIS -> iris
        Palette.PERIWINKLE -> periwinkle
        Palette.SAGE -> sage
        Palette.MIST -> mist
        Palette.SPRING -> spring
        Palette.CYAN -> cyan
        Palette.VIOLET -> violet
    }
    return if (dark) palette.darkScheme else palette.lightScheme
}

private class SiyeColors(
    brand: Long,
    brandHover: Long,
    page: Long,
    panel: Long,
    soft: Long,
    ink: Long,
    muted: Long,
    line: Long,
    tint: Long,
    tintInk: Long,
    onBrand: Long = 0xFFFFFFFF,
    action: Long = brand,
    onAction: Long = onBrand,
) {
    val brand = Color(brand)
    val brandHover = Color(brandHover)
    val page = Color(page)
    val panel = Color(panel)
    val soft = Color(soft)
    val ink = Color(ink)
    val muted = Color(muted)
    val line = Color(line)
    val tint = Color(tint)
    val tintInk = Color(tintInk)
    val onBrand = Color(onBrand)
    val action = Color(action)
    val onAction = Color(onAction)
}

private class SiyePalette(light: SiyeColors, dark: SiyeColors) {
    val lightScheme = light.toMaterialScheme(false, light, dark)
    val darkScheme = dark.toMaterialScheme(true, light, dark)
}

// Source tokens: AI知识库/配色风格库/主题资源/{theme}.json.
private val graphite = SiyePalette(
    light = SiyeColors(
        brand = 0xFF596273, brandHover = 0xFF454E5E,
        page = 0xFFF5F6F8, panel = 0xFFFFFFFF, soft = 0xFFEBEDF1,
        ink = 0xFF292D36, muted = 0xFF626977, line = 0xFFDFE2E8,
        tint = 0xFFE8EBF1, tintInk = 0xFF4C5669,
    ),
    dark = SiyeColors(
        brand = 0xFFB3BECE, brandHover = 0xFFC8D0DE,
        page = 0xFF17191E, panel = 0xFF21242C, soft = 0xFF2B303B,
        ink = 0xFFE9ECF3, muted = 0xFFA8AFBF, line = 0xFF383F4C,
        tint = 0xFF323B4D, tintInk = 0xFFCCD5E7, onBrand = 0xFF202733,
    ),
)

private val lavender = SiyePalette(
    light = SiyeColors(
        brand = 0xFF7957B5, brandHover = 0xFF67449F,
        page = 0xFFF6F5F9, panel = 0xFFFFFFFF, soft = 0xFFEFEDF5,
        ink = 0xFF292532, muted = 0xFF6C6679, line = 0xFFE5E1ED,
        tint = 0xFFECE5F7, tintInk = 0xFF644294,
    ),
    dark = SiyeColors(
        brand = 0xFFBCA0E5, brandHover = 0xFFCBB3EE,
        page = 0xFF18151F, panel = 0xFF221E2C, soft = 0xFF2C2638,
        ink = 0xFFEDE8F5, muted = 0xFFADA4BB, line = 0xFF3A3249,
        tint = 0xFF342842, tintInk = 0xFFD0B6F2, onBrand = 0xFF21152F,
    ),
)

private val iris = SiyePalette(
    light = SiyeColors(
        brand = 0xFF674CB3, brandHover = 0xFF553A9D,
        page = 0xFFF5F4FA, panel = 0xFFFFFFFF, soft = 0xFFECEAF6,
        ink = 0xFF282439, muted = 0xFF6A647D, line = 0xFFE1DDED,
        tint = 0xFFEAE3FA, tintInk = 0xFF5D429C,
    ),
    dark = SiyeColors(
        brand = 0xFFAB92EA, brandHover = 0xFFBEA7F7,
        page = 0xFF171420, panel = 0xFF211C30, soft = 0xFF2D2640,
        ink = 0xFFEEE8FC, muted = 0xFFADA2C4, line = 0xFF3B3153,
        tint = 0xFF34264D, tintInk = 0xFFCCB8FA, onBrand = 0xFF1D1038,
    ),
)

private val periwinkle = SiyePalette(
    light = SiyeColors(
        brand = 0xFF5267B4, brandHover = 0xFF4659A7,
        page = 0xFFF3F5FA, panel = 0xFFFFFFFF, soft = 0xFFEAEDF6,
        ink = 0xFF252B3D, muted = 0xFF5F6A82, line = 0xFFDEE3EF,
        tint = 0xFFE5EBFA, tintInk = 0xFF485C9F,
    ),
    dark = SiyeColors(
        brand = 0xFF9AAEF2, brandHover = 0xFFB0C0FC,
        page = 0xFF141924, panel = 0xFF1C2331, soft = 0xFF252E40,
        ink = 0xFFE7EDF9, muted = 0xFFA2AFC9, line = 0xFF323F58,
        tint = 0xFF273653, tintInk = 0xFFBCCCFB, onBrand = 0xFF14213E,
    ),
)

private val sage = SiyePalette(
    light = SiyeColors(
        brand = 0xFF4E7A65, brandHover = 0xFF3D6552,
        page = 0xFFF4F7F5, panel = 0xFFFFFFFF, soft = 0xFFE9F0EC,
        ink = 0xFF26372F, muted = 0xFF5B7063, line = 0xFFDCE6DF,
        tint = 0xFFE3EFE7, tintInk = 0xFF416751,
    ),
    dark = SiyeColors(
        brand = 0xFF9AC7AC, brandHover = 0xFFACD7BE,
        page = 0xFF141D19, panel = 0xFF1C2822, soft = 0xFF27372E,
        ink = 0xFFE6F1EA, muted = 0xFFA1B9AA, line = 0xFF344B3D,
        tint = 0xFF284535, tintInk = 0xFFB1DBBF, onBrand = 0xFF122B1E,
    ),
)

private val mist = SiyePalette(
    light = SiyeColors(
        brand = 0xFF3B778D, brandHover = 0xFF2C6378,
        page = 0xFFF2F7F8, panel = 0xFFFFFFFF, soft = 0xFFE6F0F3,
        ink = 0xFF243740, muted = 0xFF586F7B, line = 0xFFD9E5EA,
        tint = 0xFFDFEFF4, tintInk = 0xFF32667B,
    ),
    dark = SiyeColors(
        brand = 0xFF8CC4D6, brandHover = 0xFFA4D6E5,
        page = 0xFF131D23, panel = 0xFF1B2931, soft = 0xFF253942,
        ink = 0xFFE3F0F5, muted = 0xFF9FB8C4, line = 0xFF304953,
        tint = 0xFF233E4D, tintInk = 0xFFAAD8E8, onBrand = 0xFF102C37,
    ),
)

private val spring = SiyePalette(
    light = SiyeColors(
        brand = 0xFF3D6E24, brandHover = 0xFF315C1B,
        page = 0xFFF5F9F2, panel = 0xFFFFFFFF, soft = 0xFFE8F0E2,
        ink = 0xFF1E301B, muted = 0xFF53684B, line = 0xFFD7E2CE,
        tint = 0xFFE5F6D7, tintInk = 0xFF365D20,
        action = 0xFF8EF04A, onAction = 0xFF19320C,
    ),
    dark = SiyeColors(
        brand = 0xFFB4F88E, brandHover = 0xFFC8FFA9,
        page = 0xFF121B10, panel = 0xFF1B2818, soft = 0xFF293923,
        ink = 0xFFEDF8E6, muted = 0xFFADBEA1, line = 0xFF394F30,
        tint = 0xFF29451D, tintInk = 0xFFC2F3A5, onBrand = 0xFF173209,
        action = 0xFF9BF45C, onAction = 0xFF172C0D,
    ),
)

private val cyan = SiyePalette(
    light = SiyeColors(
        brand = 0xFF006C79, brandHover = 0xFF005762,
        page = 0xFFF0F9F9, panel = 0xFFFFFFFF, soft = 0xFFDFF0F1,
        ink = 0xFF123338, muted = 0xFF466A70, line = 0xFFCFE4E5,
        tint = 0xFFD9F6F6, tintInk = 0xFF075C64,
        action = 0xFF37E6EB, onAction = 0xFF07373B,
    ),
    dark = SiyeColors(
        brand = 0xFF70E8F1, brandHover = 0xFF9AF4F7,
        page = 0xFF0C1C20, panel = 0xFF13292E, soft = 0xFF1D393F,
        ink = 0xFFE2F7F8, muted = 0xFF9EBEC4, line = 0xFF2A5058,
        tint = 0xFF164047, tintInk = 0xFF9AF0F4, onBrand = 0xFF07333A,
        action = 0xFF40E9F0, onAction = 0xFF052D33,
    ),
)

private val violet = SiyePalette(
    light = SiyeColors(
        brand = 0xFF7435B5, brandHover = 0xFF612799,
        page = 0xFFF8F3FF, panel = 0xFFFFFFFF, soft = 0xFFEEE4FA,
        ink = 0xFF301D43, muted = 0xFF705882, line = 0xFFE3D5F0,
        tint = 0xFFEADCFC, tintInk = 0xFF65319A,
        action = 0xFFBB7AFF, onAction = 0xFF25103C,
    ),
    dark = SiyeColors(
        brand = 0xFFD4A4FF, brandHover = 0xFFE2C0FF,
        page = 0xFF1B1028, panel = 0xFF281937, soft = 0xFF372349,
        ink = 0xFFF6EAFF, muted = 0xFFC2A3D7, line = 0xFF513466,
        tint = 0xFF45235F, tintInk = 0xFFE0BAFF, onBrand = 0xFF311147,
        action = 0xFFBC73FF, onAction = 0xFF260D3D,
    ),
)

private fun SiyeColors.toMaterialScheme(
    darkMode: Boolean,
    light: SiyeColors,
    dark: SiyeColors,
): ColorScheme {
    // Compact controls also use primary and muted as text on raised surfaces.
    val primary = if (darkMode) brand else lerp(brand, brandHover, 0.72f)
    // The saturated palettes distinguish readable brand text from their bright action fill.
    val separateAction = action != brand
    val mutedText = lerp(muted, ink, 0.16f)
    val fixedText = lerp(light.tintInk, light.ink, 0.15f)
    return ColorScheme(
        primary = primary,
        onPrimary = onBrand,
        primaryContainer = lerp(tint, brand, 0.09f),
        onPrimaryContainer = tintInk,
        inversePrimary = if (darkMode) light.tintInk else dark.brand,
        secondary = if (separateAction) action else tintInk,
        onSecondary = if (separateAction) onAction else onBrand,
        secondaryContainer = tint,
        onSecondaryContainer = tintInk,
        tertiary = lerp(primary, ink, 0.32f),
        onTertiary = onBrand,
        tertiaryContainer = lerp(soft, tint, 0.6f),
        onTertiaryContainer = tintInk,
        background = page,
        onBackground = ink,
        surface = page,
        onSurface = ink,
        surfaceVariant = soft,
        onSurfaceVariant = mutedText,
        surfaceTint = primary,
        inverseSurface = ink,
        inverseOnSurface = page,
        error = if (darkMode) Color(0xFFEBCB8B) else Color(0xFF735014),
        onError = if (darkMode) Color(0xFF36280A) else Color.White,
        errorContainer = if (darkMode) Color(0xFF44371F) else Color(0xFFFAF0D7),
        onErrorContainer = if (darkMode) Color(0xFFEBCB8B) else Color(0xFF735014),
        outline = mutedText,
        outlineVariant = line,
        scrim = Color.Black,
        surfaceBright = if (darkMode) line else panel,
        surfaceDim = if (darkMode) page else line,
        surfaceContainerLowest = if (darkMode) lerp(page, Color.Black, 0.2f) else panel,
        surfaceContainerLow = if (darkMode) panel else lerp(page, soft, 0.5f),
        surfaceContainer = if (darkMode) lerp(panel, soft, 0.48f) else soft,
        surfaceContainerHigh = if (darkMode) soft else lerp(soft, line, 0.3f),
        surfaceContainerHighest = lerp(soft, line, 0.6f),
        primaryFixed = light.tint,
        primaryFixedDim = lerp(light.tint, dark.brandHover, 0.55f),
        onPrimaryFixed = light.ink,
        onPrimaryFixedVariant = fixedText,
        secondaryFixed = light.soft,
        secondaryFixedDim = lerp(light.soft, dark.brandHover, 0.5f),
        onSecondaryFixed = light.ink,
        onSecondaryFixedVariant = fixedText,
        tertiaryFixed = light.line,
        tertiaryFixedDim = lerp(light.line, dark.brandHover, 0.35f),
        onTertiaryFixed = light.ink,
        onTertiaryFixedVariant = fixedText,
    )
}
