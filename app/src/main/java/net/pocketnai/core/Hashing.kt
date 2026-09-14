package net.pocketnai.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** 内容哈希工具。sha256 会写入数据库，用于识别丢失或损坏的图片文件。 */
object Hashing {

    private const val BUFFER_SIZE = 64 * 1024

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256(file: File): String =
        file.inputStream().use(::sha256)

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
}
