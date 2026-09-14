package net.pocketnai.ui.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.pocketnai.domain.model.GenerationRequest

/**
 * 详情页“复用参数”到生成页之间的单向传递通道。
 *
 * 用一次性的 StateFlow 而不是导航参数：参数对象字段很多，塞进路由字符串既冗长又易错。
 * 生成页消费后必须调用 [consume]，否则返回生成页时会重复覆盖用户正在编辑的内容。
 *
 * 传递的是 [GenerationRequest] 而不是 `GenerationParams`：图生图历史如果只带回参数、
 * 不带回起点图，那些参数（Strength）就没有任何意义。
 */
class GenerationDraftStore {

    private val _pending = MutableStateFlow<GenerationRequest?>(null)

    val pending: StateFlow<GenerationRequest?> = _pending.asStateFlow()

    /** 来自详情页的“复用参数”，不会自动开始生成（规划书 4.3）。 */
    fun post(request: GenerationRequest) {
        _pending.value = request
    }

    fun consume(): GenerationRequest? {
        val value = _pending.value
        _pending.value = null
        return value
    }
}
