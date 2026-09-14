package net.pocketnai.domain.image

import net.pocketnai.core.Outcome

/**
 * 把本地的一张参考图按目标形状编码成请求体里要用的 base64。
 *
 * 抽成接口是为了让 [net.pocketnai.data.repo.GenerationRepository] 不必依赖 Android 的
 * 位图 API：仓库层只需要"给我一个字符串"，解码与绘制留在数据层。
 *
 * 注意返回的是 **base64 字符串本身**，它只允许存在于一次请求的构造过程中 ——
 * 不进数据库、不进日志、不长期驻留内存（见 AGENTS.md 的安全约束）。
 */
fun interface ReferenceImageEncoder {

    /**
     * [transform] 为 null 表示**原图直接编码**，不做任何裁剪或补齐。
     * Vibe 编码需要这样：官方也是把原图交给 `encode-vibe`。
     */
    suspend fun encodeBase64(
        relativePath: String,
        transform: ImageTransform?,
    ): Outcome<String>
}
