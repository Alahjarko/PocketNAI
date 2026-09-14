package net.pocketnai.data.files

import android.content.Context
import android.os.StatFs
import net.pocketnai.data.network.ZipImageExtractor
import java.io.File

/**
 * 应用私有目录的文件管理（规划书 5.1 与 6.2）。
 *
 * 目录结构：
 * ```
 * files/generations/<generation-id>/0001.png, 0002.png, ...   <- 生成结果
 * files/references/<sha256>.png                              <- 参考图（内容寻址）
 * files/vibes/<cache-key>.vibe                               <- encode-vibe 产物（内容寻址）
 * cache/incoming/<generation-id>/                            <- ZIP 与解包中间文件，随时可删
 * ```
 *
 * **参考图与 vibe 用内容寻址而不是按生成记录分目录**：同一张图被多次使用时只占一份磁盘，
 * 而删除某条历史不会牵连到别的历史还在用的文件。代价是这类文件不能随生成记录一起删，
 * 必须由 [cleanupOrphans] 按"仍被引用的路径集合"回收 —— 这也是为什么它在启动清理里。
 *
 * 内部路径不含任何用户输入，因此不存在文件名过长、非法字符、同名覆盖、
 * Emoji 归一化等问题，Prompt 也不会意外出现在系统日志或崩溃路径里。
 */
class GenerationFileStore(context: Context) {

    private val appContext = context.applicationContext

    /** 正式历史目录，不参与系统缓存清理。 */
    val generationsRoot: File = File(appContext.filesDir, DIR_GENERATIONS)

    /** 参考图目录（内容寻址）。 */
    val referencesRoot: File = File(appContext.filesDir, DIR_REFERENCES)

    /** encode-vibe 产物目录（内容寻址）。 */
    val vibesRoot: File = File(appContext.filesDir, DIR_VIBES)

    /** 中间文件目录，放在 cache 下，系统空间紧张时可被回收。 */
    private val incomingRoot: File = File(appContext.cacheDir, DIR_INCOMING)

    fun generationDir(generationId: String): File = File(generationsRoot, generationId)

    /** 把数据库里的相对路径解析成绝对文件。相对路径以应用私有目录为根。 */
    fun resolve(relativePath: String): File = File(appContext.filesDir, relativePath)

    /** 数据库里保存相对路径时使用的形式，例如 `generations/<id>/0001.png`。 */
    fun relativePathOf(generationId: String, ordinal: Int): String =
        "$DIR_GENERATIONS/$generationId/${fileOrdinalName(ordinal)}"

    /** 参考图的相对路径。用内容哈希命名，因此同图同路径。 */
    fun referenceRelativePath(sha256: String): String = "$DIR_REFERENCES/$sha256$PNG_SUFFIX"

    /** encode-vibe 产物的相对路径。缓存键由调用方按 `模型 + 图哈希 + 信息量` 组装。 */
    fun vibeRelativePath(cacheKey: String): String = "$DIR_VIBES/$cacheKey$VIBE_SUFFIX"

    /**
     * 写入一张参考图。**已经存在同内容文件时直接复用**，不重复写盘。
     *
     * 先写 `.part` 再在同一目录内 rename，保证不会出现"半张图"被数据库引用的情况
     * （与 [commitImages] 同样的理由）。
     */
    fun writeReference(sha256: String, bytes: ByteArray): File =
        writeContentAddressed(referencesRoot, sha256 + PNG_SUFFIX, bytes)

    fun writeVibe(cacheKey: String, bytes: ByteArray): File =
        writeContentAddressed(vibesRoot, cacheKey + VIBE_SUFFIX, bytes)

    private fun writeContentAddressed(root: File, fileName: String, bytes: ByteArray): File {
        val target = File(root, fileName)
        if (target.isFile && target.length() == bytes.size.toLong()) return target
        if (!root.exists() && !root.mkdirs()) {
            throw java.io.IOException("无法创建参考图目录: $root")
        }
        val staging = File(root, "$fileName.part")
        staging.outputStream().buffered().use { it.write(bytes) }
        if (!staging.renameTo(target)) {
            staging.copyTo(target, overwrite = true)
            staging.delete()
        }
        return target
    }

    /** 历史目录当前是否已存在该图片文件，用于启动时识别丢失文件。 */
    fun exists(relativePath: String): Boolean = resolve(relativePath).isFile

    /** 为一次任务创建独立的中间目录。 */
    fun newIncomingDir(generationId: String): File =
        File(incomingRoot, generationId).apply { mkdirs() }

    /** ZIP 临时文件。解包并提交数据库后必须删除。 */
    fun newArchiveFile(generationId: String): File =
        File(newIncomingDir(generationId), ARCHIVE_FILE_NAME)

