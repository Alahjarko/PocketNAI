package net.pocketnai.domain.model

/**
 * 主题模式偏好的纯领域表示。
 *
 * 刻意不带显示文案：这是用户偏好，展示名称属于界面层，
 * 由 `stringResource` 提供（见 `ui/settings/SettingsScreen.kt`）。
 */
enum class ThemeMode {
    /** 跟随系统的深色/浅色设置。 */
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        val DEFAULT: ThemeMode = SYSTEM

        fun fromNameOrDefault(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
