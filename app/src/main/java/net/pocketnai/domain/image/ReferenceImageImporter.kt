package net.pocketnai.domain.image

import net.pocketnai.core.Outcome

/**
 * 参考图的来源。
 *
 * 用字符串而不是 `Uri` / `File`：领域层不依赖 Android，`content://` 这类 URI
 * 由数据层自己解析回来。这样 [net.pocketnai.ui.generate.GenerateViewModel] 也不必碰 Android 类型。
 */
sealed interface ReferenceSource {

    /** 相册 / 文件选择器返回的 URI。 */
    data class PickedUri(val uri: String) : ReferenceSource

    /** 应用私有目录内的相对路径（"从历史选图"）。 */
    data class LocalPath(val relativePath: String) : ReferenceSource
}

/** 一张已经归一化并落盘的参考图。 */
data class PreparedReference(
    val relativePath: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
)

/**
 * 把用户选的图导入成本地可复用的参考图。
 *
 * 与 [ReferenceImageEncoder] 分开：导入发生在用户点"选图"的时候（要落盘、要能重开应用后还在），
 * 编码发生在提交生成的时候（只产出一个临时字符串）。两件事的失败语义完全不同 ——
 * 导入失败要让用户重选，编码失败只影响这一次提交。
 */
interface ReferenceImageImporter {

    suspend fun import(source: ReferenceSource): Outcome<PreparedReference>
}
