package net.pocketnai.ui.theme

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/** One interruptible clock for every color role; first composition uses the target immediately. */
@Composable
internal fun animatedColorScheme(target: ColorScheme): ColorScheme {
    var displayed by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        val start = displayed
        if (start === target) return@LaunchedEffect
        animate(0f, 1f, animationSpec = tween(280)) { fraction, _ ->
            displayed = blendColorScheme(start, target, fraction)
        }
        displayed = target
    }
    return displayed
}

/** Shared by production and native preview frames. */
internal fun blendColorScheme(start: ColorScheme, end: ColorScheme, fraction: Float): ColorScheme {
    val blended = end.copy(
        primary = lerp(start.primary, end.primary, fraction),
        onPrimary = lerp(start.onPrimary, end.onPrimary, fraction),
        primaryContainer = lerp(start.primaryContainer, end.primaryContainer, fraction),
        onPrimaryContainer = lerp(start.onPrimaryContainer, end.onPrimaryContainer, fraction),
        inversePrimary = lerp(start.inversePrimary, end.inversePrimary, fraction),
        secondary = lerp(start.secondary, end.secondary, fraction),
        onSecondary = lerp(start.onSecondary, end.onSecondary, fraction),
        secondaryContainer = lerp(start.secondaryContainer, end.secondaryContainer, fraction),
        onSecondaryContainer = lerp(start.onSecondaryContainer, end.onSecondaryContainer, fraction),
        tertiary = lerp(start.tertiary, end.tertiary, fraction),
        onTertiary = lerp(start.onTertiary, end.onTertiary, fraction),
        tertiaryContainer = lerp(start.tertiaryContainer, end.tertiaryContainer, fraction),
        onTertiaryContainer = lerp(start.onTertiaryContainer, end.onTertiaryContainer, fraction),
        background = lerp(start.background, end.background, fraction),
        onBackground = lerp(start.onBackground, end.onBackground, fraction),
        surface = lerp(start.surface, end.surface, fraction),
        onSurface = lerp(start.onSurface, end.onSurface, fraction),
        surfaceVariant = lerp(start.surfaceVariant, end.surfaceVariant, fraction),
        onSurfaceVariant = lerp(start.onSurfaceVariant, end.onSurfaceVariant, fraction),
        surfaceTint = lerp(start.surfaceTint, end.surfaceTint, fraction),
        inverseSurface = lerp(start.inverseSurface, end.inverseSurface, fraction),
        inverseOnSurface = lerp(start.inverseOnSurface, end.inverseOnSurface, fraction),
        error = lerp(start.error, end.error, fraction),
        onError = lerp(start.onError, end.onError, fraction),
        errorContainer = lerp(start.errorContainer, end.errorContainer, fraction),
        onErrorContainer = lerp(start.onErrorContainer, end.onErrorContainer, fraction),
        outline = lerp(start.outline, end.outline, fraction),
        outlineVariant = lerp(start.outlineVariant, end.outlineVariant, fraction),
        scrim = lerp(start.scrim, end.scrim, fraction),
        surfaceBright = lerp(start.surfaceBright, end.surfaceBright, fraction),
        surfaceDim = lerp(start.surfaceDim, end.surfaceDim, fraction),
        surfaceContainer = lerp(start.surfaceContainer, end.surfaceContainer, fraction),
        surfaceContainerLow = lerp(start.surfaceContainerLow, end.surfaceContainerLow, fraction),
        surfaceContainerLowest = lerp(start.surfaceContainerLowest, end.surfaceContainerLowest, fraction),
        surfaceContainerHigh = lerp(start.surfaceContainerHigh, end.surfaceContainerHigh, fraction),
        surfaceContainerHighest = lerp(start.surfaceContainerHighest, end.surfaceContainerHighest, fraction),
    )


    return blended.copy(
        onPrimary = readableThemeColor(blended.onPrimary, start.onPrimary, end.onPrimary, blended.primary),
        onPrimaryContainer = readableThemeColor(blended.onPrimaryContainer, start.onPrimaryContainer, end.onPrimaryContainer, blended.primaryContainer),
        onSecondary = readableThemeColor(blended.onSecondary, start.onSecondary, end.onSecondary, blended.secondary),
        onSecondaryContainer = readableThemeColor(blended.onSecondaryContainer, start.onSecondaryContainer, end.onSecondaryContainer, blended.secondaryContainer),
        onTertiary = readableThemeColor(blended.onTertiary, start.onTertiary, end.onTertiary, blended.tertiary),
        onTertiaryContainer = readableThemeColor(blended.onTertiaryContainer, start.onTertiaryContainer, end.onTertiaryContainer, blended.tertiaryContainer),
        onBackground = readableThemeColor(blended.onBackground, start.onBackground, end.onBackground, blended.background),
        onSurface = readableThemeColor(blended.onSurface, start.onSurface, end.onSurface, blended.surface),
        onSurfaceVariant = readableThemeColor(blended.onSurfaceVariant, start.onSurfaceVariant, end.onSurfaceVariant, blended.surfaceVariant),
        inverseOnSurface = readableThemeColor(blended.inverseOnSurface, start.inverseOnSurface, end.inverseOnSurface, blended.inverseSurface),
        onError = readableThemeColor(blended.onError, start.onError, end.onError, blended.error),
        onErrorContainer = readableThemeColor(blended.onErrorContainer, start.onErrorContainer, end.onErrorContainer, blended.errorContainer),
    )
}

/** Keep text/icons readable while light and dark surfaces pass through their middle values. */
internal fun readableThemeColor(candidate: Color, start: Color, end: Color, background: Color): Color {
    fun contrast(ink: Color): Float {
        val a = ink.luminance(); val b = background.luminance()
        return (max(a, b) + 0.05f) / (min(a, b) + 0.05f)
    }
    if (contrast(candidate) >= 4.5f) return candidate
    val endpoint = if (contrast(start) > contrast(end)) start else end
    if (contrast(endpoint) >= 4.5f) return endpoint
    return if (background.luminance() > 0.179f) Color.Black else Color.White
}
