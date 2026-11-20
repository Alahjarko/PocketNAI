package net.pocketnai.domain.model

/**
 * 主题配色偏好的纯领域表示。
 *
 * 默认 [DYNAMIC]：Android 12+ 跟随系统壁纸动态取色，低于 Android 12 时自动回退至 [INDIGO]；
 * 其余预设提供克制低饱和的 Material 3 完整色阶，保证画廊作品始终是视觉主体。
 */
enum class ThemeAccent {
    DYNAMIC,
    INDIGO,
    ROSE,
    SAGE,
    AMBER,
    OCEAN,
    GRAPHITE,
    ;

    companion object {
        val DEFAULT: ThemeAccent = DYNAMIC

        fun fromNameOrDefault(name: String?): ThemeAccent =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
