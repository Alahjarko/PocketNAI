package net.pocketnai.ui.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 流式生成期间的中间预览（规划书 6.3）。
 *
 * 中间图本身由 [net.pocketnai.data.repo.GenerationRepository] 写在 cache 目录，
 * 这里只保存"哪个生成任务当前该显示哪张预览"—— 画廊的占位卡片按生成 id 取用。
 *
 * 与 [GenerationDraftStore] 同属"生成页与画廊之间的窄通道"：生成事件由
 * `GenerateViewModel` 消费，而占位卡片画在画廊里（另一个 ViewModel），
 * 中间预览因此需要一个共享的、只读的落点。
 *
 * 生命周期：新预览覆盖旧预览（同一任务只保留一张）；生成收尾时由写入方清除，
 * 进程被回收时残留的预览文件由文件层在启动清理时删掉。
 */
class GenerationPreviewStore {

    data class Preview(
        val generationId: String,
        /** 预览 PNG 的绝对路径（cache 目录，随时可被下一张覆盖）。 */
        val path: String,
        /** 服务端给出的进度（0–1）；没有就是 null，界面不编造数字。 */
        val progress: Double?,
    )

    private val _previews = MutableStateFlow<Map<String, Preview>>(emptyMap())

    /** 按生成 id 索引的当前预览。 */
    val previews: StateFlow<Map<String, Preview>> = _previews.asStateFlow()

    fun update(preview: Preview) {
        _previews.update { it + (preview.generationId to preview) }
    }

    fun clear(generationId: String) {
        _previews.update { it - generationId }
    }

    fun clearAll() {
        _previews.value = emptyMap()
    }
}
