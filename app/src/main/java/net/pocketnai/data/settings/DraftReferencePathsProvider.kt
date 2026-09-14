package net.pocketnai.data.settings

import net.pocketnai.domain.image.LiveReferencePathsProvider

/**
 * 把"编辑区草稿里挂着的参考图"告诉启动清理。
 *
 * 实现放在数据层而不是让仓库去读草稿：仓库只需要一份路径集合，
 * 读草稿这件事属于本地设置数据的职责。
 */
class DraftReferencePathsProvider(
    private val preferences: GenerationDraftPreferences,
) : LiveReferencePathsProvider {

    override fun provide(): Set<String> =
        preferences.load()
            ?.references
            ?.map { it.relativePath }
            ?.toSet()
            .orEmpty()
}
