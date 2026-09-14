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
 * files/generations/<generation-id>/0001.png, 0002.png, ...
 * cache/incoming/<generation-id>/            <- ZIP 与解包中间文件，随时可删
 * ```
 *
 * 内部路径不含任何用户输入，因此不存在文件名过长、非法字符、同名覆盖、
 * Emoji 归一化等问题，Prompt 也不会意外出现在系统日志或崩溃路径里。
 */
class GenerationFileStore(context: Context) {

    private val appContext = context.applicationContext

    /** 正式历史目录，不参与系统缓存清理。 */
    val generationsRoot: File = File(appContext.filesDir, DIR_GENERATIONS)

    /** 中间文件目录，放在 cache 下，系统空间紧张时可被回收。 */
    private val incomingRoot: File = File(appContext.cacheDir, DIR_INCOMING)

    fun generationDir(generationId: String): File = File(generationsRoot, generationId)

    /** 把数据库里的相对路径解析成绝对文件。相对路径以应用私有目录为根。 */
    fun resolve(relativePath: String): File = File(appContext.filesDir, relativePath)

    /** 数据库里保存相对路径时使用的形式，例如 `generations/<id>/0001.png`。 */
    fun relativePathOf(generationId: String, ordinal: Int): String =
        "$DIR_GENERATIONS/$generationId/${fileOrdinalName(ordinal)}"

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
     * 启动时清理：删除没有任何数据库记录的图片目录，以及残留的中间文件。
     *
     * 规划书 7.2 要求“应用启动时应能够清理孤立临时文件并识别丢失文件”，
     * 缺失文件的识别由仓库层按数据库记录逐个 file.exists() 完成。
     */
    fun cleanupOrphans(knownGenerationIds: Set<String>): CleanupReport {
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

        return CleanupReport(removedGenerationDirs = removedDirs, removedIncomingDirs = removedIncoming)
    }

    /** 当前历史目录占用字节数。 */
    fun usedBytes(): Long = generationsRoot.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

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
    )

    private fun fileOrdinalName(ordinal: Int): String =
        ordinal.toString().padStart(4, '0') + ".png"

    private companion object {
        const val DIR_GENERATIONS = "generations"
        const val DIR_INCOMING = "incoming"
        const val ARCHIVE_FILE_NAME = "response.zip"

        /** 预留 64 MB 余量，宁可提前报“空间不足”也不要写满用户设备。 */
        const val SAFETY_MARGIN_BYTES = 64L * 1024 * 1024
    }
}