    /**
     * 把解包出来的临时 PNG 迁入正式目录。
     *
     * 采用“先复制到目标目录内的 .part 文件，再重命名为最终名”的方式：
     * 同一目录内的 rename 是原子的，因此不会出现“数据库有记录但文件只有一半”的情况。
     * 中间文件与正式目录可能不在同一文件系统，所以不直接使用跨目录 rename。
     */
    fun commitImages(
        generationId: String,
        extracted: List<ZipImageExtractor.ExtractedImage>,
    ): List<CommittedImage> {
        val destination = generationDir(generationId)
        if (!destination.exists() && !destination.mkdirs()) {
            throw java.io.IOException("无法创建历史目录: $destination")
        }
        return extracted.map { image ->
            val finalFile = File(destination, fileOrdinalName(image.ordinal))
            val staging = File(destination, "${image.ordinal}.part")
            image.file.inputStream().use { input ->
                staging.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (!staging.renameTo(finalFile)) {
                // 极端情况下 rename 失败（例如目标已存在），退化为覆盖复制后删除中间文件。
                staging.copyTo(finalFile, overwrite = true)
                staging.delete()
            }
            CommittedImage(
                ordinal = image.ordinal,
                file = finalFile,
                byteSize = image.byteSize,
                sha256 = image.sha256,
                width = image.width,
                height = image.height,
            )
        }
    }

    /** 删除某次生成的全部图片与目录。数据库记录由仓库层负责。 */
    fun deleteGeneration(generationId: String) {
        generationDir(generationId).deleteRecursively()
        File(incomingRoot, generationId).deleteRecursively()
    }

    /** 删除 ZIP 中间文件所在目录。 */
    fun clearIncoming(generationId: String) {
        File(incomingRoot, generationId).deleteRecursively()
    }

    /**
     * 启动时清理：删除没有任何数据库记录的图片目录、残留的中间文件，
     * 以及不再被任何生成记录引用的参考图与 vibe 文件。
     *
     * 规划书 7.2 要求”应用启动时应能够清理孤立临时文件并识别丢失文件”，
     * 缺失文件的识别由仓库层按数据库记录逐个 file.exists() 完成。
     *
     * [referencedRelativePaths] 是参考图与 vibe 的存活集合。它**必须**来自数据库的
     * 完整查询结果：漏掉一条就会被误删，用户下次打开那条历史时会看到”参考图已不在本机”。
     */
    fun cleanupOrphans(
        knownGenerationIds: Set<String>,
        referencedRelativePaths: Set<String> = emptySet(),
    ): CleanupReport {
        var removedDirs = 0
        var removedIncoming = 0

        generationsRoot.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            if (dir.name !in knownGenerationIds) {
                if (dir.deleteRecursively()) removedDirs++
            }
        }

        incomingRoot.listFiles()?.forEach { dir ->
            if (dir.deleteRecursively()) removedIncoming++
        }

        val removedReferences = removeUnreferenced(referencesRoot, referencedRelativePaths) +
            removeUnreferenced(vibesRoot, referencedRelativePaths)

        return CleanupReport(
            removedGenerationDirs = removedDirs,
            removedIncomingDirs = removedIncoming,
            removedReferenceFiles = removedReferences,
        )
    }

    /** 删掉目录里所有"不在存活集合中"的文件；`.part` 残片一律删除。 */
    private fun removeUnreferenced(root: File, referenced: Set<String>): Int {
        var removed = 0
        root.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            // 相对路径与数据库里保存的形式一致：`<目录名>/<文件名>`。
            val relativePath = "${root.name}/${file.name}"
            val alive = relativePath in referenced && !file.name.endsWith(PART_SUFFIX)
            if (!alive && file.delete()) removed++
        }
        return removed
    }

    /** 当前历史、参考图与 vibe 目录占用的总字节数。 */
    fun usedBytes(): Long =
        listOf(generationsRoot, referencesRoot, vibesRoot).sumOf { root ->
            root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }

    /**
     * 是否还有足够空间写入 [additionalBytes]。
     * 保留 [SAFETY_MARGIN_BYTES] 余量，避免把设备写满（规划书 9.1“本地存储空间不足”）。
     */
    fun hasSpaceFor(additionalBytes: Long): Boolean {
        val stat = StatFs(generationsRoot.let { if (it.exists()) it else appContext.filesDir }.path)
        val available = stat.availableBytes
        return available - additionalBytes > SAFETY_MARGIN_BYTES
    }

    data class CommittedImage(
        val ordinal: Int,
        val file: File,
        val byteSize: Long,
        val sha256: String,
        val width: Int,
        val height: Int,
    )

    data class CleanupReport(
        val removedGenerationDirs: Int,
        val removedIncomingDirs: Int,
        val removedReferenceFiles: Int = 0,
    )

    private fun fileOrdinalName(ordinal: Int): String =
        ordinal.toString().padStart(4, '0') + ".png"

    private companion object {
        const val DIR_GENERATIONS = "generations"
        const val DIR_REFERENCES = "references"
        const val DIR_VIBES = "vibes"
        const val DIR_INCOMING = "incoming"
        const val ARCHIVE_FILE_NAME = "response.zip"
        const val PNG_SUFFIX = ".png"
        const val VIBE_SUFFIX = ".vibe"
        const val PART_SUFFIX = ".part"

        /** 预留 64 MB 余量，宁可提前报"空间不足"也不要写满用户设备。 */
        const val SAFETY_MARGIN_BYTES = 64L * 1024 * 1024
    }
}
