package com.wear.wearbuddy.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import com.wear.wearbuddy.data.CloudSettings

/**
 * Wearbuddy 主题：Material 3 Expressive，默认标准蓝。
 *
 * Wear Compose Material3 的 ColorScheme 与通用 M3 不是同一个类（多出 primaryDim /
 * surfaceContainer 等角色），这里为四个主题色各自构造完整方案。
 * 字体大小设置通过缩放 Density.fontScale 实现（系统原生语义，作用于所有 sp 文本）。
 */

/** 标准蓝：以 Material 蓝色调色板（Google Blue 种子）构造的深色方案。 */
private val BlueColorScheme = ColorScheme(
    primary = ColorBluePrimary,
    primaryDim = ColorBluePrimaryDim,
    onPrimary = ColorBlueOnPrimary,
    primaryContainer = ColorBluePrimaryContainer,
    onPrimaryContainer = ColorBlueOnPrimaryContainer,
    secondary = ColorBlueSecondary,
    secondaryDim = ColorBlueSecondaryDim,
    secondaryContainer = ColorBlueSecondaryContainer,
    onSecondary = ColorBlueOnSecondary,
    onSecondaryContainer = ColorBlueOnSecondaryContainer,
    tertiary = ColorBlueTertiary,
    tertiaryDim = ColorBlueTertiaryDim,
    tertiaryContainer = ColorBlueTertiaryContainer,
    onTertiary = ColorBlueOnTertiary,
    onTertiaryContainer = ColorBlueOnTertiaryContainer,
    surfaceContainerLow = ColorSurfaceLow,
    surfaceContainer = ColorSurface,
    surfaceContainerHigh = ColorSurfaceHigh,
    onSurface = ColorOnSurface,
    onSurfaceVariant = ColorOnSurfaceVariant,
    outline = ColorOutline,
    outlineVariant = ColorOutlineVariant,
    background = ColorBackground,
    onBackground = ColorOnBackground,
    error = ColorError,
    errorDim = ColorErrorDim,
    errorContainer = ColorErrorContainer,
    onError = ColorOnError,
    onErrorContainer = ColorOnErrorContainer
)

private val GreenColorScheme = BlueColorScheme.copy(
    primary = ColorGreenPrimary,
    primaryDim = ColorGreenPrimaryDim,
    onPrimary = ColorGreenOnPrimary,
    primaryContainer = ColorGreenPrimaryContainer,
    onPrimaryContainer = ColorGreenOnPrimaryContainer,
    secondary = ColorGreenSecondary,
    secondaryDim = ColorGreenSecondaryDim,
    secondaryContainer = ColorGreenSecondaryContainer,
    onSecondary = ColorGreenOnSecondary,
    onSecondaryContainer = ColorGreenOnSecondaryContainer,
    tertiary = ColorGreenTertiary,
    tertiaryDim = ColorGreenTertiaryDim,
    tertiaryContainer = ColorGreenTertiaryContainer,
    onTertiary = ColorGreenOnTertiary,
    onTertiaryContainer = ColorGreenOnTertiaryContainer
)

private val PurpleColorScheme = BlueColorScheme.copy(
    primary = ColorPurplePrimary,
    primaryDim = ColorPurplePrimaryDim,
    onPrimary = ColorPurpleOnPrimary,
    primaryContainer = ColorPurplePrimaryContainer,
    onPrimaryContainer = ColorPurpleOnPrimaryContainer,
    secondary = ColorPurpleSecondary,
    secondaryDim = ColorPurpleSecondaryDim,
    secondaryContainer = ColorPurpleSecondaryContainer,
    onSecondary = ColorPurpleOnSecondary,
    onSecondaryContainer = ColorPurpleOnSecondaryContainer,
    tertiary = ColorPurpleTertiary,
    tertiaryDim = ColorPurpleTertiaryDim,
    tertiaryContainer = ColorPurpleTertiaryContainer,
    onTertiary = ColorPurpleOnTertiary,
    onTertiaryContainer = ColorPurpleOnTertiaryContainer
)

private val AmberColorScheme = BlueColorScheme.copy(
    primary = ColorAmberPrimary,
    primaryDim = ColorAmberPrimaryDim,
    onPrimary = ColorAmberOnPrimary,
    primaryContainer = ColorAmberPrimaryContainer,
    onPrimaryContainer = ColorAmberOnPrimaryContainer,
    secondary = ColorAmberSecondary,
    secondaryDim = ColorAmberSecondaryDim,
    secondaryContainer = ColorAmberSecondaryContainer,
    onSecondary = ColorAmberOnSecondary,
    onSecondaryContainer = ColorAmberOnSecondaryContainer,
    tertiary = ColorAmberTertiary,
    tertiaryDim = ColorAmberTertiaryDim,
    tertiaryContainer = ColorAmberTertiaryContainer,
    onTertiary = ColorAmberOnTertiary,
    onTertiaryContainer = ColorAmberOnTertiaryContainer
)

fun colorSchemeOf(themeColor: String): ColorScheme = when (themeColor) {
    CloudSettings.THEME_GREEN -> GreenColorScheme
    CloudSettings.THEME_PURPLE -> PurpleColorScheme
    CloudSettings.THEME_AMBER -> AmberColorScheme
    else -> BlueColorScheme
}

fun fontScaleOf(fontScale: String): Float = when (fontScale) {
    CloudSettings.FONT_SMALL -> 0.85f
    CloudSettings.FONT_LARGE -> 1.2f
    else -> 1.0f
}

@Composable
fun WearbuddyTheme(
    themeColor: String = CloudSettings.THEME_BLUE,
    fontScale: String = CloudSettings.FONT_STANDARD,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val scale = fontScaleOf(fontScale)
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * scale)
    ) {
        MaterialTheme(
            colorScheme = colorSchemeOf(themeColor),
            content = content
        )
    }
}
