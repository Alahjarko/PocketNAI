package net.pocketnai.ui

import androidx.compose.runtime.staticCompositionLocalOf
import net.pocketnai.di.AppContainer

/**
 * 把 [AppContainer] 提供给整棵 Compose 树。
 *
 * 用 CompositionLocal 而不是给每个屏幕传一长串依赖：屏幕的构造参数会因此只剩
 * 真正的交互回调，配合各屏幕内部的 `viewModelFactory { initializer { ... } }`
 * 就能完成装配，不需要额外的依赖注入框架。
 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LocalAppContainer 未提供：请确认 PocketNaiApp 被包裹在 CompositionLocalProvider 内")
}
