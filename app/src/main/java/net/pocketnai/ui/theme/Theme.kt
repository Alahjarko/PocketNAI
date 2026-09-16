package net.pocketnai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import net.pocketnai.domain.model.ThemeMode

/**
 * 权重高亮的两种底纹色。
 *
 * 由主题提供而不是让界面自己判明暗：`ThemeMode` 允许用户强制浅色/深色，
 * 而 `isSystemInDarkTheme()` 看不到这个覆盖 —— 界面里再判一次必然会判错。
 */
@Immutable
data class WeightHighlightColors(
    /** 权重 < 1，绿色。 */
    val weaker: Color,
    /** 权重 > 1，红色。 */
    val stronger: Color,
)

val LocalWeightHighlightColors = staticCompositionLocalOf {
    WeightHighlightColors(
        weaker = WeightWeakerHighlightLight,
        stronger = WeightStrongerHighlightLight,
    )
}

/**
 * 应用主题。
 *
 * [themeMode] 允许用户显式选择浅色或深色，默认跟随系统。
 * Android 12+ 支持动态取色（用户壁纸取色），默认开启但可关闭。
 */
@Composable
fun PocketNaiTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> PocketNaiDarkColors
        else -> PocketNaiLightColors
    }

    val weightHighlights = if (darkTheme) {
        WeightHighlightColors(WeightWeakerHighlightDark, WeightStrongerHighlightDark)
    } else {
        WeightHighlightColors(WeightWeakerHighlightLight, WeightStrongerHighlightLight)
    }

    MaterialTheme(
        colorScheme = colorScheme,
    ) {
        CompositionLocalProvider(
            LocalWeightHighlightColors provides weightHighlights,
            content = content,
        )
    }
}
