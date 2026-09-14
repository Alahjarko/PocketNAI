package net.pocketnai.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 一套低饱和的靛蓝 / 紫罗兰配色。
 *
 * 生成类应用的界面会长时间被图片占据，因此界面颜色刻意偏中性、对比度克制，
 * 让用户的作品而不是应用本身的主题成为视觉主体。
 */

private val IndigoPrimary = Color(0xFF4A4E8F)
private val IndigoOnPrimary = Color(0xFFFFFFFF)
private val IndigoContainer = Color(0xFFE0E0FF)
private val IndigoOnContainer = Color(0xFF040A4C)

private val VioletSecondary = Color(0xFF5C5D72)
private val VioletContainer = Color(0xFFE1E0F9)
private val VioletOnContainer = Color(0xFF191A2C)

private val NeutralSurfaceLight = Color(0xFFFBF8FF)
private val NeutralSurfaceVariantLight = Color(0xFFE3E1EC)
private val NeutralOnSurfaceLight = Color(0xFF1B1B21)
private val NeutralOnSurfaceVariantLight = Color(0xFF46464F)
private val OutlineLight = Color(0xFF777680)

private val IndigoPrimaryDark = Color(0xFFBFC2FF)
private val IndigoOnPrimaryDark = Color(0xFF1B2064)
private val IndigoContainerDark = Color(0xFF32367A)
private val IndigoOnContainerDark = Color(0xFFE0E0FF)

private val VioletPrimaryDark = Color(0xFFC5C4DD)
private val VioletContainerDark = Color(0xFF444559)

private val NeutralSurfaceDark = Color(0xFF131318)
private val NeutralSurfaceVariantDark = Color(0xFF46464F)
private val NeutralOnSurfaceDark = Color(0xFFE4E1E9)
private val NeutralOnSurfaceVariantDark = Color(0xFFC7C5D0)
private val OutlineDark = Color(0xFF918F9A)

val PocketNaiLightColors = lightColorScheme(
    primary = IndigoPrimary,
    onPrimary = IndigoOnPrimary,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = IndigoOnContainer,
    secondary = VioletSecondary,
    secondaryContainer = VioletContainer,
    onSecondaryContainer = VioletOnContainer,
    background = NeutralSurfaceLight,
    onBackground = NeutralOnSurfaceLight,
    surface = NeutralSurfaceLight,
    onSurface = NeutralOnSurfaceLight,
    surfaceVariant = NeutralSurfaceVariantLight,
    onSurfaceVariant = NeutralOnSurfaceVariantLight,
    outline = OutlineLight,
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

val PocketNaiDarkColors = darkColorScheme(
    primary = IndigoPrimaryDark,
    onPrimary = IndigoOnPrimaryDark,
    primaryContainer = IndigoContainerDark,
    onPrimaryContainer = IndigoOnContainerDark,
    secondary = VioletPrimaryDark,
    secondaryContainer = VioletContainerDark,
    onSecondaryContainer = Color(0xFFE1E0F9),
    background = NeutralSurfaceDark,
    onBackground = NeutralOnSurfaceDark,
    surface = NeutralSurfaceDark,
    onSurface = NeutralOnSurfaceDark,
    surfaceVariant = NeutralSurfaceVariantDark,
    onSurfaceVariant = NeutralOnSurfaceVariantDark,
    outline = OutlineDark,
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)
