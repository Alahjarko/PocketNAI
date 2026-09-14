package net.pocketnai.ui.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.pocketnai.domain.model.GenerationParams

/**
 * 详情页“复用参数”到生成页之间的单向传递通道。
 *
 * 用一次性的 StateFlow 而不是导航参数：参数对象字段很多，塞进路由字符串既冗长又易错。
 * 生成页消费后必须调用 [consume]，否则返回生成页时会重复覆盖用户正在编辑的内容。
 */
class GenerationDraftStore {

    private val _pending = MutableStateFlow<GenerationParams?>(null)

    val pending: StateFlow<GenerationParams?> = _pending.asStateFlow()

    /** 来自详情页的“复用参数”，不会自动开始生成（规划书 4.3）。 */
    fun post(params: GenerationParams) {
        _pending.value = params
    }

    fun consume(): GenerationParams? {
        val value = _pending.value
        _pending.value = null
        return value
    }
}
