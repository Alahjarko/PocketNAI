package net.pocketnai.data.network

import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * 把 NovelAI 返回的 ZIP 解成一组受验证的 PNG（规划书 6.2）。
 *
 * ZIP 只作为网络传输容器：解包完成后立即删除 ZIP，历史里不保存也不展示 ZIP。
 *
 * 硬性约束：
 * 1. entry 名称必须通过安全检查（拒绝绝对路径、`..`、盘符、控制字符），
 *    且规范化后仍位于目标目录内，防止 Zip Slip；
 * 2. 输出文件名由本类按序号生成（`0001.png`…），**完全不使用 entry 名称**，
 *    因此即使名称检查被绕过也不存在路径穿越；
 * 3. 限制 entry 数量、单个 entry 大小和总解压大小，防止 ZIP 炸弹；
 * 4. 逐个校验 PNG 签名与 IHDR，扩展名不作数；
 * 5. 任何一步失败都清理已写出的临时文件，不留半成品。
 */
class ZipImageExtractor(private val limits: Limits = Limits()) {

    data class Limits(
        /** 单次响应允许的最大 entry 数。 */
        val maxEntries: Int = 32,
        /** 单个图片 entry 的最大字节数。 */
        val maxEntryBytes: Long = 64L * 1024 * 1024,
        /** 整批解压的最大字节数。 */
        val maxTotalBytes: Long = 256L * 1024 * 1024,
    )

    /** 一张已通过签名校验的临时 PNG。写入正式目录由文件存储层负责。 */
    data class ExtractedImage(
        /** 同批序号，从 1 开始，对应内部文件名 0001.png。 */
        val ordinal: Int,
        val file: File,
        val byteSize: Long,
        val sha256: String,
        val width: Int,
        val height: Int,
    )

    sealed interface Result {
        /**
         * 至少解出 1 张有效 PNG。
         * [rejectedEntries] 记录因签名/尺寸非法而被丢弃的 entry 数，
         * 供上层判断是否需要标记为 Partial。
         */
        data class Success(
            val images: List<ExtractedImage>,
            val rejectedEntries: Int = 0,
        ) : Result

        data class Failure(val error: AppError) : Result
    }

    private class LimitExceededException(message: String) : IOException(message)

    /**
     * 从 [zipStream] 解包到 [targetDir]。[targetDir] 必须是本次任务专用的临时目录。
     */
    fun extract(zipStream: InputStream, targetDir: File): Result {
        val canonicalTarget = targetDir.canonicalFile
        if (!canonicalTarget.isDirectory && !canonicalTarget.mkdirs()) {
            return Result.Failure(
                AppError.of(ErrorCode.STORAGE_FULL, detail = "无法创建临时目录: $canonicalTarget"),
            )
        }

        val written = mutableListOf<File>()
        val images = mutableListOf<ExtractedImage>()
        var rejected = 0
        var entryCount = 0
        var totalBytes = 0L

        try {
            ZipInputStream(zipStream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    entryCount++
                    if (entryCount > limits.maxEntries) {
                        throw LimitExceededException("ZIP entry 数超过上限 ${limits.maxEntries}")
                    }
                    if (!isSafeEntryName(entry.name, canonicalTarget)) {
                        // 名称非法：不信任该 entry，直接跳过并计入丢弃数。
                        rejected++
                        zip.closeEntry()
                        continue
                    }

                    val ordinal = images.size + 1
                    val outFile = File(canonicalTarget, fileNameFor(ordinal))
                    var entryBytes: Long
                    try {
                        entryBytes = copyBounded(
                            input = zip,
                            outFile = outFile,
                            limit = limits.maxEntryBytes,
                        )
                    } catch (limit: LimitExceededException) {
                        outFile.delete()
                        rejected++
                        zip.closeEntry()
                        continue
                    }
                    written += outFile
                    totalBytes += entryBytes
                    if (totalBytes > limits.maxTotalBytes) {
                        throw LimitExceededException("解压总大小超过上限 ${limits.maxTotalBytes} 字节")
                    }

                    val dimensions = PngValidator.readDimensions(outFile)
                    if (dimensions == null) {
                        // 不是合法 PNG：删除并记录，不进入最终历史。
                        outFile.delete()
                        written.remove(outFile)
                        rejected++
                        zip.closeEntry()
                        continue
                    }

                    images += ExtractedImage(
                        ordinal = ordinal,
                        file = outFile,
                        byteSize = entryBytes,
                        sha256 = sha256Of(outFile),
                        width = dimensions.width,
                        height = dimensions.height,
                    )
                    zip.closeEntry()
                }
            }
        } catch (e: ZipException) {
            cleanup(written)
            return Result.Failure(
                AppError.of(ErrorCode.ZIP_INVALID, detail = "ZIP 解析失败: ${e.message.orEmpty()}"),
            )
        } catch (e: LimitExceededException) {
            cleanup(written)
            return Result.Failure(
                AppError.of(ErrorCode.ZIP_INVALID, detail = e.message.orEmpty()),
            )
        } catch (e: IOException) {
            cleanup(written)
            return Result.Failure(
                AppError.of(ErrorCode.ZIP_INVALID, detail = "读取响应流失败: ${e.message.orEmpty()}"),
            )
        }

        if (images.isEmpty()) {
            cleanup(written)
            return Result.Failure(
                AppError.of(
                    ErrorCode.ZIP_INVALID,
                    detail = "响应中没有通过校验的 PNG（entry=$entryCount, rejected=$rejected）",
                ),
            )
        }

        return Result.Success(images = images, rejectedEntries = rejected)
    }

    /** 内部文件名一律按序号生成，与 entry 名称、用户输入完全解耦（规划书 5.1）。 */
    private fun fileNameFor(ordinal: Int): String =
        ordinal.toString().padStart(4, '0') + ".png"

    private fun isSafeEntryName(name: String, canonicalTarget: File): Boolean {
        if (name.isBlank()) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.contains("..")) return false
        // Windows 盘符与 NTFS 数据流。
        if (name.contains(':')) return false
        if (name.any { it.code < 0x20 || it.code == 0x7F }) return false
        // 双保险：规范化后必须仍在目标目录内。即使名称检查被绕过，
        // 输出路径也不使用 entry 名称，因此这里只是纵深防御。
        val candidate = File(canonicalTarget, name).canonicalFile
        val prefix = canonicalTarget.path + File.separator
        return candidate.path.startsWith(prefix)
    }

    /** 有界拷贝：超过 [limit] 立刻抛错，避免 ZIP 炸弹把磁盘写满。 */
    private fun copyBounded(input: InputStream, outFile: File, limit: Long): Long {
        var total = 0L
        outFile.outputStream().buffered().use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) {
                    throw LimitExceededException("entry 超过大小上限 $limit 字节")
                }
                output.write(buffer, 0, read)
            }
        }
        return total
    }

    private fun sha256Of(file: File): String =
        file.inputStream().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString(separator = "") { "%02x".format(it) }
        }

    private fun cleanup(files: List<File>) {
        files.forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
