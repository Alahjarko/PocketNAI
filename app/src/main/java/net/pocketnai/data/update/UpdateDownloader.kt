package net.pocketnai.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.domain.update.UpdateRelease
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * 把 Release 附件里的 APK 下载到应用 cache 目录，并在落盘前做签名校验。
 *
 * ## 为什么落在 cache
 * 更新包只用于"下载完立刻安装"，装完即可被系统回收；
 * 它**不是用户数据**，不进 files 目录、不参与任何历史/清理逻辑。
 *
 * ## 为什么必须校验签名
 * 系统安装器本身会拒绝签名不一致的覆盖安装，但那时用户看到的是
 * "应用未安装"这类含糊的系统提示；这里提前校验是为了给出明确原因
 * （"下载的安装包与当前应用不是同一签名"），并早一步丢弃坏包。
 * 判定不了签名（旧系统读不到只有 v2 签名的包）时放行 —— 由系统安装器兜底。
 */
class UpdateDownloader(
    private val context: Context,
    private val client: OkHttpClient,
) {

    suspend fun download(
        release: UpdateRelease,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Outcome<File> = withContext(Dispatchers.IO) {
        if (!release.downloadUrl.startsWith("https://")) {
            return@withContext Outcome.Failure(
                AppError.of(ErrorCode.UPDATE_DOWNLOAD_FAILED, detail = "insecure url"),
            )
        }

        val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        val target = File(dir, "PocketNAI-${release.versionCode}.apk")

        // 已经下好且校验过的文件直接复用：用户在授权"安装未知应用"之后
        // 会再点一次安装，那时不该把 20+ MB 重新下一遍。
        if (target.isFile && signatureMatchesInstalled(target)) {
            return@withContext Outcome.Success(target)
        }

        val request = Request.Builder()
            .url(release.downloadUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        // 先写 .part 再改名：中断留下的半截文件不会被当成完整安装包。
        val partial = File(dir, "${target.name}.part")
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Outcome.Failure(
                        AppError.of(ErrorCode.UPDATE_DOWNLOAD_FAILED, detail = "http ${response.code}"),
                    )
                }
                val body = response.body
                    ?: return@withContext Outcome.Failure(
                        AppError.of(ErrorCode.UPDATE_DOWNLOAD_FAILED, detail = "empty body"),
                    )

                val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                body.byteStream().use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            partial.delete()
            return@withContext Outcome.Failure(AppError.of(ErrorCode.NETWORK_UNAVAILABLE))
        }

        if (!partial.renameTo(target)) {
            partial.delete()
            return@withContext Outcome.Failure(
                AppError.of(ErrorCode.UPDATE_DOWNLOAD_FAILED, detail = "rename failed"),
            )
        }

        if (!signatureMatchesInstalled(target)) {
            target.delete()
            return@withContext Outcome.Failure(AppError.of(ErrorCode.UPDATE_SIGNATURE_MISMATCH))
        }

        Outcome.Success(target)
    }

    /** 只在**确定**签名不一致时返回 false；读不到签名信息时放行（系统安装器兜底）。 */
    private fun signatureMatchesInstalled(apk: File): Boolean {
        val apkDigests = signingDigests(archiveInfo(apk.absolutePath)) ?: return true
        val selfDigests = signingDigests(selfInfo()) ?: return true
        return apkDigests == selfDigests
    }

    private fun archiveInfo(path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }

    private fun selfInfo(): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }

    /** 返回签名证书的 SHA-256 集合；拿不到（旧系统 + 只有 v2 签名的包）时返回 null。 */
    private fun signingDigests(info: PackageInfo?): Set<String>? {
        if (info == null) return null
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners ?: return null
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: return null
        }
        if (signatures.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.mapTo(mutableSetOf()) { signature ->
            digest.digest(signature.toByteArray())
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }

    private companion object {
        const val DIR_NAME = "updates"
        const val USER_AGENT = "PocketNAI-Android"
        const val BUFFER_BYTES = 64 * 1024
    }
}
