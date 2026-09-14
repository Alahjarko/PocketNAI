package net.pocketnai.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.pocketnai.PocketNaiApplication
import net.pocketnai.ui.theme.PocketNaiTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PocketNaiApplication).container

        // 启动时恢复：清理孤立临时文件，并把上次被系统回收时卡住的任务标记为失败。
        // 放在与界面渲染并行的位置，不阻塞首帧。
        lifecycleScope.launch {
            runCatching { container.generationRepository.cleanupOnStartup() }
        }

        setContent {
            val themeMode by container.settingsStore.themeMode.collectAsStateWithLifecycle()
            PocketNaiTheme(themeMode = themeMode) {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    PocketNaiApp()
                }
            }
        }
    }
}
